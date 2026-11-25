package com.orbiteq.app.service

import com.orbiteq.app.dsp.EqEngineFrame
import com.orbiteq.app.model.DspBackend
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.MbcMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspEffectControllerTest {

    @Test
    fun thirtyOneBandAttachedFirstWhenSupported() {
        val factory = FakeDspBackendFactory()
        val controller = DspEffectController(factory)

        val backend = controller.attach(4821)

        assertEquals(DspBackend.DYNAMICS_PROCESSING, backend)
        assertEquals(DspBackend.DYNAMICS_PROCESSING, controller.currentBackend)
        assertEquals(4821, controller.currentSessionId)
        assertEquals(31, controller.currentPreEqBandCount)
        assertEquals(listOf("DP(4821,31)"), factory.attempts)
        assertEquals(1, factory.activeHandlesCount())
    }

    @Test
    fun thirtyOneBandFailureFallsBackToTenBandThenLegacyThenSessionZeroWithoutLeaks() {
        // 31-band fails on 4821 -> succeeds on 10-band 4821
        val tenBandFactory = FakeDspBackendFactory(
            dpFailure = { sessionId, bands ->
                if (sessionId == 4821 && bands == 31) IllegalStateException("31-band probe failed") else null
            },
        )
        val tenBandController = DspEffectController(tenBandFactory)
        assertEquals(DspBackend.DYNAMICS_PROCESSING, tenBandController.attach(4821))
        assertEquals(4821, tenBandController.currentSessionId)
        assertEquals(10, tenBandController.currentPreEqBandCount)
        assertEquals(listOf("DP(4821,31)", "DP(4821,10)"), tenBandFactory.attempts)
        assertEquals(1, tenBandFactory.activeHandlesCount())

        // 31-band and 10-band fail on 4821 -> falls back to Legacy Equalizer on 4821
        val legacyFactory = FakeDspBackendFactory(
            dpFailure = { _, _ -> UnsupportedOperationException("OEM DynamicsProcessing unsupported") },
        )
        val legacyController = DspEffectController(legacyFactory)
        assertEquals(DspBackend.LEGACY_EQ, legacyController.attach(4821))
        assertEquals(4821, legacyController.currentSessionId)
        assertEquals(5, legacyController.currentPreEqBandCount)
        assertEquals(listOf("DP(4821,31)", "DP(4821,10)", "LEGACY(4821)"), legacyFactory.attempts)
        assertEquals(1, legacyFactory.activeHandlesCount())

        // All backends fail on 777 -> retries session 0 (31 -> 10 -> Legacy)
        val sessionZeroFactory = FakeDspBackendFactory(
            dpFailure = { sessionId, bands ->
                when {
                    sessionId == 777 -> IllegalArgumentException("Invalid session $sessionId")
                    sessionId == 0 && bands == 31 -> IllegalStateException("31-band unsupported on 0")
                    else -> null
                }
            },
            legacyFailure = { sessionId ->
                if (sessionId == 777) RuntimeException("Legacy rejected 777") else null
            },
        )
        val zeroController = DspEffectController(sessionZeroFactory)
        assertEquals(DspBackend.DYNAMICS_PROCESSING, zeroController.attach(777))
        assertEquals(0, zeroController.currentSessionId)
        assertEquals(10, zeroController.currentPreEqBandCount)
        assertEquals(
            listOf("DP(777,31)", "DP(777,10)", "LEGACY(777)", "DP(0,31)", "DP(0,10)"),
            sessionZeroFactory.attempts,
        )
        assertEquals(1, sessionZeroFactory.activeHandlesCount())

        // Session 0 also rejects 31 & 10 -> falls back to session 0 LEGACY_EQ
        val legacyZeroFactory = FakeDspBackendFactory(
            dpFailure = { _, _ -> RuntimeException("DP unavailable everywhere") },
            legacyFailure = { sessionId -> if (sessionId > 0) RuntimeException("Session $sessionId blocked") else null },
        )
        val legacyZeroController = DspEffectController(legacyZeroFactory)
        assertEquals(DspBackend.LEGACY_EQ, legacyZeroController.attach(888))
        assertEquals(0, legacyZeroController.currentSessionId)
        assertEquals(DspBackend.LEGACY_EQ, legacyZeroController.currentBackend)
        assertEquals(
            listOf("DP(888,31)", "DP(888,10)", "LEGACY(888)", "DP(0,31)", "DP(0,10)", "LEGACY(0)"),
            legacyZeroFactory.attempts,
        )
        assertEquals(1, legacyZeroFactory.activeHandlesCount())
    }

    @Test
    fun rapidSessionReconnectsReleaseEveryPreviousHandleWithZeroLeaks() {
        val factory = FakeDspBackendFactory(
            dpFailure = { sessionId, _ ->
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
        assertEquals(0, controller.currentPreEqBandCount)
    }

    @Test
    fun totalCreationFailureReleasesPreviousHandleAndResetsToNoneWithoutCrashing() {
        var failAll = false
        val factory = FakeDspBackendFactory(
            dpFailure = { _, _ -> if (failAll) RuntimeException("AudioFlinger died") else null },
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
        assertEquals(0, controller.currentPreEqBandCount)

        controller.apply(sampleFrame(), mbcMode = MbcMode.OFF, active = true)
    }

    @Test
    fun applyRuntimeExceptionReleasesHandleAndResetsBackendToNone() {
        val factory = FakeDspBackendFactory(
            applyFailure = { IllegalStateException("DynamicsProcessing setEnabled(true) failed: -1") },
        )
        val controller = DspEffectController(factory)

        assertEquals(DspBackend.DYNAMICS_PROCESSING, controller.attach(1234))
        assertEquals(1, factory.activeHandlesCount())

        controller.apply(sampleFrame(), mbcMode = MbcMode.SUBTLE, active = true)

        assertEquals(1, factory.createdHandles.single().releaseCount)
        assertEquals(0, factory.activeHandlesCount())
        assertEquals(DspBackend.NONE, controller.currentBackend)
        assertEquals(0, controller.currentSessionId)
        assertFalse(controller.isRampActive)
    }

    @Test
    fun engagingAndDisablingBypassRampSmoothlyOverThreeTicksWithoutClicks() {
        val factory = FakeDspBackendFactory()
        val controller = DspEffectController(factory)
        controller.attach(100)
        val handle = factory.createdHandles.single()

        val target = sampleFrame(
            preEq31Val = -3.0f,
            postEqVal = 3.0f,
            inputGainDb = -2.0f,
            leftTrimDb = -3.0f,
        )

        // Tick 1: bypassRamp -> 0.34
        controller.apply(target, mbcMode = MbcMode.SUBTLE, active = true)
        assertTrue(controller.isRampActive)
        assertTrue(handle.applyCalls[0].active)
        assertEquals(-3.0f * 0.34f, handle.applyCalls[0].frame.preEq31Db[0], 0.01f)
        assertEquals(3.0f * 0.34f, handle.applyCalls[0].frame.postEqDb[0], 0.01f)
        assertEquals(-2.0f * 0.34f, handle.applyCalls[0].frame.inputGainDb, 0.01f)

        // Tick 2: bypassRamp -> 0.68
        controller.apply(target, mbcMode = MbcMode.SUBTLE, active = true)
        assertTrue(controller.isRampActive)
        assertTrue(handle.applyCalls[1].active)
        assertEquals(-3.0f * 0.68f, handle.applyCalls[1].frame.preEq31Db[0], 0.01f)
        assertEquals(3.0f * 0.68f, handle.applyCalls[1].frame.postEqDb[0], 0.01f)

        // Tick 3: bypassRamp -> 1.00 (settled)
        controller.apply(target, mbcMode = MbcMode.SUBTLE, active = true)
        assertFalse(controller.isRampActive)
        assertTrue(handle.applyCalls[2].active)
        assertEquals(-3.0f, handle.applyCalls[2].frame.preEq31Db[0], 0.01f)
        assertEquals(3.0f, handle.applyCalls[2].frame.postEqDb[0], 0.01f)
        assertEquals(-2.0f, handle.applyCalls[2].frame.inputGainDb, 0.01f)
        assertEquals(-3.0f, handle.applyCalls[2].frame.leftBalanceTrimDb, 0.01f)

        // Toggle bypass (active = false): keeps native handle active while ramping down toward 0 dB over ~3 ticks
        val zeroedBypassFrame = sampleFrame(preEq31Val = 0f, postEqVal = 0f, inputGainDb = 0f, leftTrimDb = 0f)
        controller.apply(zeroedBypassFrame, mbcMode = MbcMode.SUBTLE, active = false)
        assertTrue(controller.isRampActive)
        assertTrue("Native handle must stay enabled on tick 1 of bypass fade", handle.applyCalls[3].active)
        assertEquals(-3.0f * 0.66f, handle.applyCalls[3].frame.preEq31Db[0], 0.05f)
        assertEquals(3.0f * 0.66f, handle.applyCalls[3].frame.postEqDb[0], 0.05f)

        controller.apply(zeroedBypassFrame, mbcMode = MbcMode.SUBTLE, active = false)
        assertTrue(controller.isRampActive)
        assertTrue("Native handle must stay enabled on tick 2 of bypass fade", handle.applyCalls[4].active)
        assertEquals(-3.0f * 0.32f, handle.applyCalls[4].frame.preEq31Db[0], 0.05f)
        assertEquals(3.0f * 0.32f, handle.applyCalls[4].frame.postEqDb[0], 0.05f)

        controller.apply(zeroedBypassFrame, mbcMode = MbcMode.SUBTLE, active = false)
        assertFalse(controller.isRampActive)
        assertFalse("Native handle must disable only once bypassRamp <= 0.01", handle.applyCalls[5].active)
        assertEquals(0f, handle.applyCalls[5].frame.preEq31Db[0], 0.001f)
        assertEquals(0f, handle.applyCalls[5].frame.postEqDb[0], 0.001f)
        assertEquals(0f, handle.applyCalls[5].frame.inputGainDb, 0.001f)
        assertEquals(0f, handle.applyCalls[5].frame.leftBalanceTrimDb, 0.001f)
    }

    @Test
    fun activeGainJumpsAreSlewLimitedToThreeDbPerTick() {
        val factory = FakeDspBackendFactory()
        val controller = DspEffectController(factory)
        controller.attach(100)
        val handle = factory.createdHandles.single()

        val flat = sampleFrame(preEq31Val = 0f, postEqVal = 0f, inputGainDb = 0f)
        repeat(3) {
            controller.apply(flat, mbcMode = MbcMode.SUBTLE, active = true)
        }
        assertFalse(controller.isRampActive)

        val bigJump = sampleFrame(preEq31Val = -9.0f, postEqVal = 9.0f, inputGainDb = -7.5f)
        controller.apply(bigJump, mbcMode = MbcMode.PUNCHY, active = true)
        assertTrue(controller.isRampActive)
        assertEquals(-3.0f, handle.applyCalls.last().frame.preEq31Db[0], 0.001f)
        assertEquals(3.0f, handle.applyCalls.last().frame.postEqDb[0], 0.001f)
        assertEquals(-3.0f, handle.applyCalls.last().frame.inputGainDb, 0.001f)

        controller.apply(bigJump, mbcMode = MbcMode.PUNCHY, active = true)
        assertTrue(controller.isRampActive)
        assertEquals(-6.0f, handle.applyCalls.last().frame.preEq31Db[0], 0.001f)
        assertEquals(6.0f, handle.applyCalls.last().frame.postEqDb[0], 0.001f)
        assertEquals(-6.0f, handle.applyCalls.last().frame.inputGainDb, 0.001f)

        controller.apply(bigJump, mbcMode = MbcMode.PUNCHY, active = true)
        assertFalse(controller.isRampActive)
        assertEquals(-9.0f, handle.applyCalls.last().frame.preEq31Db[0], 0.001f)
        assertEquals(9.0f, handle.applyCalls.last().frame.postEqDb[0], 0.001f)
        assertEquals(-7.5f, handle.applyCalls.last().frame.inputGainDb, 0.001f)
    }

    @Test
    fun bypassFadeOutFromHighGainsRampsProportionallyWithoutSlewStall() {
        val factory = FakeDspBackendFactory()
        val controller = DspEffectController(factory)
        controller.attach(100)
        val handle = factory.createdHandles.single()

        val highGainFrame = sampleFrame(
            preEq31Val = -12.0f,
            postEqVal = 12.0f,
            inputGainDb = -12.0f,
            leftTrimDb = -12.0f,
        )
        // 4 ticks needed while engaged because +/-3.0 dB/tick slew limit caps +/-12 dB jumps
        repeat(4) {
            controller.apply(highGainFrame, mbcMode = MbcMode.SUBTLE, active = true)
        }
        assertFalse(controller.isRampActive)
        assertEquals(-12.0f, handle.applyCalls.last().frame.preEq31Db[0], 0.001f)
        assertEquals(12.0f, handle.applyCalls.last().frame.postEqDb[0], 0.001f)
        assertEquals(-12.0f, handle.applyCalls.last().frame.inputGainDb, 0.001f)
        assertEquals(-12.0f, handle.applyCalls.last().frame.leftBalanceTrimDb, 0.001f)

        val baseIndex = handle.applyCalls.size

        // Tick 1 of bypass: proportional 0.66x fade (-7.92 dB, NOT stalled at -9.0 dB)
        controller.apply(highGainFrame, mbcMode = MbcMode.SUBTLE, active = false)
        assertTrue(controller.isRampActive)
        assertTrue(handle.applyCalls[baseIndex].active)
        assertEquals(-12.0f * 0.66f, handle.applyCalls[baseIndex].frame.preEq31Db[0], 0.01f)
        assertEquals(12.0f * 0.66f, handle.applyCalls[baseIndex].frame.postEqDb[0], 0.01f)
        assertEquals(-12.0f * 0.66f, handle.applyCalls[baseIndex].frame.inputGainDb, 0.01f)
        assertEquals(-12.0f * 0.66f, handle.applyCalls[baseIndex].frame.leftBalanceTrimDb, 0.01f)

        // Tick 2 of bypass: proportional 0.32x fade (-3.84 dB, NOT stalled at -6.0 dB)
        controller.apply(highGainFrame, mbcMode = MbcMode.SUBTLE, active = false)
        assertTrue(controller.isRampActive)
        assertTrue(handle.applyCalls[baseIndex + 1].active)
        assertEquals(-12.0f * 0.32f, handle.applyCalls[baseIndex + 1].frame.preEq31Db[0], 0.01f)
        assertEquals(12.0f * 0.32f, handle.applyCalls[baseIndex + 1].frame.postEqDb[0], 0.01f)
        assertEquals(-12.0f * 0.32f, handle.applyCalls[baseIndex + 1].frame.inputGainDb, 0.01f)
        assertEquals(-12.0f * 0.32f, handle.applyCalls[baseIndex + 1].frame.leftBalanceTrimDb, 0.01f)

        // Tick 3 of bypass: bypassRamp <= 0.01f disables native handle cleanly from -3.84 dB to 0 dB
        controller.apply(highGainFrame, mbcMode = MbcMode.SUBTLE, active = false)
        assertFalse(controller.isRampActive)
        assertFalse(handle.applyCalls[baseIndex + 2].active)
        assertEquals(0f, handle.applyCalls[baseIndex + 2].frame.preEq31Db[0], 0.001f)
        assertEquals(0f, handle.applyCalls[baseIndex + 2].frame.postEqDb[0], 0.001f)
        assertEquals(0f, handle.applyCalls[baseIndex + 2].frame.inputGainDb, 0.001f)
        assertEquals(0f, handle.applyCalls[baseIndex + 2].frame.leftBalanceTrimDb, 0.001f)
    }

    @Test
    fun dirtyTrackerSuppressesIdenticalFramesAndTriggersOnlyOnThresholdChanges() {
        val tracker = DspDirtyTracker(preEqBandCount = 31)
        val pre31 = FloatArray(31) { 1.0f }
        val post10 = FloatArray(10) { 2.0f }

        // First active frame must write every stage
        val firstPlan = tracker.evaluateAndCommit(
            preEq = pre31,
            postEq = post10,
            leftInputGainDb = -1.0f,
            rightInputGainDb = -3.0f,
            mbcMode = MbcMode.SUBTLE,
            active = true,
        )
        assertTrue(firstPlan.writeEnabled)
        assertTrue(firstPlan.targetEnabled)
        assertTrue(firstPlan.writeLeftInputGain)
        assertTrue(firstPlan.writeRightInputGain)
        assertTrue(firstPlan.writePreEq)
        assertTrue(firstPlan.writePostEq)
        assertTrue(firstPlan.writeMbc)

        // Identical frame -> zero writes
        val settledPlan = tracker.evaluateAndCommit(
            preEq = pre31.copyOf(),
            postEq = post10.copyOf(),
            leftInputGainDb = -1.0f,
            rightInputGainDb = -3.0f,
            mbcMode = MbcMode.SUBTLE,
            active = true,
        )
        assertFalse(settledPlan.hasAnyWrite)

        // Sub-threshold delta (< 0.05 dB) -> zero writes
        val subThresholdPre = pre31.copyOf().apply { this[4] = 1.04f }
        val subThresholdPost = post10.copyOf().apply { this[2] = 2.049f }
        val subPlan = tracker.evaluateAndCommit(
            preEq = subThresholdPre,
            postEq = subThresholdPost,
            leftInputGainDb = -1.04f,
            rightInputGainDb = -3.04f,
            mbcMode = MbcMode.SUBTLE,
            active = true,
        )
        assertFalse(subPlan.hasAnyWrite)

        // Exact 0.05 dB delta on preEq[4] (testing IEEE-754 1.05f - 1.0f boundary) -> writes ONLY preEq
        val preBoundary = pre31.copyOf().apply { this[4] = 1.05f }
        val prePlan = tracker.evaluateAndCommit(
            preEq = preBoundary,
            postEq = post10.copyOf(),
            leftInputGainDb = -1.0f,
            rightInputGainDb = -3.0f,
            mbcMode = MbcMode.SUBTLE,
            active = true,
        )
        assertTrue(prePlan.writePreEq)
        assertFalse(prePlan.writePostEq)
        assertFalse(prePlan.writeLeftInputGain)
        assertFalse(prePlan.writeRightInputGain)
        assertFalse(prePlan.writeMbc)
        assertFalse(prePlan.writeEnabled)

        // Switching MbcMode -> writes ONLY mbc
        val mbcPlan = tracker.evaluateAndCommit(
            preEq = preBoundary,
            postEq = post10.copyOf(),
            leftInputGainDb = -1.0f,
            rightInputGainDb = -3.0f,
            mbcMode = MbcMode.PUNCHY,
            active = true,
        )
        assertTrue(mbcPlan.writeMbc)
        assertFalse(mbcPlan.writePreEq)
        assertFalse(mbcPlan.writePostEq)

        // Right balance input gain shift >= 0.05 dB -> writes ONLY right input gain
        val rightGainPlan = tracker.evaluateAndCommit(
            preEq = preBoundary,
            postEq = post10.copyOf(),
            leftInputGainDb = -1.0f,
            rightInputGainDb = -3.05f,
            mbcMode = MbcMode.PUNCHY,
            active = true,
        )
        assertTrue(rightGainPlan.writeRightInputGain)
        assertFalse(rightGainPlan.writeLeftInputGain)
        assertFalse(rightGainPlan.writePreEq)

        // Disabling writes enabled=false once, then repeated inactive calls write nothing
        val disablePlan = tracker.evaluateAndCommit(
            preEq = FloatArray(31),
            postEq = FloatArray(10),
            leftInputGainDb = 0f,
            rightInputGainDb = 0f,
            mbcMode = MbcMode.PUNCHY,
            active = false,
        )
        assertTrue(disablePlan.writeEnabled)
        assertFalse(disablePlan.targetEnabled)
        assertFalse(disablePlan.writePreEq)

        val repeatDisablePlan = tracker.evaluateAndCommit(
            preEq = FloatArray(31),
            postEq = FloatArray(10),
            leftInputGainDb = 0f,
            rightInputGainDb = 0f,
            mbcMode = MbcMode.PUNCHY,
            active = false,
        )
        assertFalse(repeatDisablePlan.hasAnyWrite)
    }

    @Test
    fun legacyDirtyTrackerSuppressesSubFiveMillibelWrites() {
        val tracker = LegacyDirtyTracker(bandCount = 5)

        val first = tracker.evaluateAndCommit(intArrayOf(100, 200, 0, -100, -200), active = true)
        assertTrue(first.writeEnabled)
        assertTrue(first.targetEnabled)
        assertEquals(listOf(0, 1, 2, 3, 4), first.dirtyBands)

        // Identical & sub-5mB deltas -> empty
        val identical = tracker.evaluateAndCommit(intArrayOf(100, 200, 0, -100, -200), active = true)
        assertFalse(identical.writeEnabled)
        assertTrue(identical.dirtyBands.isEmpty())

        val subThreshold = tracker.evaluateAndCommit(intArrayOf(104, 196, 4, -104, -196), active = true)
        assertFalse(subThreshold.writeEnabled)
        assertTrue(subThreshold.dirtyBands.isEmpty())

        // Band 2 changes by exactly 5 mB -> only band 2 dirty
        val dirtyOne = tracker.evaluateAndCommit(intArrayOf(100, 200, 5, -100, -200), active = true)
        assertEquals(listOf(2), dirtyOne.dirtyBands)

        val offOnce = tracker.evaluateAndCommit(intArrayOf(0, 0, 0, 0, 0), active = false)
        assertTrue(offOnce.writeEnabled)
        assertFalse(offOnce.targetEnabled)

        val offRepeat = tracker.evaluateAndCommit(intArrayOf(0, 0, 0, 0, 0), active = false)
        assertFalse(offRepeat.writeEnabled)
        assertTrue(offRepeat.dirtyBands.isEmpty())
    }

    @Test
    fun channelBalanceGainsAndThreeTierMbcConfigsMatchSpecification() {
        val frame = sampleFrame(inputGainDb = -2.0f, leftTrimDb = -4.5f, rightTrimDb = 1.5f)
        val (leftGain, rightGain) = DspEffectController.effectiveChannelInputGains(frame)
        assertEquals(-6.5f, leftGain, 0.001f)
        assertEquals(-2.0f, rightGain, 0.001f) // never positive boost from balance trim

        val off = AndroidDspBackendFactory.mbcConfigFor(MbcMode.OFF)
        assertFalse(off.enabled)

        val subtle = AndroidDspBackendFactory.mbcConfigFor(MbcMode.SUBTLE)
        assertTrue(subtle.enabled)
        assertArrayEquals(floatArrayOf(1.45f, 1.30f, 1.30f, 1.45f), subtle.ratios, 0.001f)
        assertArrayEquals(floatArrayOf(-18f, -16f, -16f, -17f), subtle.thresholdsDb, 0.001f)
        assertEquals(8f, subtle.kneeDb, 0.001f)
        assertArrayEquals(floatArrayOf(25f, 18f, 14f, 12f), subtle.attacksMs, 0.001f)
        assertArrayEquals(floatArrayOf(140f, 120f, 100f, 90f), subtle.releasesMs, 0.001f)

        val punchy = AndroidDspBackendFactory.mbcConfigFor(MbcMode.PUNCHY)
        assertTrue(punchy.enabled)
        assertArrayEquals(floatArrayOf(2.0f, 1.65f, 1.65f, 2.0f), punchy.ratios, 0.001f)
        assertArrayEquals(floatArrayOf(-15f, -13f, -13f, -14f), punchy.thresholdsDb, 0.001f)
        assertEquals(5f, punchy.kneeDb, 0.001f)
        assertArrayEquals(floatArrayOf(18f, 14f, 10f, 9f), punchy.attacksMs, 0.001f)
        assertArrayEquals(floatArrayOf(110f, 95f, 85f, 75f), punchy.releasesMs, 0.001f)
    }

    @Test
    fun releaseDuringBypassRampStopsImmediatelyWithoutWaitingForRampTicks() {
        val factory = FakeDspBackendFactory()
        val controller = DspEffectController(factory)
        controller.attach(321)
        val handle = factory.createdHandles.single()

        val activeFrame = sampleFrame(preEq31Val = -3.0f, postEqVal = 3.0f, inputGainDb = -1.5f)
        repeat(3) {
            controller.apply(activeFrame, mbcMode = MbcMode.SUBTLE, active = true)
        }
        assertFalse(controller.isRampActive)

        // Start a bypass ramp (tick 1 keeps handle active), then call release() immediately
        controller.apply(activeFrame, mbcMode = MbcMode.SUBTLE, active = false)
        assertTrue(controller.isRampActive)
        assertEquals(0, handle.releaseCount)

        controller.release()
        assertEquals(1, handle.releaseCount)
        assertFalse(controller.isRampActive)
        assertEquals(DspBackend.NONE, controller.currentBackend)
        assertEquals(0, controller.currentPreEqBandCount)
    }

    @Test
    fun settledControllerWithFakeDirtyHandleEmitsZeroWritesOnIdenticalFrames() {
        val factory = FakeDspBackendFactory()
        val controller = DspEffectController(factory)
        controller.attach(777)
        val handle = factory.createdHandles.single()

        val frame = sampleFrame(
            preEq31Val = -2.0f,
            postEqVal = 1.5f,
            inputGainDb = -1.0f,
            leftTrimDb = -3.0f,
            rightTrimDb = 0f,
        )

        // Settle over 3 engage ramp ticks
        repeat(3) {
            controller.apply(frame, mbcMode = MbcMode.SUBTLE, active = true)
        }
        assertFalse(controller.isRampActive)
        val writesAfterSettle = handle.nativeWriteCount

        // Repeat identical settled frames 10 times -> zero additional native writes
        repeat(10) {
            controller.apply(frame, mbcMode = MbcMode.SUBTLE, active = true)
        }
        assertEquals(writesAfterSettle, handle.nativeWriteCount)

        // Switch MbcMode -> emits exactly 1 native write (MBC stage only)
        controller.apply(frame, mbcMode = MbcMode.PUNCHY, active = true)
        assertEquals(writesAfterSettle + 1, handle.nativeWriteCount)

        // Repeat identical settled frame again -> zero additional writes
        repeat(5) {
            controller.apply(frame, mbcMode = MbcMode.PUNCHY, active = true)
        }
        assertEquals(writesAfterSettle + 1, handle.nativeWriteCount)
    }

    private fun sampleFrame(
        preEq31Val: Float = 0f,
        postEqVal: Float = 1.0f,
        inputGainDb: Float = -0.5f,
        leftTrimDb: Float = 0f,
        rightTrimDb: Float = 0f,
    ): EqEngineFrame = EqEngineFrame(
        preEqDb = FloatArray(10) { preEq31Val },
        postEqDb = FloatArray(10) { postEqVal },
        totalEqDb = FloatArray(10) { preEq31Val + postEqVal },
        adaptiveDeltaDb = FloatArray(10),
        inputGainDb = inputGainDb,
        loudnessBoostDb = 0f,
        engineStatus = EngineStatus.AUTO_FFT_LOCKED,
        displayBins36 = FloatArray(36),
        spectralTag = "Balanced",
        preEq31Db = FloatArray(31) { preEq31Val },
        leftBalanceTrimDb = leftTrimDb,
        rightBalanceTrimDb = rightTrimDb,
    )

    private data class ApplyCall(
        val frame: EqEngineFrame,
        val mbcMode: MbcMode,
        val active: Boolean,
    )

    private class FakeHandle(
        override val backend: DspBackend,
        override val sessionId: Int,
        override val preEqBandCount: Int,
        private val applyFailure: (() -> Throwable?) = { null },
    ) : DspSessionHandle {
        val applyCalls = mutableListOf<ApplyCall>()
        private val dirtyTracker = DspDirtyTracker(preEqBandCount = preEqBandCount)
        var nativeWriteCount: Int = 0
            private set
        var releaseCount: Int = 0
            private set

        override fun apply(frame: EqEngineFrame, mbcMode: MbcMode, active: Boolean) {
            applyFailure()?.let { throw it }
            applyCalls += ApplyCall(frame = frame, mbcMode = mbcMode, active = active)
            val preSource = if (preEqBandCount == 31) frame.preEq31Db else frame.preEqDb
            val (leftGain, rightGain) = DspEffectController.effectiveChannelInputGains(frame)
            val plan = dirtyTracker.evaluateAndCommit(
                preEq = preSource,
                postEq = frame.postEqDb,
                leftInputGainDb = leftGain,
                rightInputGainDb = rightGain,
                mbcMode = mbcMode,
                active = active,
            )
            if (plan.writeEnabled) nativeWriteCount += 1
            if (plan.writeLeftInputGain) nativeWriteCount += 1
            if (plan.writeRightInputGain) nativeWriteCount += 1
            if (plan.writePreEq) nativeWriteCount += 1
            if (plan.writePostEq) nativeWriteCount += 1
            if (plan.writeMbc) nativeWriteCount += 1
        }

        override fun release() {
            releaseCount += 1
        }
    }

    private class FakeDspBackendFactory(
        private val dpFailure: (Int, Int) -> Throwable? = { _, _ -> null },
        private val legacyFailure: (Int) -> Throwable? = { null },
        private val applyFailure: () -> Throwable? = { null },
    ) : DspBackendFactory {
        val createdHandles = mutableListOf<FakeHandle>()
        val attempts = mutableListOf<String>()

        override fun createDynamicsProcessing(sessionId: Int, preEqBandCount: Int): DspSessionHandle {
            attempts += "DP($sessionId,$preEqBandCount)"
            dpFailure(sessionId, preEqBandCount)?.let { throw it }
            return FakeHandle(DspBackend.DYNAMICS_PROCESSING, sessionId, preEqBandCount, applyFailure)
                .also { createdHandles += it }
        }

        override fun createLegacyEqualizer(sessionId: Int): DspSessionHandle {
            attempts += "LEGACY($sessionId)"
            legacyFailure(sessionId)?.let { throw it }
            return FakeHandle(DspBackend.LEGACY_EQ, sessionId, 5, applyFailure)
                .also { createdHandles += it }
        }

        fun activeHandlesCount(): Int = createdHandles.count { it.releaseCount == 0 }
    }
}
