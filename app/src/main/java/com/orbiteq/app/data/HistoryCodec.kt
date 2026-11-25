package com.orbiteq.app.data

import com.orbiteq.app.dsp.AppRuleResolver
import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.AppRuleAction
import com.orbiteq.app.model.CustomHeadphoneProfile
import com.orbiteq.app.model.CustomPreset
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.TrackActivityEntry
import org.json.JSONArray
import org.json.JSONObject

object HistoryCodec {

    const val MAX_HISTORY_ITEMS = 50
    const val PRE_EQ_BAND_COUNT = 31
    private const val BAND_COUNT = 10
    private const val MIN_GAIN_DB = -15f
    private const val MAX_GAIN_DB = 15f

    fun appendHistory(
        existing: List<TrackActivityEntry>,
        newEntry: TrackActivityEntry,
        maxItems: Int = MAX_HISTORY_ITEMS,
    ): List<TrackActivityEntry> {
        if (maxItems <= 0) return emptyList()
        val newKey = AppRuleResolver.trackKey(newEntry.packageName, newEntry.trackTitle)
        val result = ArrayList<TrackActivityEntry>(minOf(existing.size + 1, maxItems))
        val seenKeys = HashSet<String>(minOf(existing.size + 1, maxItems))
        result.add(newEntry)
        seenKeys.add(newKey)

        for (entry in existing) {
            if (result.size >= maxItems) break
            val key = AppRuleResolver.trackKey(entry.packageName, entry.trackTitle)
            if (seenKeys.add(key)) {
                result.add(entry)
            }
        }
        return result
    }

    fun encodeHistory(items: List<TrackActivityEntry>): String {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("packageName", item.packageName)
            obj.put("appName", item.appName)
            obj.put("trackTitle", item.trackTitle)
            obj.put("artist", item.artist)
            obj.put("modeUsed", item.modeUsed.name)
            obj.put("spectralTag", item.spectralTag)
            obj.put("timestampMs", item.timestampMs)
            array.put(obj)
        }
        return array.toString()
    }

    fun decodeHistory(json: String?): List<TrackActivityEntry> {
        val trimmed = json?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return try {
            val array = JSONArray(trimmed)
            val result = ArrayList<TrackActivityEntry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.safeString("id")
                val packageName = obj.safeString("packageName")
                val trackTitle = obj.safeString("trackTitle")
                if (packageName.isBlank() || trackTitle.isBlank()) continue
                val modeUsed = parseEnum<PleasureMode>(obj.safeString("modeUsed")) ?: PleasureMode.NOVA
                result.add(
                    TrackActivityEntry(
                        id = id.ifEmpty { "$packageName|$trackTitle|$i" },
                        packageName = packageName,
                        appName = obj.safeString("appName", packageName),
                        trackTitle = trackTitle,
                        artist = obj.safeString("artist"),
                        modeUsed = modeUsed,
                        spectralTag = obj.safeString("spectralTag", "Balanced").ifEmpty { "Balanced" },
                        timestampMs = obj.optLong("timestampMs", 0L),
                    ),
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodePinnedTracks(map: Map<String, PleasureMode>): String = encodeEnumMap(map)

    fun decodePinnedTracks(json: String?): Map<String, PleasureMode> = decodeEnumMap(json)

    fun encodeAppRules(map: Map<String, AppRule>): String {
        val root = JSONObject()
        for ((key, rule) in map) {
            if (key.isBlank() || rule.packageName.isBlank()) continue
            val obj = JSONObject()
            obj.put("packageName", rule.packageName)
            obj.put("displayName", rule.displayName)
            obj.put("action", rule.action.name)
            obj.put("mode", rule.mode.name)
            root.put(key, obj)
        }
        return root.toString()
    }

    fun decodeAppRules(json: String?): Map<String, AppRule> {
        val trimmed = json?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return try {
            val root = JSONObject(trimmed)
            val result = LinkedHashMap<String, AppRule>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = root.optJSONObject(key) ?: continue
                val packageName = obj.safeString("packageName", key)
                if (key.isBlank() || packageName.isBlank()) continue
                val action = parseEnum<AppRuleAction>(obj.safeString("action")) ?: continue
                val mode = parseEnum<PleasureMode>(obj.safeString("mode")) ?: continue
                val displayName = obj.safeString("displayName", packageName).ifEmpty { packageName }
                result[key] = AppRule(
                    packageName = packageName,
                    displayName = displayName,
                    action = action,
                    mode = mode,
                )
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun encodeDeviceProfiles(map: Map<String, HeadphoneProfile>): String = encodeEnumMap(map)

    fun decodeDeviceProfiles(json: String?): Map<String, HeadphoneProfile> = decodeEnumMap(json)

    fun encodeDeviceModes(map: Map<String, PleasureMode>): String = encodeEnumMap(map)

    fun decodeDeviceModes(json: String?): Map<String, PleasureMode> = decodeEnumMap(json)

    fun encodeStringMap(map: Map<String, String>): String {
        val root = JSONObject()
        for ((key, value) in map) {
            if (key.isNotBlank() && value.isNotBlank()) {
                root.put(key, value)
            }
        }
        return root.toString()
    }

    fun decodeStringMap(json: String?): Map<String, String> {
        val trimmed = json?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return try {
            val root = JSONObject(trimmed)
            val result = LinkedHashMap<String, String>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.isBlank()) continue
                val value = (root.opt(key) as? String)?.takeIf { it.isNotBlank() } ?: continue
                result[key] = value
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun encodeCustomPresets(list: List<CustomPreset>): String {
        val array = JSONArray()
        for (preset in list) {
            if (preset.id.isBlank() || preset.name.isBlank()) continue
            val obj = JSONObject()
            obj.put("id", preset.id)
            obj.put("name", preset.name)
            obj.put("mode", preset.mode.name)
            obj.put("intensity", preset.intensity.coerceIn(0, 100))
            obj.put("bassMacroDb", finiteOrZero(preset.bassMacroDb).toDouble())
            obj.put("airMacroDb", finiteOrZero(preset.airMacroDb).toDouble())
            obj.put("trimsDb", floatListToJsonArray(normalizeTrims(preset.trimsDb)))
            array.put(obj)
        }
        return array.toString()
    }

    fun decodeCustomPresets(json: String?): List<CustomPreset> {
        val trimmed = json?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return try {
            val array = JSONArray(trimmed)
            val result = ArrayList<CustomPreset>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.safeString("id")
                val name = obj.safeString("name")
                val mode = parseEnum<PleasureMode>(obj.safeString("mode")) ?: continue
                if (id.isBlank() || name.isBlank()) continue
                result.add(
                    CustomPreset(
                        id = id,
                        name = name,
                        mode = mode,
                        intensity = obj.optInt("intensity", 78).coerceIn(0, 100),
                        bassMacroDb = obj.safeFloat("bassMacroDb"),
                        airMacroDb = obj.safeFloat("airMacroDb"),
                        trimsDb = jsonArrayToFloatList(obj.optJSONArray("trimsDb"), BAND_COUNT),
                    ),
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeCustomHeadphoneProfiles(list: List<CustomHeadphoneProfile>): String {
        val array = JSONArray()
        for (profile in list) {
            if (profile.id.isBlank() || profile.name.isBlank()) continue
            val obj = JSONObject()
            obj.put("id", profile.id)
            obj.put("name", profile.name)
            obj.put("gains31Db", floatListToJsonArray(normalizeGains31(profile.gains31Db)))
            obj.put("extraHeadroomDb", sanitizeHeadroom(profile.extraHeadroomDb).toDouble())
            obj.put("sourceLabel", profile.sourceLabel.trim().ifEmpty { "AutoEQ" })
            array.put(obj)
        }
        return array.toString()
    }

    fun decodeCustomHeadphoneProfiles(json: String?): List<CustomHeadphoneProfile> {
        val trimmed = json?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return try {
            val array = JSONArray(trimmed)
            val result = ArrayList<CustomHeadphoneProfile>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.safeString("id")
                val name = obj.safeString("name")
                if (id.isBlank() || name.isBlank()) continue
                result.add(
                    CustomHeadphoneProfile(
                        id = id,
                        name = name,
                        gains31Db = jsonArrayToFloatList(
                            array = obj.optJSONArray("gains31Db"),
                            count = PRE_EQ_BAND_COUNT,
                            minValue = MIN_GAIN_DB,
                            maxValue = MAX_GAIN_DB,
                        ),
                        extraHeadroomDb = sanitizeHeadroom(obj.safeFloat("extraHeadroomDb")),
                        sourceLabel = obj.safeString("sourceLabel", "AutoEQ").trim().ifEmpty { "AutoEQ" },
                    ),
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeTrims(trims: List<Float>): String = floatListToJsonArray(normalizeTrims(trims)).toString()

    fun decodeTrims(json: String?): List<Float> {
        val trimmed = json?.trim()?.takeIf { it.isNotEmpty() } ?: return zeroList(BAND_COUNT)
        return try {
            jsonArrayToFloatList(JSONArray(trimmed), BAND_COUNT)
        } catch (_: Exception) {
            zeroList(BAND_COUNT)
        }
    }

    fun normalizeTrims(trims: List<Float>): List<Float> =
        List(BAND_COUNT) { index -> finiteOrZero(trims.getOrElse(index) { 0f }) }

    fun normalizeGains31(gains: List<Float>): List<Float> =
        List(PRE_EQ_BAND_COUNT) { index ->
            sanitizeClampedFinite(gains.getOrElse(index) { 0f }, MIN_GAIN_DB, MAX_GAIN_DB)
        }

    private fun floatListToJsonArray(values: List<Float>): JSONArray {
        val array = JSONArray()
        for (value in values) {
            array.put(finiteOrZero(value).toDouble())
        }
        return array
    }

    private fun jsonArrayToFloatList(
        array: JSONArray?,
        count: Int,
        minValue: Float? = null,
        maxValue: Float? = null,
    ): List<Float> {
        if (array == null) return zeroList(count)
        return List(count) { index ->
            if (index >= array.length() || array.isNull(index)) {
                0f
            } else {
                val raw = array.optDouble(index, Double.NaN).toFloat()
                if (!raw.isFinite()) {
                    0f
                } else if (minValue != null && maxValue != null) {
                    val clamped = raw.coerceIn(minValue, maxValue)
                    if (clamped == 0f) 0f else clamped
                } else {
                    if (raw == 0f) 0f else raw
                }
            }
        }
    }

    private fun zeroList(count: Int): List<Float> = List(count) { 0f }

    private fun finiteOrZero(value: Float): Float =
        if (value.isFinite()) (if (value == 0f) 0f else value) else 0f

    private fun sanitizeClampedFinite(value: Float, min: Float, max: Float): Float {
        if (!value.isFinite()) return 0f
        val clamped = value.coerceIn(min, max)
        return if (clamped == 0f) 0f else clamped
    }

    private fun sanitizeHeadroom(value: Float): Float =
        sanitizeClampedFinite(value, 0f, MAX_GAIN_DB)

    private fun <E : Enum<E>> encodeEnumMap(map: Map<String, E>): String {
        val root = JSONObject()
        for ((key, value) in map) {
            if (key.isNotBlank()) {
                root.put(key, value.name)
            }
        }
        return root.toString()
    }

    private inline fun <reified E : Enum<E>> decodeEnumMap(json: String?): Map<String, E> {
        val trimmed = json?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return try {
            val root = JSONObject(trimmed)
            val result = LinkedHashMap<String, E>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.isBlank()) continue
                val enumValue = parseEnum<E>(root.safeString(key)) ?: continue
                result[key] = enumValue
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private inline fun <reified E : Enum<E>> parseEnum(raw: String): E? {
        if (raw.isEmpty()) return null
        return enumValues<E>().firstOrNull { it.name == raw }
    }

    private fun JSONObject.safeString(key: String, default: String = ""): String =
        (opt(key) as? String) ?: default

    private fun JSONObject.safeFloat(key: String, default: Float = 0f): Float {
        if (isNull(key)) return default
        val raw = optDouble(key, default.toDouble()).toFloat()
        return if (raw.isFinite()) raw else default
    }
}
