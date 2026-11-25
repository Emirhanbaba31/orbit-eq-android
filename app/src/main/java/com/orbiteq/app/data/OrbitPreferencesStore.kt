package com.orbiteq.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orbiteq.app.dsp.AppRuleResolver
import com.orbiteq.app.model.OrbitSettings
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.ResponseSpeed
import com.orbiteq.app.model.SessionAttachmentMode
import com.orbiteq.app.model.TrackActivityEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.orbitDataStore: DataStore<Preferences> by preferencesDataStore(name = "orbit_eq_preferences")

internal interface OrbitPersistenceGateway {
    val settingsFlow: Flow<OrbitSettings>
    val historyFlow: Flow<List<TrackActivityEntry>>
    suspend fun updateSettings(transform: (OrbitSettings) -> OrbitSettings)
    suspend fun saveHistory(items: List<TrackActivityEntry>)
}

class OrbitPreferencesStore(context: Context) : OrbitPersistenceGateway {

    private val appContext = context.applicationContext

    private val preferencesFlow: Flow<Preferences> = appContext.orbitDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    override val settingsFlow: Flow<OrbitSettings> = preferencesFlow
        .map { prefs -> prefs.toOrbitSettings() }
        .distinctUntilChanged()

    override val historyFlow: Flow<List<TrackActivityEntry>> = preferencesFlow
        .map { prefs ->
            HistoryCodec.decodeHistory(prefs[Keys.RECENT_HISTORY_JSON])
                .take(HistoryCodec.MAX_HISTORY_ITEMS)
        }
        .distinctUntilChanged()

    override suspend fun updateSettings(transform: (OrbitSettings) -> OrbitSettings) {
        appContext.orbitDataStore.edit { prefs ->
            val current = prefs.toOrbitSettings()
            val updated = transform(current)
            prefs[Keys.POWER_ENABLED] = updated.powerEnabled
            prefs[Keys.BYPASSED] = updated.bypassed
            prefs[Keys.SELECTED_MODE] = updated.selectedMode.name
            prefs[Keys.INTENSITY_PERCENT] = updated.intensityPercent.coerceIn(0, 100)
            prefs[Keys.BASS_MACRO_DB] = finiteOrDefault(updated.bassMacroDb, 2.0f)
            prefs[Keys.AIR_MACRO_DB] = finiteOrDefault(updated.airMacroDb, 1.5f)
            prefs[Keys.DYNAMIC_LOUDNESS] = updated.dynamicLoudness
            prefs[Keys.MBC_ENABLED] = updated.mbcEnabled
            prefs[Keys.RESPONSE_SPEED] = updated.responseSpeed.name
            prefs[Keys.MANUAL_TRIMS_JSON] = HistoryCodec.encodeTrims(updated.manualTrimsDb)
            prefs[Keys.THEME_MODE] = updated.themeMode.name
            prefs[Keys.SESSION_MODE] = updated.sessionMode.name
            prefs[Keys.Q30_TIP_DISMISSED] = updated.q30TipDismissed
            prefs[Keys.APP_RULES_JSON] = HistoryCodec.encodeAppRules(updated.appRules)
            prefs[Keys.PINNED_TRACKS_JSON] = HistoryCodec.encodePinnedTracks(updated.pinnedTracks)
            prefs[Keys.DEVICE_PROFILES_JSON] = HistoryCodec.encodeDeviceProfiles(updated.deviceProfiles)
            prefs[Keys.DEVICE_DEFAULT_MODES_JSON] = HistoryCodec.encodeDeviceModes(updated.deviceDefaultModes)
            prefs[Keys.CUSTOM_PRESETS_JSON] = HistoryCodec.encodeCustomPresets(updated.customPresets)
        }
    }

    override suspend fun saveHistory(items: List<TrackActivityEntry>) {
        val capped = items.take(HistoryCodec.MAX_HISTORY_ITEMS)
        appContext.orbitDataStore.edit { prefs ->
            prefs[Keys.RECENT_HISTORY_JSON] = HistoryCodec.encodeHistory(capped)
        }
    }

    private fun Preferences.toOrbitSettings(): OrbitSettings {
        val rulesRaw = this[Keys.APP_RULES_JSON]
        val appRules = if (rulesRaw == null) {
            AppRuleResolver.defaultAppRules()
        } else {
            HistoryCodec.decodeAppRules(rulesRaw)
        }

        return OrbitSettings(
            powerEnabled = this[Keys.POWER_ENABLED] ?: false,
            bypassed = this[Keys.BYPASSED] ?: false,
            selectedMode = parseEnumOrDefault(this[Keys.SELECTED_MODE], PleasureMode.NOVA),
            intensityPercent = (this[Keys.INTENSITY_PERCENT] ?: 78).coerceIn(0, 100),
            bassMacroDb = finiteOrDefault(this[Keys.BASS_MACRO_DB], 2.0f),
            airMacroDb = finiteOrDefault(this[Keys.AIR_MACRO_DB], 1.5f),
            dynamicLoudness = this[Keys.DYNAMIC_LOUDNESS] ?: true,
            mbcEnabled = this[Keys.MBC_ENABLED] ?: true,
            responseSpeed = parseEnumOrDefault(this[Keys.RESPONSE_SPEED], ResponseSpeed.BALANCED),
            manualTrimsDb = HistoryCodec.decodeTrims(this[Keys.MANUAL_TRIMS_JSON]),
            themeMode = parseEnumOrDefault(this[Keys.THEME_MODE], OrbitThemeMode.DEEP_SPACE_HUD),
            sessionMode = parseEnumOrDefault(this[Keys.SESSION_MODE], SessionAttachmentMode.AUTO),
            q30TipDismissed = this[Keys.Q30_TIP_DISMISSED] ?: false,
            appRules = appRules,
            pinnedTracks = HistoryCodec.decodePinnedTracks(this[Keys.PINNED_TRACKS_JSON]),
            deviceProfiles = HistoryCodec.decodeDeviceProfiles(this[Keys.DEVICE_PROFILES_JSON]),
            deviceDefaultModes = HistoryCodec.decodeDeviceModes(this[Keys.DEVICE_DEFAULT_MODES_JSON]),
            customPresets = HistoryCodec.decodeCustomPresets(this[Keys.CUSTOM_PRESETS_JSON]),
        )
    }

    private inline fun <reified E : Enum<E>> parseEnumOrDefault(raw: String?, default: E): E {
        if (raw.isNullOrBlank()) return default
        return enumValues<E>().firstOrNull { it.name == raw } ?: default
    }

    private fun finiteOrDefault(value: Float?, default: Float): Float =
        if (value != null && value.isFinite()) value else default

    private object Keys {
        val POWER_ENABLED = booleanPreferencesKey("power_enabled")
        val BYPASSED = booleanPreferencesKey("bypassed")
        val SELECTED_MODE = stringPreferencesKey("selected_mode")
        val INTENSITY_PERCENT = intPreferencesKey("intensity_percent")
        val BASS_MACRO_DB = floatPreferencesKey("bass_macro_db")
        val AIR_MACRO_DB = floatPreferencesKey("air_macro_db")
        val DYNAMIC_LOUDNESS = booleanPreferencesKey("dynamic_loudness")
        val MBC_ENABLED = booleanPreferencesKey("mbc_enabled")
        val RESPONSE_SPEED = stringPreferencesKey("response_speed")
        val MANUAL_TRIMS_JSON = stringPreferencesKey("manual_trims_json")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SESSION_MODE = stringPreferencesKey("session_mode")
        val Q30_TIP_DISMISSED = booleanPreferencesKey("q30_tip_dismissed")
        val APP_RULES_JSON = stringPreferencesKey("app_rules_json")
        val PINNED_TRACKS_JSON = stringPreferencesKey("pinned_tracks_json")
        val DEVICE_PROFILES_JSON = stringPreferencesKey("device_profiles_json")
        val DEVICE_DEFAULT_MODES_JSON = stringPreferencesKey("device_default_modes_json")
        val CUSTOM_PRESETS_JSON = stringPreferencesKey("custom_presets_json")
        val RECENT_HISTORY_JSON = stringPreferencesKey("recent_history_json")
    }
}
