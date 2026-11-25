package com.orbiteq.app.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class ParametricFilterType {
    PK,
    LS,
    HS,
}

data class ParametricFilter(
    val type: ParametricFilterType,
    val freqHz: Double,
    val gainDb: Double,
    val q: Double,
)

/**
 * Evaluates RBJ Audio EQ Cookbook digital biquad transfer function magnitudes in double precision.
 */
object ParametricBiquadEvaluator {

    const val DEFAULT_SAMPLE_RATE_HZ: Double = 48000.0
    const val DEFAULT_SHELF_Q: Double = 0.7071067811865476

    private const val MIN_GAIN_DB: Float = -15f
    private const val MAX_GAIN_DB: Float = 15f
    private const val MIN_LINEAR_MAG: Double = 1e-12
    private const val MIN_DENOM_MAG_SQ: Double = 1e-24

    fun evaluateFilterDb(
        filter: ParametricFilter,
        evalFreqHz: Double,
        sampleRateHz: Double = DEFAULT_SAMPLE_RATE_HZ,
    ): Double {
        if (!filter.freqHz.isFinite() ||
            !filter.gainDb.isFinite() ||
            !filter.q.isFinite() ||
            !evalFreqHz.isFinite() ||
            !sampleRateHz.isFinite()
        ) {
            return 0.0
        }
        val nyquist = sampleRateHz / 2.0
        if (sampleRateHz <= 0.0 ||
            filter.gainDb == 0.0 ||
            filter.q <= 0.0 ||
            filter.freqHz <= 0.0 ||
            filter.freqHz >= nyquist ||
            evalFreqHz <= 0.0 ||
            evalFreqHz >= nyquist
        ) {
            return 0.0
        }

        val amp = 10.0.pow(filter.gainDb / 40.0)
        val w0 = 2.0 * PI * filter.freqHz / sampleRateHz
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * filter.q)

        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double

        when (filter.type) {
            ParametricFilterType.PK -> {
                b0 = 1.0 + alpha * amp
                b1 = -2.0 * cosW0
                b2 = 1.0 - alpha * amp
                a0 = 1.0 + alpha / amp
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha / amp
            }

            ParametricFilterType.LS -> {
                val twoSqrtAAlpha = 2.0 * sqrt(amp) * alpha
                b0 = amp * ((amp + 1.0) - (amp - 1.0) * cosW0 + twoSqrtAAlpha)
                b1 = 2.0 * amp * ((amp - 1.0) - (amp + 1.0) * cosW0)
                b2 = amp * ((amp + 1.0) - (amp - 1.0) * cosW0 - twoSqrtAAlpha)
                a0 = (amp + 1.0) + (amp - 1.0) * cosW0 + twoSqrtAAlpha
                a1 = -2.0 * ((amp - 1.0) + (amp + 1.0) * cosW0)
                a2 = (amp + 1.0) + (amp - 1.0) * cosW0 - twoSqrtAAlpha
            }

            ParametricFilterType.HS -> {
                val twoSqrtAAlpha = 2.0 * sqrt(amp) * alpha
                b0 = amp * ((amp + 1.0) + (amp - 1.0) * cosW0 + twoSqrtAAlpha)
                b1 = -2.0 * amp * ((amp - 1.0) + (amp + 1.0) * cosW0)
                b2 = amp * ((amp + 1.0) + (amp - 1.0) * cosW0 - twoSqrtAAlpha)
                a0 = (amp + 1.0) - (amp - 1.0) * cosW0 + twoSqrtAAlpha
                a1 = 2.0 * ((amp - 1.0) - (amp + 1.0) * cosW0)
                a2 = (amp + 1.0) - (amp - 1.0) * cosW0 - twoSqrtAAlpha
            }
        }

        val w = 2.0 * PI * evalFreqHz / sampleRateHz
        val cos1 = cos(w)
        val sin1 = sin(w)
        val cos2 = cos(2.0 * w)
        val sin2 = sin(2.0 * w)

        val numRe = b0 + b1 * cos1 + b2 * cos2
        val numIm = -b1 * sin1 - b2 * sin2
        val denRe = a0 + a1 * cos1 + a2 * cos2
        val denIm = -a1 * sin1 - a2 * sin2

        val numMagSq = numRe * numRe + numIm * numIm
        val denMagSq = denRe * denRe + denIm * denIm
        if (!numMagSq.isFinite() || !denMagSq.isFinite() || denMagSq <= MIN_DENOM_MAG_SQ) {
            return 0.0
        }

        val mag = sqrt(numMagSq / denMagSq)
        val db = 20.0 * log10(mag.coerceAtLeast(MIN_LINEAR_MAG))
        return if (!db.isFinite() || db == 0.0) 0.0 else db
    }

    fun evaluateOnIso31(
        filters: List<ParametricFilter>,
        sampleRateHz: Double = DEFAULT_SAMPLE_RATE_HZ,
    ): List<Float> = List(PleasureProfiles.ISO_31_FREQUENCIES_HZ.size) { idx ->
        val freq = PleasureProfiles.ISO_31_FREQUENCIES_HZ[idx].toDouble()
        var sumDb = 0.0
        for (filter in filters) {
            sumDb += evaluateFilterDb(filter, freq, sampleRateHz)
        }
        val clamped = (if (sumDb.isFinite()) sumDb.toFloat() else 0f).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        if (clamped == 0f) 0f else clamped
    }
}
