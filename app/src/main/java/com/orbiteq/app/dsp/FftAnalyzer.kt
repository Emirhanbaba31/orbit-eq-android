package com.orbiteq.app.dsp

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

data class SpectralFrame(
    val bandEnergiesNorm: FloatArray,
    val displayBins36: FloatArray,
    val broadbandRms: Float,
    val spectralTag: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpectralFrame) return false
        return bandEnergiesNorm.contentEquals(other.bandEnergiesNorm) &&
            displayBins36.contentEquals(other.displayBins36) &&
            broadbandRms == other.broadbandRms &&
            spectralTag == other.spectralTag
    }

    override fun hashCode(): Int {
        var result = bandEnergiesNorm.contentHashCode()
        result = 31 * result + displayBins36.contentHashCode()
        result = 31 * result + broadbandRms.hashCode()
        result = 31 * result + spectralTag.hashCode()
        return result
    }
}

object FftAnalyzer {

    private const val BAND_COUNT = 10
    private const val DISPLAY_BIN_COUNT = 36
    private const val SILENT_RMS_THRESHOLD = 1e-4f
    private const val PINK_BAND_NORM_GAIN = 1.58f
    private const val DISPLAY_BIN_NORM_GAIN = 2.8f
    private const val BYTE_SCALE = 128f

    private val TWO_POW_NEG_ONE_SIXTH = 2f.pow(-1f / 6f)
    private val TWO_POW_POS_ONE_SIXTH = 2f.pow(1f / 6f)
    private val LOG_ISO_CENTERS = FloatArray(BAND_COUNT) { idx ->
        log2(PleasureProfiles.ISO_FREQUENCIES_HZ[idx].toFloat())
    }
    private val DISPLAY_CENTERS_HZ = FloatArray(DISPLAY_BIN_COUNT) { idx ->
        31f * 2f.pow((idx / (DISPLAY_BIN_COUNT - 1).toFloat()) * 9f)
    }

    fun analyze(fftBytes: ByteArray, sampleRateHz: Int = 48000): SpectralFrame {
        val n = fftBytes.size
        if (n < 4 || n % 2 != 0 || sampleRateHz <= 0) {
            return zeroFrame()
        }

        val halfN = n / 2
        val mags = FloatArray(halfN + 1)
        var sumSquares = 0f

        for (k in 1 until halfN) {
            val re = fftBytes[2 * k].toFloat() / BYTE_SCALE
            val im = fftBytes[2 * k + 1].toFloat() / BYTE_SCALE
            val mag = hypot(re, im)
            mags[k] = mag
            sumSquares += mag * mag
        }

        val nyquistMag = abs(fftBytes[1].toFloat()) / BYTE_SCALE
        mags[halfN] = nyquistMag
        sumSquares += nyquistMag * nyquistMag

        if (!sumSquares.isFinite() || sumSquares <= 0f) {
            return zeroFrame()
        }

        val broadbandRms = sqrt(sumSquares / halfN.toFloat())
        if (!broadbandRms.isFinite() || broadbandRms <= 0f) {
            return zeroFrame()
        }

        val totalEnergy = sqrt(sumSquares)
        val df = sampleRateHz.toFloat() / n.toFloat()

        val bandEnergiesNorm = extractIsoBands(mags, halfN, df, totalEnergy)
        val displayBins36 = extractDisplayBins(mags, halfN, df, totalEnergy)
        val spectralTag = if (broadbandRms < SILENT_RMS_THRESHOLD) {
            "Balanced"
        } else {
            classifySpectralTag(bandEnergiesNorm)
        }

        return SpectralFrame(
            bandEnergiesNorm = bandEnergiesNorm,
            displayBins36 = displayBins36,
            broadbandRms = broadbandRms,
            spectralTag = spectralTag,
        )
    }

    private fun extractIsoBands(
        mags: FloatArray,
        halfN: Int,
        df: Float,
        totalEnergy: Float,
    ): FloatArray {
        val bandPowers = FloatArray(BAND_COUNT)
        for (k in 1..halfN) {
            val mag = mags[k]
            if (mag <= 0f) continue
            val magSq = mag * mag
            val logF = log2(k * df)
            for (b in 0 until BAND_COUNT) {
                val weight = 1f - abs(logF - LOG_ISO_CENTERS[b])
                if (weight > 0f) {
                    bandPowers[b] += weight * magSq
                }
            }
        }

        return FloatArray(BAND_COUNT) { b ->
            val ratio = sqrt(bandPowers[b]) / totalEnergy
            val scaled = ratio * PINK_BAND_NORM_GAIN
            if (scaled.isFinite()) scaled.coerceIn(0f, 1f) else 0f
        }
    }

    private fun extractDisplayBins(
        mags: FloatArray,
        halfN: Int,
        df: Float,
        totalEnergy: Float,
    ): FloatArray {
        return FloatArray(DISPLAY_BIN_COUNT) { idx ->
            val centerHz = DISPLAY_CENTERS_HZ[idx]
            val fLow = centerHz * TWO_POW_NEG_ONE_SIXTH
            val fHigh = centerHz * TWO_POW_POS_ONE_SIXTH
            val kLow = kotlin.math.ceil(fLow / df).toInt().coerceAtLeast(1)
            val kHigh = kotlin.math.floor(fHigh / df).toInt().coerceAtMost(halfN)

            val binEnergy = if (kLow <= kHigh) {
                var sum = 0f
                for (k in kLow..kHigh) {
                    val m = mags[k]
                    sum += m * m
                }
                sqrt(sum)
            } else {
                val binF = centerHz / df
                if (binF < 1f) {
                    mags[1] * binF.coerceAtLeast(0f)
                } else {
                    val k0 = floor(binF).toInt().coerceIn(1, halfN)
                    val k1 = (k0 + 1).coerceAtMost(halfN)
                    val frac = (binF - k0).coerceIn(0f, 1f)
                    mags[k0] * (1f - frac) + mags[k1] * frac
                }
            }

            val scaled = (binEnergy / totalEnergy) * DISPLAY_BIN_NORM_GAIN
            if (scaled.isFinite()) scaled.coerceIn(0f, 1f) else 0f
        }
    }

    private fun classifySpectralTag(bands: FloatArray): String {
        val subScore = (bands[0] + bands[1]) / 2f
        val lowScore = (bands[0] + bands[1] + bands[2]) / 3f
        val warmScore = (bands[2] + bands[3]) / 2f
        val vocalScore = (bands[4] + bands[5] + bands[6]) / 3f
        val highScore = (bands[7] + bands[8] + bands[9]) / 3f

        return when {
            (bands[1] > 0.55f || bands[0] > 0.55f) &&
                lowScore > vocalScore + 0.14f &&
                lowScore > highScore + 0.14f -> "Sub-Heavy"

            vocalScore > lowScore + 0.12f &&
                vocalScore > highScore + 0.12f &&
                bands[5] > 0.45f -> "Vocal"

            highScore > vocalScore + 0.08f &&
                highScore > 0.48f &&
                (bands[8] > 0.48f || bands[7] > 0.55f) -> "Wide Stage"

            warmScore > highScore + 0.14f &&
                warmScore > 0.45f &&
                subScore <= warmScore + 0.10f -> "Warm"

            else -> "Balanced"
        }
    }

    private fun zeroFrame(): SpectralFrame = SpectralFrame(
        bandEnergiesNorm = FloatArray(BAND_COUNT),
        displayBins36 = FloatArray(DISPLAY_BIN_COUNT),
        broadbandRms = 0f,
        spectralTag = "Balanced",
    )
}
