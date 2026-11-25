package com.orbiteq.app.dsp

import com.orbiteq.app.model.CustomHeadphoneProfile
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.ResponseSpeed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class AdaptiveEqEngineTest {

    @Test
    fun q30CleanBaseCompensationLandsInPreEqAndAddsWithManualTrims() {
        val engine = AdaptiveEqEngine()
        val trims = listOf(1.5f, 0f, -1.0f, 0f, 0.5f, 0f, -1.0f, 0.5f, 2.0f, -0.5f)
        val expectedQ30_31 = PleasureProfiles.compensationCurve31For(HeadphoneProfile.Q30_CLEAN_BASE)
        val trims31 = PleasureProfiles.interpolate10To31(trims.toFloatArray())

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

        val expectedPre31 = FloatArray(31) { i -> (expectedQ30_31[i] + trims31[i]).coerceIn(-15f, 15f) }
        val expectedPre10 = PleasureProfiles.sample31To10(expectedPre31)
        assertArrayEquals(expectedPre31, frame.preEq31Db, 0.0001f)
        assertArrayEquals(expectedPre10, frame.preEqDb, 0.0001f)
        assertArrayEquals(FloatArray(10), frame.postEqDb, 0.0001f)
        assertArrayEquals(expectedPre10, frame.totalEqDb, 0.0001f)
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
            PleasureProfiles.sample31To10(
                PleasureProfiles.compensationCurve31For(HeadphoneProfile.Q30_CLEAN_BASE),
            ),
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
    fun unavailableFftCaptureImmediatelyEntersProfileFallbackAndDecaysAdaptiveDelta() {
        val engine = AdaptiveEqEngine()
        val muddyFft = syntheticFftForFrequencyRange(minHz = 125f, maxHz = 250f, re = 100, im = 20)

        // First establish non-zero adaptive deltas under Tier 1 live FFT.
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
                fftCaptureAvailable = true,
            )
        }
        assertEquals(EngineStatus.AUTO_FFT_LOCKED, lockedFrame.engineStatus)
        val initialMudDelta = lockedFrame.adaptiveDeltaDb[2]
        assertTrue("Expected negative delta before Tier 2 drop", initialMudDelta < -0.5f)

        // Even with isMediaPlaying = false and dt = 10 ms (< 1500 ms), fftCaptureAvailable = false must
        // immediately report PROFILE_FALLBACK and decay adaptive deltas toward 0.
        val tier2Immediate = engine.processFrame(
            fftBytes = null,
            nowMs = 1410L,
            isMediaPlaying = false,
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
            fftCaptureAvailable = false,
        )
        assertEquals(EngineStatus.PROFILE_FALLBACK, tier2Immediate.engineStatus)
        assertTrue(
            "Adaptive delta should begin decaying immediately: ${tier2Immediate.adaptiveDeltaDb[2]} vs $initialMudDelta",
            abs(tier2Immediate.adaptiveDeltaDb[2]) < abs(initialMudDelta),
        )
        assertArrayEquals(
            PleasureProfiles.sample31To10(
                PleasureProfiles.compensationCurve31For(HeadphoneProfile.Q30_CLEAN_BASE),
            ),
            tier2Immediate.preEqDb,
            0.0001f,
        )
        assertTrue("Dynamic loudness remains active in Tier 2", tier2Immediate.loudnessBoostDb > 0f)
        assertTrue("Pleasure mode + macros remain active in Tier 2", tier2Immediate.postEqDb[0] > 5.0f)

        // Fresh engine starting in Tier 2 (fftCaptureAvailable = false) also starts directly in PROFILE_FALLBACK.
        val freshTier2Engine = AdaptiveEqEngine()
        val freshFrame = freshTier2Engine.processFrame(
            fftBytes = muddyFft,
            nowMs = 0L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = false,
            mode = PleasureMode.NOVA,
            intensityPercent = 80,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 1.0f,
            airMacroDb = 1.0f,
            dynamicLoudness = false,
            volumeRatio = 0.8f,
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { 0f },
            fftCaptureAvailable = false,
        )
        assertEquals(EngineStatus.PROFILE_FALLBACK, freshFrame.engineStatus)
        assertArrayEquals(FloatArray(10), freshFrame.adaptiveDeltaDb, 0.0001f)
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
        assertArrayEquals(FloatArray(31), idleFrame.preEq31Db, 0.0001f)
        assertEquals(0f, idleFrame.leftBalanceTrimDb, 0.0001f)
        assertEquals(0f, idleFrame.rightBalanceTrimDb, 0.0001f)
        assertArrayEquals(FloatArray(31), bypassFrame.preEq31Db, 0.0001f)
        assertEquals(0f, bypassFrame.leftBalanceTrimDb, 0.0001f)
        assertEquals(0f, bypassFrame.rightBalanceTrimDb, 0.0001f)
    }

    @Test
    fun sampleRateHzForwardsToFftAnalyzerAndAlignsAdaptiveBands() {
        val engine96k = AdaptiveEqEngine()
        val bin11Fft = ByteArray(1024)
        bin11Fft[2 * 11] = 96

        lateinit var frame96k: EqEngineFrame
        repeat(10) { step ->
            frame96k = engine96k.processFrame(
                fftBytes = bin11Fft,
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
                sampleRateHz = 96000,
            )
        }

        // At 96 kHz, bin 11 is 1031.25 Hz -> cuts 1 kHz (band 5), not 500 Hz (band 4)
        assertTrue(
            "At 96 kHz, 1 kHz delta (${frame96k.adaptiveDeltaDb[5]}) should be strongly negative",
            frame96k.adaptiveDeltaDb[5] < -0.5f,
        )
        assertTrue(
            "At 96 kHz, 500 Hz delta (${frame96k.adaptiveDeltaDb[4]}) should lift rather than cut",
            frame96k.adaptiveDeltaDb[4] > 0f,
        )
    }

    @Test
    fun closedLoopDeembeddingConvergesSmoothlyForPostEffectAndPreEffectWithoutOscillation() {
        // (a) Simulated post-effect feedback with GRAVITY + Q30_CLEAN_BASE over 200 frames
        val postEffectEngine = AdaptiveEqEngine()
        val postDeltas = mutableListOf<FloatArray>()
        var prevTotalEq = FloatArray(10)
        lateinit var lastPostFrame: EqEngineFrame

        for (step in 0 until 200) {
            val postFft = syntheticPinkMusicFftWithBandGainDb(prevTotalEq)
            lastPostFrame = postEffectEngine.processFrame(
                fftBytes = postFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.GRAVITY,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 2.0f,
                airMacroDb = 1.0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
                manualTrimsDb = List(10) { 0f },
                deembedEnabled = true,
            )
            assertEquals(EngineStatus.AUTO_FFT_LOCKED, lastPostFrame.engineStatus)
            assertTrue(lastPostFrame.adaptiveDeltaDb.all { it.isFinite() && it in -5.5f..5.5f })
            assertTrue(lastPostFrame.totalEqDb.all { it.isFinite() })
            postDeltas += lastPostFrame.adaptiveDeltaDb.copyOf()
            prevTotalEq = lastPostFrame.totalEqDb
        }

        var postTailMaxJitter = 0f
        for (i in 181 until 200) {
            for (b in 0 until 10) {
                val diff = abs(postDeltas[i][b] - postDeltas[i - 1][b])
                if (diff > postTailMaxJitter) postTailMaxJitter = diff
            }
        }
        assertTrue(
            "Post-effect closed-loop final 20 frames max jitter ($postTailMaxJitter dB) must be < 0.15 dB",
            postTailMaxJitter < 0.15f,
        )

        // Compare against post-effect feedback with deembedEnabled = false
        val noDeembedEngine = AdaptiveEqEngine()
        var noDeembedPrevEq = FloatArray(10)
        lateinit var lastNoDeembedFrame: EqEngineFrame
        for (step in 0 until 200) {
            val postFft = syntheticPinkMusicFftWithBandGainDb(noDeembedPrevEq)
            lastNoDeembedFrame = noDeembedEngine.processFrame(
                fftBytes = postFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.GRAVITY,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 2.0f,
                airMacroDb = 1.0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
                manualTrimsDb = List(10) { 0f },
                deembedEnabled = false,
            )
            noDeembedPrevEq = lastNoDeembedFrame.totalEqDb
        }

        val netBassWithDeembed = lastPostFrame.totalEqDb[0] + lastPostFrame.totalEqDb[1]
        val netBassWithoutDeembed = lastNoDeembedFrame.totalEqDb[0] + lastNoDeembedFrame.totalEqDb[1]
        assertTrue(
            "De-embedded GRAVITY net bass ($netBassWithDeembed dB) must retain significantly more bass than without de-embedding ($netBassWithoutDeembed dB)",
            netBassWithDeembed > netBassWithoutDeembed + 0.5f,
        )
        assertTrue(
            "De-embedded 31 Hz adaptive delta (${lastPostFrame.adaptiveDeltaDb[0]} dB) must avoid self-cancellation compared to non-deembedded (${lastNoDeembedFrame.adaptiveDeltaDb[0]} dB)",
            lastPostFrame.adaptiveDeltaDb[0] > lastNoDeembedFrame.adaptiveDeltaDb[0] + 0.4f,
        )

        // (b) Pre-effect constant spectrum with GRAVITY + Q30_CLEAN_BASE over 200 frames
        val preEffectEngine = AdaptiveEqEngine()
        val preDeltas = mutableListOf<FloatArray>()
        val constantPinkFft = syntheticPinkMusicFftWithBandGainDb(FloatArray(10))

        for (step in 0 until 200) {
            val frame = preEffectEngine.processFrame(
                fftBytes = constantPinkFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.GRAVITY,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 2.0f,
                airMacroDb = 1.0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
                manualTrimsDb = List(10) { 0f },
                deembedEnabled = true,
            )
            assertEquals(EngineStatus.AUTO_FFT_LOCKED, frame.engineStatus)
            assertTrue(frame.adaptiveDeltaDb.all { it.isFinite() && it in -5.5f..5.5f })
            preDeltas += frame.adaptiveDeltaDb.copyOf()
        }

        var preTailMaxJitter = 0f
        for (i in 181 until 200) {
            for (b in 0 until 10) {
                val diff = abs(preDeltas[i][b] - preDeltas[i - 1][b])
                if (diff > preTailMaxJitter) preTailMaxJitter = diff
            }
        }
        assertTrue(
            "Pre-effect constant-spectrum final 20 frames max jitter ($preTailMaxJitter dB) must be < 0.15 dB",
            preTailMaxJitter < 0.15f,
        )
    }

    @Test
    fun noiseFloorFlatnessGuardDecaysAdaptiveDeltaWhileKeepingAutoFftLocked() {
        val engine = AdaptiveEqEngine()
        val muddyFft = syntheticFftForFrequencyRange(minHz = 125f, maxHz = 250f, re = 100, im = 20)

        lateinit var lockedFrame: EqEngineFrame
        repeat(10) { step ->
            lockedFrame = engine.processFrame(
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
        assertEquals(EngineStatus.AUTO_FFT_LOCKED, lockedFrame.engineStatus)
        val initialDelta = lockedFrame.adaptiveDeltaDb[2]
        assertTrue("Expected negative delta before noise floor", initialDelta < -0.5f)

        val whiteNoiseFft = syntheticWhiteNoiseFft(size = 1024, re = 2, im = 2)
        var prevAbs = abs(initialDelta)
        lateinit var noiseFrame: EqEngineFrame

        // Advance past 1500 ms watchdog threshold (25 * 100 ms = 2500 ms)
        repeat(25) { step ->
            noiseFrame = engine.processFrame(
                fftBytes = whiteNoiseFft,
                nowMs = 1000L + step * 100L,
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
            assertEquals(
                "Noise floor must NOT trip PROFILE_FALLBACK watchdog",
                EngineStatus.AUTO_FFT_LOCKED,
                noiseFrame.engineStatus,
            )
            val currentAbs = abs(noiseFrame.adaptiveDeltaDb[2])
            assertTrue(
                "Noise floor must gently decay adaptive delta ($currentAbs <= $prevAbs)",
                currentAbs <= prevAbs + 0.0001f,
            )
            prevAbs = currentAbs
        }

        assertTrue(
            "After 25 noise frames, delta (${noiseFrame.adaptiveDeltaDb[2]}) should decay significantly from $initialDelta",
            abs(noiseFrame.adaptiveDeltaDb[2]) < abs(initialDelta) * 0.25f,
        )
    }

    @Test
    fun trackChangeFastDecayDropsAdaptiveDeltaByAtLeastHalfImmediately() {
        val engine = AdaptiveEqEngine()
        val muddyFft = syntheticFftForFrequencyRange(minHz = 125f, maxHz = 250f, re = 100, im = 20)

        lateinit var prevFrame: EqEngineFrame
        repeat(12) { step ->
            prevFrame = engine.processFrame(
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
                trackKey = "spotify|track a",
            )
        }
        val beforeSwitch = abs(prevFrame.adaptiveDeltaDb[2])
        assertTrue("Expected established delta before track change", beforeSwitch > 0.5f)

        val transitionFrame = engine.processFrame(
            fftBytes = ByteArray(1024),
            nowMs = 700L,
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
            trackKey = "spotify|track b",
        )
        val afterSwitch = abs(transitionFrame.adaptiveDeltaDb[2])
        assertTrue(
            "Track switch must immediately drop abs(adaptiveDelta) by >= 50%: before=$beforeSwitch, after=$afterSwitch",
            afterSwitch <= beforeSwitch * 0.5f,
        )
    }

    @Test
    fun preEq31DbCombinesCustomProfileManualTrimsAndSubsonicFilter() {
        val engine = AdaptiveEqEngine()
        val customGains = MutableList(31) { idx -> if (idx == 22) 4.0f else -1.0f }
        customGains[0] = -12.0f
        customGains[5] = 25.0f // should clamp to +15
        val customProfile = CustomHeadphoneProfile(
            id = "custom-hd600",
            name = "HD 600 AutoEQ",
            gains31Db = customGains,
            extraHeadroomDb = 1.5f,
        )
        val trims = listOf(2.0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1.0f)
        val trims31 = PleasureProfiles.interpolate10To31(trims.toFloatArray())

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
            customHeadphoneProfile = customProfile,
            subsonicFilterEnabled = true,
        )

        // Subsonic filter applies -6.0 dB at 20 Hz (index 0) and -2.5 dB at 25 Hz (index 1)
        // Index 0: (-12.0 + 2.0 - 6.0) = -16.0 -> clamped to -15.0
        assertEquals(-15.0f, frame.preEq31Db[0], 0.0001f)
        // Index 1 (25 Hz): (-1.0 + 2.0 - 2.5) = -1.5
        assertEquals(-1.5f, frame.preEq31Db[1], 0.0001f)
        // Index 5 (63 Hz): custom 25.0 clamped to 15.0 first, + trim[1]=0.0 -> 15.0
        assertEquals(15.0f, frame.preEq31Db[5], 0.0001f)
        // Index 22 (3150 Hz): custom 4.0 + trim[22] (=0.0) -> 4.0
        assertEquals(4.0f, frame.preEq31Db[22], 0.0001f)
        // Verify preEqDb matches sample31To10(preEq31Db)
        assertArrayEquals(PleasureProfiles.sample31To10(frame.preEq31Db), frame.preEqDb, 0.0001f)
        // Index 10 (200 Hz): custom -1.0 + trims31[10]
        assertEquals(-1.0f + trims31[10], frame.preEq31Db[10], 0.0001f)
    }

    @Test
    fun trueHeadroomModeAnd31BandCascadePeakAndCustomExtraHeadroomWorkAccurately() {
        val engineStandard = AdaptiveEqEngine()
        val engineTrueHeadroom = AdaptiveEqEngine()

        // Custom profile with a narrow +6.0 dB peak at 3150 Hz (index 22) between 2k and 4k anchors
        val customGains = List(31) { idx -> if (idx == 22) 6.0f else 0f }
        val customProfile = CustomHeadphoneProfile(
            id = "custom-peak",
            name = "Narrow Peak",
            gains31Db = customGains,
            extraHeadroomDb = 1.0f,
        )

        val standardFrame = engineStandard.processFrame(
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
            headphoneProfile = HeadphoneProfile.Q30_SAFE_STACK, // ignored extraHeadroom because customProfile is set
            manualTrimsDb = List(10) { 0f },
            customHeadphoneProfile = customProfile,
            trueHeadroomMode = false,
        )

        val trueHeadroomFrame = engineTrueHeadroom.processFrame(
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
            headphoneProfile = HeadphoneProfile.Q30_SAFE_STACK,
            manualTrimsDb = List(10) { 0f },
            customHeadphoneProfile = customProfile,
            trueHeadroomMode = true,
        )

        // Peak at 3150 Hz is +6.0 dB, custom extraHeadroomDb is 1.0 dB
        assertEquals(-(6.0f - 1.5f) - 1.0f, standardFrame.inputGainDb, 0.0001f)
        assertEquals(-6.0f - 1.0f, trueHeadroomFrame.inputGainDb, 0.0001f)
        assertEquals(1.5f, standardFrame.inputGainDb - trueHeadroomFrame.inputGainDb, 0.0001f)
    }

    @Test
    fun channelBalanceAttenuatesOppositeChannelUpToMinus12DbNeverBoosting() {
        val engine = AdaptiveEqEngine()

        fun frameForBalance(balance: Float): EqEngineFrame = engine.processFrame(
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
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { 0f },
            channelBalance = balance,
        )

        val centered = frameForBalance(0f)
        assertEquals(0f, centered.leftBalanceTrimDb, 0.0001f)
        assertEquals(0f, centered.rightBalanceTrimDb, 0.0001f)

        val rightBias = frameForBalance(0.5f)
        assertEquals(-6.0f, rightBias.leftBalanceTrimDb, 0.0001f)
        assertEquals(0f, rightBias.rightBalanceTrimDb, 0.0001f)

        val leftBias = frameForBalance(-0.5f)
        assertEquals(0f, leftBias.leftBalanceTrimDb, 0.0001f)
        assertEquals(-6.0f, leftBias.rightBalanceTrimDb, 0.0001f)

        val clampedRight = frameForBalance(2.5f)
        assertEquals(-12.0f, clampedRight.leftBalanceTrimDb, 0.0001f)
        assertEquals(0f, clampedRight.rightBalanceTrimDb, 0.0001f)

        val nanBalance = frameForBalance(Float.NaN)
        assertEquals(0f, nanBalance.leftBalanceTrimDb, 0.0001f)
        assertEquals(0f, nanBalance.rightBalanceTrimDb, 0.0001f)

        assertNotEquals(rightBias, leftBias)
        assertEquals(rightBias, rightBias.copy())
        assertEquals(rightBias.hashCode(), rightBias.copy().hashCode())
    }

    private fun syntheticPinkMusicFftWithBandGainDb(
        bandGainDb: FloatArray,
        size: Int = 1024,
        sampleRateHz: Int = 48000,
    ): ByteArray {
        val bytes = ByteArray(size)
        val halfN = size / 2
        val df = sampleRateHz.toFloat() / size.toFloat()
        val isoFreqs = FloatArray(10) { i -> PleasureProfiles.ISO_FREQUENCIES_HZ[i].toFloat() }
        val logIso = FloatArray(10) { i -> log2(isoFreqs[i]) }

        for (k in 1 until halfN) {
            val freq = k * df
            val logF = log2(freq)
            val gainDb = when {
                freq <= isoFreqs[0] -> bandGainDb[0]
                freq >= isoFreqs[9] -> bandGainDb[9]
                else -> {
                    var idx = 0
                    while (idx < 8 && isoFreqs[idx + 1] <= freq) {
                        idx++
                    }
                    val frac = ((logF - logIso[idx]) / (logIso[idx + 1] - logIso[idx])).coerceIn(0f, 1f)
                    bandGainDb[idx] + (bandGainDb[idx + 1] - bandGainDb[idx]) * frac
                }
            }
            val baseMag = 40f / sqrt(k.toFloat())
            val gainedMag = (baseMag * 10f.pow(gainDb / 20f)).roundToInt().coerceIn(0, 127)
            bytes[2 * k] = gainedMag.toByte()
            bytes[2 * k + 1] = 0
        }
        return bytes
    }

    private fun syntheticWhiteNoiseFft(size: Int, re: Int, im: Int): ByteArray {
        val bytes = ByteArray(size)
        val halfN = size / 2
        for (k in 1 until halfN) {
            bytes[2 * k] = (if (k % 2 == 0) re else -re).toByte()
            bytes[2 * k + 1] = (if (k % 3 == 0) im else -im).toByte()
        }
        bytes[1] = sqrt((re * re + im * im).toFloat()).roundToInt().coerceIn(1, 127).toByte()
        return bytes
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
