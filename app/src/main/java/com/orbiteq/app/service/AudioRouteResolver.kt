package com.orbiteq.app.service

import android.media.AudioDeviceInfo
import com.orbiteq.app.dsp.PleasureProfiles
import com.orbiteq.app.model.ConnectedOutputDevice

data class OutputDeviceCandidate(
    val type: Int,
    val productName: String?,
    val isSink: Boolean = true,
)

object AudioRouteResolver {

    fun resolve(
        candidates: List<OutputDeviceCandidate>,
        defaultBluetoothName: String,
        defaultWiredName: String,
        defaultSpeakerName: String,
    ): ConnectedOutputDevice {
        val sinkCandidates = candidates.filter { it.isSink }

        val a2dp = sinkCandidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val ble = sinkCandidates.firstOrNull { isBleAudioType(it.type) }
        val btDevice = a2dp ?: ble
        if (btDevice != null) {
            val rawName = btDevice.productName?.trim().orEmpty()
            val displayName = rawName.ifEmpty { defaultBluetoothName }
            return ConnectedOutputDevice(
                name = displayName,
                isBluetooth = true,
                isSpeaker = false,
                isSoundcoreQ30 = PleasureProfiles.detectSoundcoreQ30(displayName),
            )
        }

        val wiredOrUsb = sinkCandidates.firstOrNull { isWiredOrUsbType(it.type) }
        if (wiredOrUsb != null) {
            val rawName = wiredOrUsb.productName?.trim().orEmpty()
            val displayName = rawName.ifEmpty { defaultWiredName }
            return ConnectedOutputDevice(
                name = displayName,
                isBluetooth = false,
                isSpeaker = false,
                isSoundcoreQ30 = PleasureProfiles.detectSoundcoreQ30(displayName),
            )
        }

        return ConnectedOutputDevice(
            name = defaultSpeakerName,
            isBluetooth = false,
            isSpeaker = true,
            isSoundcoreQ30 = false,
        )
    }

    fun resolveFromSystem(
        devices: Array<out AudioDeviceInfo>,
        defaultBluetoothName: String,
        defaultWiredName: String,
        defaultSpeakerName: String,
    ): ConnectedOutputDevice {
        val candidates = devices.map { device ->
            OutputDeviceCandidate(
                type = device.type,
                productName = device.productName?.toString(),
                isSink = device.isSink,
            )
        }
        return resolve(
            candidates = candidates,
            defaultBluetoothName = defaultBluetoothName,
            defaultWiredName = defaultWiredName,
            defaultSpeakerName = defaultSpeakerName,
        )
    }

    fun isRelevantOutputType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            isBleAudioType(type) ||
            isWiredOrUsbType(type) ||
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    private fun isBleAudioType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER
    }

    private fun isWiredOrUsbType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_HEARING_AID
    }
}
