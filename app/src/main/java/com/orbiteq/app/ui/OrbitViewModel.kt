package com.orbiteq.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.orbiteq.app.R
import com.orbiteq.app.data.OrbitEngineRepository
import com.orbiteq.app.dsp.PleasureProfiles
import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.ConnectedOutputDevice
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.ResponseSpeed
import com.orbiteq.app.model.SessionAttachmentMode
import com.orbiteq.app.service.OrbitAudioService
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

    private val _permissionState = MutableStateFlow(readPermissions())
    val permissionState: StateFlow<OrbitPermissionState> = _permissionState.asStateFlow()

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

    fun setPowerEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        OrbitEngineRepository.setPowerEnabled(enabled)
        if (enabled) {
            OrbitAudioService.startForegroundFull(app)
        } else {
            OrbitAudioService.stopEngine(app)
        }
    }

    fun setBypassed(bypassed: Boolean) {
        OrbitEngineRepository.setBypassed(bypassed)
    }

    fun selectMode(mode: PleasureMode) {
        OrbitEngineRepository.selectModeManually(mode)
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

    fun setMbcEnabled(enabled: Boolean) {
        OrbitEngineRepository.setMbcEnabled(enabled)
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
        val detected = resolveOutputDevice(app, outputs)
        OrbitEngineRepository.updateConnectedDevice(detected)
    }

    private fun resolveOutputDevice(
        app: Application,
        devices: Array<out AudioDeviceInfo>,
    ): ConnectedOutputDevice {
        val btDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
        if (btDevice != null) {
            val rawName = btDevice.productName?.toString()?.trim().orEmpty()
            val displayName = rawName.ifEmpty { app.getString(R.string.device_bluetooth_default) }
            return ConnectedOutputDevice(
                name = displayName,
                isBluetooth = true,
                isSpeaker = false,
                isSoundcoreQ30 = PleasureProfiles.detectSoundcoreQ30(displayName),
            )
        }

        val wiredOrUsb = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID
        }
        if (wiredOrUsb != null) {
            val rawName = wiredOrUsb.productName?.toString()?.trim().orEmpty()
            val displayName = rawName.ifEmpty { app.getString(R.string.device_wired_default) }
            return ConnectedOutputDevice(
                name = displayName,
                isBluetooth = false,
                isSpeaker = false,
                isSoundcoreQ30 = PleasureProfiles.detectSoundcoreQ30(displayName),
            )
        }

        return ConnectedOutputDevice(
            name = app.getString(R.string.device_speaker_default),
            isBluetooth = false,
            isSpeaker = true,
            isSoundcoreQ30 = false,
        )
    }
}
