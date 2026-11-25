package com.orbiteq.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orbiteq.app.dsp.AppRuleResolver
import com.orbiteq.app.model.DeEsserMode
import com.orbiteq.app.model.MbcMode
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
        .map { prefs -> decodeSettings(prefs) }
        .distinctUntilChanged()

    override val historyFlow: Flow<List<TrackActivityEntry>> = preferencesFlow
        .map { prefs ->
            HistoryCodec.decodeHistory(prefs[Keys.RECENT_HISTORY_JSON])
                .take(HistoryCodec.MAX_HISTORY_ITEMS)
        }
        .distinctUntilChanged()

    override suspend fun updateSettings(transform: (OrbitSettings) -> OrbitSettings) {
        appContext.orbitDataStore.edit { prefs ->
            val current = decodeSettings(prefs)
            val updated = transform(current)
            encodeSettings(prefs, updated)
        }
    }

    override suspend fun saveHistory(items: List<TrackActivityEntry>) {
        val capped = items.take(HistoryCodec.MAX_HISTORY_ITEMS)
        appContext.orbitDataStore.edit { prefs ->
            prefs[Keys.RECENT_HISTORY_JSON] = HistoryCodec.encodeHistory(capped)
        }
    }

    private object Keys {
        val POWER_ENABLED = booleanPreferencesKey("power_enabled")
        val BYPASSED = booleanPreferencesKey("bypassed")
        val SELECTED_MODE = stringPreferencesKey("selected_mode")
        val INTENSITY_PERCENT = intPreferencesKey("intensity_percent")
        val BASS_MACRO_DB = floatPreferencesKey("bass_macro_db")
        val AIR_MACRO_DB = floatPreferencesKey("air_macro_db")
        val DYNAMIC_LOUDNESS = booleanPreferencesKey("dynamic_loudness")
        val MBC_ENABLED = booleanPreferencesKey("mbc_enabled")
        val MBC_MODE = stringPreferencesKey("mbc_mode")
        val SUBSONIC_FILTER_ENABLED = booleanPreferencesKey("subsonic_filter_enabled")
        val TRUE_HEADROOM_MODE = booleanPreferencesKey("true_headroom_mode")
        val CHANNEL_BALANCE = floatPreferencesKey("channel_balance")
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
        val CUSTOM_HEADPHONE_PROFILES_JSON = stringPreferencesKey("custom_headphone_profiles_json")
        val DEVICE_CUSTOM_PROFILE_IDS_JSON = stringPreferencesKey("device_custom_profile_ids_json")
        val RECENT_HISTORY_JSON = stringPreferencesKey("recent_history_json")
        val TONE_TILT_DB_PER_OCT = floatPreferencesKey("tone_tilt_db_per_oct")
        val DE_ESSER_MODE = stringPreferencesKey("de_esser_mode")
        val LOUDNESS_MATCHED_BYPASS = booleanPreferencesKey("loudness_matched_bypass")
    }

    internal companion object {

        private const val MIN_TONE_TILT_DB_PER_OCT = -1.5f
        private const val MAX_TONE_TILT_DB_PER_OCT = 1.5f

        internal fun encodeSettings(prefs: MutablePreferences, updated: OrbitSettings) {
            val resolvedMbcMode = updated.mbcMode
            val balance = finiteOrDefault(updated.channelBalance, 0f).coerceIn(-1.0f, 1.0f)
            val toneTilt = finiteOrDefault(updated.toneTiltDbPerOct, 0f)
                .coerceIn(MIN_TONE_TILT_DB_PER_OCT, MAX_TONE_TILT_DB_PER_OCT)

            prefs[Keys.POWER_ENABLED] = updated.powerEnabled
            prefs[Keys.BYPASSED] = updated.bypassed
            prefs[Keys.SELECTED_MODE] = updated.selectedMode.name
            prefs[Keys.INTENSITY_PERCENT] = updated.intensityPercent.coerceIn(0, 100)
            prefs[Keys.BASS_MACRO_DB] = finiteOrDefault(updated.bassMacroDb, 2.0f)
            prefs[Keys.AIR_MACRO_DB] = finiteOrDefault(updated.airMacroDb, 1.5f)
            prefs[Keys.DYNAMIC_LOUDNESS] = updated.dynamicLoudness
            prefs[Keys.MBC_MODE] = resolvedMbcMode.name
            prefs[Keys.MBC_ENABLED] = resolvedMbcMode != MbcMode.OFF
            prefs[Keys.SUBSONIC_FILTER_ENABLED] = updated.subsonicFilterEnabled
            prefs[Keys.TRUE_HEADROOM_MODE] = updated.trueHeadroomMode
            prefs[Keys.CHANNEL_BALANCE] = if (balance == 0f) 0f else balance
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
            prefs[Keys.CUSTOM_HEADPHONE_PROFILES_JSON] = HistoryCodec.encodeCustomHeadphoneProfiles(
                updated.customHeadphoneProfiles,
            )
            prefs[Keys.DEVICE_CUSTOM_PROFILE_IDS_JSON] = HistoryCodec.encodeStringMap(
                updated.deviceCustomProfileIds,
            )
            prefs[Keys.TONE_TILT_DB_PER_OCT] = if (toneTilt == 0f) 0f else toneTilt
            prefs[Keys.DE_ESSER_MODE] = updated.deEsserMode.name
            prefs[Keys.LOUDNESS_MATCHED_BYPASS] = updated.loudnessMatchedBypass
        }

        internal fun migrateMbcMode(mbcModeRaw: String?, legacyMbcEnabled: Boolean?): MbcMode {
            val parsed = mbcModeRaw?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                MbcMode.entries.firstOrNull { it.name == raw }
            }
            if (parsed != null) return parsed
            return when (legacyMbcEnabled) {
                false -> MbcMode.OFF
                true, null -> MbcMode.SUBTLE
            }
        }

        internal fun decodeSettings(prefs: Preferences): OrbitSettings {
            val rulesRaw = prefs[Keys.APP_RULES_JSON]
            val appRules = if (rulesRaw == null) {
                AppRuleResolver.defaultAppRules()
            } else {
                HistoryCodec.decodeAppRules(rulesRaw)
            }
            val mbcMode = migrateMbcMode(
                mbcModeRaw = prefs[Keys.MBC_MODE],
                legacyMbcEnabled = prefs[Keys.MBC_ENABLED],
            )
            val balance = finiteOrDefault(prefs[Keys.CHANNEL_BALANCE], 0f).coerceIn(-1.0f, 1.0f)
            val toneTilt = finiteOrDefault(prefs[Keys.TONE_TILT_DB_PER_OCT], 0f)
                .coerceIn(MIN_TONE_TILT_DB_PER_OCT, MAX_TONE_TILT_DB_PER_OCT)

            return OrbitSettings(
                powerEnabled = prefs[Keys.POWER_ENABLED] ?: false,
                bypassed = prefs[Keys.BYPASSED] ?: false,
                selectedMode = parseEnumOrDefault(prefs[Keys.SELECTED_MODE], PleasureMode.NOVA),
                intensityPercent = (prefs[Keys.INTENSITY_PERCENT] ?: 78).coerceIn(0, 100),
                bassMacroDb = finiteOrDefault(prefs[Keys.BASS_MACRO_DB], 2.0f),
                airMacroDb = finiteOrDefault(prefs[Keys.AIR_MACRO_DB], 1.5f),
                dynamicLoudness = prefs[Keys.DYNAMIC_LOUDNESS] ?: true,
                mbcEnabled = mbcMode != MbcMode.OFF,
                responseSpeed = parseEnumOrDefault(prefs[Keys.RESPONSE_SPEED], ResponseSpeed.BALANCED),
                manualTrimsDb = HistoryCodec.decodeTrims(prefs[Keys.MANUAL_TRIMS_JSON]),
                themeMode = parseEnumOrDefault(prefs[Keys.THEME_MODE], OrbitThemeMode.DEEP_SPACE_HUD),
                sessionMode = parseEnumOrDefault(prefs[Keys.SESSION_MODE], SessionAttachmentMode.AUTO),
                q30TipDismissed = prefs[Keys.Q30_TIP_DISMISSED] ?: false,
                appRules = appRules,
                pinnedTracks = HistoryCodec.decodePinnedTracks(prefs[Keys.PINNED_TRACKS_JSON]),
                deviceProfiles = HistoryCodec.decodeDeviceProfiles(prefs[Keys.DEVICE_PROFILES_JSON]),
                deviceDefaultModes = HistoryCodec.decodeDeviceModes(prefs[Keys.DEVICE_DEFAULT_MODES_JSON]),
                customPresets = HistoryCodec.decodeCustomPresets(prefs[Keys.CUSTOM_PRESETS_JSON]),
                mbcMode = mbcMode,
                subsonicFilterEnabled = prefs[Keys.SUBSONIC_FILTER_ENABLED] ?: true,
                trueHeadroomMode = prefs[Keys.TRUE_HEADROOM_MODE] ?: false,
                channelBalance = if (balance == 0f) 0f else balance,
                customHeadphoneProfiles = HistoryCodec.decodeCustomHeadphoneProfiles(
                    prefs[Keys.CUSTOM_HEADPHONE_PROFILES_JSON],
                ),
                deviceCustomProfileIds = HistoryCodec.decodeStringMap(
                    prefs[Keys.DEVICE_CUSTOM_PROFILE_IDS_JSON],
                ),
                toneTiltDbPerOct = if (toneTilt == 0f) 0f else toneTilt,
                deEsserMode = parseEnumOrDefault(prefs[Keys.DE_ESSER_MODE], DeEsserMode.GENTLE),
                loudnessMatchedBypass = prefs[Keys.LOUDNESS_MATCHED_BYPASS] ?: true,
            )
        }

        private inline fun <reified E : Enum<E>> parseEnumOrDefault(raw: String?, default: E): E {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return default
            return enumValues<E>().firstOrNull { it.name == trimmed } ?: default
        }

        private fun finiteOrDefault(value: Float?, default: Float): Float =
            if (value != null && value.isFinite()) value else default
    }
}
