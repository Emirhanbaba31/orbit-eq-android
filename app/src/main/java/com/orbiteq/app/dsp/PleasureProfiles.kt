package com.orbiteq.app.dsp

import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.PleasureMode
import kotlin.math.log2
import kotlin.math.sqrt

object PleasureProfiles {

    val ISO_FREQUENCIES_HZ: IntArray = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val ISO_LABELS: List<String> = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    private val ISO_10_FREQUENCIES_FLOAT_HZ: FloatArray = FloatArray(ISO_FREQUENCIES_HZ.size) { i ->
        ISO_FREQUENCIES_HZ[i].toFloat()
    }

    val ISO_31_FREQUENCIES_HZ: FloatArray = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
        200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
        2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f,
    )

    val ISO_31_UPPER_CUTOFFS_HZ: FloatArray = FloatArray(ISO_31_FREQUENCIES_HZ.size) { i ->
        if (i == ISO_31_FREQUENCIES_HZ.lastIndex) {
            20000f
        } else {
            sqrt(ISO_31_FREQUENCIES_HZ[i] * ISO_31_FREQUENCIES_HZ[i + 1])
        }
    }

    private const val MIN_GAIN_DB = -15f
    private const val MAX_GAIN_DB = 15f

    private val NOVA_TARGET = floatArrayOf(2.8f, 3.2f, 1.4f, -0.6f, -0.4f, 0.8f, 1.8f, 2.2f, 3.0f, 2.5f)
    private val GRAVITY_TARGET = floatArrayOf(5.5f, 5.0f, 2.6f, -1.2f, -0.8f, 0.4f, 1.2f, 1.6f, 2.2f, 1.5f)
    private val HORIZON_TARGET = floatArrayOf(1.0f, 1.2f, 0.4f, -0.5f, 0.2f, 1.4f, 2.6f, 3.8f, 4.6f, 4.2f)
    private val ECLIPSE_TARGET = floatArrayOf(2.6f, 2.8f, 1.8f, 1.0f, 0.4f, -0.4f, -1.4f, -1.8f, -1.2f, -1.6f)
    private val COMMS_TARGET = floatArrayOf(-3.2f, -1.8f, -0.5f, 0.8f, 2.4f, 3.6f, 3.0f, 1.2f, -0.6f, -1.4f)

    private val Q30_CLEAN_BASE_CURVE = floatArrayOf(-1.5f, -3.5f, -4.5f, -3.0f, -0.5f, 0.5f, 2.0f, 2.5f, -1.5f, 0.5f)
    private val Q30_SAFE_STACK_CURVE = floatArrayOf(-1.0f, -2.0f, -2.5f, -1.5f, 0.0f, 0.5f, 1.0f, 1.0f, -1.0f, 0.0f)
    private val FLAT_GENERIC_CURVE = FloatArray(10)
    private val PHONE_SPEAKER_SAFE_CURVE = floatArrayOf(-6.0f, -4.0f, -1.5f, 0.0f, 0.5f, 1.0f, 1.0f, 0.5f, 0.0f, -1.0f)

    // Calibrated 31-band curves sculpting Q30's 80-250 Hz mid-bass bloom, 2.5-4 kHz dip, and 6-8 kHz resonance.
    private val Q30_CLEAN_BASE_31_CURVE = floatArrayOf(
        -1.0f, -1.2f, -1.5f, -2.1f, -2.8f, -3.5f, -4.2f, -4.6f, -4.8f, -4.2f,
        -3.6f, -3.0f, -1.8f, -1.0f, -0.5f, 0.0f, 0.3f, 0.5f, 0.8f, 1.3f,
        2.0f, 2.6f, 3.0f, 2.5f, 0.8f, -1.4f, -1.9f, -0.6f, 0.3f, 0.5f, 0.2f,
    )
    private val Q30_SAFE_STACK_31_CURVE = floatArrayOf(
        -0.6f, -0.8f, -1.0f, -1.3f, -1.7f, -2.0f, -2.3f, -2.5f, -2.6f, -2.3f,
        -1.9f, -1.5f, -0.9f, -0.4f, 0.0f, 0.2f, 0.3f, 0.5f, 0.7f, 0.9f,
        1.0f, 1.3f, 1.4f, 1.0f, 0.2f, -0.8f, -1.1f, -0.4f, 0.0f, 0.0f, -0.2f,
    )
    private val FLAT_GENERIC_31_CURVE = FloatArray(31)
    private val PHONE_SPEAKER_SAFE_31_CURVE = floatArrayOf(
        -7.5f, -6.8f, -6.0f, -5.3f, -4.7f, -4.0f, -3.1f, -2.2f, -1.5f, -0.9f,
        -0.4f, 0.0f, 0.2f, 0.4f, 0.5f, 0.7f, 0.8f, 1.0f, 1.0f, 1.0f,
        1.0f, 0.8f, 0.7f, 0.5f, 0.3f, 0.1f, 0.0f, -0.3f, -0.6f, -1.0f, -1.5f,
    )

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

    fun compensationCurve31For(profile: HeadphoneProfile): FloatArray {
        val raw = when (profile) {
            HeadphoneProfile.Q30_CLEAN_BASE -> Q30_CLEAN_BASE_31_CURVE
            HeadphoneProfile.Q30_SAFE_STACK -> Q30_SAFE_STACK_31_CURVE
            HeadphoneProfile.FLAT_GENERIC -> FLAT_GENERIC_31_CURVE
            HeadphoneProfile.PHONE_SPEAKER_SAFE -> PHONE_SPEAKER_SAFE_31_CURVE
        }
        return FloatArray(31) { i ->
            val v = raw[i]
            if (v.isFinite()) v.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) else 0f
        }
    }

    fun interpolate10To31(curve10: FloatArray): FloatArray {
        val safe10 = FloatArray(ISO_10_FREQUENCIES_FLOAT_HZ.size) { i ->
            val v = if (i < curve10.size) curve10[i] else 0f
            if (v.isFinite()) v else 0f
        }
        return resampleLog2(ISO_10_FREQUENCIES_FLOAT_HZ, safe10, ISO_31_FREQUENCIES_HZ)
    }

    fun sample31To10(curve31: FloatArray): FloatArray {
        val safe31 = FloatArray(ISO_31_FREQUENCIES_HZ.size) { i ->
            val v = if (i < curve31.size) curve31[i] else 0f
            if (v.isFinite()) v else 0f
        }
        return resampleLog2(ISO_31_FREQUENCIES_HZ, safe31, ISO_10_FREQUENCIES_FLOAT_HZ)
    }

    internal fun resampleLog2(
        sourceFreqsHz: FloatArray,
        sourceGainsDb: FloatArray,
        targetFreqsHz: FloatArray,
    ): FloatArray {
        if (sourceFreqsHz.isEmpty() || sourceGainsDb.isEmpty()) {
            return FloatArray(targetFreqsHz.size)
        }
        if (sourceFreqsHz.size == 1) {
            val constant = normalizeZero(if (sourceGainsDb[0].isFinite()) sourceGainsDb[0] else 0f)
            return FloatArray(targetFreqsHz.size) { constant }
        }

        val sourceLog2 = FloatArray(sourceFreqsHz.size) { i -> log2(sourceFreqsHz[i]) }
        val lastIdx = sourceFreqsHz.lastIndex

        return FloatArray(targetFreqsHz.size) { tIdx ->
            val targetFreq = targetFreqsHz[tIdx]
            val interpolated = when {
                targetFreq <= sourceFreqsHz[0] -> sourceGainsDb[0]
                targetFreq >= sourceFreqsHz[lastIdx] -> sourceGainsDb[lastIdx]
                else -> {
                    var lo = 0
                    var hi = lastIdx
                    while (hi - lo > 1) {
                        val mid = (lo + hi) ushr 1
                        if (sourceFreqsHz[mid] <= targetFreq) {
                            lo = mid
                        } else {
                            hi = mid
                        }
                    }
                    val f0 = sourceFreqsHz[lo]
                    val f1 = sourceFreqsHz[hi]
                    if (targetFreq == f0 || f1 <= f0) {
                        sourceGainsDb[lo]
                    } else if (targetFreq == f1) {
                        sourceGainsDb[hi]
                    } else {
                        val denom = sourceLog2[hi] - sourceLog2[lo]
                        val fraction = if (denom > 1e-6f) {
                            ((log2(targetFreq) - sourceLog2[lo]) / denom).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        sourceGainsDb[lo] + (sourceGainsDb[hi] - sourceGainsDb[lo]) * fraction
                    }
                }
            }
            normalizeZero(if (interpolated.isFinite()) interpolated else 0f)
        }
    }

    fun nextMode(current: PleasureMode): PleasureMode = when (current) {
        PleasureMode.NOVA -> PleasureMode.GRAVITY
        PleasureMode.GRAVITY -> PleasureMode.HORIZON
        PleasureMode.HORIZON -> PleasureMode.ECLIPSE
        PleasureMode.ECLIPSE -> PleasureMode.COMMS
        PleasureMode.COMMS -> PleasureMode.NOVA
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

    private fun normalizeZero(value: Float): Float = if (value == 0f) 0f else value
}
