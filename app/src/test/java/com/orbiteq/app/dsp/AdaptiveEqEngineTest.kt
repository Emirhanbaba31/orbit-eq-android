package com.orbiteq.app.dsp

import com.orbiteq.app.model.CustomHeadphoneProfile
import com.orbiteq.app.model.DeEsserMode
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
        assertEquals(4.8f, minVolFrame.loudnessBoostDb, 0.01f)
        assertEquals(4.8f, minVolFrame.postEqDb[0], 0.01f)
        assertEquals(4.4f, minVolFrame.postEqDb[1], 0.01f)
        assertEquals(2.4f, minVolFrame.postEqDb[8], 0.01f)
        assertEquals(2.2f, minVolFrame.postEqDb[9], 0.01f)
        assertEquals(0.0f, minVolFrame.postEqDb[4], 0.01f)
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
        assertTrue(lowVolFrame.loudnessBoostDb > 0f && lowVolFrame.loudnessBoostDb <= 4.8f)
        assertTrue(lowVolFrame.postEqDb[0] > 2.0f && lowVolFrame.postEqDb[0] <= 4.8f)
        assertTrue(lowVolFrame.postEqDb[8] > 1.2f && lowVolFrame.postEqDb[8] <= 2.4f)

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

    @Test
    fun modeAwarePsychoacousticReferencesRespectGravityBassAndHorizonAir() {
        val bassHeavyFft = syntheticFftForFrequencyRange(minHz = 35f, maxHz = 110f, re = 72, im = 18)
        val gravityEngine = AdaptiveEqEngine()
        val commsEngine = AdaptiveEqEngine()
        lateinit var gravityBassFrame: EqEngineFrame
        lateinit var commsBassFrame: EqEngineFrame

        repeat(10) { step ->
            gravityBassFrame = gravityEngine.processFrame(
                fftBytes = bassHeavyFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.GRAVITY,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 0f,
                airMacroDb = 0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
                manualTrimsDb = List(10) { 0f },
                deembedEnabled = false,
            )
            commsBassFrame = commsEngine.processFrame(
                fftBytes = bassHeavyFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.COMMS,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 0f,
                airMacroDb = 0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
                manualTrimsDb = List(10) { 0f },
                deembedEnabled = false,
            )
        }

        assertTrue(
            "GRAVITY 31 Hz delta (${gravityBassFrame.adaptiveDeltaDb[0]}) should cut less bass than COMMS (${commsBassFrame.adaptiveDeltaDb[0]})",
            gravityBassFrame.adaptiveDeltaDb[0] > commsBassFrame.adaptiveDeltaDb[0] + 0.15f,
        )
        assertTrue(
            "GRAVITY 62 Hz delta (${gravityBassFrame.adaptiveDeltaDb[1]}) should cut less bass than COMMS (${commsBassFrame.adaptiveDeltaDb[1]})",
            gravityBassFrame.adaptiveDeltaDb[1] > commsBassFrame.adaptiveDeltaDb[1] + 0.15f,
        )

        val brightFft = syntheticFftForFrequencyRange(minHz = 5000f, maxHz = 18000f, re = 64, im = 16)
        val horizonEngine = AdaptiveEqEngine()
        val eclipseEngine = AdaptiveEqEngine()
        lateinit var horizonAirFrame: EqEngineFrame
        lateinit var eclipseAirFrame: EqEngineFrame

        repeat(10) { step ->
            horizonAirFrame = horizonEngine.processFrame(
                fftBytes = brightFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.HORIZON,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 0f,
                airMacroDb = 0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
                manualTrimsDb = List(10) { 0f },
                deembedEnabled = false,
            )
            eclipseAirFrame = eclipseEngine.processFrame(
                fftBytes = brightFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.ECLIPSE,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 0f,
                airMacroDb = 0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
                manualTrimsDb = List(10) { 0f },
                deembedEnabled = false,
            )
        }

        assertTrue(
            "HORIZON 8 kHz delta (${horizonAirFrame.adaptiveDeltaDb[8]}) should preserve more air than ECLIPSE (${eclipseAirFrame.adaptiveDeltaDb[8]})",
            horizonAirFrame.adaptiveDeltaDb[8] > eclipseAirFrame.adaptiveDeltaDb[8] + 0.15f,
        )
        assertTrue(
            "HORIZON 16 kHz delta (${horizonAirFrame.adaptiveDeltaDb[9]}) should preserve more air than ECLIPSE (${eclipseAirFrame.adaptiveDeltaDb[9]})",
            horizonAirFrame.adaptiveDeltaDb[9] > eclipseAirFrame.adaptiveDeltaDb[9] + 0.15f,
        )
    }

    @Test
    fun iso226PerceptualLoudnessUsesNonlinearExponentAndZeroesAt70Percent() {
        val engine = AdaptiveEqEngine()

        fun frameAtVolume(volume: Float): EqEngineFrame = engine.processFrame(
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
            volumeRatio = volume,
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { 0f },
        )

        val atThreshold = frameAtVolume(0.70f)
        assertEquals(0f, atThreshold.loudnessBoostDb, 0.0001f)
        assertArrayEquals(FloatArray(10), atThreshold.postEqDb, 0.0001f)

        val aboveThreshold = frameAtVolume(0.75f)
        assertEquals(0f, aboveThreshold.loudnessBoostDb, 0.0001f)

        val halfThreshold = frameAtVolume(0.35f)
        val expectedScale = 0.5f.pow(1.35f)
        assertTrue("Expected nonlinear pow(1.35) scale < 0.5, got $expectedScale", expectedScale < 0.5f)
        assertEquals(4.8f * expectedScale, halfThreshold.loudnessBoostDb, 0.001f)
        assertTrue(halfThreshold.loudnessBoostDb < 2.4f)
        assertEquals(4.8f * expectedScale, halfThreshold.postEqDb[0], 0.001f)
        assertEquals(4.4f * expectedScale, halfThreshold.postEqDb[1], 0.001f)
        assertEquals(0.0f, halfThreshold.postEqDb[4], 0.0001f)
        assertEquals(0.0f, halfThreshold.postEqDb[5], 0.0001f)
        assertEquals(2.4f * expectedScale, halfThreshold.postEqDb[8], 0.001f)

        val vol15 = frameAtVolume(0.15f)
        val vol45 = frameAtVolume(0.45f)
        assertTrue(vol15.loudnessBoostDb > vol45.loudnessBoostDb && vol45.loudnessBoostDb > 0f)
        assertTrue(vol15.postEqDb[0] > vol15.postEqDb[8])
        assertTrue(vol15.postEqDb[8] > vol15.postEqDb[7])
        assertEquals(0.0f, vol15.postEqDb[5], 0.0001f)
    }

    @Test
    fun analogTiltCurve31ShapesPreEq31DbAndTotalEq31DbSymmetricallyAround1kHz() {
        val engine = AdaptiveEqEngine()

        val warmFrame = engine.processFrame(
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
            toneTiltDbPerOct = -1.0f,
        )
        val brightFrame = engine.processFrame(
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
            dynamicLoudness = false,
            volumeRatio = 0.8f,
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { 0f },
            toneTiltDbPerOct = 1.0f,
        )

        assertEquals(31, warmFrame.totalEq31Db.size)
        assertEquals(0f, warmFrame.preEq31Db[17], 0.0001f)
        assertEquals(0f, warmFrame.totalEq31Db[17], 0.0001f)
        for (i in 0 until 17) {
            assertTrue("Warm tilt below 1k ($i) should boost: ${warmFrame.preEq31Db[i]}", warmFrame.preEq31Db[i] > 0f)
        }
        for (i in 18 until 31) {
            assertTrue("Warm tilt above 1k ($i) should cut: ${warmFrame.preEq31Db[i]}", warmFrame.preEq31Db[i] < 0f)
        }
        assertEquals(4.0f, warmFrame.preEq31Db[0], 0.0001f)
        assertEquals(-4.0f, warmFrame.preEq31Db[30], 0.0001f)
        assertArrayEquals(warmFrame.preEq31Db, warmFrame.totalEq31Db, 0.0001f)

        for (i in 0 until 31) {
            assertEquals(
                "Tilt +1.0 should mirror -1.0 at band $i",
                -warmFrame.preEq31Db[i],
                brightFrame.preEq31Db[i],
                0.0001f,
            )
        }

        // Combined 31-band curve includes both preEq31Db and interpolated postEqDb
        val combinedFrame = engine.processFrame(
            fftBytes = ByteArray(1024),
            nowMs = 200L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = false,
            mode = PleasureMode.NOVA,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 2.0f,
            airMacroDb = 1.5f,
            dynamicLoudness = false,
            volumeRatio = 0.8f,
            headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            manualTrimsDb = List(10) { 0f },
            toneTiltDbPerOct = -0.6f,
        )
        val expectedPost31 = PleasureProfiles.interpolate10To31(combinedFrame.postEqDb)
        for (i in 0 until 31) {
            val expectedTotal = (combinedFrame.preEq31Db[i] + expectedPost31[i]).coerceIn(-15f, 15f)
            assertEquals(expectedTotal, combinedFrame.totalEq31Db[i], 0.0001f)
        }
    }

    @Test
    fun deEsserAssistCuts4To8kHzHarshnessWithoutDulling16kHzAir() {
        val harshFft = syntheticPinkMusicFftWithBandGainDb(
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 3.5f, 4.0f, 4.5f, 4.5f),
        )

        fun runWithDeEsser(mode: DeEsserMode, deembed: Boolean): EqEngineFrame {
            val engine = AdaptiveEqEngine()
            lateinit var lastFrame: EqEngineFrame
            repeat(12) { step ->
                lastFrame = engine.processFrame(
                    fftBytes = harshFft,
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
                    deembedEnabled = deembed,
                    deEsserMode = mode,
                )
            }
            return lastFrame
        }

        val offFrame = runWithDeEsser(DeEsserMode.OFF, deembed = false)
        val gentleFrame = runWithDeEsser(DeEsserMode.GENTLE, deembed = false)
        val strongFrame = runWithDeEsser(DeEsserMode.STRONG, deembed = false)

        assertTrue("Expected negative 4 kHz delta in OFF baseline: ${offFrame.adaptiveDeltaDb[7]}", offFrame.adaptiveDeltaDb[7] < -0.5f)
        assertTrue("Expected negative 8 kHz delta in OFF baseline: ${offFrame.adaptiveDeltaDb[8]}", offFrame.adaptiveDeltaDb[8] < -0.5f)
        assertTrue("Expected negative 16 kHz delta in OFF baseline: ${offFrame.adaptiveDeltaDb[9]}", offFrame.adaptiveDeltaDb[9] < -0.5f)

        assertTrue(
            "GENTLE (${gentleFrame.adaptiveDeltaDb[7]}) must cut 4 kHz harder than OFF (${offFrame.adaptiveDeltaDb[7]})",
            gentleFrame.adaptiveDeltaDb[7] < offFrame.adaptiveDeltaDb[7] - 0.1f,
        )
        assertTrue(
            "GENTLE (${gentleFrame.adaptiveDeltaDb[8]}) must cut 8 kHz harder than OFF (${offFrame.adaptiveDeltaDb[8]})",
            gentleFrame.adaptiveDeltaDb[8] < offFrame.adaptiveDeltaDb[8] - 0.1f,
        )
        assertTrue(
            "STRONG (${strongFrame.adaptiveDeltaDb[6]}) must cut 2 kHz harder than OFF (${offFrame.adaptiveDeltaDb[6]})",
            strongFrame.adaptiveDeltaDb[6] < offFrame.adaptiveDeltaDb[6] - 0.05f,
        )
        assertTrue(
            "STRONG (${strongFrame.adaptiveDeltaDb[7]}) must cut 4 kHz harder than GENTLE (${gentleFrame.adaptiveDeltaDb[7]})",
            strongFrame.adaptiveDeltaDb[7] < gentleFrame.adaptiveDeltaDb[7] - 0.1f,
        )
        assertTrue(
            "STRONG (${strongFrame.adaptiveDeltaDb[8]}) must cut 8 kHz harder than GENTLE (${gentleFrame.adaptiveDeltaDb[8]})",
            strongFrame.adaptiveDeltaDb[8] < gentleFrame.adaptiveDeltaDb[8] - 0.1f,
        )

        // 16 kHz (band 9) must never receive increased cut sensitivity
        assertEquals(offFrame.adaptiveDeltaDb[9], gentleFrame.adaptiveDeltaDb[9], 0.0001f)
        assertEquals(offFrame.adaptiveDeltaDb[9], strongFrame.adaptiveDeltaDb[9], 0.0001f)

        val deembedOff = runWithDeEsser(DeEsserMode.OFF, deembed = true)
        val deembedStrong = runWithDeEsser(DeEsserMode.STRONG, deembed = true)
        assertTrue(deembedStrong.adaptiveDeltaDb[7] < deembedOff.adaptiveDeltaDb[7] - 0.1f)
        assertTrue(deembedStrong.adaptiveDeltaDb[8] < deembedOff.adaptiveDeltaDb[8] - 0.1f)
        assertTrue(
            "With de-embedding, STRONG 16 kHz (${deembedStrong.adaptiveDeltaDb[9]}) must not dull more than OFF (${deembedOff.adaptiveDeltaDb[9]})",
            deembedStrong.adaptiveDeltaDb[9] >= deembedOff.adaptiveDeltaDb[9] - 0.0001f,
        )
    }

    @Test
    fun computePerceivedNetGainDbAndEqEngineFrameEqualityContracts() {
        val weights = AdaptiveEqEngine.PERCEPTUAL_LOUDNESS_WEIGHTS_31
        assertEquals(31, weights.size)
        assertEquals(1.0f, weights.sum(), 1e-5f)
        assertTrue(weights.all { it.isFinite() && it > 0f })
        val minMidbandWeight = (14..23).minOf { weights[it] }
        val maxOutsideMidband = ((0..13) + (24..30)).maxOf { weights[it] }
        assertTrue(
            "Every 500 Hz - 4 kHz weight ($minMidbandWeight) should exceed outside weights ($maxOutsideMidband)",
            minMidbandWeight > maxOutsideMidband,
        )
        assertTrue(weights[0] < weights[7])
        assertTrue(weights[30] < weights[26])

        val engine = AdaptiveEqEngine()
        assertEquals(0f, engine.computePerceivedNetGainDb(FloatArray(31) { 3.0f }, -3.0f), 0.001f)
        assertEquals(1.5f, engine.computePerceivedNetGainDb(FloatArray(31) { 4.0f }, -2.5f), 0.001f)

        val subBassOnly = FloatArray(31) { idx -> if (idx < 5) 10.0f else 0f }
        val midbandOnly = FloatArray(31) { idx -> if (idx in 14..23) 10.0f else 0f }
        val topEndOnly = FloatArray(31) { idx -> if (idx >= 27) 10.0f else 0f }
        val subScore = engine.computePerceivedNetGainDb(subBassOnly, 0f)
        val midScore = engine.computePerceivedNetGainDb(midbandOnly, 0f)
        val topScore = engine.computePerceivedNetGainDb(topEndOnly, 0f)
        assertTrue("Midband ($midScore) must carry higher perceptual weight than sub-bass ($subScore)", midScore > subScore * 3f)
        assertTrue("Midband ($midScore) must carry higher perceptual weight than 10-20 kHz extremes ($topScore)", midScore > topScore * 3f)
        assertTrue(engine.computePerceivedNetGainDb(FloatArray(31) { Float.NaN }, Float.NaN).isFinite())

        val frameA = EqEngineFrame(
            preEqDb = FloatArray(10),
            postEqDb = FloatArray(10),
            totalEqDb = FloatArray(10),
            adaptiveDeltaDb = FloatArray(10),
            inputGainDb = 0f,
            loudnessBoostDb = 0f,
            engineStatus = EngineStatus.AUTO_FFT_LOCKED,
            displayBins36 = FloatArray(36),
            spectralTag = "Balanced",
            totalEq31Db = FloatArray(31) { 1.0f },
            bypassMatchGainDb = -1.5f,
        )
        val frameB = frameA.copy(totalEq31Db = FloatArray(31) { 2.0f })
        val frameC = frameA.copy(bypassMatchGainDb = -2.0f)
        val frameCopy = frameA.copy(totalEq31Db = FloatArray(31) { 1.0f })
        assertEquals(frameA, frameCopy)
        assertEquals(frameA.hashCode(), frameCopy.hashCode())
        assertNotEquals(frameA, frameB)
        assertNotEquals(frameA, frameC)
    }

    @Test
    fun loudnessMatchedBypassInvariantsEnforceAttenuationOnlyAndNeverAttenuateAutoBypass() {
        val engine = AdaptiveEqEngine()
        val pinkFft = syntheticPinkMusicFftWithBandGainDb(FloatArray(10))

        lateinit var activeFrame: EqEngineFrame
        repeat(6) { step ->
            activeFrame = engine.processFrame(
                fftBytes = pinkFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.NOVA,
                intensityPercent = 100,
                speed = ResponseSpeed.BALANCED,
                bassMacroDb = 2.0f,
                airMacroDb = 1.5f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
                manualTrimsDb = List(10) { 0f },
                deEsserMode = DeEsserMode.GENTLE,
            )
        }
        assertEquals("Active frames must report bypassMatchGainDb = 0f", 0f, activeFrame.bypassMatchGainDb, 0.0001f)
        assertTrue("Active frame should apply negative headroom: ${activeFrame.inputGainDb}", activeFrame.inputGainDb < -1.0f)

        val manualMatchedBypass = engine.processFrame(
            fftBytes = pinkFft,
            nowMs = 400L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = true,
            mode = PleasureMode.NOVA,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 2.0f,
            airMacroDb = 1.5f,
            dynamicLoudness = false,
            volumeRatio = 0.8f,
            headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            manualTrimsDb = List(10) { 0f },
            loudnessMatchedBypass = true,
            isAutoBypassed = false,
        )
        assertEquals(EngineStatus.BYPASSED, manualMatchedBypass.engineStatus)
        assertArrayEquals(FloatArray(10), manualMatchedBypass.preEqDb, 0.0001f)
        assertArrayEquals(FloatArray(10), manualMatchedBypass.postEqDb, 0.0001f)
        assertArrayEquals(FloatArray(10), manualMatchedBypass.totalEqDb, 0.0001f)
        assertArrayEquals(FloatArray(31), manualMatchedBypass.preEq31Db, 0.0001f)
        assertArrayEquals(FloatArray(31), manualMatchedBypass.totalEq31Db, 0.0001f)
        assertEquals(0f, manualMatchedBypass.leftBalanceTrimDb, 0.0001f)
        assertEquals(0f, manualMatchedBypass.rightBalanceTrimDb, 0.0001f)
        assertTrue(
            "Matched bypass gain (${manualMatchedBypass.bypassMatchGainDb}) must be strictly in [-9.0, -0.05)",
            manualMatchedBypass.bypassMatchGainDb in -9.0f..-0.05f,
        )
        assertEquals(manualMatchedBypass.bypassMatchGainDb, manualMatchedBypass.inputGainDb, 0.0001f)

        val autoBypassed = engine.processFrame(
            fftBytes = pinkFft,
            nowMs = 450L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = true,
            mode = PleasureMode.NOVA,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 2.0f,
            airMacroDb = 1.5f,
            dynamicLoudness = false,
            volumeRatio = 0.8f,
            headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            manualTrimsDb = List(10) { 0f },
            loudnessMatchedBypass = true,
            isAutoBypassed = true,
        )
        assertEquals(EngineStatus.BYPASSED, autoBypassed.engineStatus)
        assertEquals(0f, autoBypassed.inputGainDb, 0.0001f)
        assertEquals(0f, autoBypassed.bypassMatchGainDb, 0.0001f)

        val disabledMatchedBypass = engine.processFrame(
            fftBytes = pinkFft,
            nowMs = 500L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = true,
            mode = PleasureMode.NOVA,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 2.0f,
            airMacroDb = 1.5f,
            dynamicLoudness = false,
            volumeRatio = 0.8f,
            headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            manualTrimsDb = List(10) { 0f },
            loudnessMatchedBypass = false,
            isAutoBypassed = false,
        )
        assertEquals(0f, disabledMatchedBypass.inputGainDb, 0.0001f)
        assertEquals(0f, disabledMatchedBypass.bypassMatchGainDb, 0.0001f)

        // Power off resets remembered bypass match gain
        engine.processFrame(
            fftBytes = pinkFft,
            nowMs = 550L,
            isMediaPlaying = false,
            powerEnabled = false,
            bypassed = false,
            mode = PleasureMode.NOVA,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 0f,
            airMacroDb = 0f,
            dynamicLoudness = false,
            volumeRatio = 0.8f,
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { 0f },
        )
        val bypassAfterReset = engine.processFrame(
            fftBytes = pinkFft,
            nowMs = 600L,
            isMediaPlaying = false,
            powerEnabled = true,
            bypassed = true,
            mode = PleasureMode.NOVA,
            intensityPercent = 100,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 0f,
            airMacroDb = 0f,
            dynamicLoudness = false,
            volumeRatio = 0.8f,
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { 0f },
            loudnessMatchedBypass = true,
            isAutoBypassed = false,
        )
        assertEquals(0f, bypassAfterReset.inputGainDb, 0.0001f)
        assertEquals(0f, bypassAfterReset.bypassMatchGainDb, 0.0001f)

        // Net positive perceived EQ must clamp bypass match gain to 0f (never boost!)
        val positiveEngine = AdaptiveEqEngine()
        positiveEngine.processFrame(
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
            manualTrimsDb = List(10) { 4.0f },
        )
        val positiveBypass = positiveEngine.processFrame(
            fftBytes = ByteArray(1024),
            nowMs = 150L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = true,
            mode = PleasureMode.NOVA,
            intensityPercent = 0,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 0f,
            airMacroDb = 0f,
            dynamicLoudness = false,
            volumeRatio = 0.8f,
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { 4.0f },
            loudnessMatchedBypass = true,
            isAutoBypassed = false,
        )
        assertEquals(0f, positiveBypass.bypassMatchGainDb, 0.0001f)
        assertEquals(0f, positiveBypass.inputGainDb, 0.0001f)

        // Extreme negative EQ clamps bypass match gain at -9.0f
        val extremeCutEngine = AdaptiveEqEngine()
        extremeCutEngine.processFrame(
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
            manualTrimsDb = List(10) { -15.0f },
        )
        val clampedBypass = extremeCutEngine.processFrame(
            fftBytes = ByteArray(1024),
            nowMs = 150L,
            isMediaPlaying = true,
            powerEnabled = true,
            bypassed = true,
            mode = PleasureMode.NOVA,
            intensityPercent = 0,
            speed = ResponseSpeed.BALANCED,
            bassMacroDb = 0f,
            airMacroDb = 0f,
            dynamicLoudness = false,
            volumeRatio = 0.8f,
            headphoneProfile = HeadphoneProfile.FLAT_GENERIC,
            manualTrimsDb = List(10) { -15.0f },
            loudnessMatchedBypass = true,
            isAutoBypassed = false,
        )
        assertEquals(-9.0f, clampedBypass.bypassMatchGainDb, 0.0001f)
        assertEquals(-9.0f, clampedBypass.inputGainDb, 0.0001f)
    }

    @Test
    fun closedLoop200FrameStabilityUnderRefinedNovaQ30DeEsserStrongAndToneTilt() {
        val engine = AdaptiveEqEngine()
        val deltas = mutableListOf<FloatArray>()
        var prevTotalEq = FloatArray(10)

        for (step in 0 until 200) {
            val postFft = syntheticPinkMusicFftWithBandGainDb(prevTotalEq)
            val frame = engine.processFrame(
                fftBytes = postFft,
                nowMs = step * 50L,
                isMediaPlaying = true,
                powerEnabled = true,
                bypassed = false,
                mode = PleasureMode.NOVA,
                intensityPercent = 100,
                speed = ResponseSpeed.REACTIVE,
                bassMacroDb = 2.0f,
                airMacroDb = 1.0f,
                dynamicLoudness = false,
                volumeRatio = 0.8f,
                headphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
                manualTrimsDb = List(10) { 0f },
                deembedEnabled = true,
                toneTiltDbPerOct = 0.5f,
                deEsserMode = DeEsserMode.STRONG,
            )
            assertEquals(EngineStatus.AUTO_FFT_LOCKED, frame.engineStatus)
            assertTrue(frame.adaptiveDeltaDb.all { it.isFinite() && it in -5.5f..5.5f })
            assertTrue(frame.totalEq31Db.all { it.isFinite() && it in -15f..15f })
            assertTrue(frame.inputGainDb.isFinite() && frame.inputGainDb <= 0f)
            assertEquals(0f, frame.bypassMatchGainDb, 0.0001f)
            deltas += frame.adaptiveDeltaDb.copyOf()
            prevTotalEq = frame.totalEqDb
        }

        var tailMaxJitter = 0f
        for (i in 181 until 200) {
            for (b in 0 until 10) {
                val diff = abs(deltas[i][b] - deltas[i - 1][b])
                if (diff > tailMaxJitter) tailMaxJitter = diff
            }
        }
        assertTrue(
            "200-frame NOVA+Q30+DeEsser.STRONG+Tilt closed-loop tail max jitter ($tailMaxJitter dB) must be < 0.15 dB",
            tailMaxJitter < 0.15f,
        )
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
