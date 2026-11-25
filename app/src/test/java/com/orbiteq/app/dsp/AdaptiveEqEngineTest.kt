package com.orbiteq.app.dsp

import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.ResponseSpeed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class AdaptiveEqEngineTest {

    @Test
    fun q30CleanBaseCompensationLandsInPreEqAndAddsWithManualTrims() {
        val engine = AdaptiveEqEngine()
        val trims = listOf(1.5f, 0f, -1.0f, 0f, 0.5f, 0f, -1.0f, 0.5f, 2.0f, -0.5f)
        val expectedQ30 = PleasureProfiles.compensationCurveFor(HeadphoneProfile.Q30_CLEAN_BASE)

        val frame = engine.processFrame(
            fftBytes = ByteArray(1024),
            nowMs = 100L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = false,
            mode = PleasureMode.NOVA,
            intensityPercent = 0,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 0f,
            airMacroDb = 0f,
            dynamicLoudness = false,
            volumeRatio = 0.8f,
            headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            manualTrimsDb = trims,
        )

        val expectedPre = FloatArray(10) { i -> (expectedQ30[i] + trims[i]).coerceIn(-15f, 15f) }
        assertArrayEquals(expectedPre, frame.preEqDb, 0.0001f)
        assertArrayEquals(FloatArray(10), frame.postEqDb, 0.0001f)
        assertArrayEquals(expectedPre, frame.totalEqDb, 0.0001f)
    }

    @Test
    fun muddyBassFftProducesNegativeDeltaAt125To250HzAndThinLowBassLifts31To62Hz() {
        val muddyEngine = AdaptiveEqEngine()
        val muddyFft = syntheticFftForFrequencyRange(minHz = 125f, maxHz = 250f, re = 96, im = 24)
        lateinit var muddyFrame: EqEngineFrame

        repeat(10) { step ->
            muddyFrame = muddyEngine.processFrame(
                fftBytes = muddyFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.NOVA,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 0f,
                airMacroDb = 0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
                manualTrimsDb = List(10) { 0f },
            )
        }

        assertTrue("125 Hz adaptive delta should be negative: ${muddyFrame.adaptiveDeltaDb[2]}", muddyFrame.adaptiveDeltaDb[2] < -0.5f)
        assertTrue("250 Hz adaptive delta should be negative: ${muddyFrame.adaptiveDeltaDb[3]}", muddyFrame.adaptiveDeltaDb[3] < -0.5f)
        assertTrue(muddyFrame.adaptiveDeltaDb.all { it.isFinite() && it in -5.5f..5.5f })

        val thinEngine = AdaptiveEqEngine()
        val thinFft = syntheticFftForFrequencyRange(minHz = 1200f, maxHz = 4000f, re = 80, im = 20)
        lateinit var thinFrame: EqEngineFrame

        repeat(10) { step ->
            thinFrame = thinEngine.processFrame(
                fftBytes = thinFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.NOVA,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 0f,
                airMacroDb = 0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
                manualTrimsDb = List(10) { 0f },
            )
        }

        assertTrue("31 Hz adaptive delta should lift thin tracks: ${thinFrame.adaptiveDeltaDb[0]}", thinFrame.adaptiveDeltaDb[0] > 0.5f)
        assertTrue("62 Hz adaptive delta should lift thin tracks: ${thinFrame.adaptiveDeltaDb[1]}", thinFrame.adaptiveDeltaDb[1] > 0.5f)
        assertTrue(thinFrame.adaptiveDeltaDb.all { it.isFinite() && it in -5.5f..5.5f })

        // Verify intensityPercent scales the adaptive delta clamp [-5.5f, +5.5f] * (intensity / 100f)
        val halfIntensityEngine = AdaptiveEqEngine()
        lateinit var halfFrame: EqEngineFrame
        repeat(15) { step ->
            halfFrame = halfIntensityEngine.processFrame(
                fftBytes = muddyFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.NOVA,
                intensityPercent = 50,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 0f,
                airMacroDb = 0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
                manualTrimsDb = List(10) { 0f },
            )
        }
        assertTrue(halfFrame.adaptiveDeltaDb.all { it in -2.7501f..2.7501f })
    }

    @Test
    fun asymmetricEmaReactiveConvergesFasterThanChillAcrossFiveFrames() {
        val reactiveEngine = AdaptiveEqEngine()
        val chillEngine = AdaptiveEqEngine()
        val muddyFft = syntheticFftForFrequencyRange(minHz = 125f, maxHz = 250f, re = 96, im = 24)
        lateinit var reactiveFrame: EqEngineFrame
        lateinit var chillFrame: EqEngineFrame

        repeat(5) { step ->
            reactiveFrame = reactiveEngine.processFrame(
                fftBytes = muddyFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.NOVA,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 0f,
                airMacroDb = 0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
                manualTrimsDb = List(10) { 0f },
            )
            chillFrame = chillEngine.processFrame(
                fftBytes = muddyFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.NOVA,
                intensityPercent = 100,
                speed = ResponseSpeed.CHILL,
                bassMacroDb = 0f,
                airMacroDb = 0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
                manualTrimsDb = List(10) { 0f },
            )
        }

        assertTrue(
            "REACTIVE (${reactiveFrame.adaptiveDeltaDb[2]}) should converge faster than CHILL (${chillFrame.adaptiveDeltaDb[2]})",
            abs(reactiveFrame.adaptiveDeltaDb[2]) > abs(chillFrame.adaptiveDeltaDb[2]) * 2.0f,
        )
    }

    @Test
    fun fletcherMunsonLoudnessBoostsLowVolumeAndZeroesAboveThresholdOrWhenDisabled() {
        val engine = AdaptiveEqEngine()

        val minVolFrame = engine.processFrame(
            fftBytes = ByteArray(1024),
            nowMs = 100L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = false,
            mode = PleasureMode.NOVA,
            intensityPercent = 0,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 0f,
            airMacroDb = 0f,
            dynamicLoudness = true,
            volumeRatio = 0.0f,
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { 0f },
        )
        assertEquals(4.0f, minVolFrame.loudnessBoostDb, 0.01f)
        assertEquals(4.0f, minVolFrame.postEqDb[0], 0.01f)
        assertEquals(4.0f, minVolFrame.postEqDb[1], 0.01f)
        assertEquals(2.8f, minVolFrame.postEqDb[8], 0.01f)
        assertEquals(2.8f, minVolFrame.postEqDb[9], 0.01f)
        assertEquals(0.0f, minVolFrame.postEqDb[5], 0.01f)

        val lowVolFrame = engine.processFrame(
            fftBytes = ByteArray(1024),
            nowMs = 150L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = false,
            mode = PleasureMode.NOVA,
            intensityPercent = 0,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 0f,
            airMacroDb = 0f,
            dynamicLoudness = true,
            volumeRatio = 0.20f,
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { 0f },
        )
        assertTrue(lowVolFrame.loudnessBoostDb > 0f && lowVolFrame.loudnessBoostDb <= 4.0f)
        assertTrue(lowVolFrame.postEqDb[0] > 2.0f && lowVolFrame.postEqDb[0] <= 4.0f)
        assertTrue(lowVolFrame.postEqDb[8] > 1.5f && lowVolFrame.postEqDb[8] <= 2.8f)

        val highVolFrame = engine.processFrame(
            fftBytes = ByteArray(1024),
            nowMs = 200L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = false,
            mode = PleasureMode.NOVA,
            intensityPercent = 0,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 0f,
            airMacroDb = 0f,
            dynamicLoudness = true,
            volumeRatio = 0.80f,
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { 0f },
        )
        assertEquals(0f, highVolFrame.loudnessBoostDb, 0.0001f)
        assertArrayEquals(FloatArray(10), highVolFrame.postEqDb, 0.0001f)

        val disabledFrame = engine.processFrame(
            fftBytes = ByteArray(1024),
            nowMs = 250L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = false,
            mode = PleasureMode.NOVA,
            intensityPercent = 0,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 0f,
            airMacroDb = 0f,
            dynamicLoudness = false,
            volumeRatio = 0.20f,
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { 0f },
        )
        assertEquals(0f, disabledFrame.loudnessBoostDb, 0.0001f)
        assertArrayEquals(FloatArray(10), disabledFrame.postEqDb, 0.0001f)
    }

    @Test
    fun extremeGainStackingClampsAllBandsAndAppliesNegativePreampHeadroom() {
        val engine = AdaptiveEqEngine()
        val thinFft = syntheticFftForFrequencyRange(minHz = 1200f, maxHz = 3200f, re = 90, im = 30)
        lateinit var frame: EqEngineFrame

        repeat(8) { step ->
            frame = engine.processFrame(
                fftBytes = thinFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.GRAVITY,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 8.0f,
                airMacroDb = 8.0f,
                dynamicLoudness = true,
                volumeRatio = 0.10f,
                headphoneProfile = HeadphoneProfile.Q30_SAFE_STACK,
                manualTrimsDb = List(10) { 8.0f },
            )
        }

        assertTrue(frame.preEqDb.all { it.isFinite() && it in -15f..15f })
        assertTrue(frame.postEqDb.all { it.isFinite() && it in -15f..15f })
        assertTrue(frame.totalEqDb.all { it.isFinite() && it in -15f..15f })
        assertTrue(frame.adaptiveDeltaDb.all { it.isFinite() && it in -5.5f..5.5f })
        assertTrue(frame.inputGainDb.isFinite())

        val peakPositive = frame.totalEqDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        val maxAllowedInputGain = -max(0f, peakPositive - 1.5f) - 2.0f
        assertTrue(
            "Expected inputGainDb (${frame.inputGainDb}) <= $maxAllowedInputGain",
            frame.inputGainDb <= maxAllowedInputGain + 0.0001f,
        )
    }

    @Test
    fun drmSilentFftWatchdogSwitchesToProfileFallbackAfter1500msAndDecaysAdaptiveDelta() {
        val engine = AdaptiveEqEngine()
        val muddyFft = syntheticFftForFrequencyRange(minHz = 125f, maxHz = 250f, re = 100, im = 20)

        lateinit var lockedFrame: EqEngineFrame
        repeat(8) { step ->
            lockedFrame = engine.processFrame(
                fftBytes = muddyFft,
                nowMs = 1000L + step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.GRAVITY,
                intensityPercent = 100,
                speed = ResponseSpeed.BALANCED,
                bassMacroDb = 2.0f,
                airMacroDb = 1.5f,
                dynamicLoudness = true,
                volumeRatio = 0.25f,
                headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
                manualTrimsDb = List(10) { 0f },
            )
        }
        assertEquals(EngineStatus.AUTO_FFT_LOCKED, lockedFrame.engineStatus)
        val initialMudDelta = lockedFrame.adaptiveDeltaDb[2]
        assertTrue("Expected non-trivial locked delta before silence", initialMudDelta < -0.5f)

        val zeroFft = ByteArray(1024)
        val beforeThreshold = engine.processFrame(
            fftBytes = zeroFft,
            nowMs = 2000L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = false,
            mode = PleasureMode.GRAVITY,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 2.0f,
            airMacroDb = 1.5f,
            dynamicLoudness = true,
            volumeRatio = 0.25f,
            headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            manualTrimsDb = List(10) { 0f },
        )
        assertEquals(EngineStatus.AUTO_FFT_LOCKED, beforeThreshold.engineStatus)

        val justBeforeThreshold = engine.processFrame(
            fftBytes = zeroFft,
            nowMs = 3499L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = false,
            mode = PleasureMode.GRAVITY,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 2.0f,
            airMacroDb = 1.5f,
            dynamicLoudness = true,
            volumeRatio = 0.25f,
            headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            manualTrimsDb = List(10) { 0f },
        )
        assertEquals(EngineStatus.AUTO_FFT_LOCKED, justBeforeThreshold.engineStatus)

        val fallbackStart = engine.processFrame(
            fftBytes = zeroFft,
            nowMs = 3500L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = false,
            mode = PleasureMode.GRAVITY,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 2.0f,
            airMacroDb = 1.5f,
            dynamicLoudness = true,
            volumeRatio = 0.25f,
            headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            manualTrimsDb = List(10) { 0f },
        )
        assertEquals(EngineStatus.PROFILE_FALLBACK, fallbackStart.engineStatus)
        assertTrue(
            "Fallback should decay adaptive delta (${fallbackStart.adaptiveDeltaDb[2]}) toward 0 from $initialMudDelta",
            abs(fallbackStart.adaptiveDeltaDb[2]) < abs(initialMudDelta),
        )

        var laterFallback = fallbackStart
        repeat(45) { idx ->
            val input = if (idx % 3 == 0) null else if (idx % 3 == 1) ByteArray(2) else zeroFft
            laterFallback = engine.processFrame(
                fftBytes = input,
                nowMs = 3550L + idx * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.GRAVITY,
                intensityPercent = 100,
                speed = ResponseSpeed.BALANCED,
                bassMacroDb = 2.0f,
                airMacroDb = 1.5f,
                dynamicLoudness = true,
                volumeRatio = 0.25f,
                headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
                manualTrimsDb = List(10) { 0f },
            )
        }

        assertEquals(EngineStatus.PROFILE_FALLBACK, laterFallback.engineStatus)
        assertTrue(
            "Adaptive delta should decay near zero after sustained fallback: ${laterFallback.adaptiveDeltaDb[2]}",
            abs(laterFallback.adaptiveDeltaDb[2]) < abs(initialMudDelta) * 0.2f,
        )
        assertArrayEquals(
            PleasureProfiles.compensationCurveFor(HeadphoneProfile.Q30_CLEAN_BASE),
            laterFallback.preEqDb,
            0.0001f,
        )
        assertTrue("Dynamic loudness stays active during fallback", laterFallback.loudnessBoostDb > 0f)
        assertTrue("Pleasure mode GRAVITY + macros stay active in postEqDb", laterFallback.postEqDb[0] > 5.0f)

        // Recovery when live FFT resumes
        val recovered = engine.processFrame(
            fftBytes = muddyFft,
            nowMs = 6000L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = false,
            mode = PleasureMode.GRAVITY,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 2.0f,
            airMacroDb = 1.5f,
            dynamicLoudness = true,
            volumeRatio = 0.25f,
            headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            manualTrimsDb = List(10) { 0f },
        )
        assertEquals(EngineStatus.AUTO_FFT_LOCKED, recovered.engineStatus)
    }

    @Test
    fun bypassAndPowerOffBehaviorsRespectTelemetryContracts() {
        val engine = AdaptiveEqEngine()
        val activeFft = syntheticFftForFrequencyRange(minHz = 400f, maxHz = 1600f, re = 80, im = 40)

        val bypassFrame = engine.processFrame(
            fftBytes = activeFft,
            nowMs = 100L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = true,
            mode = PleasureMode.GRAVITY,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 5.0f,
            airMacroDb = 3.0f,
            dynamicLoudness = true,
            volumeRatio = 0.20f,
            headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            manualTrimsDb = List(10) { 4.0f },
        )

        assertEquals(EngineStatus.BYPASSED, bypassFrame.engineStatus)
        assertArrayEquals(FloatArray(10), bypassFrame.preEqDb, 0.0001f)
        assertArrayEquals(FloatArray(10), bypassFrame.postEqDb, 0.0001f)
        assertArrayEquals(FloatArray(10), bypassFrame.totalEqDb, 0.0001f)
        assertEquals(0f, bypassFrame.inputGainDb, 0.0001f)
        assertEquals(0f, bypassFrame.loudnessBoostDb, 0.0001f)
        assertTrue("Bypass must retain non-zero live displayBins36", bypassFrame.displayBins36.any { it > 0.05f })
        assertTrue(bypassFrame.displayBins36.all { it.isFinite() && it in 0f..1f })

        val idleFrame = engine.processFrame(
            fftBytes = activeFft,
            nowMs = 200L,
            isMediaPlaying = true,
            powerEnabled = false,
            bypassed = false,
            mode = PleasureMode.GRAVITY,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 5.0f,
            airMacroDb = 3.0f,
            dynamicLoudness = true,
            volumeRatio = 0.20f,
            headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            manualTrimsDb = List(10) { 4.0f },
        )

        assertEquals(EngineStatus.IDLE, idleFrame.engineStatus)
        assertArrayEquals(FloatArray(10), idleFrame.preEqDb, 0.0001f)
        assertArrayEquals(FloatArray(10), idleFrame.postEqDb, 0.0001f)
        assertArrayEquals(FloatArray(10), idleFrame.totalEqDb, 0.0001f)
        assertEquals(0f, idleFrame.inputGainDb, 0.0001f)
        assertEquals(0f, idleFrame.loudnessBoostDb, 0.0001f)
    }

    private fun syntheticFftForFrequencyRange(
        minHz: Float,
        maxHz: Float,
        re: Int,
        im: Int,
        size: Int = 1024,
        sampleRateHz: Int = 48000,
    ): ByteArray {
        val bytes = ByteArray(size)
        val df = sampleRateHz.toFloat() / size.toFloat()
        val kStart = (minHz / df).roundToInt().coerceIn(1, size / 2 - 1)
        val kEnd = (maxHz / df).roundToInt().coerceIn(kStart, size / 2 - 1)
        for (k in kStart..kEnd) {
            bytes[2 * k] = re.toByte()
            bytes[2 * k + 1] = im.toByte()
        }
        return bytes
    }
}
