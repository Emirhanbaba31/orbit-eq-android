package com.orbiteq.app.dsp

import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.AppRuleAction
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.ModeResolutionSource
import com.orbiteq.app.model.PleasureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppRuleResolverTest {

    @Test
    fun defaultAppRulesMatchSpecificationExactly() {
        val rules = AppRuleResolver.defaultAppRules()

        assertEquals(7, rules.size)
        assertEquals(
            AppRule("com.spotify.music", "Spotify", AppRuleAction.USE_MODE, PleasureMode.NOVA),
            rules["com.spotify.music"],
        )
        assertEquals(
            AppRule("com.google.android.apps.youtube.music", "YouTube Music", AppRuleAction.USE_MODE, PleasureMode.NOVA),
            rules["com.google.android.apps.youtube.music"],
        )
        assertEquals(
            AppRule("com.google.android.youtube", "YouTube", AppRuleAction.USE_MODE, PleasureMode.COMMS),
            rules["com.google.android.youtube"],
        )
        assertEquals(
            AppRule("com.audible.application", "Audible", AppRuleAction.USE_MODE, PleasureMode.COMMS),
            rules["com.audible.application"],
        )
        assertEquals(
            AppRule("com.google.android.apps.podcasts", "Podcasts", AppRuleAction.USE_MODE, PleasureMode.COMMS),
            rules["com.google.android.apps.podcasts"],
        )
        assertEquals(
            AppRule("com.google.android.apps.maps", "Google Maps", AppRuleAction.BYPASS, PleasureMode.COMMS),
            rules["com.google.android.apps.maps"],
        )
        assertEquals(
            AppRule("com.google.android.dialer", "Phone", AppRuleAction.BYPASS, PleasureMode.COMMS),
            rules["com.google.android.dialer"],
        )
    }

    @Test
    fun trackKeyNormalizesTrimmedLowercaseUsingRootLocaleUnderTurkishLocale() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            val keyUpperAsciiI = AppRuleResolver.trackKey("  COM.SPOTIFY.MUSIC ", "  ISTANBUL NIGHTS  ")
            val keyLowerAsciiI = AppRuleResolver.trackKey("com.spotify.music", "istanbul nights")

            assertEquals("com.spotify.music|istanbul nights", keyUpperAsciiI)
            assertEquals(keyLowerAsciiI, keyUpperAsciiI)
            assertFalse(keyUpperAsciiI.contains('\u0131'))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun strictPrecedenceManualOverPinnedOverAppOverDeviceOverGlobal() {
        val pkg = "com.spotify.music"
        val title = "Orbital Drift"
        val pinned = mapOf(AppRuleResolver.trackKey(pkg, title) to PleasureMode.HORIZON)
        val appRules = mapOf(
            pkg to AppRule(pkg, "Spotify", AppRuleAction.USE_MODE, PleasureMode.GRAVITY),
        )

        val manualWins = AppRuleResolver.resolvePleasureMode(
            packageName = pkg,
            trackTitle = title,
            manualOverride = PleasureMode.ECLIPSE,
            pinnedTracks = pinned,
            appRules = appRules,
            deviceDefaultMode = PleasureMode.COMMS,
            fallbackMode = PleasureMode.NOVA,
        )
        assertEquals(
            ResolvedModeDecision(PleasureMode.ECLIPSE, autoBypassed = false, source = ModeResolutionSource.MANUAL_OVERRIDE),
            manualWins,
        )

        val pinnedWins = AppRuleResolver.resolvePleasureMode(
            packageName = pkg,
            trackTitle = title,
            manualOverride = null,
            pinnedTracks = pinned,
            appRules = appRules,
            deviceDefaultMode = PleasureMode.COMMS,
            fallbackMode = PleasureMode.NOVA,
        )
        assertEquals(
            ResolvedModeDecision(PleasureMode.HORIZON, autoBypassed = false, source = ModeResolutionSource.PINNED_TRACK),
            pinnedWins,
        )

        val appRuleWins = AppRuleResolver.resolvePleasureMode(
            packageName = pkg,
            trackTitle = "Unpinned Song",
            manualOverride = null,
            pinnedTracks = pinned,
            appRules = appRules,
            deviceDefaultMode = PleasureMode.COMMS,
            fallbackMode = PleasureMode.NOVA,
        )
        assertEquals(
            ResolvedModeDecision(PleasureMode.GRAVITY, autoBypassed = false, source = ModeResolutionSource.APP_RULE),
            appRuleWins,
        )

        val deviceDefaultWins = AppRuleResolver.resolvePleasureMode(
            packageName = "com.example.player",
            trackTitle = "Unpinned Song",
            manualOverride = null,
            pinnedTracks = pinned,
            appRules = appRules,
            deviceDefaultMode = PleasureMode.COMMS,
            fallbackMode = PleasureMode.NOVA,
        )
        assertEquals(
            ResolvedModeDecision(PleasureMode.COMMS, autoBypassed = false, source = ModeResolutionSource.DEVICE_DEFAULT),
            deviceDefaultWins,
        )

        val globalFallbackWins = AppRuleResolver.resolvePleasureMode(
            packageName = "com.example.player",
            trackTitle = "Unpinned Song",
            manualOverride = null,
            pinnedTracks = pinned,
            appRules = appRules,
            deviceDefaultMode = null,
            fallbackMode = PleasureMode.NOVA,
        )
        assertEquals(
            ResolvedModeDecision(PleasureMode.NOVA, autoBypassed = false, source = ModeResolutionSource.GLOBAL_DEFAULT),
            globalFallbackWins,
        )
    }

    @Test
    fun excludedAppReturnsAutoBypassedEvenWithDeviceDefaultUnlessManualOrPinnedOverrides() {
        val mapsPkg = "com.google.android.apps.maps"
        val appRules = AppRuleResolver.defaultAppRules()
        val pinned = mapOf(AppRuleResolver.trackKey(mapsPkg, "Custom Route") to PleasureMode.ECLIPSE)

        val excludedByRule = AppRuleResolver.resolvePleasureMode(
            packageName = mapsPkg,
            trackTitle = "Turn right onto Istiklal",
            manualOverride = null,
            pinnedTracks = pinned,
            appRules = appRules,
            deviceDefaultMode = PleasureMode.GRAVITY,
            fallbackMode = PleasureMode.NOVA,
        )
        assertEquals(
            ResolvedModeDecision(PleasureMode.COMMS, autoBypassed = true, source = ModeResolutionSource.APP_RULE),
            excludedByRule,
        )

        val manualOverridesBypass = AppRuleResolver.resolvePleasureMode(
            packageName = mapsPkg,
            trackTitle = "Turn right onto Istiklal",
            manualOverride = PleasureMode.HORIZON,
            pinnedTracks = pinned,
            appRules = appRules,
            deviceDefaultMode = PleasureMode.GRAVITY,
            fallbackMode = PleasureMode.NOVA,
        )
        assertEquals(
            ResolvedModeDecision(PleasureMode.HORIZON, autoBypassed = false, source = ModeResolutionSource.MANUAL_OVERRIDE),
            manualOverridesBypass,
        )

        val pinnedOverridesBypass = AppRuleResolver.resolvePleasureMode(
            packageName = mapsPkg,
            trackTitle = "Custom Route",
            manualOverride = null,
            pinnedTracks = pinned,
            appRules = appRules,
            deviceDefaultMode = PleasureMode.GRAVITY,
            fallbackMode = PleasureMode.NOVA,
        )
        assertEquals(
            ResolvedModeDecision(PleasureMode.ECLIPSE, autoBypassed = false, source = ModeResolutionSource.PINNED_TRACK),
            pinnedOverridesBypass,
        )
    }

    @Test
    fun q30HeadphoneCompensationStaysIndependentAcrossAppAndTrackSwitches() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            val defaultQ30 = AppRuleResolver.resolveHeadphoneProfile(
                deviceName = "Soundcore Life Q30",
                isSpeaker = false,
                savedDeviceProfiles = emptyMap(),
            )
            assertEquals(HeadphoneProfile.Q30_CLEAN_BASE, defaultQ30)

            val savedSafeStack = AppRuleResolver.resolveHeadphoneProfile(
                deviceName = "SOUNDCORE LIFE Q30",
                isSpeaker = false,
                savedDeviceProfiles = mapOf("soundcore life q30" to HeadphoneProfile.Q30_SAFE_STACK),
            )
            assertEquals(HeadphoneProfile.Q30_SAFE_STACK, savedSafeStack)

            val speakerDefault = AppRuleResolver.resolveHeadphoneProfile(
                deviceName = "Phone Speaker",
                isSpeaker = true,
                savedDeviceProfiles = emptyMap(),
            )
            assertEquals(HeadphoneProfile.PHONE_SPEAKER_SAFE, speakerDefault)

            val genericHeadphone = AppRuleResolver.resolveHeadphoneProfile(
                deviceName = "INZONE Buds",
                isSpeaker = false,
                savedDeviceProfiles = emptyMap(),
            )
            assertEquals(HeadphoneProfile.FLAT_GENERIC, genericHeadphone)

            // Switching apps/tracks/manual modes never mutates or alters resolved Q30 compensation
            val appRules = AppRuleResolver.defaultAppRules()
            val pinned = mapOf(AppRuleResolver.trackKey("com.google.android.youtube", "Live Session") to PleasureMode.HORIZON)

            val spotifyMode = AppRuleResolver.resolvePleasureMode(
                packageName = "com.spotify.music",
                trackTitle = "Deep Orbit",
                manualOverride = null,
                pinnedTracks = pinned,
                appRules = appRules,
                deviceDefaultMode = PleasureMode.GRAVITY,
                fallbackMode = PleasureMode.NOVA,
            )
            val youtubePinnedMode = AppRuleResolver.resolvePleasureMode(
                packageName = "com.google.android.youtube",
                trackTitle = "Live Session",
                manualOverride = PleasureMode.ECLIPSE,
                pinnedTracks = pinned,
                appRules = appRules,
                deviceDefaultMode = PleasureMode.GRAVITY,
                fallbackMode = PleasureMode.NOVA,
            )
            assertEquals(PleasureMode.NOVA, spotifyMode.mode)
            assertEquals(PleasureMode.ECLIPSE, youtubePinnedMode.mode)
            assertEquals(
                HeadphoneProfile.Q30_SAFE_STACK,
                AppRuleResolver.resolveHeadphoneProfile(
                    deviceName = "Soundcore Life Q30",
                    isSpeaker = false,
                    savedDeviceProfiles = mapOf("Soundcore Life Q30" to HeadphoneProfile.Q30_SAFE_STACK),
                ),
            )
            assertTrue(PleasureProfiles.detectSoundcoreQ30("Soundcore Life Q30"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
