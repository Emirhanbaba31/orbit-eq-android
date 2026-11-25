package com.orbiteq.app.data

import com.orbiteq.app.dsp.AppRuleResolver
import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.AppRuleAction
import com.orbiteq.app.model.CustomHeadphoneProfile
import com.orbiteq.app.model.CustomPreset
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.TrackActivityEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class HistoryCodecTest {

    @Test
    fun adding65UniqueTracksCapsHistoryAt50WhilePinnedTracksRetainFirstSongWithoutEviction() {
        var history = emptyList<TrackActivityEntry>()
        val firstKey = AppRuleResolver.trackKey("com.spotify.music", "Track 01")
        val pinnedTracks = mapOf(firstKey to PleasureMode.HORIZON)

        for (index in 1..65) {
            val label = "Track %02d".format(Locale.ROOT, index)
            val entry = TrackActivityEntry(
                id = "id-$index",
                packageName = "com.spotify.music",
                appName = "Spotify",
                trackTitle = label,
                artist = "Orbit Artist",
                modeUsed = PleasureMode.NOVA,
                spectralTag = "Balanced",
                timestampMs = 1_700_000_000_000L + index,
            )
            history = HistoryCodec.appendHistory(history, entry)
        }

        assertEquals(HistoryCodec.MAX_HISTORY_ITEMS, history.size)
        assertEquals(50, history.size)
        assertEquals("Track 65", history.first().trackTitle)
        assertEquals("Track 16", history.last().trackTitle)
        assertFalse(history.any { it.trackTitle == "Track 01" })

        val roundTrippedHistory = HistoryCodec.decodeHistory(HistoryCodec.encodeHistory(history))
        val roundTrippedPinned = HistoryCodec.decodePinnedTracks(HistoryCodec.encodePinnedTracks(pinnedTracks))

        assertEquals(history, roundTrippedHistory)
        assertEquals(PleasureMode.HORIZON, roundTrippedPinned[firstKey])
        assertEquals(1, roundTrippedPinned.size)
    }

    @Test
    fun appendHistoryDeduplicatesAndBumpsExistingTrackToTop() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            val entry1 = TrackActivityEntry(
                id = "1",
                packageName = "com.spotify.music",
                appName = "Spotify",
                trackTitle = "Istanbul Moonlight",
                artist = "Artist A",
                modeUsed = PleasureMode.NOVA,
                spectralTag = "Balanced",
                timestampMs = 100L,
            )
            val entry2 = entry1.copy(id = "2", trackTitle = "Solar Wind", timestampMs = 200L)
            val entry3 = entry1.copy(id = "3", trackTitle = "Deep Space", timestampMs = 300L)
            val bumpedEntry1 = entry1.copy(
                id = "4",
                packageName = " COM.SPOTIFY.MUSIC ",
                trackTitle = "ISTANBUL MOONLIGHT",
                modeUsed = PleasureMode.GRAVITY,
                spectralTag = "Sub-Heavy",
                timestampMs = 400L,
            )

            var history = emptyList<TrackActivityEntry>()
            history = HistoryCodec.appendHistory(history, entry1)
            history = HistoryCodec.appendHistory(history, entry2)
            history = HistoryCodec.appendHistory(history, entry3)
            history = HistoryCodec.appendHistory(history, bumpedEntry1)

            assertEquals(3, history.size)
            assertEquals("4", history[0].id)
            assertEquals(PleasureMode.GRAVITY, history[0].modeUsed)
            assertEquals("Sub-Heavy", history[0].spectralTag)
            assertEquals("Deep Space", history[1].trackTitle)
            assertEquals("Solar Wind", history[2].trackTitle)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun jsonCodecsRoundTripTurkishUnicodeQuotesAndAllModelMapsCleanly() {
        val history = listOf(
            TrackActivityEntry(
                id = "tr-1",
                packageName = "com.spotify.music",
                appName = "Spotify \"TR\"",
                trackTitle = "Işık & Gölge — ğüşöçıİĞÜŞÖÇ",
                artist = "Şair \"İstanbul\"",
                modeUsed = PleasureMode.ECLIPSE,
                spectralTag = "Warm",
                timestampMs = 1_760_000_000_123L,
            ),
        )
        assertEquals(history, HistoryCodec.decodeHistory(HistoryCodec.encodeHistory(history)))

        val pinned = mapOf(
            AppRuleResolver.trackKey("com.spotify.music", "Işık \"ĞüşöçıİĞÜŞÖÇ\"") to PleasureMode.HORIZON,
            AppRuleResolver.trackKey("com.google.android.youtube", "Podcast \"42\"") to PleasureMode.COMMS,
        )
        assertEquals(pinned, HistoryCodec.decodePinnedTracks(HistoryCodec.encodePinnedTracks(pinned)))

        val rules = AppRuleResolver.defaultAppRules() + (
            "com.example.tr" to AppRule(
                packageName = "com.example.tr",
                displayName = "Türkü \"Çalar\" ğüşöçı",
                action = AppRuleAction.USE_MODE,
                mode = PleasureMode.GRAVITY,
            )
            )
        assertEquals(rules, HistoryCodec.decodeAppRules(HistoryCodec.encodeAppRules(rules)))

        val deviceProfiles = mapOf(
            "soundcore life q30" to HeadphoneProfile.Q30_SAFE_STACK,
            "builtin_speaker" to HeadphoneProfile.PHONE_SPEAKER_SAFE,
        )
        assertEquals(deviceProfiles, HistoryCodec.decodeDeviceProfiles(HistoryCodec.encodeDeviceProfiles(deviceProfiles)))

        val deviceModes = mapOf(
            "soundcore life q30" to PleasureMode.GRAVITY,
            "builtin_speaker" to PleasureMode.COMMS,
        )
        assertEquals(deviceModes, HistoryCodec.decodeDeviceModes(HistoryCodec.encodeDeviceModes(deviceModes)))

        val trims = listOf(-6.5f, -3.0f, 0f, 1.25f, 2.5f, 4.0f, -1.0f, 0.5f, 3.25f, -2.0f)
        val presets = listOf(
            CustomPreset(
                id = "preset-1",
                name = "Gece \"Q30\" — ğüşöçıİĞÜŞÖÇ",
                mode = PleasureMode.ECLIPSE,
                intensity = 84,
                bassMacroDb = 3.5f,
                airMacroDb = -1.5f,
                trimsDb = trims,
            ),
        )
        assertEquals(presets, HistoryCodec.decodeCustomPresets(HistoryCodec.encodeCustomPresets(presets)))
        assertEquals(trims, HistoryCodec.decodeTrims(HistoryCodec.encodeTrims(trims)))
    }

    @Test
    fun corruptedEmptyAndNonFinitePayloadsFallbackSafelyWithoutThrowing() {
        val badInputs = listOf(
            null,
            "",
            "   ",
            "null",
            "{not-valid-json",
            "[1, 2",
            "{\"bad\": \"UNKNOWN_VALUE\"}",
            "{\"entries\": null}",
        )

        badInputs.forEach { bad ->
            assertEquals(emptyList<TrackActivityEntry>(), HistoryCodec.decodeHistory(bad))
            assertEquals(emptyMap<String, PleasureMode>(), HistoryCodec.decodePinnedTracks(bad))
            assertEquals(emptyMap<String, AppRule>(), HistoryCodec.decodeAppRules(bad))
            assertEquals(emptyMap<String, HeadphoneProfile>(), HistoryCodec.decodeDeviceProfiles(bad))
            assertEquals(emptyMap<String, PleasureMode>(), HistoryCodec.decodeDeviceModes(bad))
            assertEquals(emptyList<CustomPreset>(), HistoryCodec.decodeCustomPresets(bad))

            val decodedTrims = HistoryCodec.decodeTrims(bad)
            assertEquals(10, decodedTrims.size)
            assertTrue(decodedTrims.all { it.isFinite() })
        }

        val partialTrims = HistoryCodec.decodeTrims("[1.5, \"oops\", null, 3.0]")
        assertEquals(10, partialTrims.size)
        assertEquals(listOf(1.5f, 0f, 0f, 3.0f, 0f, 0f, 0f, 0f, 0f, 0f), partialTrims)

        val nonFiniteEncoded = HistoryCodec.encodeTrims(listOf(Float.NaN, Float.POSITIVE_INFINITY, 2.25f))
        val nonFiniteDecoded = HistoryCodec.decodeTrims(nonFiniteEncoded)
        assertEquals(10, nonFiniteDecoded.size)
        assertEquals(listOf(0f, 0f, 2.25f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), nonFiniteDecoded)

        val overflowTrims = HistoryCodec.decodeTrims("[1e100, -1e100, 2.5]")
        assertEquals(10, overflowTrims.size)
        assertEquals(listOf(0f, 0f, 2.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), overflowTrims)
        assertTrue(overflowTrims.all { it.isFinite() })

        val overflowPresetJson =
            """[{"id":"p1","name":"Overflow","mode":"NOVA","intensity":78,"bassMacroDb":1e100,"airMacroDb":-1e100,"trimsDb":[1e100,-1e100,1.5]}]"""
        val decodedPresets = HistoryCodec.decodeCustomPresets(overflowPresetJson)
        assertEquals(1, decodedPresets.size)
        assertEquals(0f, decodedPresets[0].bassMacroDb, 0.0001f)
        assertEquals(0f, decodedPresets[0].airMacroDb, 0.0001f)
        assertTrue(decodedPresets[0].trimsDb.all { it.isFinite() })
        assertEquals(1.5f, decodedPresets[0].trimsDb[2], 0.0001f)
    }

    @Test
    fun normalizeGains31PadsTruncatesSanitizesNonFiniteAndClampsTo15Db() {
        assertEquals(31, HistoryCodec.PRE_EQ_BAND_COUNT)

        val shortDirty = listOf(22.5f, -25.0f, Float.NaN, Float.NEGATIVE_INFINITY, -0.0f, 3.25f)
        val normalizedShort = HistoryCodec.normalizeGains31(shortDirty)
        assertEquals(31, normalizedShort.size)
        assertEquals(15f, normalizedShort[0], 0.0001f)
        assertEquals(-15f, normalizedShort[1], 0.0001f)
        assertEquals(0f, normalizedShort[2], 0.0001f)
        assertEquals(0f, normalizedShort[3], 0.0001f)
        assertEquals(0f, normalizedShort[4], 0.0001f)
        assertEquals(3.25f, normalizedShort[5], 0.0001f)
        assertTrue(normalizedShort.drop(6).all { it == 0f })

        val overlong = List(40) { idx -> if (idx == 30) -18f else 2.5f }
        val normalizedOverlong = HistoryCodec.normalizeGains31(overlong)
        assertEquals(31, normalizedOverlong.size)
        assertEquals(-15f, normalizedOverlong[30], 0.0001f)
        assertTrue(normalizedOverlong.all { it.isFinite() && it in -15f..15f })
    }

    @Test
    fun customHeadphoneProfilesAndStringMapRoundTripAndRejectCorruptInputsSafely() {
        val gains31 = List(31) { idx -> ((idx - 15) * 0.75f).coerceIn(-15f, 15f) }
        val profiles = listOf(
            CustomHeadphoneProfile(
                id = "q30-harman",
                name = "Soundcore \"Q30\" — ğüşöçıİĞÜŞÖÇ",
                gains31Db = gains31,
                extraHeadroomDb = 1.5f,
                sourceLabel = "AutoEQ",
            ),
        )

        val encodedProfiles = HistoryCodec.encodeCustomHeadphoneProfiles(profiles)
        val decodedProfiles = HistoryCodec.decodeCustomHeadphoneProfiles(encodedProfiles)
        assertEquals(profiles, decodedProfiles)

        val bindingMap = mapOf(
            "soundcore life q30" to "q30-harman",
            "sony wh-1000xm5" to "xm5-harman",
        )
        assertEquals(bindingMap, HistoryCodec.decodeStringMap(HistoryCodec.encodeStringMap(bindingMap)))

        val badInputs = listOf(null, "", "  ", "null", "{bad", "[1,2", """{"k":null}""")
        badInputs.forEach { bad ->
            assertEquals(emptyList<CustomHeadphoneProfile>(), HistoryCodec.decodeCustomHeadphoneProfiles(bad))
            assertEquals(emptyMap<String, String>(), HistoryCodec.decodeStringMap(bad))
        }

        val corruptProfileJson = """
            [
              {"id": "", "name": "No ID", "gains31Db": [1, 2]},
              {"id": "p-ok", "name": "Overflow HP", "gains31Db": [1e100, 30.0, -30.0, "bad", 2.25], "extraHeadroomDb": -2.0, "sourceLabel": ""},
              123
            ]
        """.trimIndent()
        val sanitizedProfiles = HistoryCodec.decodeCustomHeadphoneProfiles(corruptProfileJson)
        assertEquals(1, sanitizedProfiles.size)
        assertEquals("p-ok", sanitizedProfiles[0].id)
        assertEquals(31, sanitizedProfiles[0].gains31Db.size)
        assertEquals(0f, sanitizedProfiles[0].gains31Db[0], 0.0001f)
        assertEquals(15f, sanitizedProfiles[0].gains31Db[1], 0.0001f)
        assertEquals(-15f, sanitizedProfiles[0].gains31Db[2], 0.0001f)
        assertEquals(0f, sanitizedProfiles[0].gains31Db[3], 0.0001f)
        assertEquals(2.25f, sanitizedProfiles[0].gains31Db[4], 0.0001f)
        assertEquals(0f, sanitizedProfiles[0].extraHeadroomDb, 0.0001f)
        assertEquals("AutoEQ", sanitizedProfiles[0].sourceLabel)

        val mixedMapJson = """{"valid":"id-1","": "empty-key","blank-val":"","num":123,"obj":{},"nil":null}"""
        assertEquals(mapOf("valid" to "id-1"), HistoryCodec.decodeStringMap(mixedMapJson))
    }
}
