package com.orbiteq.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBandFaderMathTest {

    @Test
    fun verticalDragUpAndDownUpdatesDbProportionallyAndClampsToRange() {
        // 160 px usable track maps full 16 dB range (-8.0 .. +8.0 dB) => 0.1 dB per px
        val upStep = stepFaderDrag(
            currentRawDb = 0f,
            lastReportedDb = 0f,
            wasSnappedZero = true,
            deltaYPx = -25f,
            usableTrackHeightPx = 160f,
        )
        assertEquals(2.5f, upStep.nextRawDb, 1e-4f)
        assertEquals(2.5f, upStep.nextSnappedDb, 1e-4f)
        assertTrue(upStep.changed)
        assertFalse(upStep.nowAtZero)

        val clampedMax = stepFaderDrag(
            currentRawDb = 6.5f,
            lastReportedDb = 6.5f,
            wasSnappedZero = false,
            deltaYPx = -50f,
            usableTrackHeightPx = 160f,
        )
        assertEquals(8.0f, clampedMax.nextRawDb, 1e-4f)
        assertEquals(8.0f, clampedMax.nextSnappedDb, 1e-4f)

        val clampedMin = stepFaderDrag(
            currentRawDb = -6.0f,
            lastReportedDb = -6.0f,
            wasSnappedZero = false,
            deltaYPx = 60f,
            usableTrackHeightPx = 160f,
        )
        assertEquals(-8.0f, clampedMin.nextRawDb, 1e-4f)
        assertEquals(-8.0f, clampedMin.nextSnappedDb, 1e-4f)
    }

    @Test
    fun magneticZeroSnapEngagesWithinDetentAndReleasesCleanlyPastThreshold() {
        val enterDetent = stepFaderDrag(
            currentRawDb = 0.8f,
            lastReportedDb = 0.8f,
            wasSnappedZero = false,
            deltaYPx = 6f, // -0.6 dB -> raw = 0.2 dB (inside +-0.35 dB detent)
            usableTrackHeightPx = 160f,
        )
        assertEquals(0.2f, enterDetent.nextRawDb, 1e-4f)
        assertEquals(0f, enterDetent.nextSnappedDb, 1e-4f)
        assertTrue(enterDetent.changed)
        assertTrue(enterDetent.crossedIntoZeroDetent)
        assertTrue(enterDetent.nowAtZero)

        val exitDetent = stepFaderDrag(
            currentRawDb = enterDetent.nextRawDb,
            lastReportedDb = enterDetent.nextSnappedDb,
            wasSnappedZero = enterDetent.nowAtZero,
            deltaYPx = 7f, // -0.7 dB -> raw = -0.5 dB (exits detent)
            usableTrackHeightPx = 160f,
        )
        assertEquals(-0.5f, exitDetent.nextRawDb, 1e-4f)
        assertEquals(-0.5f, exitDetent.nextSnappedDb, 1e-4f)
        assertTrue(exitDetent.changed)
        assertFalse(exitDetent.crossedIntoZeroDetent)
        assertFalse(exitDetent.nowAtZero)
    }

    @Test
    fun subStepDeltaWithinZeroDetentAvoidsRedundantChangeCallbacks() {
        val tinyMove = stepFaderDrag(
            currentRawDb = 0.0f,
            lastReportedDb = 0.0f,
            wasSnappedZero = true,
            deltaYPx = -2f, // +0.2 dB -> snapped back to 0.0 dB
            usableTrackHeightPx = 160f,
        )
        assertEquals(0.2f, tinyMove.nextRawDb, 1e-4f)
        assertEquals(0f, tinyMove.nextSnappedDb, 1e-4f)
        assertFalse(tinyMove.changed)
        assertFalse(tinyMove.crossedIntoZeroDetent)
    }
}
