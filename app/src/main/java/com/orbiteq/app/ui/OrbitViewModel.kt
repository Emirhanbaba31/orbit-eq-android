package com.orbiteq.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.orbiteq.app.BuildConfig
import com.orbiteq.app.R
import com.orbiteq.app.data.DiagnosticsFormatter
import com.orbiteq.app.data.DiagnosticsSnapshot
import com.orbiteq.app.data.OrbitEngineRepository
import com.orbiteq.app.dsp.AutoEqCatalog
import com.orbiteq.app.dsp.AutoEqParseResult
import com.orbiteq.app.dsp.AutoEqParser
import com.orbiteq.app.dsp.CatalogHeadphoneEntry
import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.MbcMode
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.ResponseSpeed
import com.orbiteq.app.model.SessionAttachmentMode
import com.orbiteq.app.service.AudioRouteResolver
import com.orbiteq.app.service.OrbitAudioService
import com.orbiteq.app.service.OrbitWidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class OrbitPermissionState(
    val hasRecordAudio: Boolean = false,
    val hasNotificationAccess: Boolean = false,
    val hasPostNotifications: Boolean = true,
    val isBatteryOptimizationIgnored: Boolean = false,
)

class OrbitViewModel(application: Application) : AndroidViewModel(application) {

    val uiState: StateFlow<OrbitUiState> = OrbitEngineRepository.uiState
    val nowPlayingArtwork: StateFlow<Bitmap?> = OrbitEngineRepository.nowPlayingArtwork

    private val _permissionState = MutableStateFlow(readPermissions())
    val permissionState: StateFlow<OrbitPermissionState> = _permissionState.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    init {
        OrbitEngineRepository.init(application)
        refreshSystemState()
    }

    fun onAppResumed() {
        refreshSystemState()
        if (uiState.value.settings.powerEnabled) {
            OrbitAudioService.promoteToFull(getApplication())
        }
    }

    fun onInitialPersistedPowerActive() {
        if (uiState.value.settings.powerEnabled && !OrbitAudioService.isRunning) {
            OrbitAudioService.promoteToFull(getApplication())
        }
    }

    fun refreshSystemState() {
        _permissionState.value = readPermissions()
        refreshConnectedDeviceWhenIdle()
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        _permissionState.value = readPermissions()
        if (granted && uiState.value.settings.powerEnabled) {
            OrbitAudioService.promoteToFull(getApplication())
        }
    }

    fun postStatusBanner(message: String) {
        val trimmed = message.trim()
        if (trimmed.isNotEmpty()) {
            _statusBannerMessage.value = trimmed
        }
    }

    fun clearStatusBanner() {
        _statusBannerMessage.value = null
    }

    fun setPowerEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        OrbitEngineRepository.setPowerEnabled(enabled)
        if (enabled) {
            OrbitAudioService.startForegroundFull(app)
        } else {
            OrbitAudioService.stopEngine(app)
        }
        OrbitWidgetUpdater.requestUpdate(app)
    }

    fun setBypassed(bypassed: Boolean) {
        OrbitEngineRepository.setBypassed(bypassed)
        OrbitWidgetUpdater.requestUpdate(getApplication())
    }

    fun selectMode(mode: PleasureMode) {
        OrbitEngineRepository.selectModeManually(mode)
        OrbitWidgetUpdater.requestUpdate(getApplication())
    }

    fun saveCurrentModeForActiveApp() {
        OrbitEngineRepository.saveCurrentModeForActiveApp()
    }

    fun setIntensityPercent(percent: Int) {
        OrbitEngineRepository.setIntensityPercent(percent)
    }

    fun setBassMacroDb(db: Float) {
        OrbitEngineRepository.setBassMacroDb(db)
    }

    fun setAirMacroDb(db: Float) {
        OrbitEngineRepository.setAirMacroDb(db)
    }

    fun setDynamicLoudness(enabled: Boolean) {
        OrbitEngineRepository.setDynamicLoudness(enabled)
    }

    fun setMbcMode(mode: MbcMode) {
        OrbitEngineRepository.setMbcMode(mode)
    }

    fun setSubsonicFilterEnabled(enabled: Boolean) {
        OrbitEngineRepository.setSubsonicFilterEnabled(enabled)
    }

    fun setTrueHeadroomMode(enabled: Boolean) {
        OrbitEngineRepository.setTrueHeadroomMode(enabled)
    }

    fun setChannelBalance(balance: Float) {
        OrbitEngineRepository.setChannelBalance(balance)
    }

    fun setResponseSpeed(speed: ResponseSpeed) {
        OrbitEngineRepository.setResponseSpeed(speed)
    }

    fun setBandTrim(bandIndex: Int, db: Float) {
        OrbitEngineRepository.setBandTrim(bandIndex, db)
    }

    fun resetManualTrims() {
        OrbitEngineRepository.resetManualTrims()
    }

    fun saveCustomPreset(name: String) {
        OrbitEngineRepository.saveCustomPreset(name)
    }

    fun applyCustomPreset(presetId: String) {
        OrbitEngineRepository.applyCustomPreset(presetId)
    }

    fun deleteCustomPreset(presetId: String) {
        OrbitEngineRepository.deleteCustomPreset(presetId)
    }

    fun setHeadphoneProfile(profile: HeadphoneProfile) {
        OrbitEngineRepository.setHeadphoneProfileForCurrentDevice(profile)
        OrbitWidgetUpdater.requestUpdate(getApplication())
    }

    fun importAutoEqText(name: String, rawText: String): AutoEqParseResult {
        val result = AutoEqParser.parse(rawText)
        if (result is AutoEqParseResult.Success) {
            val resolvedName = name.trim().ifEmpty { "AutoEQ Profile" }
            val imported = OrbitEngineRepository.importCustomHeadphoneProfile(
                name = resolvedName,
                gains31Db = result.gains31Db,
                sourceLabel = "AutoEQ",
                assignToCurrentDevice = true,
            )
            if (imported != null) {
                OrbitWidgetUpdater.requestUpdate(getApplication())
            }
        }
        return result
    }

    fun importCatalogHeadphone(entry: CatalogHeadphoneEntry) {
        val existing = uiState.value.settings.customHeadphoneProfiles.firstOrNull {
            it.name.equals(entry.name, ignoreCase = true)
        }
        if (existing != null) {
            OrbitEngineRepository.selectCustomHeadphoneProfileForCurrentDevice(existing.id)
        } else {
            val catalogProfile = AutoEqCatalog.toCustomProfile(entry)
            OrbitEngineRepository.importCustomHeadphoneProfile(
                name = catalogProfile.name,
                gains31Db = catalogProfile.gains31Db,
                extraHeadroomDb = catalogProfile.extraHeadroomDb,
                sourceLabel = catalogProfile.sourceLabel,
                assignToCurrentDevice = true,
            )
        }
        OrbitWidgetUpdater.requestUpdate(getApplication())
    }

    fun selectCustomHeadphoneProfile(profileId: String) {
        OrbitEngineRepository.selectCustomHeadphoneProfileForCurrentDevice(profileId)
        OrbitWidgetUpdater.requestUpdate(getApplication())
    }

    fun deleteCustomHeadphoneProfile(profileId: String) {
        OrbitEngineRepository.deleteCustomHeadphoneProfile(profileId)
        OrbitWidgetUpdater.requestUpdate(getApplication())
    }

    fun dismissQ30Tip() {
        OrbitEngineRepository.dismissQ30Tip()
    }

    fun setAppRule(rule: AppRule) {
        OrbitEngineRepository.setAppRule(rule)
    }

    fun removeAppRule(packageName: String) {
        OrbitEngineRepository.removeAppRule(packageName)
    }

    fun togglePinTrack(packageName: String, trackTitle: String, mode: PleasureMode) {
        OrbitEngineRepository.togglePinTrack(packageName, trackTitle, mode)
    }

    fun setThemeMode(themeMode: OrbitThemeMode) {
        OrbitEngineRepository.setThemeMode(themeMode)
    }

    fun setSessionMode(mode: SessionAttachmentMode) {
        OrbitEngineRepository.setSessionMode(mode)
    }

    fun exportBackupJson(): String = OrbitEngineRepository.exportBackupJson()

    fun importBackupJson(rawJson: String): Boolean {
        val ok = OrbitEngineRepository.importBackupJson(rawJson)
        if (ok) {
            OrbitWidgetUpdater.requestUpdate(getApplication())
        }
        return ok
    }

    fun buildDiagnosticsReport(): String {
        val perms = readPermissions()
        _permissionState.value = perms
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val deviceModel = when {
            manufacturer.isEmpty() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model".trim()
        }.ifEmpty { "Android" }
        return DiagnosticsFormatter.format(
            DiagnosticsSnapshot(
                versionName = BuildConfig.VERSION_NAME,
                androidSdk = Build.VERSION.SDK_INT,
                deviceModel = deviceModel,
                uiState = uiState.value,
                hasRecordAudio = perms.hasRecordAudio,
                hasNotificationAccess = perms.hasNotificationAccess,
                hasPostNotifications = perms.hasPostNotifications,
                isBatteryExempt = perms.isBatteryOptimizationIgnored,
            ),
        )
    }

    private fun readPermissions(): OrbitPermissionState {
        val app = getApplication<Application>()
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val hasNotificationAccess = NotificationManagerCompat
            .getEnabledListenerPackages(app)
            .contains(app.packageName)
        val hasPostNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val isBatteryIgnored = app.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(app.packageName) == true

        return OrbitPermissionState(
            hasRecordAudio = hasRecordAudio,
            hasNotificationAccess = hasNotificationAccess,
            hasPostNotifications = hasPostNotifications,
            isBatteryOptimizationIgnored = isBatteryIgnored,
        )
    }

    private fun refreshConnectedDeviceWhenIdle() {
        if (OrbitAudioService.isRunning) return
        val app = getApplication<Application>()
        val audioManager = app.getSystemService(AudioManager::class.java) ?: return
        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: RuntimeException) {
            emptyArray()
        }
        val detected = AudioRouteResolver.resolveFromSystem(
            devices = outputs,
            defaultBluetoothName = app.getString(R.string.device_bluetooth_default),
            defaultWiredName = app.getString(R.string.device_wired_default),
            defaultSpeakerName = app.getString(R.string.device_speaker_default),
        )
        OrbitEngineRepository.updateConnectedDevice(detected)
    }
}
