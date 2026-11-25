package com.orbiteq.app.data

import com.orbiteq.app.dsp.AppRuleResolver
import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.AppRuleAction
import com.orbiteq.app.model.CustomHeadphoneProfile
import com.orbiteq.app.model.CustomPreset
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.MbcMode
import com.orbiteq.app.model.OrbitSettings
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.ResponseSpeed
import com.orbiteq.app.model.SessionAttachmentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitBackupCodecTest {

    @Test
    fun encodeAndDecodeBackupRoundTripsAllV2SettingsAndExcludesRuntimeFlagsAndHistory() {
        val trims = listOf(-4.5f, -2.0f, 0f, 1.5f, 3.0f, 2.0f, -1.0f, 0.5f, 2.5f, -3.5f)
        val gains31 = List(31) { idx -> (idx - 15) * 0.5f }
        val customPreset = CustomPreset(
            id = "preset-night",
            name = "Gece Modu",
            mode = PleasureMode.ECLIPSE,
            intensity = 85,
            bassMacroDb = 3.5f,
            airMacroDb = -1.5f,
            trimsDb = trims,
        )
        val customProfile = CustomHeadphoneProfile(
            id = "autoeq-q30",
            name = "Soundcore Life Q30 (Harman 31-Band)",
            gains31Db = gains31,
            extraHeadroomDb = 1.5f,
            sourceLabel = "AutoEQ",
        )
        val settings = OrbitSettings(
            powerEnabled = true,
            bypassed = true,
            selectedMode = PleasureMode.ECLIPSE,
            intensityPercent = 85,
            bassMacroDb = 4.5f,
            airMacroDb = -1.5f,
            dynamicLoudness = false,
            mbcEnabled = true,
            responseSpeed = ResponseSpeed.REACTIVE,
            manualTrimsDb = trims,
            themeMode = OrbitThemeMode.LUNAR_MONOCHROME,
            sessionMode = SessionAttachmentMode.GLOBAL_ONLY,
            q30TipDismissed = true,
            appRules = mapOf(
                "com.spotify.music" to AppRule(
                    packageName = "com.spotify.music",
                    displayName = "Spotify",
                    action = AppRuleAction.USE_MODE,
                    mode = PleasureMode.GRAVITY,
                ),
            ),
            pinnedTracks = mapOf(
                AppRuleResolver.trackKey("com.spotify.music", "Orbit Song") to PleasureMode.HORIZON,
            ),
            deviceProfiles = mapOf("soundcore life q30" to HeadphoneProfile.Q30_SAFE_STACK),
            deviceDefaultModes = mapOf("soundcore life q30" to PleasureMode.NOVA),
            customPresets = listOf(customPreset),
            mbcMode = MbcMode.PUNCHY,
            subsonicFilterEnabled = false,
            trueHeadroomMode = true,
            channelBalance = -0.35f,
            customHeadphoneProfiles = listOf(customProfile),
            deviceCustomProfileIds = mapOf("soundcore life q30" to "autoeq-q30"),
        )

        val json = OrbitBackupCodec.encodeBackup(settings, exportedAtMs = 1_760_000_000_000L)
        assertFalse(json.contains("powerEnabled"))
        assertFalse(json.contains("bypassed"))
        assertFalse(json.contains("q30TipDismissed"))
        assertFalse(json.contains("recentActivity"))

        val result = OrbitBackupCodec.decodeBackup(json)
        assertTrue(result is BackupImportResult.Success)
        val payload = (result as BackupImportResult.Success).payload

        assertEquals(OrbitBackupCodec.CURRENT_SCHEMA_VERSION, payload.schemaVersion)
        assertEquals(1_760_000_000_000L, payload.exportedAtMs)
        assertEquals(PleasureMode.ECLIPSE, payload.selectedMode)
        assertEquals(85, payload.intensityPercent)
        assertEquals(4.5f, payload.bassMacroDb, 0.0001f)
        assertEquals(-1.5f, payload.airMacroDb, 0.0001f)
        assertFalse(payload.dynamicLoudness)
        assertEquals(MbcMode.PUNCHY, payload.mbcMode)
        assertFalse(payload.subsonicFilterEnabled)
        assertTrue(payload.trueHeadroomMode)
        assertEquals(-0.35f, payload.channelBalance, 0.0001f)
        assertEquals(ResponseSpeed.REACTIVE, payload.responseSpeed)
        assertEquals(trims, payload.manualTrimsDb)
        assertEquals(OrbitThemeMode.LUNAR_MONOCHROME, payload.themeMode)
        assertEquals(SessionAttachmentMode.GLOBAL_ONLY, payload.sessionMode)
        assertEquals(listOf(customPreset), payload.customPresets)
        assertEquals(listOf(customProfile), payload.customHeadphoneProfiles)
        assertEquals(mapOf("soundcore life q30" to HeadphoneProfile.Q30_SAFE_STACK), payload.deviceProfiles)
        assertEquals(mapOf("soundcore life q30" to "autoeq-q30"), payload.deviceCustomProfileIds)
        assertEquals(mapOf("soundcore life q30" to PleasureMode.NOVA), payload.deviceDefaultModes)
        assertEquals(settings.appRules, payload.appRules)
        assertEquals(settings.pinnedTracks, payload.pinnedTracks)
    }

    @Test
    fun mergeIntoPreservesRuntimeFlagsMergesCollectionsAndDropsDanglingCustomProfileBindings() {
        val localOnlyPreset = CustomPreset(
            id = "local-preset",
            name = "Local Only",
            mode = PleasureMode.NOVA,
            intensity = 70,
            bassMacroDb = 1.0f,
            airMacroDb = 1.0f,
            trimsDb = List(10) { 0f },
        )
        val sharedPresetOld = localOnlyPreset.copy(id = "shared-preset", name = "Old Shared")
        val sharedPresetNew = localOnlyPreset.copy(id = "shared-preset", name = "New Shared", intensity = 92)
        val importedPreset = localOnlyPreset.copy(id = "imported-preset", name = "Imported")

        val localCustomProfile = CustomHeadphoneProfile(
            id = "local-hp",
            name = "Local HP",
            gains31Db = List(31) { 0.5f },
        )
        val importedCustomProfile = CustomHeadphoneProfile(
            id = "imported-hp",
            name = "Imported HP",
            gains31Db = List(31) { -1.0f },
        )

        val current = OrbitSettings(
            powerEnabled = true,
            bypassed = true,
            q30TipDismissed = true,
            selectedMode = PleasureMode.NOVA,
            customPresets = listOf(localOnlyPreset, sharedPresetOld),
            customHeadphoneProfiles = listOf(localCustomProfile),
            deviceProfiles = mapOf("Local Headphones" to HeadphoneProfile.FLAT_GENERIC),
            deviceCustomProfileIds = mapOf("Local Headphones" to "local-hp"),
            deviceDefaultModes = mapOf("Local Headphones" to PleasureMode.NOVA),
            appRules = mapOf(
                "com.local.app" to AppRule("com.local.app", "Local", AppRuleAction.USE_MODE, PleasureMode.COMMS),
            ),
            pinnedTracks = mapOf("com.local.app|song" to PleasureMode.ECLIPSE),
        )

        val payload = OrbitBackupPayload(
            schemaVersion = 2,
            exportedAtMs = 123456L,
            selectedMode = PleasureMode.HORIZON,
            intensityPercent = 66,
            bassMacroDb = -2.0f,
            airMacroDb = 5.0f,
            dynamicLoudness = false,
            mbcMode = MbcMode.OFF,
            subsonicFilterEnabled = false,
            trueHeadroomMode = true,
            channelBalance = 0.4f,
            responseSpeed = ResponseSpeed.CHILL,
            manualTrimsDb = List(10) { 1.0f },
            themeMode = OrbitThemeMode.LUNAR_MONOCHROME,
            sessionMode = SessionAttachmentMode.GLOBAL_ONLY,
            customPresets = listOf(sharedPresetNew, importedPreset),
            customHeadphoneProfiles = listOf(importedCustomProfile),
            deviceProfiles = mapOf("  Soundcore Life Q30  " to HeadphoneProfile.Q30_CLEAN_BASE),
            deviceCustomProfileIds = mapOf(
                "  Soundcore Life Q30  " to "imported-hp",
                "Ghost Device" to "missing-profile-id",
            ),
            deviceDefaultModes = mapOf("  Soundcore Life Q30  " to PleasureMode.HORIZON),
            appRules = mapOf(
                "  COM.IMPORTED.APP  " to AppRule(
                    packageName = "  COM.IMPORTED.APP  ",
                    displayName = "Imported App",
                    action = AppRuleAction.BYPASS,
                    mode = PleasureMode.COMMS,
                ),
            ),
            pinnedTracks = mapOf("  COM.IMPORTED.APP|TRACK  " to PleasureMode.GRAVITY),
        )

        val merged = OrbitBackupCodec.mergeInto(current, payload)

        // Runtime & tip flags stay untouched
        assertTrue(merged.powerEnabled)
        assertTrue(merged.bypassed)
        assertTrue(merged.q30TipDismissed)

        // Scalar tuning updated + mbcEnabled synchronized
        assertEquals(PleasureMode.HORIZON, merged.selectedMode)
        assertEquals(66, merged.intensityPercent)
        assertEquals(-2.0f, merged.bassMacroDb, 0.0001f)
        assertEquals(5.0f, merged.airMacroDb, 0.0001f)
        assertFalse(merged.dynamicLoudness)
        assertEquals(MbcMode.OFF, merged.mbcMode)
        assertFalse(merged.mbcEnabled)
        assertFalse(merged.subsonicFilterEnabled)
        assertTrue(merged.trueHeadroomMode)
        assertEquals(0.4f, merged.channelBalance, 0.0001f)
        assertEquals(ResponseSpeed.CHILL, merged.responseSpeed)
        assertEquals(List(10) { 1.0f }, merged.manualTrimsDb)
        assertEquals(OrbitThemeMode.LUNAR_MONOCHROME, merged.themeMode)
        assertEquals(SessionAttachmentMode.GLOBAL_ONLY, merged.sessionMode)

        // Collections merged by id/normalized key, dangling profile id dropped
        assertEquals(listOf(localOnlyPreset, sharedPresetNew, importedPreset), merged.customPresets)
        assertEquals(listOf(localCustomProfile, importedCustomProfile), merged.customHeadphoneProfiles)
        assertEquals(
            mapOf(
                "local headphones" to HeadphoneProfile.FLAT_GENERIC,
                "soundcore life q30" to HeadphoneProfile.Q30_CLEAN_BASE,
            ),
            merged.deviceProfiles,
        )
        assertEquals(
            mapOf(
                "local headphones" to "local-hp",
                "soundcore life q30" to "imported-hp",
            ),
            merged.deviceCustomProfileIds,
        )
        assertFalse(merged.deviceCustomProfileIds.containsKey("ghost device"))
        assertEquals(
            mapOf(
                "local headphones" to PleasureMode.NOVA,
                "soundcore life q30" to PleasureMode.HORIZON,
            ),
            merged.deviceDefaultModes,
        )
        assertTrue(merged.appRules.containsKey("com.local.app"))
        assertEquals("com.imported.app", merged.appRules["com.imported.app"]?.packageName)
        assertEquals(
            mapOf(
                "com.local.app|song" to PleasureMode.ECLIPSE,
                "com.imported.app|track" to PleasureMode.GRAVITY,
            ),
            merged.pinnedTracks,
        )
    }

    @Test
    fun decodeBackupSupportsV1BackupsByMigratingMbcEnabledAndDefaultingV2Fields() {
        val v1DisabledMbc = """
            {
              "schemaVersion": 1,
              "exportedAtMs": 55555,
              "settings": {
                "selectedMode": "GRAVITY",
                "intensityPercent": 82,
                "bassMacroDb": 3.0,
                "airMacroDb": 1.0,
                "dynamicLoudness": true,
                "mbcEnabled": false,
                "responseSpeed": "CHILL"
              }
            }
        """.trimIndent()

        val resultOff = OrbitBackupCodec.decodeBackup(v1DisabledMbc)
        assertTrue(resultOff is BackupImportResult.Success)
        val payloadOff = (resultOff as BackupImportResult.Success).payload
        assertEquals(1, payloadOff.schemaVersion)
        assertEquals(PleasureMode.GRAVITY, payloadOff.selectedMode)
        assertEquals(MbcMode.OFF, payloadOff.mbcMode)
        assertTrue(payloadOff.subsonicFilterEnabled)
        assertFalse(payloadOff.trueHeadroomMode)
        assertEquals(0f, payloadOff.channelBalance, 0.0001f)
        assertTrue(payloadOff.customHeadphoneProfiles.isEmpty())
        assertTrue(payloadOff.deviceCustomProfileIds.isEmpty())

        val v1EnabledMbc = """
            {
              "schemaVersion": 1,
              "settings": {
                "mbcEnabled": true
              }
            }
        """.trimIndent()
        val resultSubtle = OrbitBackupCodec.decodeBackup(v1EnabledMbc)
        assertTrue(resultSubtle is BackupImportResult.Success)
        assertEquals(MbcMode.SUBTLE, (resultSubtle as BackupImportResult.Success).payload.mbcMode)
    }

    @Test
    fun decodeBackupRejectsInvalidRootEmptyCorruptAndUnsupportedSchemaVersionsWithoutThrowing() {
        val invalidPayloads = listOf(
            "",
            "   ",
            "null",
            "42",
            "\"hello\"",
            "[1, 2, 3]",
            "{not-valid-json",
            "{}",
            """{"schemaVersion": 3, "settings": {}}""",
            """{"schemaVersion": 99, "settings": {}}""",
            """{"schemaVersion": 0, "settings": {}}""",
            """{"schemaVersion": -1, "settings": {}}""",
            """{"schemaVersion": "2", "settings": {}}""",
            """{"schemaVersion": 2.5, "settings": {}}""",
            """{"schemaVersion": 2}""",
            """{"schemaVersion": 2, "settings": null}""",
            """{"schemaVersion": 2, "settings": []}""",
            """{"schemaVersion": 2, "settings": "corrupt"}""",
        )

        invalidPayloads.forEach { raw ->
            val result = OrbitBackupCodec.decodeBackup(raw)
            assertTrue("Expected Error for payload: $raw", result is BackupImportResult.Error)
            assertTrue((result as BackupImportResult.Error).reason.isNotBlank())
        }
    }

    @Test
    fun decodeBackupSanitizesWrongTypedFieldsNonFiniteNumbersAndUnknownEnumsSafely() {
        val nastyInnerPayload = """
            {
              "schemaVersion": 2,
              "exportedAtMs": "bad",
              "settings": {
                "selectedMode": "UNKNOWN_MODE",
                "intensityPercent": 999,
                "bassMacroDb": "not-a-number",
                "airMacroDb": 1e100,
                "dynamicLoudness": "truthy-string",
                "mbcMode": "TURBO",
                "mbcEnabled": false,
                "subsonicFilterEnabled": 123,
                "trueHeadroomMode": "yes",
                "channelBalance": -4.5,
                "responseSpeed": "WARP",
                "themeMode": 42,
                "sessionMode": null,
                "manualTrimsDb": [1e100, -50.0, "oops", 3.5],
                "customPresets": "not-an-array",
                "customHeadphoneProfiles": [
                  {"id": "hp1", "name": "Valid", "gains31Db": [1e100, 42.0, -42.0, 2.5], "extraHeadroomDb": -3.0},
                  {"id": "", "name": "MissingId"},
                  "bad-element"
                ],
                "deviceProfiles": {"q30": "NON_EXISTENT", "": "FLAT_GENERIC"},
                "deviceCustomProfileIds": {"q30": 123, "ok": "hp1"}
              }
            }
        """.trimIndent()

        val result = OrbitBackupCodec.decodeBackup(nastyInnerPayload)
        assertTrue(result is BackupImportResult.Success)
        val payload = (result as BackupImportResult.Success).payload

        assertEquals(0L, payload.exportedAtMs)
        assertEquals(PleasureMode.NOVA, payload.selectedMode)
        assertEquals(100, payload.intensityPercent)
        assertEquals(2.0f, payload.bassMacroDb, 0.0001f)
        assertEquals(1.5f, payload.airMacroDb, 0.0001f)
        assertTrue(payload.dynamicLoudness)
        assertEquals(MbcMode.OFF, payload.mbcMode)
        assertTrue(payload.subsonicFilterEnabled)
        assertFalse(payload.trueHeadroomMode)
        assertEquals(-1.0f, payload.channelBalance, 0.0001f)
        assertEquals(ResponseSpeed.BALANCED, payload.responseSpeed)
        assertEquals(OrbitThemeMode.DEEP_SPACE_HUD, payload.themeMode)
        assertEquals(SessionAttachmentMode.AUTO, payload.sessionMode)
        assertEquals(10, payload.manualTrimsDb.size)
        assertTrue(payload.manualTrimsDb.all { it.isFinite() && it in -8f..8f })
        assertEquals(0f, payload.manualTrimsDb[0], 0.0001f)
        assertEquals(-8f, payload.manualTrimsDb[1], 0.0001f)
        assertEquals(0f, payload.manualTrimsDb[2], 0.0001f)
        assertEquals(3.5f, payload.manualTrimsDb[3], 0.0001f)

        assertEquals(1, payload.customHeadphoneProfiles.size)
        val hp = payload.customHeadphoneProfiles[0]
        assertEquals(31, hp.gains31Db.size)
        assertEquals(0f, hp.gains31Db[0], 0.0001f)
        assertEquals(15f, hp.gains31Db[1], 0.0001f)
        assertEquals(-15f, hp.gains31Db[2], 0.0001f)
        assertEquals(2.5f, hp.gains31Db[3], 0.0001f)
        assertEquals(0f, hp.extraHeadroomDb, 0.0001f)
        assertTrue(payload.deviceProfiles.isEmpty())
        assertEquals(mapOf("ok" to "hp1"), payload.deviceCustomProfileIds)
    }
}
