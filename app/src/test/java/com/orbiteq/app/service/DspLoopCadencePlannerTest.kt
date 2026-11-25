package com.orbiteq.app.service

import com.orbiteq.app.model.ConnectedOutputDevice
import com.orbiteq.app.model.MbcMode
import com.orbiteq.app.model.NowPlayingInfo
import com.orbiteq.app.model.OrbitSettings
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.PleasureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun spectrumPublishActionClearsToZeroWhenLiveFftDropsEvenIfUiVisible() {
        assertEquals(
            SpectrumPublishAction.PUBLISH_LIVE,
            DspLoopCadencePlanner.spectrumPublishAction(isUiVisible = true, isLiveFftActive = true),
        )
        assertEquals(
            SpectrumPublishAction.CLEAR_TO_ZERO,
            DspLoopCadencePlanner.spectrumPublishAction(isUiVisible = true, isLiveFftActive = false),
        )
        assertEquals(
            SpectrumPublishAction.CLEAR_TO_ZERO,
            DspLoopCadencePlanner.spectrumPublishAction(isUiVisible = false, isLiveFftActive = false),
        )
        assertEquals(
            SpectrumPublishAction.PRESERVE_PREVIOUS,
            DspLoopCadencePlanner.spectrumPublishAction(isUiVisible = false, isLiveFftActive = true),
        )
    }

    @Test
    fun widgetTileSignatureIgnoresEqSliderChangesAndTracksWidgetTileFields() {
        val base = OrbitUiState(
            settings = OrbitSettings(
                powerEnabled = true,
                bypassed = false,
                intensityPercent = 70,
                bassMacroDb = 2.0f,
                airMacroDb = 1.5f,
                channelBalance = 0f,
                manualTrimsDb = List(10) { 0f },
            ),
            activeMode = PleasureMode.NOVA,
            autoBypassedByRule = false,
            nowPlaying = NowPlayingInfo(
                packageName = "com.spotify.music",
                trackTitle = "Starlight",
                artist = "Muse",
                isPlaying = true,
            ),
            connectedDevice = ConnectedOutputDevice(name = "Soundcore Life Q30"),
        )
        val baseSig = OrbitWidgetUpdater.signatureFor(base)

        val sliderTweak = base.copy(
            settings = base.settings.copy(
                intensityPercent = 95,
                bassMacroDb = 6.0f,
                airMacroDb = 4.5f,
                channelBalance = -0.42f,
                mbcMode = MbcMode.PUNCHY,
                manualTrimsDb = List(10) { 3.0f },
            ),
        )
        assertEquals(baseSig, OrbitWidgetUpdater.signatureFor(sliderTweak))

        assertNotEquals(baseSig, OrbitWidgetUpdater.signatureFor(base.copy(settings = base.settings.copy(powerEnabled = false))))
        assertNotEquals(baseSig, OrbitWidgetUpdater.signatureFor(base.copy(settings = base.settings.copy(bypassed = true))))
        assertNotEquals(baseSig, OrbitWidgetUpdater.signatureFor(base.copy(autoBypassedByRule = true)))
        assertNotEquals(baseSig, OrbitWidgetUpdater.signatureFor(base.copy(activeMode = PleasureMode.GRAVITY)))
        assertNotEquals(baseSig, OrbitWidgetUpdater.signatureFor(base.copy(nowPlaying = base.nowPlaying.copy(trackTitle = "Hysteria"))))
        assertNotEquals(baseSig, OrbitWidgetUpdater.signatureFor(base.copy(nowPlaying = base.nowPlaying.copy(artist = "Other"))))
        assertNotEquals(baseSig, OrbitWidgetUpdater.signatureFor(base.copy(connectedDevice = base.connectedDevice.copy(name = "Speaker"))))
    }
}
