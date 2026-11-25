package com.orbiteq.app.data

import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.CustomHeadphoneProfile
import com.orbiteq.app.model.CustomPreset
import com.orbiteq.app.model.DeEsserMode
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.MbcMode
import com.orbiteq.app.model.OrbitSettings
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.ResponseSpeed
import com.orbiteq.app.model.SessionAttachmentMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

data class OrbitBackupPayload(
    val schemaVersion: Int,
    val exportedAtMs: Long,
    val selectedMode: PleasureMode,
    val intensityPercent: Int,
    val bassMacroDb: Float,
    val airMacroDb: Float,
    val dynamicLoudness: Boolean,
    val mbcMode: MbcMode,
    val subsonicFilterEnabled: Boolean,
    val trueHeadroomMode: Boolean,
    val channelBalance: Float,
    val responseSpeed: ResponseSpeed,
    val manualTrimsDb: List<Float>,
    val themeMode: OrbitThemeMode,
    val sessionMode: SessionAttachmentMode,
    val customPresets: List<CustomPreset>,
    val customHeadphoneProfiles: List<CustomHeadphoneProfile>,
    val deviceProfiles: Map<String, HeadphoneProfile>,
    val deviceCustomProfileIds: Map<String, String>,
    val deviceDefaultModes: Map<String, PleasureMode>,
    val appRules: Map<String, AppRule>,
    val pinnedTracks: Map<String, PleasureMode>,
    val toneTiltDbPerOct: Float = 0f,
    val deEsserMode: DeEsserMode = DeEsserMode.GENTLE,
    val loudnessMatchedBypass: Boolean = true,
)

sealed interface BackupImportResult {
    data class Success(val payload: OrbitBackupPayload) : BackupImportResult
    data class Error(val reason: String) : BackupImportResult
}

object OrbitBackupCodec {

    const val CURRENT_SCHEMA_VERSION = 3

    private const val BAND_COUNT = 10
    private const val MIN_TRIM_DB = -8.0f
    private const val MAX_TRIM_DB = 8.0f
    private const val MIN_MACRO_DB = -6.0f
    private const val MAX_MACRO_DB = 8.0f
    private const val MIN_TONE_TILT_DB_PER_OCT = -1.5f
    private const val MAX_TONE_TILT_DB_PER_OCT = 1.5f
    private const val DEFAULT_INTENSITY = 78
    private const val DEFAULT_BASS_MACRO_DB = 2.0f
    private const val DEFAULT_AIR_MACRO_DB = 1.5f
    private const val DEFAULT_TONE_TILT_DB_PER_OCT = 0f

    fun encodeBackup(
        settings: OrbitSettings,
        exportedAtMs: Long = System.currentTimeMillis(),
    ): String {
        val settingsObj = JSONObject().apply {
            put("selectedMode", settings.selectedMode.name)
            put("intensityPercent", settings.intensityPercent.coerceIn(0, 100))
            put("bassMacroDb", sanitizeFloat(settings.bassMacroDb, MIN_MACRO_DB, MAX_MACRO_DB, DEFAULT_BASS_MACRO_DB).toDouble())
            put("airMacroDb", sanitizeFloat(settings.airMacroDb, MIN_MACRO_DB, MAX_MACRO_DB, DEFAULT_AIR_MACRO_DB).toDouble())
            put("dynamicLoudness", settings.dynamicLoudness)
            put("mbcMode", settings.mbcMode.name)
            put("subsonicFilterEnabled", settings.subsonicFilterEnabled)
            put("trueHeadroomMode", settings.trueHeadroomMode)
            put("channelBalance", sanitizeFloat(settings.channelBalance, -1.0f, 1.0f, 0f).toDouble())
            put("responseSpeed", settings.responseSpeed.name)
            put("manualTrimsDb", trimsToJsonArray(settings.manualTrimsDb))
            put("themeMode", settings.themeMode.name)
            put("sessionMode", settings.sessionMode.name)
            put("customPresets", JSONArray(HistoryCodec.encodeCustomPresets(settings.customPresets)))
            put(
                "customHeadphoneProfiles",
                JSONArray(HistoryCodec.encodeCustomHeadphoneProfiles(settings.customHeadphoneProfiles)),
            )
            put("deviceProfiles", JSONObject(HistoryCodec.encodeDeviceProfiles(settings.deviceProfiles)))
            put("deviceCustomProfileIds", JSONObject(HistoryCodec.encodeStringMap(settings.deviceCustomProfileIds)))
            put("deviceDefaultModes", JSONObject(HistoryCodec.encodeDeviceModes(settings.deviceDefaultModes)))
            put("appRules", JSONObject(HistoryCodec.encodeAppRules(settings.appRules)))
            put("pinnedTracks", JSONObject(HistoryCodec.encodePinnedTracks(settings.pinnedTracks)))
            put(
                "toneTiltDbPerOct",
                sanitizeFloat(
                    settings.toneTiltDbPerOct,
                    MIN_TONE_TILT_DB_PER_OCT,
                    MAX_TONE_TILT_DB_PER_OCT,
                    DEFAULT_TONE_TILT_DB_PER_OCT,
                ).toDouble(),
            )
            put("deEsserMode", settings.deEsserMode.name)
            put("loudnessMatchedBypass", settings.loudnessMatchedBypass)
        }

        return JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
            put("exportedAtMs", exportedAtMs.coerceAtLeast(0L))
            put("settings", settingsObj)
        }.toString(2)
    }

    fun decodeBackup(rawJson: String): BackupImportResult {
        val trimmed = rawJson.trim().trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) {
            return BackupImportResult.Error("Backup JSON is empty")
        }

        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return BackupImportResult.Error("Invalid backup JSON format")
        }

        val schemaVersion = parseSchemaVersion(root)
            ?: return BackupImportResult.Error("Unsupported or invalid backup schemaVersion")
        if (schemaVersion !in 1..CURRENT_SCHEMA_VERSION) {
            return BackupImportResult.Error("Unsupported backup schemaVersion: $schemaVersion")
        }

        val settingsObj = root.optJSONObject("settings")
            ?: return BackupImportResult.Error("Missing 'settings' object in backup")

        return try {
            val exportedAtMs = safeNonNegativeLong(root.opt("exportedAtMs"))
            val mbcModeRaw = settingsObj.opt("mbcMode") as? String
            val legacyMbcEnabled = settingsObj.opt("mbcEnabled") as? Boolean
            val resolvedMbcMode = OrbitPreferencesStore.migrateMbcMode(
                mbcModeRaw = mbcModeRaw,
                legacyMbcEnabled = legacyMbcEnabled,
            )

            val payload = OrbitBackupPayload(
                schemaVersion = schemaVersion,
                exportedAtMs = exportedAtMs,
                selectedMode = parseEnumOrDefault(settingsObj.opt("selectedMode") as? String, PleasureMode.NOVA),
                intensityPercent = settingsObj.safeIntInRange("intensityPercent", 0, 100, DEFAULT_INTENSITY),
                bassMacroDb = settingsObj.safeFloatInRange(
                    "bassMacroDb",
                    MIN_MACRO_DB,
                    MAX_MACRO_DB,
                    DEFAULT_BASS_MACRO_DB,
                ),
                airMacroDb = settingsObj.safeFloatInRange(
                    "airMacroDb",
                    MIN_MACRO_DB,
                    MAX_MACRO_DB,
                    DEFAULT_AIR_MACRO_DB,
                ),
                dynamicLoudness = (settingsObj.opt("dynamicLoudness") as? Boolean) ?: true,
                mbcMode = resolvedMbcMode,
                subsonicFilterEnabled = (settingsObj.opt("subsonicFilterEnabled") as? Boolean) ?: true,
                trueHeadroomMode = (settingsObj.opt("trueHeadroomMode") as? Boolean) ?: false,
                channelBalance = settingsObj.safeFloatInRange("channelBalance", -1.0f, 1.0f, 0f),
                responseSpeed = parseEnumOrDefault(
                    settingsObj.opt("responseSpeed") as? String,
                    ResponseSpeed.BALANCED,
                ),
                manualTrimsDb = decodeClampedTrims(settingsObj.optJSONArray("manualTrimsDb")),
                themeMode = parseEnumOrDefault(
                    settingsObj.opt("themeMode") as? String,
                    OrbitThemeMode.DEEP_SPACE_HUD,
                ),
                sessionMode = parseEnumOrDefault(
                    settingsObj.opt("sessionMode") as? String,
                    SessionAttachmentMode.AUTO,
                ),
                customPresets = HistoryCodec.decodeCustomPresets(
                    settingsObj.optJSONArray("customPresets")?.toString(),
                ),
                customHeadphoneProfiles = HistoryCodec.decodeCustomHeadphoneProfiles(
                    settingsObj.optJSONArray("customHeadphoneProfiles")?.toString(),
                ),
                deviceProfiles = normalizeMapKeys(
                    HistoryCodec.decodeDeviceProfiles(settingsObj.optJSONObject("deviceProfiles")?.toString()),
                ),
                deviceCustomProfileIds = normalizeMapKeys(
                    HistoryCodec.decodeStringMap(settingsObj.optJSONObject("deviceCustomProfileIds")?.toString()),
                ),
                deviceDefaultModes = normalizeMapKeys(
                    HistoryCodec.decodeDeviceModes(settingsObj.optJSONObject("deviceDefaultModes")?.toString()),
                ),
                appRules = normalizeAppRules(
                    HistoryCodec.decodeAppRules(settingsObj.optJSONObject("appRules")?.toString()),
                ),
                pinnedTracks = normalizeMapKeys(
                    HistoryCodec.decodePinnedTracks(settingsObj.optJSONObject("pinnedTracks")?.toString()),
                ),
                toneTiltDbPerOct = settingsObj.safeFloatInRange(
                    "toneTiltDbPerOct",
                    MIN_TONE_TILT_DB_PER_OCT,
                    MAX_TONE_TILT_DB_PER_OCT,
                    DEFAULT_TONE_TILT_DB_PER_OCT,
                ),
                deEsserMode = parseEnumOrDefault(
                    settingsObj.opt("deEsserMode") as? String,
                    DeEsserMode.GENTLE,
                ),
                loudnessMatchedBypass = (settingsObj.opt("loudnessMatchedBypass") as? Boolean) ?: true,
            )
            BackupImportResult.Success(payload)
        } catch (ex: Exception) {
            BackupImportResult.Error(ex.message ?: "Failed to decode backup payload")
        }
    }

    fun mergeInto(current: OrbitSettings, payload: OrbitBackupPayload): OrbitSettings {
        val mergedPresets = LinkedHashMap<String, CustomPreset>()
        for (preset in current.customPresets) {
            if (preset.id.isNotBlank() && preset.name.isNotBlank()) {
                mergedPresets[preset.id] = preset
            }
        }
        for (preset in payload.customPresets) {
            if (preset.id.isNotBlank() && preset.name.isNotBlank()) {
                mergedPresets[preset.id] = preset
            }
        }

        val mergedCustomProfiles = LinkedHashMap<String, CustomHeadphoneProfile>()
        for (profile in current.customHeadphoneProfiles) {
            if (profile.id.isNotBlank() && profile.name.isNotBlank()) {
                mergedCustomProfiles[profile.id] = profile.copy(
                    gains31Db = HistoryCodec.normalizeGains31(profile.gains31Db),
                )
            }
        }
        for (profile in payload.customHeadphoneProfiles) {
            if (profile.id.isNotBlank() && profile.name.isNotBlank()) {
                mergedCustomProfiles[profile.id] = profile.copy(
                    gains31Db = HistoryCodec.normalizeGains31(profile.gains31Db),
                )
            }
        }

        val validProfileIds = mergedCustomProfiles.keys
        val mergedCustomProfileBindings = mergeNormalizedMaps(
            current.deviceCustomProfileIds,
            payload.deviceCustomProfileIds,
        ).filterValues { profileId -> profileId in validProfileIds }

        val mergedAppRules = mergeNormalizedMaps(
            normalizeAppRules(current.appRules),
            normalizeAppRules(payload.appRules),
        )

        return current.copy(
            // Preserve runtime & onboarding state from current settings
            powerEnabled = current.powerEnabled,
            bypassed = current.bypassed,
            q30TipDismissed = current.q30TipDismissed,
            // Update scalar tuning fields from imported payload
            selectedMode = payload.selectedMode,
            intensityPercent = payload.intensityPercent.coerceIn(0, 100),
            bassMacroDb = sanitizeFloat(payload.bassMacroDb, MIN_MACRO_DB, MAX_MACRO_DB, DEFAULT_BASS_MACRO_DB),
            airMacroDb = sanitizeFloat(payload.airMacroDb, MIN_MACRO_DB, MAX_MACRO_DB, DEFAULT_AIR_MACRO_DB),
            dynamicLoudness = payload.dynamicLoudness,
            mbcMode = payload.mbcMode,
            mbcEnabled = payload.mbcMode != MbcMode.OFF,
            subsonicFilterEnabled = payload.subsonicFilterEnabled,
            trueHeadroomMode = payload.trueHeadroomMode,
            channelBalance = sanitizeFloat(payload.channelBalance, -1.0f, 1.0f, 0f),
            responseSpeed = payload.responseSpeed,
            manualTrimsDb = List(BAND_COUNT) { idx ->
                sanitizeFloat(payload.manualTrimsDb.getOrElse(idx) { 0f }, MIN_TRIM_DB, MAX_TRIM_DB, 0f)
            },
            themeMode = payload.themeMode,
            sessionMode = payload.sessionMode,
            toneTiltDbPerOct = sanitizeFloat(
                payload.toneTiltDbPerOct,
                MIN_TONE_TILT_DB_PER_OCT,
                MAX_TONE_TILT_DB_PER_OCT,
                DEFAULT_TONE_TILT_DB_PER_OCT,
            ),
            deEsserMode = payload.deEsserMode,
            loudnessMatchedBypass = payload.loudnessMatchedBypass,
            // Merge collections cleanly
            customPresets = mergedPresets.values.toList(),
            customHeadphoneProfiles = mergedCustomProfiles.values.toList(),
            deviceProfiles = mergeNormalizedMaps(current.deviceProfiles, payload.deviceProfiles),
            deviceCustomProfileIds = mergedCustomProfileBindings,
            deviceDefaultModes = mergeNormalizedMaps(current.deviceDefaultModes, payload.deviceDefaultModes),
            appRules = mergedAppRules,
            pinnedTracks = mergeNormalizedMaps(current.pinnedTracks, payload.pinnedTracks),
        )
    }

    private fun parseSchemaVersion(root: JSONObject): Int? {
        val raw = root.opt("schemaVersion") as? Number ?: return null
        val asDouble = raw.toDouble()
        if (!asDouble.isFinite() || asDouble % 1.0 != 0.0) return null
        if (asDouble < Int.MIN_VALUE || asDouble > Int.MAX_VALUE) return null
        return asDouble.toInt()
    }

    private fun safeNonNegativeLong(raw: Any?): Long {
        val num = raw as? Number ?: return 0L
        val d = num.toDouble()
        if (!d.isFinite() || d < 0.0 || d > Long.MAX_VALUE.toDouble()) return 0L
        return num.toLong().coerceAtLeast(0L)
    }

    private fun trimsToJsonArray(trims: List<Float>): JSONArray {
        val array = JSONArray()
        for (i in 0 until BAND_COUNT) {
            val clamped = sanitizeFloat(trims.getOrElse(i) { 0f }, MIN_TRIM_DB, MAX_TRIM_DB, 0f)
            array.put(clamped.toDouble())
        }
        return array
    }

    private fun decodeClampedTrims(array: JSONArray?): List<Float> {
        if (array == null) return List(BAND_COUNT) { 0f }
        return List(BAND_COUNT) { i ->
            if (i >= array.length() || array.isNull(i)) {
                0f
            } else {
                val raw = array.optDouble(i, Double.NaN).toFloat()
                sanitizeFloat(raw, MIN_TRIM_DB, MAX_TRIM_DB, 0f)
            }
        }
    }

    private fun <V> mergeNormalizedMaps(
        current: Map<String, V>,
        incoming: Map<String, V>,
    ): Map<String, V> {
        val result = LinkedHashMap<String, V>()
        for ((key, value) in current) {
            val normalized = key.trim().lowercase(Locale.ROOT)
            if (normalized.isNotEmpty()) {
                result[normalized] = value
            }
        }
        for ((key, value) in incoming) {
            val normalized = key.trim().lowercase(Locale.ROOT)
            if (normalized.isNotEmpty()) {
                result[normalized] = value
            }
        }
        return result
    }

    private fun <V> normalizeMapKeys(map: Map<String, V>): Map<String, V> {
        val result = LinkedHashMap<String, V>()
        for ((key, value) in map) {
            val normalized = key.trim().lowercase(Locale.ROOT)
            if (normalized.isNotEmpty()) {
                result[normalized] = value
            }
        }
        return result
    }

    private fun normalizeAppRules(map: Map<String, AppRule>): Map<String, AppRule> {
        val result = LinkedHashMap<String, AppRule>()
        for ((key, rule) in map) {
            val normalized = key.trim().lowercase(Locale.ROOT).ifEmpty {
                rule.packageName.trim().lowercase(Locale.ROOT)
            }
            if (normalized.isEmpty()) continue
            result[normalized] = rule.copy(
                packageName = normalized,
                displayName = rule.displayName.trim().ifEmpty { normalized },
            )
        }
        return result
    }

    private fun JSONObject.safeIntInRange(key: String, min: Int, max: Int, default: Int): Int {
        val num = opt(key) as? Number ?: return default
        val d = num.toDouble()
        if (!d.isFinite()) return default
        return d.roundToInt().coerceIn(min, max)
    }

    private fun JSONObject.safeFloatInRange(key: String, min: Float, max: Float, default: Float): Float {
        val num = opt(key) as? Number ?: return default
        val f = num.toDouble().toFloat()
        return sanitizeFloat(f, min, max, default)
    }

    private fun sanitizeFloat(value: Float, min: Float, max: Float, default: Float): Float {
        if (!value.isFinite()) return default
        val clamped = value.coerceIn(min, max)
        return if (clamped == 0f) 0f else clamped
    }

    private inline fun <reified E : Enum<E>> parseEnumOrDefault(raw: String?, default: E): E {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return default
        return enumValues<E>().firstOrNull { it.name == trimmed } ?: default
    }
}
