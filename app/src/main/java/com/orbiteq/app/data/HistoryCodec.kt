package com.orbiteq.app.data

import com.orbiteq.app.dsp.AppRuleResolver
import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.AppRuleAction
import com.orbiteq.app.model.CustomPreset
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.TrackActivityEntry
import org.json.JSONArray
import org.json.JSONObject

object HistoryCodec {

    const val MAX_HISTORY_ITEMS = 50
    private const val BAND_COUNT = 10

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
            obj.put("trimsDb", trimsToJsonArray(preset.trimsDb))
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
                        trimsDb = jsonArrayToTrims(obj.optJSONArray("trimsDb")),
                    ),
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeTrims(trims: List<Float>): String = trimsToJsonArray(trims).toString()

    fun decodeTrims(json: String?): List<Float> {
        val trimmed = json?.trim()?.takeIf { it.isNotEmpty() } ?: return zeroTrims()
        return try {
            jsonArrayToTrims(JSONArray(trimmed))
        } catch (_: Exception) {
            zeroTrims()
        }
    }

    fun normalizeTrims(trims: List<Float>): List<Float> =
        List(BAND_COUNT) { index -> finiteOrZero(trims.getOrElse(index) { 0f }) }

    private fun trimsToJsonArray(trims: List<Float>): JSONArray {
        val array = JSONArray()
        for (value in normalizeTrims(trims)) {
            array.put(value.toDouble())
        }
        return array
    }

    private fun jsonArrayToTrims(array: JSONArray?): List<Float> {
        if (array == null) return zeroTrims()
        return List(BAND_COUNT) { index ->
            if (index >= array.length() || array.isNull(index)) {
                0f
            } else {
                val raw = array.optDouble(index, Double.NaN).toFloat()
                if (raw.isFinite()) raw else 0f
            }
        }
    }

    private fun zeroTrims(): List<Float> = List(BAND_COUNT) { 0f }

    private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f

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
        if (isNull(key)) default else optString(key, default)

    private fun JSONObject.safeFloat(key: String, default: Float = 0f): Float {
        if (isNull(key)) return default
        val raw = optDouble(key, default.toDouble()).toFloat()
        return if (raw.isFinite()) raw else default
    }
}
