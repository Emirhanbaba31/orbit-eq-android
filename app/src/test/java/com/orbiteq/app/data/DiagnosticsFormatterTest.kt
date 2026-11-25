package com.orbiteq.app.data

import com.orbiteq.app.model.ConnectedOutputDevice
import com.orbiteq.app.model.DspBackend
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.LiveAudioTelemetry
import com.orbiteq.app.model.MbcMode
import com.orbiteq.app.model.ModeResolutionSource
import com.orbiteq.app.model.OrbitSettings
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.SessionAttachmentMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DiagnosticsFormatterTest {

    @Test
    fun formatIncludesAppVersionSdkDeviceDspBandsSessionRateCompensationModesAndPermissions() {
        val uiState = OrbitUiState(
            settings = OrbitSettings(
                powerEnabled = true,
                bypassed = false,
                selectedMode = PleasureMode.NOVA,
                intensityPercent = 82,
                mbcMode = MbcMode.PUNCHY,
                subsonicFilterEnabled = true,
                trueHeadroomMode = true,
                channelBalance = -0.25f,
                sessionMode = SessionAttachmentMode.AUTO,
            ),
            activeMode = PleasureMode.GRAVITY,
            modeSource = ModeResolutionSource.APP_RULE,
            activeHeadphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            connectedDevice = ConnectedOutputDevice(
                name = "Soundcore Life Q30",
                isBluetooth = true,
                isSpeaker = false,
                isSoundcoreQ30 = true,
            ),
            telemetry = LiveAudioTelemetry(
                engineStatus = EngineStatus.AUTO_FFT_LOCKED,
                dspBackend = DspBackend.DYNAMICS_PROCESSING,
                activeSessionId = 412,
                sampleRateHz = 96000,
                preampAttenuationDb = -3.4f,
                loudnessBoostDb = 1.2f,
                limiterCeilingDb = -0.5f,
                dominantSpectralTag = "Sub-Heavy",
                preEqBandCount = 31,
                activeCompensationName = "Soundcore Life Q30 (Harman 31-Band)",
            ),
        )

        val report = DiagnosticsFormatter.format(
            DiagnosticsSnapshot(
                versionName = "2.0.0",
                androidSdk = 35,
                deviceModel = "Pixel 8 Pro",
                uiState = uiState,
                hasRecordAudio = true,
                hasNotificationAccess = true,
                hasPostNotifications = false,
                isBatteryExempt = true,
            ),
        )

        assertTrue(report.contains("ORBIT EQ v2.0.0"))
        assertTrue(report.contains("35"))
        assertTrue(report.contains("Pixel 8 Pro"))
        assertTrue(report.contains("DYNAMICS_PROCESSING"))
        assertTrue(report.contains("31-band"))
        assertTrue(report.contains("412"))
        assertTrue(report.contains("AUTO"))
        assertTrue(report.contains("96000 Hz"))
        assertTrue(report.contains("AUTO_FFT_LOCKED"))
        assertTrue(report.contains("Soundcore Life Q30 (Harman 31-Band)"))
        assertTrue(report.contains("GRAVITY"))
        assertTrue(report.contains("APP_RULE"))
        assertTrue(report.contains("Subsonic"))
        assertTrue(report.contains("True Headroom"))
        assertTrue(report.contains("L/R Balance"))
        assertTrue(report.contains("MBC"))
        assertTrue(report.contains("PUNCHY"))
        assertTrue(report.contains("RECORD_AUDIO"))
        assertTrue(report.contains("POST_NOTIFICATIONS"))
        assertTrue(report.contains("GRANTED"))
        assertTrue(report.contains("DENIED"))
    }

    @Test
    fun formatHandles10BandFallbackAndTurkishLocaleWithDotDecimals() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            val uiState = OrbitUiState(
                settings = OrbitSettings(
                    powerEnabled = true,
                    bypassed = true,
                    mbcMode = MbcMode.OFF,
                    subsonicFilterEnabled = false,
                    trueHeadroomMode = false,
                    channelBalance = 0f,
                ),
                activeMode = PleasureMode.COMMS,
                modeSource = ModeResolutionSource.GLOBAL_DEFAULT,
                activeHeadphoneProfile = HeadphoneProfile.PHONE_SPEAKER_SAFE,
                telemetry = LiveAudioTelemetry(
                    engineStatus = EngineStatus.BYPASSED,
                    dspBackend = DspBackend.DYNAMICS_PROCESSING,
                    activeSessionId = 0,
                    sampleRateHz = 44100,
                    preampAttenuationDb = -1.5f,
                    limiterCeilingDb = -0.5f,
                    preEqBandCount = 10,
                    activeCompensationName = "",
                ),
            )

            val report = DiagnosticsFormatter.format(
                DiagnosticsSnapshot(
                    versionName = "2.0.0",
                    androidSdk = 34,
                    deviceModel = "Galaxy S24",
                    uiState = uiState,
                    hasRecordAudio = false,
                    hasNotificationAccess = false,
                    hasPostNotifications = true,
                    isBatteryExempt = false,
                ),
            )

            assertTrue(report.contains("10-band"))
            assertTrue(report.contains("44100 Hz"))
            assertTrue(report.contains("PHONE_SPEAKER_SAFE"))
            assertTrue(report.contains("-1.5"))
            assertTrue(report.contains("-0.5"))
            assertFalse(report.contains("-1,5"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
