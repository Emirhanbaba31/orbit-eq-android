package com.orbiteq.app.data

import android.content.Context
import android.graphics.Bitmap
import com.orbiteq.app.dsp.AppRuleResolver
import com.orbiteq.app.dsp.PleasureProfiles
import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.AppRuleAction
import com.orbiteq.app.model.ConnectedOutputDevice
import com.orbiteq.app.model.CustomHeadphoneProfile
import com.orbiteq.app.model.CustomPreset
import com.orbiteq.app.model.DeEsserMode
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.LiveAudioTelemetry
import com.orbiteq.app.model.MbcMode
import com.orbiteq.app.model.NowPlayingInfo
import com.orbiteq.app.model.OrbitSettings
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.ResponseSpeed
import com.orbiteq.app.model.SessionAttachmentMode
import com.orbiteq.app.model.TrackActivityEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object OrbitEngineRepository {

    private const val BAND_COUNT = 10
    private const val MIN_TRIM_DB = -8.0f
    private const val MAX_TRIM_DB = 8.0f
    private const val MIN_MACRO_DB = -6.0f
    private const val MAX_MACRO_DB = 8.0f
    private const val MIN_TONE_TILT_DB_PER_OCT = -1.5f
    private const val MAX_TONE_TILT_DB_PER_OCT = 1.5f
    private const val MAX_CUSTOM_HEADROOM_DB = 15.0f
    private const val SPECTRAL_TAG_STABLE_FRAMES = 5
    private const val DEFAULT_INITIAL_LOAD_TIMEOUT_MS = 1500L

    private sealed interface SettingsCommand {
        data class Transform(val fn: (OrbitSettings) -> OrbitSettings) : SettingsCommand
        data class Barrier(val done: CompletableDeferred<Unit>) : SettingsCommand
    }

    private sealed interface HistoryCommand {
        data object Sync : HistoryCommand
        data class Barrier(val done: CompletableDeferred<Unit>) : HistoryCommand
    }

    private class PersistenceSession(
        val gateway: OrbitPersistenceGateway,
        dispatcher: CoroutineDispatcher,
    ) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val settingsChannel = Channel<SettingsCommand>(Channel.UNLIMITED)
        val historyChannel = Channel<HistoryCommand>(Channel.UNLIMITED)
        val initialSettingsLoaded = CompletableDeferred<Unit>()
    }

    private val initLock = Any()
    private val stateLock = Any()
    private val pendingSettingsWrites = AtomicInteger(0)
    private val pendingHistoryWrites = AtomicInteger(0)

    @Volatile
    private var session: PersistenceSession? = null

    @Volatile
    var isUiVisible: Boolean = false
        private set

    private var initialHistoryLoaded = false
    private var stableTagCandidate: String? = null
    private var stableTagStreak: Int = 0
    private var lastSpectralTrackKey: String? = null

    private val _uiState = MutableStateFlow(defaultUiState())
    val uiState: StateFlow<OrbitUiState> = _uiState.asStateFlow()

    private val _nowPlayingArtwork = MutableStateFlow<Bitmap?>(null)
    val nowPlayingArtwork: StateFlow<Bitmap?> = _nowPlayingArtwork.asStateFlow()

    fun init(context: Context) {
        if (session != null) return
        val created = synchronized(initLock) {
            if (session != null) return
            createSession(OrbitPreferencesStore(context.applicationContext), Dispatchers.IO).also {
                session = it
            }
        }
        startSessionWorkers(created)
    }

    internal fun initForTesting(
        gateway: OrbitPersistenceGateway,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        val created = synchronized(initLock) {
            resetLocked(cancelExisting = true)
            createSession(gateway, dispatcher).also { session = it }
        }
        startSessionWorkers(created)
    }

    internal fun resetForTesting() {
        synchronized(initLock) {
            resetLocked(cancelExisting = true)
        }
    }

    internal suspend fun flushPersistenceForTesting() {
        val activeSession = session ?: return
        val settingsDone = CompletableDeferred<Unit>()
        val historyDone = CompletableDeferred<Unit>()
        activeSession.settingsChannel.trySend(SettingsCommand.Barrier(settingsDone))
        activeSession.historyChannel.trySend(HistoryCommand.Barrier(historyDone))
        settingsDone.await()
        historyDone.await()
    }

    fun isInitialSettingsLoaded(): Boolean =
        session?.initialSettingsLoaded?.let { it.isCompleted && !it.isCancelled } == true

    suspend fun awaitInitialSettingsLoaded(timeoutMs: Long = DEFAULT_INITIAL_LOAD_TIMEOUT_MS): Boolean {
        val activeSession = session ?: return false
        val deferred = activeSession.initialSettingsLoaded
        if (deferred.isCompleted) {
            return !deferred.isCancelled
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
                true
            } ?: false
        } catch (ce: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    private fun resetLocked(cancelExisting: Boolean) {
        val existing = session
        session = null
        if (cancelExisting && existing != null) {
            existing.initialSettingsLoaded.cancel()
            existing.settingsChannel.close()
            existing.historyChannel.close()
            existing.scope.cancel()
        }
        synchronized(stateLock) {
            pendingSettingsWrites.set(0)
            pendingHistoryWrites.set(0)
            initialHistoryLoaded = false
            stableTagCandidate = null
            stableTagStreak = 0
            lastSpectralTrackKey = null
            isUiVisible = false
            _nowPlayingArtwork.value = null
            _uiState.value = defaultUiState()
        }
    }

    private fun createSession(
        gateway: OrbitPersistenceGateway,
        dispatcher: CoroutineDispatcher,
    ): PersistenceSession = PersistenceSession(gateway, dispatcher)

    private fun startSessionWorkers(activeSession: PersistenceSession) {
        activeSession.scope.launch {
            for (command in activeSession.settingsChannel) {
                when (command) {
                    is SettingsCommand.Barrier -> command.done.complete(Unit)
                    is SettingsCommand.Transform -> processSettingsTransform(activeSession.gateway, command.fn)
                }
            }
        }

        activeSession.scope.launch {
            for (command in activeSession.historyChannel) {
                when (command) {
                    is HistoryCommand.Barrier -> command.done.complete(Unit)
                    is HistoryCommand.Sync -> processHistorySync(activeSession.gateway)
                }
            }
        }

        activeSession.scope.launch {
            try {
                activeSession.gateway.settingsFlow.collect { persistedSettings ->
                    synchronized(stateLock) {
                        if (pendingSettingsWrites.get() == 0) {
                            _uiState.value = recomputeDerivedState(
                                _uiState.value.copy(settings = persistedSettings),
                            )
                        }
                    }
                    activeSession.initialSettingsLoaded.complete(Unit)
                }
            } catch (ce: CancellationException) {
                activeSession.initialSettingsLoaded.cancel(ce)
                throw ce
            } catch (_: Throwable) {
                activeSession.initialSettingsLoaded.cancel()
            }
        }

        activeSession.scope.launch {
            activeSession.gateway.historyFlow.collect { persistedHistory ->
                synchronized(stateLock) {
                    if (pendingHistoryWrites.get() == 0) {
                        _uiState.value = _uiState.value.copy(recentActivity = persistedHistory)
                        initialHistoryLoaded = true
                    }
                }
            }
        }
    }

    private suspend fun processSettingsTransform(
        gateway: OrbitPersistenceGateway,
        fn: (OrbitSettings) -> OrbitSettings,
    ) {
        var updatedSnapshot: OrbitSettings? = null
        try {
            gateway.updateSettings { current ->
                fn(current).also { updatedSnapshot = it }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            updatedSnapshot = null
        } finally {
            synchronized(stateLock) {
                val remaining = pendingSettingsWrites.decrementAndGet().coerceAtLeast(0)
                if (remaining == 0) {
                    val snapshot = updatedSnapshot
                    if (snapshot != null) {
                        _uiState.value = recomputeDerivedState(
                            _uiState.value.copy(settings = snapshot),
                        )
                    }
                }
            }
        }
    }

    private suspend fun processHistorySync(gateway: OrbitPersistenceGateway) {
        try {
            val loaded = synchronized(stateLock) { initialHistoryLoaded }
            val snapshotToSave = if (loaded) {
                synchronized(stateLock) { _uiState.value.recentActivity }
            } else {
                val onDisk = gateway.historyFlow.first()
                synchronized(stateLock) {
                    val inMemory = _uiState.value.recentActivity
                    var merged = onDisk
                    for (item in inMemory.asReversed()) {
                        merged = HistoryCodec.appendHistory(merged, item)
                    }
                    _uiState.value = _uiState.value.copy(recentActivity = merged)
                    initialHistoryLoaded = true
                    merged
                }
            }
            gateway.saveHistory(snapshotToSave)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Keep worker alive for subsequent history updates.
        } finally {
            synchronized(stateLock) {
                pendingHistoryWrites.decrementAndGet().coerceAtLeast(0)
            }
        }
    }

    fun setUiVisible(visible: Boolean) {
        isUiVisible = visible
    }

    fun updateNowPlayingArtwork(bitmap: Bitmap?) {
        _nowPlayingArtwork.value = bitmap
    }

    fun updateNowPlaying(info: NowPlayingInfo) {
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

            var shouldPersistHistory = false
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
                    spectralTag = if (trackChanged) {
                        "Balanced"
                    } else {
                        nextState.telemetry.dominantSpectralTag.ifBlank { "Balanced" }
                    },
                    timestampMs = System.currentTimeMillis(),
                )
                val updatedHistory = HistoryCodec.appendHistory(nextState.recentActivity, entry)
                nextState = nextState.copy(recentActivity = updatedHistory)
                shouldPersistHistory = true
            }

            _uiState.value = nextState
            if (shouldPersistHistory) {
                enqueueHistorySyncLocked()
            }
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
        synchronized(stateLock) {
            val prev = _uiState.value
            var nextHistory = prev.recentActivity
            var shouldPersistHistory = false

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
            if (shouldPersistHistory) {
                enqueueHistorySyncLocked()
            }
        }
    }

    fun setPowerEnabled(enabled: Boolean) {
        mutateSettings { settings -> settings.copy(powerEnabled = enabled) }
    }

    fun setBypassed(bypassed: Boolean) {
        mutateSettings { settings -> settings.copy(bypassed = bypassed) }
    }

    fun selectModeManually(mode: PleasureMode) {
        synchronized(stateLock) {
            val prev = _uiState.value
            val pkg = normalizePkg(prev.nowPlaying.packageName)
            val hasActiveAppRule = pkg != null && hasNormalizedKey(prev.settings.appRules, pkg)

            if (!hasActiveAppRule) {
                val deviceKey = currentDeviceKey(prev.connectedDevice)
                val updatedDeviceDefaults = removeNormalizedKey(prev.settings.deviceDefaultModes, deviceKey) +
                    (deviceKey to mode)
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
                enqueueSettingsTransformLocked { current ->
                    current.copy(
                        selectedMode = mode,
                        deviceDefaultModes = removeNormalizedKey(current.deviceDefaultModes, deviceKey) +
                            (deviceKey to mode),
                    )
                }
            } else {
                _uiState.value = recomputeDerivedState(
                    prev.copy(manualOverrideMode = mode),
                )
            }
        }
    }

    fun cycleNextMode() {
        synchronized(stateLock) {
            selectModeManually(PleasureProfiles.nextMode(_uiState.value.activeMode))
        }
    }

    fun saveCurrentModeForActiveApp() {
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
            enqueueSettingsTransformLocked { current ->
                current.copy(appRules = removeNormalizedKey(current.appRules, pkg) + (pkg to newRule))
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

    fun setMbcMode(mode: MbcMode) {
        mutateSettings { settings ->
            settings.copy(
                mbcMode = mode,
                mbcEnabled = mode != MbcMode.OFF,
            )
        }
    }

    // Preserved until Task 6 migrates OrbitViewModel to setMbcMode(MbcMode).
    fun setMbcEnabled(enabled: Boolean) {
        setMbcMode(if (enabled) MbcMode.SUBTLE else MbcMode.OFF)
    }

    fun setSubsonicFilterEnabled(enabled: Boolean) {
        mutateSettings { settings -> settings.copy(subsonicFilterEnabled = enabled) }
    }

    fun setTrueHeadroomMode(enabled: Boolean) {
        mutateSettings { settings -> settings.copy(trueHeadroomMode = enabled) }
    }

    fun setChannelBalance(balance: Float) {
        val clamped = sanitizeAndClamp(balance, -1.0f, 1.0f)
        val normalized = if (clamped == 0f) 0f else clamped
        mutateSettings { settings -> settings.copy(channelBalance = normalized) }
    }

    fun setToneTiltDbPerOct(tiltDbPerOct: Float) {
        val clamped = sanitizeAndClamp(tiltDbPerOct, MIN_TONE_TILT_DB_PER_OCT, MAX_TONE_TILT_DB_PER_OCT)
        val normalized = if (clamped == 0f) 0f else clamped
        mutateSettings { settings -> settings.copy(toneTiltDbPerOct = normalized) }
    }

    fun setDeEsserMode(mode: DeEsserMode) {
        mutateSettings { settings -> settings.copy(deEsserMode = mode) }
    }

    fun setLoudnessMatchedBypass(enabled: Boolean) {
        mutateSettings { settings -> settings.copy(loudnessMatchedBypass = enabled) }
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
            enqueueSettingsTransformLocked { current ->
                current.copy(customPresets = current.customPresets + preset)
            }
        }
    }

    fun applyCustomPreset(presetId: String) {
        synchronized(stateLock) {
            val prev = _uiState.value
            val preset = prev.settings.customPresets.firstOrNull { it.id == presetId } ?: return
            val clampedIntensity = preset.intensity.coerceIn(0, 100)
            val clampedBass = sanitizeAndClamp(preset.bassMacroDb, MIN_MACRO_DB, MAX_MACRO_DB)
            val clampedAir = sanitizeAndClamp(preset.airMacroDb, MIN_MACRO_DB, MAX_MACRO_DB)
            val normalizedTrims = HistoryCodec.normalizeTrims(preset.trimsDb)

            val nextSettings = prev.settings.copy(
                selectedMode = preset.mode,
                intensityPercent = clampedIntensity,
                bassMacroDb = clampedBass,
                airMacroDb = clampedAir,
                manualTrimsDb = normalizedTrims,
            )
            _uiState.value = recomputeDerivedState(
                prev.copy(
                    settings = nextSettings,
                    manualOverrideMode = preset.mode,
                ),
            )
            enqueueSettingsTransformLocked { current ->
                current.copy(
                    selectedMode = preset.mode,
                    intensityPercent = clampedIntensity,
                    bassMacroDb = clampedBass,
                    airMacroDb = clampedAir,
                    manualTrimsDb = normalizedTrims,
                )
            }
        }
    }

    fun deleteCustomPreset(presetId: String) {
        mutateSettings { settings ->
            settings.copy(customPresets = settings.customPresets.filterNot { it.id == presetId })
        }
    }

    fun importCustomHeadphoneProfile(
        name: String,
        gains31Db: List<Float>,
        extraHeadroomDb: Float = 0f,
        sourceLabel: String = "AutoEQ",
        assignToCurrentDevice: Boolean = true,
        preferredId: String? = null,
    ): CustomHeadphoneProfile? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return null

        val resolvedId = preferredId?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()

        val profile = CustomHeadphoneProfile(
            id = resolvedId,
            name = trimmedName,
            gains31Db = HistoryCodec.normalizeGains31(gains31Db),
            extraHeadroomDb = sanitizeAndClamp(extraHeadroomDb, 0f, MAX_CUSTOM_HEADROOM_DB).let {
                if (it == 0f) 0f else it
            },
            sourceLabel = sourceLabel.trim().ifEmpty { "AutoEQ" },
        )

        synchronized(stateLock) {
            val prev = _uiState.value
            val deviceKey = currentDeviceKey(prev.connectedDevice)
            val nextBindings = if (assignToCurrentDevice) {
                removeNormalizedKey(prev.settings.deviceCustomProfileIds, deviceKey) + (deviceKey to profile.id)
            } else {
                prev.settings.deviceCustomProfileIds
            }
            val nextSettings = prev.settings.copy(
                customHeadphoneProfiles = upsertCustomProfile(prev.settings.customHeadphoneProfiles, profile),
                deviceCustomProfileIds = nextBindings,
            )
            _uiState.value = recomputeDerivedState(prev.copy(settings = nextSettings))
            enqueueSettingsTransformLocked { current ->
                val updatedBindings = if (assignToCurrentDevice) {
                    removeNormalizedKey(current.deviceCustomProfileIds, deviceKey) + (deviceKey to profile.id)
                } else {
                    current.deviceCustomProfileIds
                }
                current.copy(
                    customHeadphoneProfiles = upsertCustomProfile(current.customHeadphoneProfiles, profile),
                    deviceCustomProfileIds = updatedBindings,
                )
            }
            return profile
        }
    }

    fun selectCustomHeadphoneProfileForCurrentDevice(profileId: String) {
        synchronized(stateLock) {
            val prev = _uiState.value
            if (prev.settings.customHeadphoneProfiles.none { it.id == profileId }) return
            val deviceKey = currentDeviceKey(prev.connectedDevice)
            val nextBindings = removeNormalizedKey(prev.settings.deviceCustomProfileIds, deviceKey) +
                (deviceKey to profileId)
            _uiState.value = recomputeDerivedState(
                prev.copy(settings = prev.settings.copy(deviceCustomProfileIds = nextBindings)),
            )
            enqueueSettingsTransformLocked { current ->
                if (current.customHeadphoneProfiles.none { it.id == profileId }) {
                    current
                } else {
                    current.copy(
                        deviceCustomProfileIds = removeNormalizedKey(current.deviceCustomProfileIds, deviceKey) +
                            (deviceKey to profileId),
                    )
                }
            }
        }
    }

    fun deleteCustomHeadphoneProfile(profileId: String) {
        mutateSettings { settings ->
            settings.copy(
                customHeadphoneProfiles = settings.customHeadphoneProfiles.filterNot { it.id == profileId },
                deviceCustomProfileIds = settings.deviceCustomProfileIds.filterValues { it != profileId },
            )
        }
    }

    fun setHeadphoneProfileForCurrentDevice(profile: HeadphoneProfile) {
        synchronized(stateLock) {
            val prev = _uiState.value
            val key = currentDeviceKey(prev.connectedDevice)
            val nextProfiles = removeNormalizedKey(prev.settings.deviceProfiles, key) + (key to profile)
            val nextCustomBindings = removeNormalizedKey(prev.settings.deviceCustomProfileIds, key)
            _uiState.value = recomputeDerivedState(
                prev.copy(
                    settings = prev.settings.copy(
                        deviceProfiles = nextProfiles,
                        deviceCustomProfileIds = nextCustomBindings,
                    ),
                ),
            )
            enqueueSettingsTransformLocked { current ->
                current.copy(
                    deviceProfiles = removeNormalizedKey(current.deviceProfiles, key) + (key to profile),
                    deviceCustomProfileIds = removeNormalizedKey(current.deviceCustomProfileIds, key),
                )
            }
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
            enqueueSettingsTransformLocked { current ->
                current.copy(
                    appRules = removeNormalizedKey(current.appRules, normalizedPkg) + (normalizedPkg to normalizedRule),
                )
            }
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
            enqueueSettingsTransformLocked { current ->
                current.copy(appRules = removeNormalizedKey(current.appRules, normalizedPkg))
            }
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
            enqueueSettingsTransformLocked { current ->
                current.copy(pinnedTracks = togglePinnedMap(current.pinnedTracks, key, mode))
            }
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

    fun exportBackupJson(exportedAtMs: Long = System.currentTimeMillis()): String {
        val currentSettings = synchronized(stateLock) { _uiState.value.settings }
        return OrbitBackupCodec.encodeBackup(currentSettings, exportedAtMs)
    }

    fun importBackupJson(rawJson: String): Boolean {
        val decoded = OrbitBackupCodec.decodeBackup(rawJson)
        if (decoded !is BackupImportResult.Success) {
            return false
        }
        synchronized(stateLock) {
            val prev = _uiState.value
            val nextSettings = OrbitBackupCodec.mergeInto(prev.settings, decoded.payload)
            _uiState.value = recomputeDerivedState(
                prev.copy(
                    settings = nextSettings,
                    manualOverrideMode = null,
                ),
            )
            enqueueSettingsTransformLocked { current ->
                OrbitBackupCodec.mergeInto(current, decoded.payload)
            }
        }
        return true
    }

    private fun mutateSettings(transform: (OrbitSettings) -> OrbitSettings) {
        synchronized(stateLock) {
            val prev = _uiState.value
            val nextSettings = transform(prev.settings)
            _uiState.value = recomputeDerivedState(prev.copy(settings = nextSettings))
            enqueueSettingsTransformLocked(transform)
        }
    }

    private fun enqueueSettingsTransformLocked(transform: (OrbitSettings) -> OrbitSettings) {
        val activeSession = session ?: return
        pendingSettingsWrites.incrementAndGet()
        if (!activeSession.settingsChannel.trySend(SettingsCommand.Transform(transform)).isSuccess) {
            pendingSettingsWrites.decrementAndGet().coerceAtLeast(0)
        }
    }

    private fun enqueueHistorySyncLocked() {
        val activeSession = session ?: return
        pendingHistoryWrites.incrementAndGet()
        if (!activeSession.historyChannel.trySend(HistoryCommand.Sync).isSuccess) {
            pendingHistoryWrites.decrementAndGet().coerceAtLeast(0)
        }
    }

    private fun defaultUiState(): OrbitUiState =
        recomputeDerivedState(
            OrbitUiState(
                settings = OrbitSettings(appRules = AppRuleResolver.defaultAppRules()),
            ),
        )

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

        val activeCustomProfileId = findNormalizedValue(state.settings.deviceCustomProfileIds, deviceKey)
        val activeCustomProfile = if (activeCustomProfileId != null) {
            state.settings.customHeadphoneProfiles.firstOrNull { it.id == activeCustomProfileId }
        } else {
            null
        }

        return state.copy(
            activeMode = decision.mode,
            modeSource = decision.source,
            autoBypassedByRule = decision.autoBypassed,
            activeHeadphoneProfile = headphoneProfile,
            activeCustomHeadphoneProfile = activeCustomProfile,
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

    private fun upsertCustomProfile(
        existing: List<CustomHeadphoneProfile>,
        profile: CustomHeadphoneProfile,
    ): List<CustomHeadphoneProfile> {
        val firstIndex = existing.indexOfFirst { it.id == profile.id }
        if (firstIndex < 0) return existing + profile
        val result = ArrayList<CustomHeadphoneProfile>(existing.size)
        existing.forEachIndexed { index, item ->
            if (index == firstIndex) {
                result.add(profile)
            } else if (item.id != profile.id) {
                result.add(item)
            }
        }
        return result
    }
}
