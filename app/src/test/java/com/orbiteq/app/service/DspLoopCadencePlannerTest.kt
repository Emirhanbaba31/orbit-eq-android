package com.orbiteq.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspLoopCadencePlannerTest {

    @Test
    fun rampActiveAlwaysUses35MsFastRampCadence() {
        assertEquals(35L, DspLoopCadencePlanner.RAMP_INTERVAL_MS)
        assertEquals(
            35L,
            DspLoopCadencePlanner.computeIntervalMs(
                isMediaPlaying = false,
                isLiveFftActive = false,
                isUiVisible = false,
                isRampActive = true,
                hasNonZeroAdaptiveDelta = false,
            ),
        )
        assertEquals(
            35L,
            DspLoopCadencePlanner.computeIntervalMs(
                isMediaPlaying = true,
                isLiveFftActive = true,
                isUiVisible = true,
                isRampActive = true,
                hasNonZeroAdaptiveDelta = true,
            ),
        )
    }

    @Test
    fun liveFftWhilePlayingUses55MsVisibleAnd110MsBackgrounded() {
        assertEquals(55L, DspLoopCadencePlanner.LIVE_VISIBLE_INTERVAL_MS)
        assertEquals(110L, DspLoopCadencePlanner.LIVE_BACKGROUND_INTERVAL_MS)

        assertEquals(
            55L,
            DspLoopCadencePlanner.computeIntervalMs(
                isMediaPlaying = true,
                isLiveFftActive = true,
                isUiVisible = true,
                isRampActive = false,
                hasNonZeroAdaptiveDelta = false,
            ),
        )
        assertEquals(
            110L,
            DspLoopCadencePlanner.computeIntervalMs(
                isMediaPlaying = true,
                isLiveFftActive = true,
                isUiVisible = false,
                isRampActive = false,
                hasNonZeroAdaptiveDelta = true,
            ),
        )
    }

    @Test
    fun nonZeroAdaptiveDeltaKeepsActiveCadenceUntilSettled() {
        assertEquals(
            55L,
            DspLoopCadencePlanner.computeIntervalMs(
                isMediaPlaying = false,
                isLiveFftActive = false,
                isUiVisible = true,
                isRampActive = false,
                hasNonZeroAdaptiveDelta = true,
            ),
        )
        assertEquals(
            110L,
            DspLoopCadencePlanner.computeIntervalMs(
                isMediaPlaying = true,
                isLiveFftActive = false,
                isUiVisible = false,
                isRampActive = false,
                hasNonZeroAdaptiveDelta = true,
            ),
        )
    }

    @Test
    fun fallbackAndIdleSettledUseBatterySavingCadence() {
        assertEquals(500L, DspLoopCadencePlanner.FALLBACK_SETTLED_INTERVAL_MS)
        assertEquals(1000L, DspLoopCadencePlanner.IDLE_SETTLED_INTERVAL_MS)

        assertEquals(
            500L,
            DspLoopCadencePlanner.computeIntervalMs(
                isMediaPlaying = true,
                isLiveFftActive = false,
                isUiVisible = true,
                isRampActive = false,
                hasNonZeroAdaptiveDelta = false,
            ),
        )
        assertEquals(
            500L,
            DspLoopCadencePlanner.computeIntervalMs(
                isMediaPlaying = true,
                isLiveFftActive = false,
                isUiVisible = false,
                isRampActive = false,
                hasNonZeroAdaptiveDelta = false,
            ),
        )
        assertEquals(
            1000L,
            DspLoopCadencePlanner.computeIntervalMs(
                isMediaPlaying = false,
                isLiveFftActive = false,
                isUiVisible = true,
                isRampActive = false,
                hasNonZeroAdaptiveDelta = false,
            ),
        )
        assertEquals(
            1000L,
            DspLoopCadencePlanner.computeIntervalMs(
                isMediaPlaying = false,
                isLiveFftActive = true,
                isUiVisible = false,
                isRampActive = false,
                hasNonZeroAdaptiveDelta = false,
            ),
        )
    }

    @Test
    fun shouldPublishSpectrumBinsOnlyWhenUiVisibleAndLiveFftActive() {
        assertTrue(DspLoopCadencePlanner.shouldPublishSpectrumBins(isUiVisible = true, isLiveFftActive = true))
        assertFalse(DspLoopCadencePlanner.shouldPublishSpectrumBins(isUiVisible = false, isLiveFftActive = true))
        assertFalse(DspLoopCadencePlanner.shouldPublishSpectrumBins(isUiVisible = true, isLiveFftActive = false))
        assertFalse(DspLoopCadencePlanner.shouldPublishSpectrumBins(isUiVisible = false, isLiveFftActive = false))
    }
}
