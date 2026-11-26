package com.orbiteq.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HeroSpectrumAxisMathTest {

    @Test
    fun populatePlotXCoordinatesAligns10BandAnchorsWith31BandLogAxisWhen31BandActive() {
        val bandXs = FloatArray(10)
        val band31Xs = FloatArray(31)
        val plotLeft = 12f
        val plotW = 888f // ~296dp at 3x density (360dp phone)

        populatePlotXCoordinates(
            plotLeft = plotLeft,
            plotW = plotW,
            use31BandCurve = true,
            outBandXs = bandXs,
            outBand31Xs = band31Xs,
        )

        assertEquals(plotLeft, band31Xs[0], 1e-4f)
        assertEquals(plotLeft + plotW, band31Xs[30], 1e-4f)
        for (b in 0 until 10) {
            assertEquals(band31Xs[ISO_10_IN_31_INDICES[b]], bandXs[b], 1e-4f)
        }
        // 36 FFT display bars span [band31Xs[2] .. band31Xs[29]] matching 31 Hz .. 16 kHz anchors
        assertEquals(band31Xs[2], bandXs[0], 1e-4f)
        assertEquals(band31Xs[29], bandXs[9], 1e-4f)
    }

    @Test
    fun populatePlotXCoordinatesUsesEqualSpacingFor10BandFallback() {
        val bandXs = FloatArray(10)
        val band31Xs = FloatArray(31)
        val plotLeft = 12f
        val plotW = 888f

        populatePlotXCoordinates(
            plotLeft = plotLeft,
            plotW = plotW,
            use31BandCurve = false,
            outBandXs = bandXs,
            outBand31Xs = band31Xs,
        )

        assertEquals(plotLeft, bandXs[0], 1e-4f)
        assertEquals(plotLeft + plotW, bandXs[9], 1e-4f)
        for (b in 1 until 10) {
            val step = bandXs[b] - bandXs[b - 1]
            assertEquals(plotW / 9f, step, 1e-3f)
        }
    }

    @Test
    fun computeFrequencyLabelXsCentersOn31BandAxisWithoutOverlapsOn360dpScreen() {
        val widthPx = 888 // 296dp at 3x density
        // Typical 9.sp SpaceMono label widths at 3x density (~33px for 2 chars, ~50px for 3 chars)
        val labelWidths = intArrayOf(33, 33, 50, 50, 50, 33, 33, 33, 33, 50)
        val minGapPx = 6

        val xs = computeFrequencyLabelXs(
            width = widthPx,
            labelWidths = labelWidths,
            useLog31Positions = true,
            minGapPx = minGapPx,
        )

        for (i in 0 until 10) {
            assertTrue("Label $i must start >= 0", xs[i] >= 0)
            assertTrue("Label $i must fit within width", xs[i] + labelWidths[i] <= widthPx)
            val expectedCenter = ISO_31_LOG_FRACTIONS[ISO_10_IN_31_INDICES[i]] * widthPx
            val actualCenter = xs[i] + labelWidths[i] * 0.5f
            assertTrue(
                "Label $i center ($actualCenter) should match 31-band fraction ($expectedCenter)",
                abs(actualCenter - expectedCenter) <= 1.0f,
            )
            if (i > 0) {
                assertTrue(
                    "Label $i must not overlap label ${i - 1}",
                    xs[i] >= xs[i - 1] + labelWidths[i - 1] + minGapPx,
                )
            }
        }
    }

    @Test
    fun computeFrequencyLabelXsPreventsOverlapUnderLargeFontScaleAnd10BandFallback() {
        val widthPx = 888
        val largeWidths = intArrayOf(66, 66, 98, 98, 98, 66, 66, 66, 66, 98)
        val minGapPx = 6

        val logXs = computeFrequencyLabelXs(
            width = widthPx,
            labelWidths = largeWidths,
            useLog31Positions = true,
            minGapPx = minGapPx,
        )
        for (i in 0 until 10) {
            assertTrue(logXs[i] >= 0)
            assertTrue(logXs[i] + largeWidths[i] <= widthPx)
            if (i > 0) {
                assertTrue(logXs[i] >= logXs[i - 1] + largeWidths[i - 1] + minGapPx)
            }
        }

        val equalXs = computeFrequencyLabelXs(
            width = widthPx,
            labelWidths = intArrayOf(33, 33, 50, 50, 50, 33, 33, 33, 33, 50),
            useLog31Positions = false,
            minGapPx = minGapPx,
        )
        assertEquals(0, equalXs[0])
        assertEquals(widthPx - 50, equalXs[9])
    }

    @Test
    fun spectrumAnimBufferClearsStale31BandDisplayValuesWhenSwitchingTo10Band() {
        val buffer = SpectrumAnimBuffer()
        val bins = List(36) { 0.4f }
        val live10 = List(10) { 2.0f }
        val target10 = List(10) { 1.0f }
        val live31 = List(31) { idx -> (idx - 15) * 0.5f }

        buffer.updateTargets(
            bins = bins,
            live = live10,
            live31 = live31,
            target = target10,
            activeSignal = true,
        )
        buffer.snapAllWithoutTick()
        assertTrue(buffer.hasLive31Curve)
        assertTrue(buffer.displayLive31.any { it != 0f })

        buffer.updateTargets(
            bins = bins,
            live = live10,
            live31 = emptyList(),
            target = target10,
            activeSignal = true,
        )
        assertTrue(!buffer.hasLive31Curve)
        assertTrue(buffer.displayLive31.all { it == 0f })
        assertTrue(buffer.targetLive31.all { it == 0f })
    }
}
