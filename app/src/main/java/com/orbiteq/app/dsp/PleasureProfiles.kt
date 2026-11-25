package com.orbiteq.app.dsp

import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.PleasureMode

object PleasureProfiles {

    val ISO_FREQUENCIES_HZ: IntArray = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val ISO_LABELS: List<String> = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    private val NOVA_TARGET = floatArrayOf(2.8f, 3.2f, 1.4f, -0.6f, -0.4f, 0.8f, 1.8f, 2.2f, 3.0f, 2.5f)
    private val GRAVITY_TARGET = floatArrayOf(5.5f, 5.0f, 2.6f, -1.2f, -0.8f, 0.4f, 1.2f, 1.6f, 2.2f, 1.5f)
    private val HORIZON_TARGET = floatArrayOf(1.0f, 1.2f, 0.4f, -0.5f, 0.2f, 1.4f, 2.6f, 3.8f, 4.6f, 4.2f)
    private val ECLIPSE_TARGET = floatArrayOf(2.6f, 2.8f, 1.8f, 1.0f, 0.4f, -0.4f, -1.4f, -1.8f, -1.2f, -1.6f)
    private val COMMS_TARGET = floatArrayOf(-3.2f, -1.8f, -0.5f, 0.8f, 2.4f, 3.6f, 3.0f, 1.2f, -0.6f, -1.4f)

    private val Q30_CLEAN_BASE_CURVE = floatArrayOf(-1.5f, -3.5f, -4.5f, -3.0f, -0.5f, 0.5f, 2.0f, 2.5f, -1.5f, 0.5f)
    private val Q30_SAFE_STACK_CURVE = floatArrayOf(-1.0f, -2.0f, -2.5f, -1.5f, 0.0f, 0.5f, 1.0f, 1.0f, -1.0f, 0.0f)
    private val FLAT_GENERIC_CURVE = FloatArray(10)
    private val PHONE_SPEAKER_SAFE_CURVE = floatArrayOf(-6.0f, -4.0f, -1.5f, 0.0f, 0.5f, 1.0f, 1.0f, 0.5f, 0.0f, -1.0f)

    fun targetCurveFor(mode: PleasureMode): FloatArray = when (mode) {
        PleasureMode.NOVA -> NOVA_TARGET.copyOf()
        PleasureMode.GRAVITY -> GRAVITY_TARGET.copyOf()
        PleasureMode.HORIZON -> HORIZON_TARGET.copyOf()
        PleasureMode.ECLIPSE -> ECLIPSE_TARGET.copyOf()
        PleasureMode.COMMS -> COMMS_TARGET.copyOf()
    }

    fun compensationCurveFor(profile: HeadphoneProfile): FloatArray = when (profile) {
        HeadphoneProfile.Q30_CLEAN_BASE -> Q30_CLEAN_BASE_CURVE.copyOf()
        HeadphoneProfile.Q30_SAFE_STACK -> Q30_SAFE_STACK_CURVE.copyOf()
        HeadphoneProfile.FLAT_GENERIC -> FLAT_GENERIC_CURVE.copyOf()
        HeadphoneProfile.PHONE_SPEAKER_SAFE -> PHONE_SPEAKER_SAFE_CURVE.copyOf()
    }

    fun extraHeadroomDbFor(profile: HeadphoneProfile): Float = when (profile) {
        HeadphoneProfile.Q30_SAFE_STACK -> 2.0f
        HeadphoneProfile.Q30_CLEAN_BASE,
        HeadphoneProfile.FLAT_GENERIC,
        HeadphoneProfile.PHONE_SPEAKER_SAFE -> 0.0f
    }

    fun detectSoundcoreQ30(deviceName: String?): Boolean {
        val normalized = deviceName?.trim()?.lowercase() ?: return false
        if (normalized.isEmpty()) return false
        return normalized.contains("soundcore life q30") || normalized.contains("q30")
    }
}
