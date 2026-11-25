package com.orbiteq.app.data

import com.orbiteq.app.dsp.AppRuleResolver
import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.AppRuleAction
import com.orbiteq.app.model.ConnectedOutputDevice
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.LiveAudioTelemetry
import com.orbiteq.app.model.MbcMode
import com.orbiteq.app.model.ModeResolutionSource
import com.orbiteq.app.model.NowPlayingInfo
import com.orbiteq.app.model.OrbitSettings
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.TrackActivityEntry
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OrbitEngineRepositoryTest {

    @Before
    fun setUp() {
        OrbitEngineRepository.resetForTesting()
    }

    @After
    fun tearDown() {
        OrbitEngineRepository.resetForTesting()
    }

    @Test
    fun manualOverrideClearsOnPackageChangeAndWhenEnteringPinnedTrack() = runBlocking {
        val gateway = FakeOrbitPersistenceGateway()
        OrbitEngineRepository.initForTesting(gateway)
        OrbitEngineRepository.flushPersistenceForTesting()

        val spotify = "com.spotify.music"
        val youtube = "com.google.android.youtube"
        val pinnedSong = "Pinned Orbit Track"

        OrbitEngineRepository.togglePinTrack(spotify, pinnedSong, PleasureMode.HORIZON)
        OrbitEngineRepository.flushPersistenceForTesting()

        OrbitEngineRepository.updateNowPlaying(
            NowPlayingInfo(
                packageName = spotify,
                appName = "Spotify",
                trackTitle = "Regular Song 1",
                artist = "Artist",
                isPlaying = true,
            ),
        )
        assertEquals(PleasureMode.NOVA, OrbitEngineRepository.uiState.value.activeMode)
        assertEquals(ModeResolutionSource.APP_RULE, OrbitEngineRepository.uiState.value.modeSource)

        OrbitEngineRepository.selectModeManually(PleasureMode.GRAVITY)
        assertEquals(PleasureMode.GRAVITY, OrbitEngineRepository.uiState.value.manualOverrideMode)
        assertEquals(PleasureMode.GRAVITY, OrbitEngineRepository.uiState.value.activeMode)
        assertEquals(ModeResolutionSource.MANUAL_OVERRIDE, OrbitEngineRepository.uiState.value.modeSource)

        // Switching to another unpinned track in the SAME app preserves the manual session override
        OrbitEngineRepository.updateNowPlaying(
            NowPlayingInfo(
                packageName = spotify,
                appName = "Spotify",
                trackTitle = "Regular Song 2",
                artist = "Artist",
                isPlaying = true,
            ),
        )
        assertEquals(PleasureMode.GRAVITY, OrbitEngineRepository.uiState.value.manualOverrideMode)
        assertEquals(ModeResolutionSource.MANUAL_OVERRIDE, OrbitEngineRepository.uiState.value.modeSource)

        // Entering a pinned track clears manualOverrideMode and activates PINNED_TRACK
        OrbitEngineRepository.updateNowPlaying(
            NowPlayingInfo(
                packageName = spotify,
                appName = "Spotify",
                trackTitle = pinnedSong,
                artist = "Artist",
                isPlaying = true,
            ),
        )
        assertNull(OrbitEngineRepository.uiState.value.manualOverrideMode)
        assertEquals(PleasureMode.HORIZON, OrbitEngineRepository.uiState.value.activeMode)
        assertEquals(ModeResolutionSource.PINNED_TRACK, OrbitEngineRepository.uiState.value.modeSource)

        // Manual override on a pinned track stays active across playback metadata refresh for the SAME track
        OrbitEngineRepository.selectModeManually(PleasureMode.ECLIPSE)
        assertEquals(PleasureMode.ECLIPSE, OrbitEngineRepository.uiState.value.manualOverrideMode)
        OrbitEngineRepository.updateNowPlaying(
            NowPlayingInfo(
                packageName = spotify,
                appName = "Spotify",
                trackTitle = pinnedSong,
                artist = "Artist",
                isPlaying = false,
            ),
        )
        assertEquals(PleasureMode.ECLIPSE, OrbitEngineRepository.uiState.value.manualOverrideMode)

        // Switching packageName clears manualOverrideMode cleanly
        OrbitEngineRepository.updateNowPlaying(
            NowPlayingInfo(
                packageName = youtube,
                appName = "YouTube",
                trackTitle = "Tech Talk",
                artist = "Channel",
                isPlaying = true,
            ),
        )
        assertNull(OrbitEngineRepository.uiState.value.manualOverrideMode)
        assertEquals(PleasureMode.COMMS, OrbitEngineRepository.uiState.value.activeMode)
        assertEquals(ModeResolutionSource.APP_RULE, OrbitEngineRepository.uiState.value.modeSource)
    }

    @Test
    fun saveCurrentModeForActiveAppPersistsPerAppRuleAndClearsManualOverride() = runBlocking {
        val gateway = FakeOrbitPersistenceGateway()
        OrbitEngineRepository.initForTesting(gateway)

        val customPkg = "com.tidal.wave"
        OrbitEngineRepository.updateNowPlaying(
            NowPlayingInfo(
                packageName = customPkg,
                appName = "Tidal Wave",
                trackTitle = "Nebula",
                artist = "Cosmo",
                isPlaying = true,
            ),
        )

        OrbitEngineRepository.selectModeManually(PleasureMode.HORIZON)
        assertEquals(PleasureMode.HORIZON, OrbitEngineRepository.uiState.value.manualOverrideMode)

        OrbitEngineRepository.saveCurrentModeForActiveApp()
        OrbitEngineRepository.flushPersistenceForTesting()

        val state = OrbitEngineRepository.uiState.value
        assertNull(state.manualOverrideMode)
        assertEquals(PleasureMode.HORIZON, state.activeMode)
        assertEquals(ModeResolutionSource.APP_RULE, state.modeSource)

        val persistedRule = gateway.currentSettings().appRules[customPkg]
        assertEquals(
            AppRule(
                packageName = customPkg,
                displayName = "Tidal Wave",
                action = AppRuleAction.USE_MODE,
                mode = PleasureMode.HORIZON,
            ),
            persistedRule,
        )
    }

    @Test
    fun togglePinTrackPersistsOutsideRolling50TrackHistoryAndUpdatesHeadphoneIndependently() = runBlocking {
        val gateway = FakeOrbitPersistenceGateway()
        OrbitEngineRepository.initForTesting(gateway)

        val pkg = "com.spotify.music"
        val firstSong = "Pinned Track #1"
        val firstKey = AppRuleResolver.trackKey(pkg, firstSong)

        OrbitEngineRepository.updateConnectedDevice(
            ConnectedOutputDevice(
                name = "Soundcore Life Q30",
                isBluetooth = true,
                isSpeaker = false,
                isSoundcoreQ30 = true,
            ),
        )
        OrbitEngineRepository.togglePinTrack(pkg, firstSong, PleasureMode.ECLIPSE)

        for (i in 1..60) {
            val title = if (i == 1) firstSong else "Track $i"
            OrbitEngineRepository.updateNowPlaying(
                NowPlayingInfo(
                    packageName = pkg,
                    appName = "Spotify",
                    trackTitle = title,
                    artist = "Artist",
                    isPlaying = true,
                ),
            )
        }
        OrbitEngineRepository.flushPersistenceForTesting()

        val state = OrbitEngineRepository.uiState.value
        assertEquals(HistoryCodec.MAX_HISTORY_ITEMS, state.recentActivity.size)
        assertFalse(state.recentActivity.any { it.trackTitle == firstSong })
        assertEquals(PleasureMode.ECLIPSE, state.settings.pinnedTracks[firstKey])
        assertEquals(PleasureMode.ECLIPSE, gateway.currentSettings().pinnedTracks[firstKey])
        assertEquals(HeadphoneProfile.Q30_CLEAN_BASE, state.activeHeadphoneProfile)
    }

    @Test
    fun updateTelemetryLocksSpectralTagAfterFiveConsecutiveAutoFftFrames() = runBlocking {
        val gateway = FakeOrbitPersistenceGateway()
        OrbitEngineRepository.initForTesting(gateway)

        OrbitEngineRepository.updateNowPlaying(
            NowPlayingInfo(
                packageName = "com.spotify.music",
                appName = "Spotify",
                trackTitle = "Sub Drop",
                artist = "Bassline",
                isPlaying = true,
            ),
        )
        OrbitEngineRepository.flushPersistenceForTesting()
        assertEquals("Balanced", OrbitEngineRepository.uiState.value.recentActivity.first().spectralTag)

        // 4 frames should not lock yet
        repeat(4) {
            OrbitEngineRepository.updateTelemetry(
                LiveAudioTelemetry(
                    engineStatus = EngineStatus.AUTO_FFT_LOCKED,
                    dominantSpectralTag = "Sub-Heavy",
                ),
            )
        }
        assertEquals("Balanced", OrbitEngineRepository.uiState.value.recentActivity.first().spectralTag)

        // Non-AUTO_FFT_LOCKED resets streak
        OrbitEngineRepository.updateTelemetry(
            LiveAudioTelemetry(
                engineStatus = EngineStatus.PROFILE_FALLBACK,
                dominantSpectralTag = "Sub-Heavy",
            ),
        )
        repeat(4) {
            OrbitEngineRepository.updateTelemetry(
                LiveAudioTelemetry(
                    engineStatus = EngineStatus.AUTO_FFT_LOCKED,
                    dominantSpectralTag = "Sub-Heavy",
                ),
            )
        }
        assertEquals("Balanced", OrbitEngineRepository.uiState.value.recentActivity.first().spectralTag)

        // 5th consecutive AUTO_FFT_LOCKED frame locks & persists the spectral tag
        OrbitEngineRepository.updateTelemetry(
            LiveAudioTelemetry(
                engineStatus = EngineStatus.AUTO_FFT_LOCKED,
                dominantSpectralTag = "Sub-Heavy",
            ),
        )
        OrbitEngineRepository.flushPersistenceForTesting()

        assertEquals("Sub-Heavy", OrbitEngineRepository.uiState.value.recentActivity.first().spectralTag)
        assertEquals("Sub-Heavy", gateway.currentHistory().first().spectralTag)

        // Switching to a fresh track must initialize its spectralTag to "Balanced", NOT inherit "Sub-Heavy".
        OrbitEngineRepository.updateNowPlaying(
            NowPlayingInfo(
                packageName = "com.spotify.music",
                appName = "Spotify",
                trackTitle = "Fresh Acoustic Track",
                artist = "New Artist",
                isPlaying = true,
            ),
        )
        OrbitEngineRepository.flushPersistenceForTesting()
        assertEquals("Fresh Acoustic Track", OrbitEngineRepository.uiState.value.recentActivity.first().trackTitle)
        assertEquals("Balanced", OrbitEngineRepository.uiState.value.recentActivity.first().spectralTag)
        assertEquals("Balanced", gateway.currentHistory().first().spectralTag)
    }

    @Test
    fun selectModeManuallyNormalizesDeviceDefaultModeKeys() = runBlocking {
        val gateway = FakeOrbitPersistenceGateway(
            initialSettings = OrbitSettings(
                appRules = AppRuleResolver.defaultAppRules(),
                deviceDefaultModes = mapOf("  Soundcore Life Q30  " to PleasureMode.ECLIPSE),
            ),
        )
        OrbitEngineRepository.initForTesting(gateway)
        OrbitEngineRepository.flushPersistenceForTesting()

        OrbitEngineRepository.updateConnectedDevice(
            ConnectedOutputDevice(
                name = "Soundcore Life Q30",
                isBluetooth = true,
                isSpeaker = false,
                isSoundcoreQ30 = true,
            ),
        )
        OrbitEngineRepository.updateNowPlaying(
            NowPlayingInfo(
                packageName = "com.custom.unlisted.player",
                appName = "Custom Player",
                trackTitle = "Test Track",
                artist = "Artist",
                isPlaying = true,
            ),
        )

        OrbitEngineRepository.selectModeManually(PleasureMode.GRAVITY)
        OrbitEngineRepository.flushPersistenceForTesting()

        val uiDefaults = OrbitEngineRepository.uiState.value.settings.deviceDefaultModes
        val persistedDefaults = gateway.currentSettings().deviceDefaultModes
        assertEquals(mapOf("soundcore life q30" to PleasureMode.GRAVITY), uiDefaults)
        assertEquals(mapOf("soundcore life q30" to PleasureMode.GRAVITY), persistedDefaults)
    }

    @Test
    fun startupHistoryMergeRacePreservesOnDiskHistoryWhenWriteRunsBeforeInitialEmission() = runBlocking {
        val persistedOnDisk = listOf(
            TrackActivityEntry(
                id = "disk-1",
                packageName = "com.spotify.music",
                appName = "Spotify",
                trackTitle = "Old Disk Song 1",
                artist = "Artist 1",
                modeUsed = PleasureMode.NOVA,
                spectralTag = "Warm",
                timestampMs = 1_000L,
            ),
            TrackActivityEntry(
                id = "disk-2",
                packageName = "com.google.android.youtube",
                appName = "YouTube",
                trackTitle = "Old Disk Song 2",
                artist = "Artist 2",
                modeUsed = PleasureMode.COMMS,
                spectralTag = "Vocal",
                timestampMs = 900L,
            ),
        )

        val historyEmissionGate = CompletableDeferred<Unit>()
        val gateway = FakeOrbitPersistenceGateway(
            initialHistory = persistedOnDisk,
            historyEmissionGate = historyEmissionGate,
        )
        OrbitEngineRepository.initForTesting(gateway, dispatcher = Dispatchers.IO)

        // Log a new playing track BEFORE the initial disk history emission arrives!
        OrbitEngineRepository.updateNowPlaying(
            NowPlayingInfo(
                packageName = "com.spotify.music",
                appName = "Spotify",
                trackTitle = "Brand New Launch Track",
                artist = "Fresh Artist",
                isPlaying = true,
            ),
        )

        // Release initial disk emission while write is pending; collector must NOT drop the new track
        // and persistence merge must NOT wipe persistedOnDisk!
        historyEmissionGate.complete(Unit)
        OrbitEngineRepository.flushPersistenceForTesting()

        val uiTitles = OrbitEngineRepository.uiState.value.recentActivity.map { it.trackTitle }
        val diskTitles = gateway.currentHistory().map { it.trackTitle }
        val expected = listOf("Brand New Launch Track", "Old Disk Song 1", "Old Disk Song 2")

        assertEquals(expected, uiTitles)
        assertEquals(expected, diskTitles)
    }

    @Test
    fun rapidSequentialSettingsMutationsExecuteInStrictFifoOrderWithoutReverts() = runBlocking {
        val observedOperations = mutableListOf<String>()
        val gateway = FakeOrbitPersistenceGateway(
            updateDelayMsProvider = { callIndex -> if (callIndex % 2 == 1) 18L else 2L },
            onBeforeSettingsWrite = { idx -> observedOperations.add("write-$idx") },
        )
        OrbitEngineRepository.initForTesting(gateway, dispatcher = Dispatchers.IO)
        OrbitEngineRepository.flushPersistenceForTesting()

        val tempRule = AppRule(
            packageName = "com.temp.player",
            displayName = "Temp Player",
            action = AppRuleAction.USE_MODE,
            mode = PleasureMode.HORIZON,
        )

        OrbitEngineRepository.setBandTrim(0, 5.5f)
        OrbitEngineRepository.resetManualTrims()
        OrbitEngineRepository.setAppRule(tempRule)
        OrbitEngineRepository.removeAppRule("com.temp.player")
        OrbitEngineRepository.togglePinTrack("com.spotify.music", "Burst Song", PleasureMode.GRAVITY)
        OrbitEngineRepository.togglePinTrack("com.spotify.music", "Burst Song", PleasureMode.GRAVITY)
        OrbitEngineRepository.setPowerEnabled(true)
        OrbitEngineRepository.setPowerEnabled(false)
        OrbitEngineRepository.setIntensityPercent(64)

        OrbitEngineRepository.flushPersistenceForTesting()

        assertEquals(1, gateway.maxConcurrentSettingsWrites.get())
        assertEquals(
            listOf("write-1", "write-2", "write-3", "write-4", "write-5", "write-6", "write-7", "write-8", "write-9"),
            observedOperations,
        )

        val finalUi = OrbitEngineRepository.uiState.value.settings
        val finalPersisted = gateway.currentSettings()

        assertEquals(List(10) { 0f }, finalPersisted.manualTrimsDb)
        assertFalse(finalPersisted.appRules.containsKey("com.temp.player"))
        assertTrue(finalPersisted.pinnedTracks.isEmpty())
        assertFalse(finalPersisted.powerEnabled)
        assertEquals(64, finalPersisted.intensityPercent)
        assertEquals(finalPersisted, finalUi)
    }

    @Test
    fun v1ToV2MbcMigrationHandlesAbsentAndExplicitKeysAcrossLegacyPreferences() {
        assertEquals(MbcMode.OFF, OrbitPreferencesStore.migrateMbcMode(mbcModeRaw = null, legacyMbcEnabled = false))
        assertEquals(MbcMode.SUBTLE, OrbitPreferencesStore.migrateMbcMode(mbcModeRaw = null, legacyMbcEnabled = true))
        assertEquals(MbcMode.SUBTLE, OrbitPreferencesStore.migrateMbcMode(mbcModeRaw = null, legacyMbcEnabled = null))
        assertEquals(MbcMode.PUNCHY, OrbitPreferencesStore.migrateMbcMode(mbcModeRaw = "PUNCHY", legacyMbcEnabled = false))
        assertEquals(MbcMode.OFF, OrbitPreferencesStore.migrateMbcMode(mbcModeRaw = "OFF", legacyMbcEnabled = true))
        assertEquals(MbcMode.OFF, OrbitPreferencesStore.migrateMbcMode(mbcModeRaw = "UNKNOWN", legacyMbcEnabled = false))

        val legacyPrefsOff = preferencesOf(
            booleanPreferencesKey("mbc_enabled") to false,
            stringPreferencesKey("app_rules_json") to """{"com.spotify.music":{"packageName":"com.spotify.music","displayName":"Spotify","action":"USE_MODE","mode":"GRAVITY"}}""",
            stringPreferencesKey("pinned_tracks_json") to """{"com.spotify.music|orbit":"HORIZON"}""",
        )
        val migratedOff = OrbitPreferencesStore.decodeSettings(legacyPrefsOff)
        assertEquals(MbcMode.OFF, migratedOff.mbcMode)
        assertFalse(migratedOff.mbcEnabled)
        assertTrue(migratedOff.subsonicFilterEnabled)
        assertFalse(migratedOff.trueHeadroomMode)
        assertEquals(0f, migratedOff.channelBalance, 0.0001f)
        assertEquals(PleasureMode.GRAVITY, migratedOff.appRules["com.spotify.music"]?.mode)
        assertEquals(PleasureMode.HORIZON, migratedOff.pinnedTracks["com.spotify.music|orbit"])

        val explicitOverridePrefs = preferencesOf(
            booleanPreferencesKey("mbc_enabled") to false,
            stringPreferencesKey("mbc_mode") to "PUNCHY",
        )
        val migratedPunchy = OrbitPreferencesStore.decodeSettings(explicitOverridePrefs)
        assertEquals(MbcMode.PUNCHY, migratedPunchy.mbcMode)
        assertTrue(migratedPunchy.mbcEnabled)
    }

    @Test
    fun customHeadphoneProfileImportSelectionDeletionAndBuiltInSwitchWorkCleanly() = runBlocking {
        val gateway = FakeOrbitPersistenceGateway()
        OrbitEngineRepository.initForTesting(gateway)
        OrbitEngineRepository.flushPersistenceForTesting()

        val q30Device = ConnectedOutputDevice(
            name = "Soundcore Life Q30",
            isBluetooth = true,
            isSpeaker = false,
            isSoundcoreQ30 = true,
        )
        OrbitEngineRepository.updateConnectedDevice(q30Device)
        assertNull(OrbitEngineRepository.uiState.value.activeCustomHeadphoneProfile)
        assertEquals(HeadphoneProfile.Q30_CLEAN_BASE, OrbitEngineRepository.uiState.value.activeHeadphoneProfile)

        val imported = OrbitEngineRepository.importCustomHeadphoneProfile(
            name = "Q30 Custom Harman",
            gains31Db = listOf(-4f, -3f, 20f, Float.NaN),
            extraHeadroomDb = 1.5f,
            sourceLabel = "AutoEQ",
            assignToCurrentDevice = true,
        )
        OrbitEngineRepository.flushPersistenceForTesting()

        assertTrue(imported != null)
        val profile = imported!!
        assertEquals(31, profile.gains31Db.size)
        assertEquals(15f, profile.gains31Db[2], 0.0001f)
        assertEquals(0f, profile.gains31Db[3], 0.0001f)
        assertEquals(profile, OrbitEngineRepository.uiState.value.activeCustomHeadphoneProfile)
        assertEquals(
            profile.id,
            gateway.currentSettings().deviceCustomProfileIds["soundcore life q30"],
        )

        // Switching to speaker drops active custom profile without deleting the Q30 binding
        OrbitEngineRepository.updateConnectedDevice(
            ConnectedOutputDevice(name = "Phone Speaker", isBluetooth = false, isSpeaker = true),
        )
        assertNull(OrbitEngineRepository.uiState.value.activeCustomHeadphoneProfile)
        assertEquals(HeadphoneProfile.PHONE_SPEAKER_SAFE, OrbitEngineRepository.uiState.value.activeHeadphoneProfile)

        // Switching back to Q30 restores custom profile
        OrbitEngineRepository.updateConnectedDevice(q30Device)
        assertEquals(profile, OrbitEngineRepository.uiState.value.activeCustomHeadphoneProfile)

        // Switching to a built-in HeadphoneProfile clears custom profile binding for current device
        OrbitEngineRepository.setHeadphoneProfileForCurrentDevice(HeadphoneProfile.Q30_SAFE_STACK)
        OrbitEngineRepository.flushPersistenceForTesting()
        assertNull(OrbitEngineRepository.uiState.value.activeCustomHeadphoneProfile)
        assertEquals(HeadphoneProfile.Q30_SAFE_STACK, OrbitEngineRepository.uiState.value.activeHeadphoneProfile)
        assertFalse(gateway.currentSettings().deviceCustomProfileIds.containsKey("soundcore life q30"))

        // Re-selecting custom profile works and unknown id is ignored
        OrbitEngineRepository.selectCustomHeadphoneProfileForCurrentDevice("non-existent")
        assertNull(OrbitEngineRepository.uiState.value.activeCustomHeadphoneProfile)
        OrbitEngineRepository.selectCustomHeadphoneProfileForCurrentDevice(profile.id)
        OrbitEngineRepository.flushPersistenceForTesting()
        assertEquals(profile, OrbitEngineRepository.uiState.value.activeCustomHeadphoneProfile)

        // Deleting profile cleans up both customHeadphoneProfiles and deviceCustomProfileIds
        OrbitEngineRepository.deleteCustomHeadphoneProfile(profile.id)
        OrbitEngineRepository.flushPersistenceForTesting()
        assertNull(OrbitEngineRepository.uiState.value.activeCustomHeadphoneProfile)
        assertTrue(gateway.currentSettings().customHeadphoneProfiles.isEmpty())
        assertTrue(gateway.currentSettings().deviceCustomProfileIds.isEmpty())
    }

    @Test
    fun v2MutatorsCycleNextModeAndVisibilityFlagsSyncStateAndPersist() = runBlocking {
        val gateway = FakeOrbitPersistenceGateway()
        OrbitEngineRepository.initForTesting(gateway)
        OrbitEngineRepository.flushPersistenceForTesting()

        OrbitEngineRepository.setMbcMode(MbcMode.OFF)
        OrbitEngineRepository.setSubsonicFilterEnabled(false)
        OrbitEngineRepository.setTrueHeadroomMode(true)
        OrbitEngineRepository.setChannelBalance(2.5f)
        OrbitEngineRepository.flushPersistenceForTesting()

        var settings = OrbitEngineRepository.uiState.value.settings
        assertEquals(MbcMode.OFF, settings.mbcMode)
        assertFalse(settings.mbcEnabled)
        assertFalse(settings.subsonicFilterEnabled)
        assertTrue(settings.trueHeadroomMode)
        assertEquals(1.0f, settings.channelBalance, 0.0001f)
        assertEquals(settings, gateway.currentSettings())

        // Pre-flight ruling: legacy setMbcEnabled delegates to setMbcMode
        OrbitEngineRepository.setMbcEnabled(true)
        OrbitEngineRepository.setChannelBalance(-2.5f)
        OrbitEngineRepository.flushPersistenceForTesting()
        settings = OrbitEngineRepository.uiState.value.settings
        assertEquals(MbcMode.SUBTLE, settings.mbcMode)
        assertTrue(settings.mbcEnabled)
        assertEquals(-1.0f, settings.channelBalance, 0.0001f)

        OrbitEngineRepository.setMbcEnabled(false)
        OrbitEngineRepository.setChannelBalance(Float.NaN)
        OrbitEngineRepository.flushPersistenceForTesting()
        settings = OrbitEngineRepository.uiState.value.settings
        assertEquals(MbcMode.OFF, settings.mbcMode)
        assertFalse(settings.mbcEnabled)
        assertEquals(0f, settings.channelBalance, 0.0001f)

        // cycleNextMode cycles NOVA -> GRAVITY -> HORIZON -> ECLIPSE -> COMMS -> NOVA
        val visited = mutableListOf<PleasureMode>()
        repeat(5) {
            OrbitEngineRepository.cycleNextMode()
            visited.add(OrbitEngineRepository.uiState.value.activeMode)
        }
        assertEquals(
            listOf(
                PleasureMode.GRAVITY,
                PleasureMode.HORIZON,
                PleasureMode.ECLIPSE,
                PleasureMode.COMMS,
                PleasureMode.NOVA,
            ),
            visited,
        )

        OrbitEngineRepository.setUiVisible(true)
        assertTrue(OrbitEngineRepository.isUiVisible)
        OrbitEngineRepository.setUiVisible(false)
        assertFalse(OrbitEngineRepository.isUiVisible)

        OrbitEngineRepository.updateNowPlayingArtwork(null)
        assertNull(OrbitEngineRepository.nowPlayingArtwork.value)
    }

    @Test
    fun importBackupJsonMergesValidBackupAndLeavesStateUntouchedOnCorruptJson() = runBlocking {
        val gateway = FakeOrbitPersistenceGateway()
        OrbitEngineRepository.initForTesting(gateway)
        OrbitEngineRepository.flushPersistenceForTesting()

        OrbitEngineRepository.setPowerEnabled(true)
        OrbitEngineRepository.setBypassed(true)
        OrbitEngineRepository.setIntensityPercent(55)
        OrbitEngineRepository.saveCustomPreset("Local Preset")
        OrbitEngineRepository.flushPersistenceForTesting()

        val stateBeforeCorrupt = OrbitEngineRepository.uiState.value
        val persistedBeforeCorrupt = gateway.currentSettings()

        assertFalse(OrbitEngineRepository.importBackupJson(""))
        assertFalse(OrbitEngineRepository.importBackupJson("{not-json"))
        assertFalse(OrbitEngineRepository.importBackupJson("""{"schemaVersion": 99, "settings": {}}"""))
        OrbitEngineRepository.flushPersistenceForTesting()

        assertEquals(stateBeforeCorrupt, OrbitEngineRepository.uiState.value)
        assertEquals(persistedBeforeCorrupt, gateway.currentSettings())

        // Create a clean backup from another settings snapshot, then import it
        val remoteJson = OrbitBackupCodec.encodeBackup(
            OrbitSettings(
                powerEnabled = false,
                bypassed = false,
                selectedMode = PleasureMode.HORIZON,
                intensityPercent = 90,
                mbcMode = MbcMode.PUNCHY,
                subsonicFilterEnabled = false,
                trueHeadroomMode = true,
                channelBalance = 0.25f,
                appRules = AppRuleResolver.defaultAppRules(),
                pinnedTracks = mapOf("com.spotify.music|distant star" to PleasureMode.ECLIPSE),
            ),
            exportedAtMs = 1_760_000_000_000L,
        )

        assertTrue(OrbitEngineRepository.importBackupJson(remoteJson))
        OrbitEngineRepository.flushPersistenceForTesting()

        val merged = OrbitEngineRepository.uiState.value.settings
        assertTrue(merged.powerEnabled)
        assertTrue(merged.bypassed)
        assertEquals(PleasureMode.HORIZON, merged.selectedMode)
        assertEquals(90, merged.intensityPercent)
        assertEquals(MbcMode.PUNCHY, merged.mbcMode)
        assertTrue(merged.mbcEnabled)
        assertFalse(merged.subsonicFilterEnabled)
        assertTrue(merged.trueHeadroomMode)
        assertEquals(0.25f, merged.channelBalance, 0.0001f)
        assertEquals(1, merged.customPresets.size)
        assertEquals("Local Preset", merged.customPresets[0].name)
        assertEquals(PleasureMode.ECLIPSE, merged.pinnedTracks["com.spotify.music|distant star"])
        assertEquals(merged, gateway.currentSettings())

        val exportedRoundTrip = OrbitEngineRepository.exportBackupJson(exportedAtMs = 1_760_000_123_000L)
        val decodedRoundTrip = OrbitBackupCodec.decodeBackup(exportedRoundTrip)
        assertTrue(decodedRoundTrip is BackupImportResult.Success)
        assertEquals(PleasureMode.HORIZON, (decodedRoundTrip as BackupImportResult.Success).payload.selectedMode)
    }

    private class FakeOrbitPersistenceGateway(
        initialSettings: OrbitSettings = OrbitSettings(appRules = AppRuleResolver.defaultAppRules()),
        initialHistory: List<TrackActivityEntry> = emptyList(),
        private val historyEmissionGate: CompletableDeferred<Unit>? = null,
        private val updateDelayMsProvider: (Int) -> Long = { 0L },
        private val onBeforeSettingsWrite: (Int) -> Unit = {},
    ) : OrbitPersistenceGateway {

        private val settingsState = MutableStateFlow(initialSettings)
        private val historyBacking = MutableStateFlow(initialHistory)
        private val gatedHistoryEvents = MutableSharedFlow<List<TrackActivityEntry>>(replay = 1)
        private val activeWrites = AtomicInteger(0)
        private val writeSequence = AtomicInteger(0)
        val maxConcurrentSettingsWrites = AtomicInteger(0)

        override val settingsFlow: Flow<OrbitSettings> = settingsState.asStateFlow()

        override val historyFlow: Flow<List<TrackActivityEntry>> = if (historyEmissionGate == null) {
            historyBacking.asStateFlow()
        } else {
            kotlinx.coroutines.flow.flow {
                historyEmissionGate.await()
                if (gatedHistoryEvents.replayCache.isEmpty()) {
                    emit(historyBacking.value)
                }
                gatedHistoryEvents.asSharedFlow().collect { emit(it) }
            }
        }

        override suspend fun updateSettings(transform: (OrbitSettings) -> OrbitSettings) {
            val concurrent = activeWrites.incrementAndGet()
            maxConcurrentSettingsWrites.updateAndGet { prev -> maxOf(prev, concurrent) }
            try {
                val seq = writeSequence.incrementAndGet()
                onBeforeSettingsWrite(seq)
                val delayMs = updateDelayMsProvider(seq)
                if (delayMs > 0L) {
                    delay(delayMs)
                }
                settingsState.value = transform(settingsState.value)
            } finally {
                activeWrites.decrementAndGet()
            }
        }

        override suspend fun saveHistory(items: List<TrackActivityEntry>) {
            val capped = items.take(HistoryCodec.MAX_HISTORY_ITEMS)
            historyBacking.value = capped
            if (historyEmissionGate != null) {
                gatedHistoryEvents.emit(capped)
            }
        }

        fun currentSettings(): OrbitSettings = settingsState.value

        fun currentHistory(): List<TrackActivityEntry> = historyBacking.value
    }
}
