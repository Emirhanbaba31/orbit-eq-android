package com.orbiteq.app.service

import android.media.AudioDeviceInfo
import com.orbiteq.app.model.ConnectedOutputDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteResolverTest {

    @Test
    fun bluetoothA2dpTakesPriorityOverBleWiredAndSpeaker() {
        val candidates = listOf(
            OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, productName = "Phone Speaker"),
            OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_WIRED_HEADPHONES, productName = "Studio Wired"),
            OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_BLE_HEADSET, productName = "LE Buds"),
            OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, productName = "Sony WH-1000XM5"),
        )

        val resolved = AudioRouteResolver.resolve(
            candidates = candidates,
            defaultBluetoothName = "Bluetooth Audio",
            defaultWiredName = "Wired Headphones",
            defaultSpeakerName = "Built-in Speaker",
        )

        assertEquals(
            ConnectedOutputDevice(
                name = "Sony WH-1000XM5",
                isBluetooth = true,
                isSpeaker = false,
                isSoundcoreQ30 = false,
            ),
            resolved,
        )
    }

    @Test
    fun bleHeadsetAndBleSpeakerBeatWiredWhenA2dpAbsent() {
        val bleHeadset = AudioRouteResolver.resolve(
            candidates = listOf(
                OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_USB_HEADSET, productName = "USB DAC"),
                OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_BLE_HEADSET, productName = "Pixel Buds Pro"),
            ),
            defaultBluetoothName = "Bluetooth Audio",
            defaultWiredName = "Wired Headphones",
            defaultSpeakerName = "Built-in Speaker",
        )
        assertEquals("Pixel Buds Pro", bleHeadset.name)
        assertTrue(bleHeadset.isBluetooth)
        assertFalse(bleHeadset.isSpeaker)

        val bleSpeaker = AudioRouteResolver.resolve(
            candidates = listOf(
                OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_WIRED_HEADSET, productName = "Aux Cable"),
                OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_BLE_SPEAKER, productName = "LE Boom"),
            ),
            defaultBluetoothName = "Bluetooth Audio",
            defaultWiredName = "Wired Headphones",
            defaultSpeakerName = "Built-in Speaker",
        )
        assertEquals("LE Boom", bleSpeaker.name)
        assertTrue(bleSpeaker.isBluetooth)
        assertFalse(bleSpeaker.isSpeaker)
    }

    @Test
    fun wiredUsbAndHearingAidBeatBuiltInSpeaker() {
        val wiredTypes = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )

        for (deviceType in wiredTypes) {
            val resolved = AudioRouteResolver.resolve(
                candidates = listOf(
                    OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, productName = "Speaker"),
                    OutputDeviceCandidate(type = deviceType, productName = "Reference Gear"),
                ),
                defaultBluetoothName = "Bluetooth Audio",
                defaultWiredName = "Wired Headphones",
                defaultSpeakerName = "Built-in Speaker",
            )
            assertEquals("Reference Gear", resolved.name)
            assertFalse("Type $deviceType should not mark isBluetooth", resolved.isBluetooth)
            assertFalse("Type $deviceType should not mark isSpeaker", resolved.isSpeaker)
        }
    }

    @Test
    fun blankOrNullProductNameFallsBackToDefaultLocalizedLabel() {
        val btFallback = AudioRouteResolver.resolve(
            candidates = listOf(
                OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, productName = "   "),
            ),
            defaultBluetoothName = "Bluetooth Audio",
            defaultWiredName = "Wired Headphones",
            defaultSpeakerName = "Built-in Speaker",
        )
        assertEquals("Bluetooth Audio", btFallback.name)
        assertTrue(btFallback.isBluetooth)

        val wiredFallback = AudioRouteResolver.resolve(
            candidates = listOf(
                OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_WIRED_HEADPHONES, productName = null),
            ),
            defaultBluetoothName = "Bluetooth Audio",
            defaultWiredName = "Wired Headphones",
            defaultSpeakerName = "Built-in Speaker",
        )
        assertEquals("Wired Headphones", wiredFallback.name)
        assertFalse(wiredFallback.isBluetooth)
        assertFalse(wiredFallback.isSpeaker)

        val speakerFallback = AudioRouteResolver.resolve(
            candidates = listOf(
                OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, productName = "Phone"),
            ),
            defaultBluetoothName = "Bluetooth Audio",
            defaultWiredName = "Wired Headphones",
            defaultSpeakerName = "Built-in Speaker",
        )
        assertEquals(
            ConnectedOutputDevice(
                name = "Built-in Speaker",
                isBluetooth = false,
                isSpeaker = true,
                isSoundcoreQ30 = false,
            ),
            speakerFallback,
        )
    }

    @Test
    fun soundcoreQ30DetectedAcrossBluetoothAndWiredNames() {
        val btQ30 = AudioRouteResolver.resolve(
            candidates = listOf(
                OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, productName = "Soundcore Life Q30"),
            ),
            defaultBluetoothName = "Bluetooth Audio",
            defaultWiredName = "Wired Headphones",
            defaultSpeakerName = "Built-in Speaker",
        )
        assertTrue(btQ30.isSoundcoreQ30)
        assertTrue(btQ30.isBluetooth)

        val wiredQ30 = AudioRouteResolver.resolve(
            candidates = listOf(
                OutputDeviceCandidate(type = AudioDeviceInfo.TYPE_USB_HEADSET, productName = "Anker Q30 AUX"),
            ),
            defaultBluetoothName = "Bluetooth Audio",
            defaultWiredName = "Wired Headphones",
            defaultSpeakerName = "Built-in Speaker",
        )
        assertTrue(wiredQ30.isSoundcoreQ30)
        assertFalse(wiredQ30.isBluetooth)
        assertFalse(wiredQ30.isSpeaker)
    }

    @Test
    fun nonSinkCandidatesAreIgnored() {
        val resolved = AudioRouteResolver.resolve(
            candidates = listOf(
                OutputDeviceCandidate(
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    productName = "Input Only Mic",
                    isSink = false,
                ),
                OutputDeviceCandidate(
                    type = AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    productName = "Non-sink Wired",
                    isSink = false,
                ),
                OutputDeviceCandidate(
                    type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    productName = "Speaker",
                    isSink = true,
                ),
            ),
            defaultBluetoothName = "Bluetooth Audio",
            defaultWiredName = "Wired Headphones",
            defaultSpeakerName = "Built-in Speaker",
        )

        assertEquals("Built-in Speaker", resolved.name)
        assertTrue(resolved.isSpeaker)
        assertFalse(resolved.isBluetooth)
    }

    @Test
    fun isRelevantOutputTypeMatchesAudioRouteTypesOnly() {
        assertTrue(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertTrue(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertTrue(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertTrue(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertTrue(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertTrue(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_HEARING_AID))
        assertTrue(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))

        assertFalse(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertFalse(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_TELEPHONY))
        assertFalse(AudioRouteResolver.isRelevantOutputType(AudioDeviceInfo.TYPE_UNKNOWN))
    }
}
