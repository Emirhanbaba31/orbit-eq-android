package com.orbiteq.app.dsp

import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.AppRuleAction
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.ModeResolutionSource
import com.orbiteq.app.model.PleasureMode
import java.util.Locale

data class ResolvedModeDecision(
    val mode: PleasureMode,
    val autoBypassed: Boolean,
    val source: ModeResolutionSource,
)

object AppRuleResolver {

    private const val BUILTIN_SPEAKER_KEY = "builtin_speaker"
    private const val UNKNOWN_OUTPUT_KEY = "unknown_output"

    private val DEFAULT_RULES: Map<String, AppRule> = listOf(
        AppRule(
            packageName = "com.spotify.music",
            displayName = "Spotify",
            action = AppRuleAction.USE_MODE,
            mode = PleasureMode.NOVA,
        ),
        AppRule(
            packageName = "com.google.android.apps.youtube.music",
            displayName = "YouTube Music",
            action = AppRuleAction.USE_MODE,
            mode = PleasureMode.NOVA,
        ),
        AppRule(
            packageName = "com.google.android.youtube",
            displayName = "YouTube",
            action = AppRuleAction.USE_MODE,
            mode = PleasureMode.COMMS,
        ),
        AppRule(
            packageName = "com.audible.application",
            displayName = "Audible",
            action = AppRuleAction.USE_MODE,
            mode = PleasureMode.COMMS,
        ),
        AppRule(
            packageName = "com.google.android.apps.podcasts",
            displayName = "Podcasts",
            action = AppRuleAction.USE_MODE,
            mode = PleasureMode.COMMS,
        ),
        AppRule(
            packageName = "com.google.android.apps.maps",
            displayName = "Google Maps",
            action = AppRuleAction.BYPASS,
            mode = PleasureMode.COMMS,
        ),
        AppRule(
            packageName = "com.google.android.dialer",
            displayName = "Phone",
            action = AppRuleAction.BYPASS,
            mode = PleasureMode.COMMS,
        ),
    ).associateBy { it.packageName }

    fun defaultAppRules(): Map<String, AppRule> = DEFAULT_RULES

    fun trackKey(packageName: String, trackTitle: String): String {
        val pkg = packageName.trim().lowercase(Locale.ROOT)
        val title = trackTitle.trim().lowercase(Locale.ROOT)
        return "$pkg|$title"
    }

    fun deviceKey(deviceName: String?, isSpeaker: Boolean): String {
        if (isSpeaker) return BUILTIN_SPEAKER_KEY
        val trimmed = deviceName?.trim()
        if (!trimmed.isNullOrEmpty()) {
            return trimmed.lowercase(Locale.ROOT)
        }
        return UNKNOWN_OUTPUT_KEY
    }

    fun resolvePleasureMode(
        packageName: String?,
        trackTitle: String?,
        manualOverride: PleasureMode?,
        pinnedTracks: Map<String, PleasureMode>,
        appRules: Map<String, AppRule>,
        deviceDefaultMode: PleasureMode?,
        fallbackMode: PleasureMode,
    ): ResolvedModeDecision {
        if (manualOverride != null) {
            return ResolvedModeDecision(
                mode = manualOverride,
                autoBypassed = false,
                source = ModeResolutionSource.MANUAL_OVERRIDE,
            )
        }

        val normalizedPkg = packageName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val normalizedTitle = trackTitle?.trim()?.lowercase(Locale.ROOT).orEmpty()

        if (normalizedPkg.isNotEmpty() && normalizedTitle.isNotEmpty()) {
            val key = "$normalizedPkg|$normalizedTitle"
            val pinnedMode = findNormalized(pinnedTracks, key)
            if (pinnedMode != null) {
                return ResolvedModeDecision(
                    mode = pinnedMode,
                    autoBypassed = false,
                    source = ModeResolutionSource.PINNED_TRACK,
                )
            }
        }

        if (normalizedPkg.isNotEmpty()) {
            val appRule = findNormalized(appRules, normalizedPkg)
            if (appRule != null) {
                return ResolvedModeDecision(
                    mode = appRule.mode,
                    autoBypassed = appRule.action == AppRuleAction.BYPASS,
                    source = ModeResolutionSource.APP_RULE,
                )
            }
        }

        if (deviceDefaultMode != null) {
            return ResolvedModeDecision(
                mode = deviceDefaultMode,
                autoBypassed = false,
                source = ModeResolutionSource.DEVICE_DEFAULT,
            )
        }

        return ResolvedModeDecision(
            mode = fallbackMode,
            autoBypassed = false,
            source = ModeResolutionSource.GLOBAL_DEFAULT,
        )
    }

    fun resolveHeadphoneProfile(
        deviceName: String?,
        isSpeaker: Boolean,
        savedDeviceProfiles: Map<String, HeadphoneProfile>,
    ): HeadphoneProfile {
        val key = deviceKey(deviceName, isSpeaker)
        findNormalized(savedDeviceProfiles, key)?.let { return it }

        val trimmedName = deviceName?.trim()
        if (!isSpeaker && PleasureProfiles.detectSoundcoreQ30(trimmedName)) {
            return HeadphoneProfile.Q30_CLEAN_BASE
        }
        if (isSpeaker) {
            return HeadphoneProfile.PHONE_SPEAKER_SAFE
        }
        return HeadphoneProfile.FLAT_GENERIC
    }

    private fun <V> findNormalized(map: Map<String, V>, normalizedKey: String): V? {
        map[normalizedKey]?.let { return it }
        return map.entries.firstOrNull { (k, _) ->
            k.trim().lowercase(Locale.ROOT) == normalizedKey
        }?.value
    }
}
