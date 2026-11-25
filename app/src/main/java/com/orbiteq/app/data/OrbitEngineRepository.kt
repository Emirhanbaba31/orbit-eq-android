package com.orbiteq.app.data

import android.content.Context
import com.orbiteq.app.dsp.AppRuleResolver
import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.AppRuleAction
import com.orbiteq.app.model.ConnectedOutputDevice
import com.orbiteq.app.model.CustomPreset
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.LiveAudioTelemetry
import com.orbiteq.app.model.NowPlayingInfo
import com.orbiteq.app.model.OrbitSettings
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.ResponseSpeed
import com.orbiteq.app.model.SessionAttachmentMode
import com.orbiteq.app.model.TrackActivityEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object OrbitEngineRepository {

    private const val BAND_COUNT = 10
    private const val MIN_TRIM_DB = -8.0f
    private const val MAX_TRIM_DB = 8.0f
    private const val MIN_MACRO_DB = -6.0f
    private const val MAX_MACRO_DB = 8.0f
    private const val SPECTRAL_TAG_STABLE_FRAMES = 5

    private val initLock = Any()
    private val stateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsWriteMutex = Mutex()
    private val historyWriteMutex = Mutex()
    private val pendingSettingsWrites = AtomicInteger(0)
    private val pendingHistoryWrites = AtomicInteger(0)

    @Volatile
    private var store: OrbitPreferencesStore? = null

    @Volatile
    private var initialHistoryLoaded = false

    private var stableTagCandidate: String? = null
    private var stableTagStreak: Int = 0
    private var lastSpectralTrackKey: String? = null

    private val _uiState = MutableStateFlow(
        recomputeDerivedState(
            OrbitUiState(
                settings = OrbitSettings(appRules = AppRuleResolver.defaultAppRules()),
            ),
        ),
    )
    val uiState: StateFlow<OrbitUiState> = _uiState.asStateFlow()

    fun init(context: Context) {
        if (store != null) return
        val createdStore = synchronized(initLock) {
            if (store != null) return
            OrbitPreferencesStore(context.applicationContext).also { store = it }
        }

        scope.launch {
            createdStore.settingsFlow.collect { persistedSettings ->
                if (pendingSettingsWrites.get() == 0) {
                    synchronized(stateLock) {
                        if (pendingSettingsWrites.get() == 0) {
                            _uiState.value = recomputeDerivedState(
                                _uiState.value.copy(settings = persistedSettings),
                            )
                        }
                    }
                }
            }
        }

        scope.launch {
            createdStore.historyFlow.collect { persistedHistory ->
                initialHistoryLoaded = true
                if (pendingHistoryWrites.get() == 0) {
                    synchronized(stateLock) {
                        if (pendingHistoryWrites.get() == 0) {
                            _uiState.value = _uiState.value.copy(recentActivity = persistedHistory)
                        }
                    }
                }
            }
        }
    }

    fun updateNowPlaying(info: NowPlayingInfo) {
        var shouldPersistHistory = false

        synchronized(stateLock) {
            val prev = _uiState.value
            if (info == prev.nowPlaying) return

            val prevPkg = normalizePkg(prev.nowPlaying.packageName)
            val newPkg = normalizePkg(info.packageName)
            val packageChanged = prevPkg != newPkg

            val prevTrackKey = trackKeyOrNull(prev.nowPlaying.packageName, prev.nowPlaying.trackTitle)
            val newTrackKey = trackKeyOrNull(info.packageName, info.trackTitle)
            val trackChanged = prevTrackKey != newTrackKey

            if (trackChanged) {
                stableTagCandidate = null
                stableTagStreak = 0
                lastSpectralTrackKey = newTrackKey
            }

            val enteringPinnedTrack = trackChanged &&
                newTrackKey != null &&
                hasNormalizedKey(prev.settings.pinnedTracks, newTrackKey)

            val nextManualOverride = if (packageChanged || enteringPinnedTrack) {
                null
            } else {
                prev.manualOverrideMode
            }

            var nextState = recomputeDerivedState(
                prev.copy(
                    nowPlaying = info,
                    manualOverrideMode = nextManualOverride,
                ),
            )

            if (info.isPlaying &&
                newPkg != null &&
                info.trackTitle.isNotBlank() &&
                (trackChanged || !prev.nowPlaying.isPlaying)
            ) {
                val appDisplayName = info.appName.ifBlank {
                    findNormalizedValue(nextState.settings.appRules, newPkg)?.displayName
                        ?: info.packageName.orEmpty()
                }
                val entry = TrackActivityEntry(
                    id = UUID.randomUUID().toString(),
                    packageName = newPkg,
                    appName = appDisplayName,
                    trackTitle = info.trackTitle.trim(),
                    artist = info.artist.trim(),
                    modeUsed = nextState.activeMode,
                    spectralTag = nextState.telemetry.dominantSpectralTag.ifBlank { "Balanced" },
                    timestampMs = System.currentTimeMillis(),
                )
                val updatedHistory = HistoryCodec.appendHistory(nextState.recentActivity, entry)
                nextState = nextState.copy(recentActivity = updatedHistory)
                shouldPersistHistory = true
            }

            _uiState.value = nextState
        }

        if (shouldPersistHistory) {
            persistHistoryUpdate()
        }
    }

    fun updateConnectedDevice(device: ConnectedOutputDevice) {
        synchronized(stateLock) {
            val prev = _uiState.value
            if (device == prev.connectedDevice) return
            _uiState.value = recomputeDerivedState(prev.copy(connectedDevice = device))
        }
    }

    fun updateTelemetry(telemetry: LiveAudioTelemetry) {
        var shouldPersistHistory = false

        synchronized(stateLock) {
            val prev = _uiState.value
            var nextHistory = prev.recentActivity

            val currentKey = trackKeyOrNull(prev.nowPlaying.packageName, prev.nowPlaying.trackTitle)
            val candidateTag = telemetry.dominantSpectralTag.trim()
            val canEvaluateTag = prev.nowPlaying.isPlaying &&
                currentKey != null &&
                telemetry.engineStatus == EngineStatus.AUTO_FFT_LOCKED &&
                candidateTag.isNotEmpty()

            if (canEvaluateTag) {
                if (lastSpectralTrackKey != currentKey || stableTagCandidate != candidateTag) {
                    lastSpectralTrackKey = currentKey
                    stableTagCandidate = candidateTag
                    stableTagStreak = 1
                } else {
                    stableTagStreak += 1
                }

                if (stableTagStreak >= SPECTRAL_TAG_STABLE_FRAMES && nextHistory.isNotEmpty()) {
                    val top = nextHistory[0]
                    val topKey = AppRuleResolver.trackKey(top.packageName, top.trackTitle)
                    if (topKey == currentKey && top.spectralTag != candidateTag) {
                        val updatedTop = top.copy(spectralTag = candidateTag)
                        nextHistory = listOf(updatedTop) + nextHistory.drop(1)
                        shouldPersistHistory = true
                    }
                }
            } else if (telemetry.engineStatus != EngineStatus.AUTO_FFT_LOCKED) {
                stableTagCandidate = null
                stableTagStreak = 0
            }

            _uiState.value = prev.copy(
                telemetry = telemetry,
                recentActivity = nextHistory,
            )
        }

        if (shouldPersistHistory) {
            persistHistoryUpdate()
        }
    }

    fun setPowerEnabled(enabled: Boolean) {
        mutateSettings { settings -> settings.copy(powerEnabled = enabled) }
    }

    fun setBypassed(bypassed: Boolean) {
        mutateSettings { settings -> settings.copy(bypassed = bypassed) }
    }

    fun selectModeManually(mode: PleasureMode) {
        var settingsToPersist: ((OrbitSettings) -> OrbitSettings)? = null

        synchronized(stateLock) {
            val prev = _uiState.value
            val pkg = normalizePkg(prev.nowPlaying.packageName)
            val hasActiveAppRule = pkg != null && hasNormalizedKey(prev.settings.appRules, pkg)

            if (!hasActiveAppRule) {
                val deviceKey = currentDeviceKey(prev.connectedDevice)
                val updatedDeviceDefaults = prev.settings.deviceDefaultModes + (deviceKey to mode)
                val updatedSettings = prev.settings.copy(
                    selectedMode = mode,
                    deviceDefaultModes = updatedDeviceDefaults,
                )
                _uiState.value = recomputeDerivedState(
                    prev.copy(
                        settings = updatedSettings,
                        manualOverrideMode = mode,
                    ),
                )
                settingsToPersist = { current ->
                    current.copy(
                        selectedMode = mode,
                        deviceDefaultModes = current.deviceDefaultModes + (deviceKey to mode),
                    )
                }
            } else {
                _uiState.value = recomputeDerivedState(
                    prev.copy(manualOverrideMode = mode),
                )
            }
        }

        settingsToPersist?.let { persistSettingsTransform(it) }
    }

    fun saveCurrentModeForActiveApp() {
        var pkgToSave: String? = null
        var ruleToSave: AppRule? = null

        synchronized(stateLock) {
            val prev = _uiState.value
            val pkg = normalizePkg(prev.nowPlaying.packageName) ?: return
            val existingRule = findNormalizedValue(prev.settings.appRules, pkg)
            val displayName = prev.nowPlaying.appName.trim().ifEmpty {
                existingRule?.displayName ?: prev.nowPlaying.packageName?.trim().orEmpty().ifEmpty { pkg }
            }
            val newRule = AppRule(
                packageName = pkg,
                displayName = displayName,
                action = AppRuleAction.USE_MODE,
                mode = prev.activeMode,
            )
            val nextRules = removeNormalizedKey(prev.settings.appRules, pkg) + (pkg to newRule)
            _uiState.value = recomputeDerivedState(
                prev.copy(
                    settings = prev.settings.copy(appRules = nextRules),
                    manualOverrideMode = null,
                ),
            )
            pkgToSave = pkg
            ruleToSave = newRule
        }

        val savedPkg = pkgToSave
        val savedRule = ruleToSave
        if (savedPkg != null && savedRule != null) {
            persistSettingsTransform { current ->
                current.copy(appRules = removeNormalizedKey(current.appRules, savedPkg) + (savedPkg to savedRule))
            }
        }
    }

    fun setIntensityPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        mutateSettings { settings -> settings.copy(intensityPercent = clamped) }
    }

    fun setBassMacroDb(db: Float) {
        val clamped = sanitizeAndClamp(db, MIN_MACRO_DB, MAX_MACRO_DB)
        mutateSettings { settings -> settings.copy(bassMacroDb = clamped) }
    }

    fun setAirMacroDb(db: Float) {
        val clamped = sanitizeAndClamp(db, MIN_MACRO_DB, MAX_MACRO_DB)
        mutateSettings { settings -> settings.copy(airMacroDb = clamped) }
    }

    fun setDynamicLoudness(enabled: Boolean) {
        mutateSettings { settings -> settings.copy(dynamicLoudness = enabled) }
    }

    fun setMbcEnabled(enabled: Boolean) {
        mutateSettings { settings -> settings.copy(mbcEnabled = enabled) }
    }

    fun setResponseSpeed(speed: ResponseSpeed) {
        mutateSettings { settings -> settings.copy(responseSpeed = speed) }
    }

    fun setBandTrim(bandIndex: Int, db: Float) {
        if (bandIndex !in 0 until BAND_COUNT) return
        val clamped = sanitizeAndClamp(db, MIN_TRIM_DB, MAX_TRIM_DB)
        mutateSettings { settings ->
            val updated = HistoryCodec.normalizeTrims(settings.manualTrimsDb).toMutableList()
            updated[bandIndex] = clamped
            settings.copy(manualTrimsDb = updated)
        }
    }

    fun resetManualTrims() {
        mutateSettings { settings ->
            settings.copy(manualTrimsDb = List(BAND_COUNT) { 0f })
        }
    }

    fun saveCustomPreset(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return

        var createdPreset: CustomPreset? = null
        synchronized(stateLock) {
            val prev = _uiState.value
            val preset = CustomPreset(
                id = UUID.randomUUID().toString(),
                name = trimmedName,
                mode = prev.activeMode,
                intensity = prev.settings.intensityPercent.coerceIn(0, 100),
                bassMacroDb = prev.settings.bassMacroDb,
                airMacroDb = prev.settings.airMacroDb,
                trimsDb = HistoryCodec.normalizeTrims(prev.settings.manualTrimsDb),
            )
            val nextSettings = prev.settings.copy(
                customPresets = prev.settings.customPresets + preset,
            )
            _uiState.value = prev.copy(settings = nextSettings)
            createdPreset = preset
        }

        val preset = createdPreset ?: return
        persistSettingsTransform { current ->
            current.copy(customPresets = current.customPresets + preset)
        }
    }

    fun applyCustomPreset(presetId: String) {
        var matchedPreset: CustomPreset? = null
        synchronized(stateLock) {
            val prev = _uiState.value
            val preset = prev.settings.customPresets.firstOrNull { it.id == presetId } ?: return
            val nextSettings = prev.settings.copy(
                selectedMode = preset.mode,
                intensityPercent = preset.intensity.coerceIn(0, 100),
                bassMacroDb = sanitizeAndClamp(preset.bassMacroDb, MIN_MACRO_DB, MAX_MACRO_DB),
                airMacroDb = sanitizeAndClamp(preset.airMacroDb, MIN_MACRO_DB, MAX_MACRO_DB),
                manualTrimsDb = HistoryCodec.normalizeTrims(preset.trimsDb),
            )
            _uiState.value = recomputeDerivedState(
                prev.copy(
                    settings = nextSettings,
                    manualOverrideMode = preset.mode,
                ),
            )
            matchedPreset = preset
        }

        val preset = matchedPreset ?: return
        persistSettingsTransform { current ->
            current.copy(
                selectedMode = preset.mode,
                intensityPercent = preset.intensity.coerceIn(0, 100),
                bassMacroDb = sanitizeAndClamp(preset.bassMacroDb, MIN_MACRO_DB, MAX_MACRO_DB),
                airMacroDb = sanitizeAndClamp(preset.airMacroDb, MIN_MACRO_DB, MAX_MACRO_DB),
                manualTrimsDb = HistoryCodec.normalizeTrims(preset.trimsDb),
            )
        }
    }

    fun deleteCustomPreset(presetId: String) {
        mutateSettings { settings ->
            settings.copy(customPresets = settings.customPresets.filterNot { it.id == presetId })
        }
    }

    fun setHeadphoneProfileForCurrentDevice(profile: HeadphoneProfile) {
        var deviceKey: String? = null
        synchronized(stateLock) {
            val prev = _uiState.value
            val key = currentDeviceKey(prev.connectedDevice)
            val nextProfiles = removeNormalizedKey(prev.settings.deviceProfiles, key) + (key to profile)
            _uiState.value = recomputeDerivedState(
                prev.copy(
                    settings = prev.settings.copy(deviceProfiles = nextProfiles),
                ),
            )
            deviceKey = key
        }

        val key = deviceKey ?: return
        persistSettingsTransform { current ->
            current.copy(
                deviceProfiles = removeNormalizedKey(current.deviceProfiles, key) + (key to profile),
            )
        }
    }

    fun setAppRule(rule: AppRule) {
        val normalizedPkg = normalizePkg(rule.packageName) ?: return
        val normalizedRule = rule.copy(
            packageName = normalizedPkg,
            displayName = rule.displayName.trim().ifEmpty { normalizedPkg },
        )

        synchronized(stateLock) {
            val prev = _uiState.value
            val currentPkg = normalizePkg(prev.nowPlaying.packageName)
            val nextManualOverride = if (currentPkg == normalizedPkg) null else prev.manualOverrideMode
            val nextRules = removeNormalizedKey(prev.settings.appRules, normalizedPkg) + (normalizedPkg to normalizedRule)
            _uiState.value = recomputeDerivedState(
                prev.copy(
                    settings = prev.settings.copy(appRules = nextRules),
                    manualOverrideMode = nextManualOverride,
                ),
            )
        }

        persistSettingsTransform { current ->
            current.copy(
                appRules = removeNormalizedKey(current.appRules, normalizedPkg) + (normalizedPkg to normalizedRule),
            )
        }
    }

    fun removeAppRule(packageName: String) {
        val normalizedPkg = normalizePkg(packageName) ?: return

        synchronized(stateLock) {
            val prev = _uiState.value
            val nextRules = removeNormalizedKey(prev.settings.appRules, normalizedPkg)
            _uiState.value = recomputeDerivedState(
                prev.copy(
                    settings = prev.settings.copy(appRules = nextRules),
                ),
            )
        }

        persistSettingsTransform { current ->
            current.copy(appRules = removeNormalizedKey(current.appRules, normalizedPkg))
        }
    }

    fun togglePinTrack(packageName: String, trackTitle: String, mode: PleasureMode) {
        if (packageName.isBlank() || trackTitle.isBlank()) return
        val key = AppRuleResolver.trackKey(packageName, trackTitle)

        synchronized(stateLock) {
            val prev = _uiState.value
            val nextPinned = togglePinnedMap(prev.settings.pinnedTracks, key, mode)
            val currentTrackKey = trackKeyOrNull(prev.nowPlaying.packageName, prev.nowPlaying.trackTitle)
            val nextManualOverride = if (currentTrackKey == key) null else prev.manualOverrideMode
            _uiState.value = recomputeDerivedState(
                prev.copy(
                    settings = prev.settings.copy(pinnedTracks = nextPinned),
                    manualOverrideMode = nextManualOverride,
                ),
            )
        }

        persistSettingsTransform { current ->
            current.copy(pinnedTracks = togglePinnedMap(current.pinnedTracks, key, mode))
        }
    }

    fun dismissQ30Tip() {
        mutateSettings { settings -> settings.copy(q30TipDismissed = true) }
    }

    fun setThemeMode(themeMode: OrbitThemeMode) {
        mutateSettings { settings -> settings.copy(themeMode = themeMode) }
    }

    fun setSessionMode(mode: SessionAttachmentMode) {
        mutateSettings { settings -> settings.copy(sessionMode = mode) }
    }

    private fun mutateSettings(transform: (OrbitSettings) -> OrbitSettings) {
        synchronized(stateLock) {
            val prev = _uiState.value
            val nextSettings = transform(prev.settings)
            _uiState.value = recomputeDerivedState(prev.copy(settings = nextSettings))
        }
        persistSettingsTransform(transform)
    }

    private fun persistSettingsTransform(transform: (OrbitSettings) -> OrbitSettings) {
        val activeStore = store ?: return
        pendingSettingsWrites.incrementAndGet()
        scope.launch {
            try {
                settingsWriteMutex.withLock {
                    activeStore.updateSettings(transform)
                }
            } finally {
                pendingSettingsWrites.decrementAndGet()
            }
        }
    }

    private fun persistHistoryUpdate() {
        val activeStore = store ?: return
        pendingHistoryWrites.incrementAndGet()
        scope.launch {
            try {
                historyWriteMutex.withLock {
                    val snapshotToSave = if (initialHistoryLoaded) {
                        synchronized(stateLock) { _uiState.value.recentActivity }
                    } else {
                        val onDisk = activeStore.historyFlow.first()
                        initialHistoryLoaded = true
                        synchronized(stateLock) {
                            val inMemory = _uiState.value.recentActivity
                            var merged = onDisk
                            for (item in inMemory.asReversed()) {
                                merged = HistoryCodec.appendHistory(merged, item)
                            }
                            _uiState.value = _uiState.value.copy(recentActivity = merged)
                            merged
                        }
                    }
                    activeStore.saveHistory(snapshotToSave)
                }
            } finally {
                pendingHistoryWrites.decrementAndGet()
            }
        }
    }

    private fun recomputeDerivedState(state: OrbitUiState): OrbitUiState {
        val deviceNameForProfile = if (state.connectedDevice.name.isBlank() && state.connectedDevice.isSoundcoreQ30) {
            "Soundcore Life Q30"
        } else {
            state.connectedDevice.name
        }
        val deviceKey = AppRuleResolver.deviceKey(deviceNameForProfile, state.connectedDevice.isSpeaker)
        val deviceDefaultMode = findNormalizedValue(state.settings.deviceDefaultModes, deviceKey)

        val decision = AppRuleResolver.resolvePleasureMode(
            packageName = state.nowPlaying.packageName,
            trackTitle = state.nowPlaying.trackTitle,
            manualOverride = state.manualOverrideMode,
            pinnedTracks = state.settings.pinnedTracks,
            appRules = state.settings.appRules,
            deviceDefaultMode = deviceDefaultMode,
            fallbackMode = state.settings.selectedMode,
        )

        val headphoneProfile = AppRuleResolver.resolveHeadphoneProfile(
            deviceName = deviceNameForProfile,
            isSpeaker = state.connectedDevice.isSpeaker,
            savedDeviceProfiles = state.settings.deviceProfiles,
        )

        return state.copy(
            activeMode = decision.mode,
            modeSource = decision.source,
            autoBypassedByRule = decision.autoBypassed,
            activeHeadphoneProfile = headphoneProfile,
        )
    }

    private fun currentDeviceKey(device: ConnectedOutputDevice): String {
        val effectiveName = if (device.name.isBlank() && device.isSoundcoreQ30) {
            "Soundcore Life Q30"
        } else {
            device.name
        }
        return AppRuleResolver.deviceKey(effectiveName, device.isSpeaker)
    }

    private fun togglePinnedMap(
        map: Map<String, PleasureMode>,
        normalizedKey: String,
        mode: PleasureMode,
    ): Map<String, PleasureMode> {
        return if (hasNormalizedKey(map, normalizedKey)) {
            removeNormalizedKey(map, normalizedKey)
        } else {
            map + (normalizedKey to mode)
        }
    }

    private fun normalizePkg(packageName: String?): String? =
        packageName?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private fun trackKeyOrNull(packageName: String?, trackTitle: String?): String? {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val title = trackTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return AppRuleResolver.trackKey(pkg, title)
    }

    private fun <V> hasNormalizedKey(map: Map<String, V>, normalizedKey: String): Boolean =
        findNormalizedValue(map, normalizedKey) != null

    private fun <V> findNormalizedValue(map: Map<String, V>, normalizedKey: String): V? {
        map[normalizedKey]?.let { return it }
        return map.entries.firstOrNull { (k, _) ->
            k.trim().lowercase(Locale.ROOT) == normalizedKey
        }?.value
    }

    private fun <V> removeNormalizedKey(map: Map<String, V>, normalizedKey: String): Map<String, V> =
        map.filterKeys { k -> k.trim().lowercase(Locale.ROOT) != normalizedKey }

    private fun sanitizeAndClamp(value: Float, min: Float, max: Float): Float =
        if (value.isFinite()) value.coerceIn(min, max) else 0f
}
