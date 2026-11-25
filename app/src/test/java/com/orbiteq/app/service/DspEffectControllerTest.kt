package com.orbiteq.app.service

import com.orbiteq.app.dsp.EqEngineFrame
import com.orbiteq.app.model.DspBackend
import com.orbiteq.app.model.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DspEffectControllerTest {

    @Test
    fun dynamicsProcessingFailureFallsBackCleanlyToLegacyEqualizer() {
        val factory = FakeDspBackendFactory(
            dpFailure = { _ -> UnsupportedOperationException("OEM DynamicsProcessing unsupported") },
        )
        val controller = DspEffectController(factory)

        val backend = controller.attach(4821)

        assertEquals(DspBackend.LEGACY_EQ, backend)
        assertEquals(DspBackend.LEGACY_EQ, controller.currentBackend)
        assertEquals(4821, controller.currentSessionId)
        assertEquals(1, factory.activeHandlesCount())

        val frame = sampleFrame()
        controller.apply(frame = frame, mbcEnabled = true, active = true)

        val activeHandle = factory.createdHandles.single()
        assertEquals(1, activeHandle.applyCalls.size)
        assertSame(frame, activeHandle.applyCalls.single().frame)
        assertEquals(true, activeHandle.applyCalls.single().mbcEnabled)
        assertEquals(true, activeHandle.applyCalls.single().active)

        controller.release()
        assertEquals(0, factory.activeHandlesCount())
        assertEquals(DspBackend.NONE, controller.currentBackend)
        assertEquals(0, controller.currentSessionId)
    }

    @Test
    fun rapidSessionReconnectsReleaseEveryPreviousHandleWithZeroLeaks() {
        val factory = FakeDspBackendFactory(
            dpFailure = { sessionId ->
                if (sessionId == 202) RuntimeException("Transient OEM failure on 202") else null
            },
        )
        val controller = DspEffectController(factory)

        assertEquals(DspBackend.DYNAMICS_PROCESSING, controller.attach(101))
        assertEquals(1, factory.activeHandlesCount())

        assertEquals(DspBackend.LEGACY_EQ, controller.attach(202))
        assertEquals(1, factory.activeHandlesCount())

        assertEquals(DspBackend.DYNAMICS_PROCESSING, controller.attach(303))
        assertEquals(1, factory.activeHandlesCount())

        assertEquals(DspBackend.DYNAMICS_PROCESSING, controller.attach(0))
        assertEquals(1, factory.activeHandlesCount())

        assertEquals(DspBackend.DYNAMICS_PROCESSING, controller.attach(404))
        assertEquals(1, factory.activeHandlesCount())
        assertEquals(404, controller.currentSessionId)
        assertEquals(DspBackend.DYNAMICS_PROCESSING, controller.currentBackend)

        // All 4 earlier handles were released exactly once; only the 5th remains open.
        assertEquals(5, factory.createdHandles.size)
        for (i in 0 until 4) {
            assertEquals("Handle $i should be released once", 1, factory.createdHandles[i].releaseCount)
        }
        assertEquals(0, factory.createdHandles[4].releaseCount)

        controller.release()
        controller.release() // idempotent
        assertEquals(0, factory.activeHandlesCount())
        assertEquals(1, factory.createdHandles[4].releaseCount)
        assertEquals(DspBackend.NONE, controller.currentBackend)
        assertEquals(0, controller.currentSessionId)
    }

    @Test
    fun positiveSessionFailureFallsBackToGlobalSessionZero() {
        val factory = FakeDspBackendFactory(
            dpFailure = { sessionId ->
                if (sessionId == 777 || sessionId == 888) IllegalArgumentException("Invalid session $sessionId") else null
            },
            legacyFailure = { sessionId ->
                if (sessionId == 777 || sessionId == 888) RuntimeException("Legacy rejected $sessionId") else null
            },
        )
        val controller = DspEffectController(factory)

        assertEquals(DspBackend.DYNAMICS_PROCESSING, controller.attach(777))
        assertEquals(0, controller.currentSessionId)
        assertEquals(DspBackend.DYNAMICS_PROCESSING, controller.currentBackend)
        assertEquals(1, factory.activeHandlesCount())

        // If session 0 DP also fails, falls back to session 0 LEGACY_EQ
        val legacyZeroFactory = FakeDspBackendFactory(
            dpFailure = { _ -> RuntimeException("DP unavailable everywhere") },
            legacyFailure = { sessionId -> if (sessionId > 0) RuntimeException("Session $sessionId blocked") else null },
        )
        val legacyZeroController = DspEffectController(legacyZeroFactory)
        assertEquals(DspBackend.LEGACY_EQ, legacyZeroController.attach(888))
        assertEquals(0, legacyZeroController.currentSessionId)
        assertEquals(DspBackend.LEGACY_EQ, legacyZeroController.currentBackend)
        assertEquals(1, legacyZeroFactory.activeHandlesCount())
    }

    @Test
    fun totalCreationFailureReleasesPreviousHandleAndResetsToNoneWithoutCrashing() {
        var failAll = false
        val factory = FakeDspBackendFactory(
            dpFailure = { _ -> if (failAll) RuntimeException("AudioFlinger died") else null },
            legacyFailure = { _ -> if (failAll) RuntimeException("Legacy died") else null },
        )
        val controller = DspEffectController(factory)

        assertEquals(DspBackend.DYNAMICS_PROCESSING, controller.attach(555))
        assertEquals(1, factory.activeHandlesCount())

        failAll = true
        assertEquals(DspBackend.NONE, controller.attach(666))
        assertEquals(0, factory.activeHandlesCount())
        assertEquals(DspBackend.NONE, controller.currentBackend)
        assertEquals(0, controller.currentSessionId)

        // Safe no-op when no backend is active
        controller.apply(sampleFrame(), mbcEnabled = false, active = true)
    }

    private fun sampleFrame(): EqEngineFrame = EqEngineFrame(
        preEqDb = FloatArray(10),
        postEqDb = FloatArray(10) { 1.0f },
        totalEqDb = FloatArray(10) { 1.0f },
        adaptiveDeltaDb = FloatArray(10),
        inputGainDb = -0.5f,
        loudnessBoostDb = 0f,
        engineStatus = EngineStatus.AUTO_FFT_LOCKED,
        displayBins36 = FloatArray(36),
        spectralTag = "Balanced",
    )

    private data class ApplyCall(
        val frame: EqEngineFrame,
        val mbcEnabled: Boolean,
        val active: Boolean,
    )

    private class FakeHandle(
        override val backend: DspBackend,
        override val sessionId: Int,
    ) : DspSessionHandle {
        val applyCalls = mutableListOf<ApplyCall>()
        var releaseCount: Int = 0
            private set

        override fun apply(frame: EqEngineFrame, mbcEnabled: Boolean, active: Boolean) {
            applyCalls += ApplyCall(frame = frame, mbcEnabled = mbcEnabled, active = active)
        }

        override fun release() {
            releaseCount += 1
        }
    }

    private class FakeDspBackendFactory(
        private val dpFailure: (Int) -> Throwable? = { null },
        private val legacyFailure: (Int) -> Throwable? = { null },
    ) : DspBackendFactory {
        val createdHandles = mutableListOf<FakeHandle>()

        override fun createDynamicsProcessing(sessionId: Int): DspSessionHandle {
            dpFailure(sessionId)?.let { throw it }
            return FakeHandle(DspBackend.DYNAMICS_PROCESSING, sessionId).also { createdHandles += it }
        }

        override fun createLegacyEqualizer(sessionId: Int): DspSessionHandle {
            legacyFailure(sessionId)?.let { throw it }
            return FakeHandle(DspBackend.LEGACY_EQ, sessionId).also { createdHandles += it }
        }

        fun activeHandlesCount(): Int = createdHandles.count { it.releaseCount == 0 }
    }
}
