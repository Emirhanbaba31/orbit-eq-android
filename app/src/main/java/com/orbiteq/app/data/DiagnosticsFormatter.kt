package com.orbiteq.app.data

import com.orbiteq.app.model.OrbitUiState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class DiagnosticsSnapshot(
    val versionName: String,
    val androidSdk: Int,
    val deviceModel: String,
    val uiState: OrbitUiState,
    val hasRecordAudio: Boolean,
    val hasNotificationAccess: Boolean,
    val hasPostNotifications: Boolean,
    val isBatteryExempt: Boolean,
)

object DiagnosticsFormatter {

    fun format(snapshot: DiagnosticsSnapshot): String {
        val state = snapshot.uiState
        val settings = state.settings
        val telemetry = state.telemetry
        val version = snapshot.versionName.trim().ifEmpty { "3.0.0" }
        val deviceModel = snapshot.deviceModel.trim().ifEmpty { "Unknown Device" }

        val deviceName = state.connectedDevice.name.trim().ifEmpty {
            if (state.connectedDevice.isSpeaker) "Phone Speaker" else "Unknown Output"
        }
        val compensationName = telemetry.activeCompensationName.trim().ifEmpty {
            state.activeCustomHeadphoneProfile?.name ?: state.activeHeadphoneProfile.name
        }
        val bypassLabel = when {
            settings.bypassed -> "MANUAL"
            state.autoBypassedByRule -> "AUTO"
            else -> "OFF"
        }
        val dspBandsSummary = if (telemetry.mbcBandCount > 0) {
            "${telemetry.preEqBandCount}-band PreEQ / ${telemetry.mbcBandCount}-band MBC"
        } else {
            "${telemetry.preEqBandCount}-band PreEQ"
        }

        return buildString {
            appendLine("ORBIT EQ v$version Diagnostics")
            appendLine("Device: $deviceModel (Android API ${snapshot.androidSdk})")
            appendLine(
                "Power: ${flagLabel(settings.powerEnabled)} | Bypass: $bypassLabel | " +
                    "Mode: ${state.activeMode.name} (${state.modeSource.name}) @ ${settings.intensityPercent}%",
            )
            appendLine(
                "DSP Backend: ${telemetry.dspBackend.name} ($dspBandsSummary) | " +
                    "Session: #${telemetry.activeSessionId} (${settings.sessionMode.name})",
            )
            appendLine(
                "Sample Rate: ${telemetry.sampleRateHz} Hz | FFT Status: ${telemetry.engineStatus.name} | " +
                    "Spectral: ${telemetry.dominantSpectralTag.ifBlank { "Balanced" }}",
            )
            appendLine("Output: $deviceName | Compensation: $compensationName")
            appendLine(
                "Preamp: ${formatDb(telemetry.preampAttenuationDb)} | " +
                    "Loudness: ${formatDb(telemetry.loudnessBoostDb)} | " +
                    "Limiter: ${formatDb(telemetry.limiterCeilingDb)}",
            )
            appendLine(
                "MBC: ${settings.mbcMode.name} | " +
                    "Subsonic: ${flagLabel(settings.subsonicFilterEnabled)} | " +
                    "True Headroom: ${flagLabel(settings.trueHeadroomMode)} | " +
                    "L/R Balance: ${formatBalance(settings.channelBalance)}",
            )
            appendLine(
                "Tone Tilt: ${formatTilt(settings.toneTiltDbPerOct)} | " +
                    "De-Esser: ${settings.deEsserMode.name} | " +
                    "Loudness-Matched A/B: ${flagLabel(settings.loudnessMatchedBypass)}",
            )
            append(
                "Permissions: RECORD_AUDIO=${permLabel(snapshot.hasRecordAudio)}, " +
                    "NotificationAccess=${permLabel(snapshot.hasNotificationAccess)}, " +
                    "POST_NOTIFICATIONS=${permLabel(snapshot.hasPostNotifications)}, " +
                    "BatteryExempt=${flagLabel(snapshot.isBatteryExempt)}",
            )
        }
    }

    private fun formatDb(db: Float): String {
        val safe = if (db.isFinite()) db else 0f
        return String.format(Locale.ROOT, "%.1f dB", safe)
    }

    private fun formatTilt(tiltDbPerOct: Float): String {
        val safe = if (tiltDbPerOct.isFinite()) tiltDbPerOct.coerceIn(-1.5f, 1.5f) else 0f
        val normalized = if ((safe * 10f).roundToInt() == 0) 0f else safe
        return String.format(Locale.ROOT, "%+.1f dB/oct", normalized)
    }

    private fun formatBalance(balance: Float): String {
        val safe = if (balance.isFinite()) balance.coerceIn(-1f, 1f) else 0f
        if (abs(safe) < 0.01f) return "0.00 (Center)"
        val side = if (safe < 0f) "L" else "R"
        val pct = (abs(safe) * 100f).roundToInt()
        return String.format(Locale.ROOT, "%+.2f (%s %d%%)", safe, side, pct)
    }

    private fun flagLabel(enabled: Boolean): String = if (enabled) "ON" else "OFF"

    private fun permLabel(granted: Boolean): String = if (granted) "GRANTED" else "DENIED"
}
