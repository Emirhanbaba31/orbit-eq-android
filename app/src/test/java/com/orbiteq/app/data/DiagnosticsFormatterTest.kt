package com.orbiteq.app.data

import com.orbiteq.app.model.ConnectedOutputDevice
import com.orbiteq.app.model.DeEsserMode
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

    @Test
    fun formatIncludesV3ToneTiltDeEsserLoudnessMatchedBypassMbcBandsAndRoundsBalance() {
        val uiState = OrbitUiState(
            settings = OrbitSettings(
                powerEnabled = true,
                bypassed = false,
                selectedMode = PleasureMode.NOVA,
                intensityPercent = 80,
                mbcMode = MbcMode.SUBTLE,
                subsonicFilterEnabled = true,
                trueHeadroomMode = false,
                channelBalance = 0.29f,
                toneTiltDbPerOct = -0.6f,
                deEsserMode = DeEsserMode.STRONG,
                loudnessMatchedBypass = false,
            ),
            activeMode = PleasureMode.NOVA,
            modeSource = ModeResolutionSource.GLOBAL_DEFAULT,
            activeHeadphoneProfile = HeadphoneProfile.Q30_CLEAN_BASE,
            telemetry = LiveAudioTelemetry(
                engineStatus = EngineStatus.AUTO_FFT_LOCKED,
                dspBackend = DspBackend.DYNAMICS_PROCESSING,
                activeSessionId = 108,
                sampleRateHz = 48000,
                preEqBandCount = 31,
                mbcBandCount = 5,
            ),
        )

        val report = DiagnosticsFormatter.format(
            DiagnosticsSnapshot(
                versionName = "3.0.0",
                androidSdk = 35,
                deviceModel = "Pixel 9",
                uiState = uiState,
                hasRecordAudio = true,
                hasNotificationAccess = true,
                hasPostNotifications = true,
                isBatteryExempt = true,
            ),
        )

        assertTrue(report.contains("31-band PreEQ / 5-band MBC"))
        assertTrue(report.contains("Tone Tilt: -0.6 dB/oct"))
        assertTrue(report.contains("De-Esser: STRONG"))
        assertTrue(report.contains("Loudness-Matched A/B: OFF"))
        assertTrue(report.contains("+0.29 (R 29%)"))
        assertFalse(report.contains("R 28%"))
    }

    @Test
    fun formatHandlesFallback4BandMbcZeroMbcNegativeBalanceRoundingAndTurkishLocale() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            val fallbackReport = DiagnosticsFormatter.format(
                DiagnosticsSnapshot(
                    versionName = "3.0.0",
                    androidSdk = 34,
                    deviceModel = "Pixel 8",
                    uiState = OrbitUiState(
                        settings = OrbitSettings(
                            channelBalance = -0.29f,
                            toneTiltDbPerOct = 0f,
                            deEsserMode = DeEsserMode.GENTLE,
                            loudnessMatchedBypass = true,
                        ),
                        telemetry = LiveAudioTelemetry(
                            dspBackend = DspBackend.DYNAMICS_PROCESSING,
                            preEqBandCount = 10,
                            mbcBandCount = 4,
                        ),
                    ),
                    hasRecordAudio = true,
                    hasNotificationAccess = true,
                    hasPostNotifications = true,
                    isBatteryExempt = false,
                ),
            )

            assertTrue(fallbackReport.contains("10-band PreEQ / 4-band MBC"))
            assertTrue(fallbackReport.contains("Tone Tilt: +0.0 dB/oct"))
            assertTrue(fallbackReport.contains("De-Esser: GENTLE"))
            assertTrue(fallbackReport.contains("Loudness-Matched A/B: ON"))
            assertTrue(fallbackReport.contains("-0.29 (L 29%)"))
            assertFalse(fallbackReport.contains("L 28%"))

            val legacyReport = DiagnosticsFormatter.format(
                DiagnosticsSnapshot(
                    versionName = "3.0.0",
                    androidSdk = 28,
                    deviceModel = "Legacy Device",
                    uiState = OrbitUiState(
                        settings = OrbitSettings(toneTiltDbPerOct = -0.0f),
                        telemetry = LiveAudioTelemetry(
                            dspBackend = DspBackend.LEGACY_EQ,
                            preEqBandCount = 10,
                            mbcBandCount = 0,
                        ),
                    ),
                    hasRecordAudio = true,
                    hasNotificationAccess = false,
                    hasPostNotifications = true,
                    isBatteryExempt = false,
                ),
            )

            assertTrue(legacyReport.contains("LEGACY_EQ (10-band PreEQ)"))
            assertFalse(legacyReport.contains("0-band MBC"))
            assertTrue(legacyReport.contains("Tone Tilt: +0.0 dB/oct"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
