package com.orbiteq.app.model

import androidx.compose.runtime.Immutable

enum class PleasureMode {
    NOVA,
    GRAVITY,
    HORIZON,
    ECLIPSE,
    COMMS,
}

enum class ResponseSpeed {
    CHILL,
    BALANCED,
    REACTIVE,
}

enum class HeadphoneProfile {
    Q30_CLEAN_BASE,
    Q30_SAFE_STACK,
    FLAT_GENERIC,
    PHONE_SPEAKER_SAFE,
}

enum class MbcMode {
    OFF,
    SUBTLE,
    PUNCHY,
}

enum class DeEsserMode {
    OFF,
    GENTLE,
    STRONG,
}

enum class OrbitThemeMode {
    DEEP_SPACE_HUD,
    LUNAR_MONOCHROME,
}

enum class EngineStatus {
    IDLE,
    AUTO_FFT_LOCKED,
    PROFILE_FALLBACK,
    BYPASSED,
}

enum class DspBackend {
    NONE,
    DYNAMICS_PROCESSING,
    LEGACY_EQ,
}

enum class SessionAttachmentMode {
    AUTO,
    GLOBAL_ONLY,
}

enum class AppRuleAction {
    USE_MODE,
    BYPASS,
}

enum class ModeResolutionSource {
    MANUAL_OVERRIDE,
    PINNED_TRACK,
    APP_RULE,
    DEVICE_DEFAULT,
    GLOBAL_DEFAULT,
}

@Immutable
data class AppRule(
    val packageName: String,
    val displayName: String,
    val action: AppRuleAction,
    val mode: PleasureMode,
)

@Immutable
data class TrackActivityEntry(
    val id: String,
    val packageName: String,
    val appName: String,
    val trackTitle: String,
    val artist: String,
    val modeUsed: PleasureMode,
    val spectralTag: String,
    val timestampMs: Long,
)

@Immutable
data class CustomPreset(
    val id: String,
    val name: String,
    val mode: PleasureMode,
    val intensity: Int,
    val bassMacroDb: Float,
    val airMacroDb: Float,
    val trimsDb: List<Float>,
)

@Immutable
data class CustomHeadphoneProfile(
    val id: String,
    val name: String,
    val gains31Db: List<Float>,
    val extraHeadroomDb: Float = 0f,
    val sourceLabel: String = "AutoEQ",
)

@Immutable
data class NowPlayingInfo(
    val packageName: String? = null,
    val appName: String = "",
    val trackTitle: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
)

@Immutable
data class ConnectedOutputDevice(
    val name: String = "",
    val isBluetooth: Boolean = false,
    val isSpeaker: Boolean = true,
    val isSoundcoreQ30: Boolean = false,
)

@Immutable
data class OrbitSettings(
    val powerEnabled: Boolean = false,
    val bypassed: Boolean = false,
    val selectedMode: PleasureMode = PleasureMode.NOVA,
    val intensityPercent: Int = 78,
    val bassMacroDb: Float = 2.0f,
    val airMacroDb: Float = 1.5f,
    val dynamicLoudness: Boolean = true,
    val mbcEnabled: Boolean = true,
    val responseSpeed: ResponseSpeed = ResponseSpeed.BALANCED,
    val manualTrimsDb: List<Float> = List(10) { 0f },
    val themeMode: OrbitThemeMode = OrbitThemeMode.DEEP_SPACE_HUD,
    val sessionMode: SessionAttachmentMode = SessionAttachmentMode.AUTO,
    val q30TipDismissed: Boolean = false,
    val appRules: Map<String, AppRule> = emptyMap(),
    val pinnedTracks: Map<String, PleasureMode> = emptyMap(),
    val deviceProfiles: Map<String, HeadphoneProfile> = emptyMap(),
    val deviceDefaultModes: Map<String, PleasureMode> = emptyMap(),
    val customPresets: List<CustomPreset> = emptyList(),
    val mbcMode: MbcMode = MbcMode.SUBTLE,
    val subsonicFilterEnabled: Boolean = true,
    val trueHeadroomMode: Boolean = false,
    val channelBalance: Float = 0f,
    val customHeadphoneProfiles: List<CustomHeadphoneProfile> = emptyList(),
    val deviceCustomProfileIds: Map<String, String> = emptyMap(),
    val toneTiltDbPerOct: Float = 0f,
    val deEsserMode: DeEsserMode = DeEsserMode.GENTLE,
    val loudnessMatchedBypass: Boolean = true,
)

@Immutable
data class LiveAudioTelemetry(
    val engineStatus: EngineStatus = EngineStatus.IDLE,
    val dspBackend: DspBackend = DspBackend.NONE,
    val activeSessionId: Int = 0,
    val sampleRateHz: Int = 48000,
    val spectrumBins36: List<Float> = List(36) { 0f },
    val liveEqCurveDb: List<Float> = List(10) { 0f },
    val adaptiveDeltaDb: List<Float> = List(10) { 0f },
    val preampAttenuationDb: Float = 0f,
    val loudnessBoostDb: Float = 0f,
    val limiterCeilingDb: Float = -0.5f,
    val dominantSpectralTag: String = "Balanced",
    val preEqBandCount: Int = 10,
    val activeCompensationName: String = "",
    val liveEqCurve31Db: List<Float> = List(31) { 0f },
    val bypassMatchGainDb: Float = 0f,
    val mbcBandCount: Int = 5,
)

@Immutable
data class OrbitUiState(
    val settings: OrbitSettings = OrbitSettings(),
    val activeMode: PleasureMode = PleasureMode.NOVA,
    val modeSource: ModeResolutionSource = ModeResolutionSource.GLOBAL_DEFAULT,
    val manualOverrideMode: PleasureMode? = null,
    val autoBypassedByRule: Boolean = false,
    val activeHeadphoneProfile: HeadphoneProfile = HeadphoneProfile.FLAT_GENERIC,
    val nowPlaying: NowPlayingInfo = NowPlayingInfo(),
    val connectedDevice: ConnectedOutputDevice = ConnectedOutputDevice(),
    val telemetry: LiveAudioTelemetry = LiveAudioTelemetry(),
    val recentActivity: List<TrackActivityEntry> = emptyList(),
    val activeCustomHeadphoneProfile: CustomHeadphoneProfile? = null,
)
