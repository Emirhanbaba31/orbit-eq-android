package com.orbiteq.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ParametricBiquadEvaluatorTest {

    @Test
    fun peakingFilterMatchesRbjTargetAtCenterFrequencyAndAttenuatesFarAway() {
        val boost = ParametricFilter(
            type = ParametricFilterType.PK,
            freqHz = 1000.0,
            gainDb = 6.0,
            q = 1.41,
        )
        val centerDb = ParametricBiquadEvaluator.evaluateFilterDb(boost, 1000.0)
        val subBassDb = ParametricBiquadEvaluator.evaluateFilterDb(boost, 63.0)
        val upperAirDb = ParametricBiquadEvaluator.evaluateFilterDb(boost, 16000.0)

        assertEquals(6.0, centerDb, 0.05)
        assertTrue("Expected < 0.5 dB at 63 Hz, got $subBassDb", abs(subBassDb) < 0.5)
        assertTrue("Expected < 0.5 dB at 16 kHz, got $upperAirDb", abs(upperAirDb) < 0.5)

        val cut = ParametricFilter(
            type = ParametricFilterType.PK,
            freqHz = 125.0,
            gainDb = -5.2,
            q = 0.90,
        )
        assertEquals(-5.2, ParametricBiquadEvaluator.evaluateFilterDb(cut, 125.0), 0.05)
    }

    @Test
    fun higherQProducesNarrowerPeakingBandwidthThanLowerQ() {
        val narrow = ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = 6.0, q = 6.0)
        val wide = ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = 6.0, q = 0.7)

        assertEquals(6.0, ParametricBiquadEvaluator.evaluateFilterDb(narrow, 1000.0), 0.05)
        assertEquals(6.0, ParametricBiquadEvaluator.evaluateFilterDb(wide, 1000.0), 0.05)

        val narrow800 = ParametricBiquadEvaluator.evaluateFilterDb(narrow, 800.0)
        val wide800 = ParametricBiquadEvaluator.evaluateFilterDb(wide, 800.0)
        val narrow1250 = ParametricBiquadEvaluator.evaluateFilterDb(narrow, 1250.0)
        val wide1250 = ParametricBiquadEvaluator.evaluateFilterDb(wide, 1250.0)

        assertTrue("Q=6.0 ($narrow800) must be lower than Q=0.7 ($wide800) at 800 Hz", narrow800 < wide800 - 1.5)
        assertTrue("Q=6.0 ($narrow1250) must be lower than Q=0.7 ($wide1250) at 1250 Hz", narrow1250 < wide1250 - 1.5)
    }

    @Test
    fun lowShelfFilterMatchesRbjDcTransitionAndStopbandTargets() {
        val lowShelf = ParametricFilter(
            type = ParametricFilterType.LS,
            freqHz = 105.0,
            gainDb = 6.0,
            q = ParametricBiquadEvaluator.DEFAULT_SHELF_Q,
        )

        val at20Hz = ParametricBiquadEvaluator.evaluateFilterDb(lowShelf, 20.0)
        val atCutoff = ParametricBiquadEvaluator.evaluateFilterDb(lowShelf, 105.0)
        val at4000Hz = ParametricBiquadEvaluator.evaluateFilterDb(lowShelf, 4000.0)

        assertEquals(6.0, at20Hz, 0.25)
        assertTrue("20 Hz low shelf should be > 5.7 dB, got $at20Hz", at20Hz > 5.7)
        assertEquals(3.0, atCutoff, 0.05)
        assertEquals(0.0, at4000Hz, 0.05)
    }

    @Test
    fun highShelfFilterMatchesRbjPassbandTransitionAndHighFrequencyTargets() {
        val highShelf = ParametricFilter(
            type = ParametricFilterType.HS,
            freqHz = 8000.0,
            gainDb = -4.0,
            q = ParametricBiquadEvaluator.DEFAULT_SHELF_Q,
        )

        val at200Hz = ParametricBiquadEvaluator.evaluateFilterDb(highShelf, 200.0)
        val atCutoff = ParametricBiquadEvaluator.evaluateFilterDb(highShelf, 8000.0)
        val at16kHz = ParametricBiquadEvaluator.evaluateFilterDb(highShelf, 16000.0)
        val at20kHz = ParametricBiquadEvaluator.evaluateFilterDb(highShelf, 20000.0)

        assertEquals(0.0, at200Hz, 0.05)
        assertEquals(-2.0, atCutoff, 0.05)
        assertTrue("16 kHz high shelf ($at16kHz) should approach -4 dB", at16kHz in -4.05..-3.3)
        assertTrue("20 kHz high shelf ($at20kHz) should approach -4 dB", at20kHz in -4.05..-3.6)
    }

    @Test
    fun evaluateOnIso31SumsFiltersClampsTo15DbAndNormalizesZero() {
        val f1 = ParametricFilter(ParametricFilterType.LS, freqHz = 105.0, gainDb = 3.0, q = 0.71)
        val f2 = ParametricFilter(ParametricFilterType.PK, freqHz = 3150.0, gainDb = 4.0, q = 1.6)
        val combined = ParametricBiquadEvaluator.evaluateOnIso31(listOf(f1, f2))
        assertEquals(31, combined.size)
        assertTrue(combined.all { it.isFinite() && it in -15f..15f })

        PleasureProfiles.ISO_31_FREQUENCIES_HZ.forEachIndexed { idx, hz ->
            val expected = (
                ParametricBiquadEvaluator.evaluateFilterDb(f1, hz.toDouble()) +
                    ParametricBiquadEvaluator.evaluateFilterDb(f2, hz.toDouble())
                ).toFloat()
            assertEquals(expected, combined[idx], 0.0005f)
        }

        val extremeBoosts = listOf(
            ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = 12.0, q = 0.8),
            ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = 10.0, q = 0.8),
        )
        val clampedHigh = ParametricBiquadEvaluator.evaluateOnIso31(extremeBoosts)
        assertEquals(15.0f, clampedHigh[17], 0.0001f)

        val extremeCuts = listOf(
            ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = -12.0, q = 0.8),
            ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = -10.0, q = 0.8),
        )
        val clampedLow = ParametricBiquadEvaluator.evaluateOnIso31(extremeCuts)
        assertEquals(-15.0f, clampedLow[17], 0.0001f)

        val zeroOut = ParametricBiquadEvaluator.evaluateOnIso31(
            listOf(ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = 0.0, q = 1.0)),
        )
        assertTrue(zeroOut.all { it == 0f && it.toRawBits() == 0 })
    }

    @Test
    fun boundaryAndNonFiniteInputsReturnZeroSafelyWithoutThrowing() {
        val valid = ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = 5.0, q = 1.0)
        assertEquals(0.0, ParametricBiquadEvaluator.evaluateFilterDb(valid, 0.0), 0.0)
        assertEquals(0.0, ParametricBiquadEvaluator.evaluateFilterDb(valid, -10.0), 0.0)
        assertEquals(0.0, ParametricBiquadEvaluator.evaluateFilterDb(valid, 24000.0), 0.0)
        assertEquals(0.0, ParametricBiquadEvaluator.evaluateFilterDb(valid, 30000.0), 0.0)
        assertEquals(0.0, ParametricBiquadEvaluator.evaluateFilterDb(valid, Double.NaN), 0.0)
        assertEquals(0.0, ParametricBiquadEvaluator.evaluateFilterDb(valid, 1000.0, sampleRateHz = 0.0), 0.0)
        assertEquals(0.0, ParametricBiquadEvaluator.evaluateFilterDb(valid, 1000.0, sampleRateHz = Double.NaN), 0.0)

        val badFilters = listOf(
            ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = 0.0, q = 1.0),
            ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = 5.0, q = 0.0),
            ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = 5.0, q = -1.0),
            ParametricFilter(ParametricFilterType.PK, freqHz = 0.0, gainDb = 5.0, q = 1.0),
            ParametricFilter(ParametricFilterType.PK, freqHz = 24000.0, gainDb = 5.0, q = 1.0),
            ParametricFilter(ParametricFilterType.PK, freqHz = Double.NaN, gainDb = 5.0, q = 1.0),
            ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = Double.POSITIVE_INFINITY, q = 1.0),
            ParametricFilter(ParametricFilterType.PK, freqHz = 1000.0, gainDb = 5.0, q = Double.NaN),
        )
        badFilters.forEach { filter ->
            assertEquals(0.0, ParametricBiquadEvaluator.evaluateFilterDb(filter, 1000.0), 0.0)
        }
    }
}
