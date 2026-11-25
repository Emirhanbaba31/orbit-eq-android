package com.orbiteq.app.dsp

import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.PleasureMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class FftAnalyzerTest {

    @Test
    fun malformedEmptyAndTinyFftBuffersReturnSafeZeroedFrameWithoutThrowing() {
        val malformedInputs = listOf(
            ByteArray(0),
            ByteArray(1),
            ByteArray(2),
            ByteArray(3),
            ByteArray(5),
        )

        malformedInputs.forEach { bytes ->
            val frame = FftAnalyzer.analyze(bytes)
            assertEquals(10, frame.bandEnergiesNorm.size)
            assertEquals(36, frame.displayBins36.size)
            assertEquals(0f, frame.broadbandRms, 0.00001f)
            assertEquals("Balanced", frame.spectralTag)
            assertEquals(0f, frame.spectralFlatness, 0.00001f)
            assertTrue(frame.bandEnergiesNorm.all { it.isFinite() && it == 0f })
            assertTrue(frame.displayBins36.all { it.isFinite() && it == 0f })
        }
    }

    @Test
    fun allZeroFftBufferReportsZeroRmsAndZeroBinsWithNoNaN() {
        val frame = FftAnalyzer.analyze(ByteArray(1024))

        assertEquals(0f, frame.broadbandRms, 0.00001f)
        assertEquals("Balanced", frame.spectralTag)
        assertEquals(0f, frame.spectralFlatness, 0.00001f)
        assertEquals(10, frame.bandEnergiesNorm.size)
        assertEquals(36, frame.displayBins36.size)
        assertTrue(frame.bandEnergiesNorm.all { it.isFinite() && it == 0f })
        assertTrue(frame.displayBins36.all { it.isFinite() && it == 0f })
    }

    @Test
    fun syntheticLowFrequencyEnergy60To100HzYieldsSubHeavyTagAndDominantLowBands() {
        val fftBytes = syntheticFftForFrequencyRange(
            minHz = 60f,
            maxHz = 100f,
            re = 96,
            im = 32,
        )

        val frame = FftAnalyzer.analyze(fftBytes, sampleRateHz = 48000)

        assertTrue("Expected non-zero broadbandRms", frame.broadbandRms > 1e-4f)
        assertEquals("Sub-Heavy", frame.spectralTag)
        val lowPeak = maxOf(frame.bandEnergiesNorm[0], frame.bandEnergiesNorm[1], frame.bandEnergiesNorm[2])
        val midPeak = (4..6).maxOf { frame.bandEnergiesNorm[it] }
        val highPeak = (7..9).maxOf { frame.bandEnergiesNorm[it] }
        assertTrue("Low bands ($lowPeak) should dominate mids ($midPeak)", lowPeak > midPeak + 0.3f)
        assertTrue("Low bands ($lowPeak) should dominate highs ($highPeak)", lowPeak > highPeak + 0.3f)
        assertTrue(frame.displayBins36.all { it.isFinite() && it in 0f..1f })
        assertTrue("Low display bins should show energy", (0..10).any { frame.displayBins36[it] > 0.1f })
    }

    @Test
    fun syntheticVocalMidFrequencyEnergy700To1800HzYieldsVocalTagAndDominantMidBands() {
        val fftBytes = syntheticFftForFrequencyRange(
            minHz = 700f,
            maxHz = 1800f,
            re = 84,
            im = -48,
        )

        val frame = FftAnalyzer.analyze(fftBytes, sampleRateHz = 48000)

        assertTrue("Expected non-zero broadbandRms", frame.broadbandRms > 1e-4f)
        assertEquals("Vocal", frame.spectralTag)
        val lowAvg = (0..2).map { frame.bandEnergiesNorm[it] }.average()
        val vocalAvg = (4..6).map { frame.bandEnergiesNorm[it] }.average()
        val highAvg = (8..9).map { frame.bandEnergiesNorm[it] }.average()
        assertTrue("Vocal bands ($vocalAvg) should dominate lows ($lowAvg)", vocalAvg > lowAvg + 0.3f)
        assertTrue("Vocal bands ($vocalAvg) should dominate highs ($highAvg)", vocalAvg > highAvg + 0.3f)
        assertTrue(frame.displayBins36.all { it.isFinite() && it in 0f..1f })
    }

    @Test
    fun syntheticHighFrequencyAndWarmSpectraClassifyCorrectly() {
        val airyBytes = syntheticFftForFrequencyRange(
            minHz = 6000f,
            maxHz = 14000f,
            re = 72,
            im = 24,
        )
        val airyFrame = FftAnalyzer.analyze(airyBytes, sampleRateHz = 48000)
        assertEquals("Wide Stage", airyFrame.spectralTag)

        val warmBytes = syntheticFftForFrequencyRange(
            minHz = 160f,
            maxHz = 420f,
            re = 88,
            im = 18,
        )
        val warmFrame = FftAnalyzer.analyze(warmBytes, sampleRateHz = 48000)
        assertEquals("Warm", warmFrame.spectralTag)
    }

    @Test
    fun pleasureProfilesDetectSoundcoreQ30AndExposeExactSpecifications() {
        assertTrue(PleasureProfiles.detectSoundcoreQ30("Soundcore Life Q30"))
        assertTrue(PleasureProfiles.detectSoundcoreQ30("soundcore Q30"))
        assertTrue(PleasureProfiles.detectSoundcoreQ30("ANKER SOUNDCORE LIFE Q30"))
        assertFalse(PleasureProfiles.detectSoundcoreQ30("AirPods Pro"))
        assertFalse(PleasureProfiles.detectSoundcoreQ30("Soundcore Life Q20"))
        assertFalse(PleasureProfiles.detectSoundcoreQ30(""))
        assertFalse(PleasureProfiles.detectSoundcoreQ30(null))

        assertArrayEquals(
            intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000),
            PleasureProfiles.ISO_FREQUENCIES_HZ,
        )
        assertEquals(
            listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"),
            PleasureProfiles.ISO_LABELS,
        )

        assertArrayEquals(
            floatArrayOf(-1.5f, -3.5f, -4.5f, -3.0f, -0.5f, 0.5f, 2.0f, 2.5f, -1.5f, 0.5f),
            PleasureProfiles.compensationCurveFor(HeadphoneProfile.Q30_CLEAN_BASE),
            0.0001f,
        )
        assertArrayEquals(
            floatArrayOf(-1.0f, -2.0f, -2.5f, -1.5f, 0.0f, 0.5f, 1.0f, 1.0f, -1.0f, 0.0f),
            PleasureProfiles.compensationCurveFor(HeadphoneProfile.Q30_SAFE_STACK),
            0.0001f,
        )
        assertArrayEquals(
            FloatArray(10),
            PleasureProfiles.compensationCurveFor(HeadphoneProfile.FLAT_GENERIC),
            0.0001f,
        )
        assertArrayEquals(
            floatArrayOf(-6.0f, -4.0f, -1.5f, 0.0f, 0.5f, 1.0f, 1.0f, 0.5f, 0.0f, -1.0f),
            PleasureProfiles.compensationCurveFor(HeadphoneProfile.PHONE_SPEAKER_SAFE),
            0.0001f,
        )

        assertEquals(0.0f, PleasureProfiles.extraHeadroomDbFor(HeadphoneProfile.Q30_CLEAN_BASE), 0.0001f)
        assertEquals(2.0f, PleasureProfiles.extraHeadroomDbFor(HeadphoneProfile.Q30_SAFE_STACK), 0.0001f)
        assertEquals(0.0f, PleasureProfiles.extraHeadroomDbFor(HeadphoneProfile.FLAT_GENERIC), 0.0001f)
        assertEquals(0.0f, PleasureProfiles.extraHeadroomDbFor(HeadphoneProfile.PHONE_SPEAKER_SAFE), 0.0001f)

        // Verify defensive copies for target and compensation curves
        PleasureMode.entries.forEach { mode ->
            val first = PleasureProfiles.targetCurveFor(mode)
            assertEquals(10, first.size)
            assertTrue(first.all { it.isFinite() })
            val originalZero = first[0]
            first[0] = 99f
            assertEquals(originalZero, PleasureProfiles.targetCurveFor(mode)[0], 0.0001f)
        }
        val compCopy = PleasureProfiles.compensationCurveFor(HeadphoneProfile.Q30_CLEAN_BASE)
        compCopy[0] = 99f
        assertEquals(-1.5f, PleasureProfiles.compensationCurveFor(HeadphoneProfile.Q30_CLEAN_BASE)[0], 0.0001f)
    }

    @Test
    fun spectralFlatnessSeparatesTonalAndPinkMusicFromWhiteAndQuantizationNoise() {
        val tonalFft = syntheticFftForFrequencyRange(minHz = 700f, maxHz = 1800f, re = 84, im = -48)
        val tonalFrame = FftAnalyzer.analyze(tonalFft, sampleRateHz = 48000)
        assertTrue(
            "Tonal spectrum flatness (${tonalFrame.spectralFlatness}) must be < 0.55",
            tonalFrame.spectralFlatness in 0f..0.5499f,
        )

        val pinkMusicFft = syntheticPinkMusicFft(size = 1024, amplitudeAtK1 = 96f, rolloffExponent = 0.5f)
        val pinkFrame = FftAnalyzer.analyze(pinkMusicFft, sampleRateHz = 48000)
        assertTrue(
            "Pink-balanced music flatness (${pinkFrame.spectralFlatness}) must be < 0.55",
            pinkFrame.spectralFlatness in 0.001f..0.5499f,
        )

        val bassHeavyMusicFft = syntheticPinkMusicFft(size = 1024, amplitudeAtK1 = 110f, rolloffExponent = 0.85f)
        val bassHeavyFrame = FftAnalyzer.analyze(bassHeavyMusicFft, sampleRateHz = 48000)
        assertTrue(
            "Bass-heavy music flatness (${bassHeavyFrame.spectralFlatness}) must be < 0.55",
            bassHeavyFrame.spectralFlatness < 0.55f,
        )

        val whiteNoiseFft = syntheticWhiteNoiseFft(size = 1024, re = 48, im = 36)
        val whiteFrame = FftAnalyzer.analyze(whiteNoiseFft, sampleRateHz = 48000)
        assertTrue(
            "White noise flatness (${whiteFrame.spectralFlatness}) must be >= 0.72",
            whiteFrame.spectralFlatness in 0.72f..1f,
        )

        val quantizationNoiseFft = syntheticWhiteNoiseFft(size = 1024, re = 1, im = 1)
        val quantFrame = FftAnalyzer.analyze(quantizationNoiseFft, sampleRateHz = 48000)
        assertTrue(
            "Quantization noise floor flatness (${quantFrame.spectralFlatness}) must be >= 0.72",
            quantFrame.spectralFlatness in 0.72f..1f,
        )
    }

    @Test
    fun sampleRateHzShiftsBinFrequenciesToMatchingIsoBands() {
        val bin11Bytes = ByteArray(1024)
        bin11Bytes[2 * 11] = 96
        bin11Bytes[2 * 11 + 1] = 0

        // At 48 kHz: k=11 -> 515.625 Hz (500 Hz band index 4)
        val frame48k = FftAnalyzer.analyze(bin11Bytes, sampleRateHz = 48000)
        assertTrue(
            "48 kHz should peak at 500 Hz (${frame48k.bandEnergiesNorm[4]}) over 1 kHz (${frame48k.bandEnergiesNorm[5]})",
            frame48k.bandEnergiesNorm[4] > frame48k.bandEnergiesNorm[5] + 0.5f,
        )

        // At 96 kHz: k=11 -> 1031.25 Hz (1000 Hz band index 5)
        val frame96k = FftAnalyzer.analyze(bin11Bytes, sampleRateHz = 96000)
        assertTrue(
            "96 kHz should peak at 1 kHz (${frame96k.bandEnergiesNorm[5]}) over 500 Hz (${frame96k.bandEnergiesNorm[4]})",
            frame96k.bandEnergiesNorm[5] > frame96k.bandEnergiesNorm[4] + 0.5f,
        )

        // At 44.1 kHz: k=23 -> ~990.5 Hz (1000 Hz band index 5)
        val bin23Bytes = ByteArray(1024)
        bin23Bytes[2 * 23] = 96
        val frame44k = FftAnalyzer.analyze(bin23Bytes, sampleRateHz = 44100)
        assertTrue(
            "44.1 kHz k=23 should peak at 1 kHz (${frame44k.bandEnergiesNorm[5]}) over 2 kHz (${frame44k.bandEnergiesNorm[6]})",
            frame44k.bandEnergiesNorm[5] > frame44k.bandEnergiesNorm[6] + 0.5f,
        )
    }

    @Test
    fun spectralFrameEqualsAndHashCodeIncludeSpectralFlatness() {
        val a = SpectralFrame(
            bandEnergiesNorm = FloatArray(10),
            displayBins36 = FloatArray(36),
            broadbandRms = 0.25f,
            spectralTag = "Balanced",
            spectralFlatness = 0.2f,
        )
        val b = a.copy(spectralFlatness = 0.8f)
        assertNotEquals(a, b)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
    }

    private fun syntheticPinkMusicFft(
        size: Int,
        amplitudeAtK1: Float,
        rolloffExponent: Float,
    ): ByteArray {
        val bytes = ByteArray(size)
        val halfN = size / 2
        for (k in 1 until halfN) {
            val kPow = k.toDouble().pow(rolloffExponent.toDouble()).toFloat()
            val mag = (amplitudeAtK1 / kPow).roundToInt().coerceIn(0, 127)
            bytes[2 * k] = mag.toByte()
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
