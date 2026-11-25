package com.orbiteq.app.dsp

import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.ResponseSpeed
import kotlin.math.abs
import kotlin.math.max

data class EqEngineFrame(
    val preEqDb: FloatArray,
    val postEqDb: FloatArray,
    val totalEqDb: FloatArray,
    val adaptiveDeltaDb: FloatArray,
    val inputGainDb: Float,
    val loudnessBoostDb: Float,
    val engineStatus: EngineStatus,
    val displayBins36: FloatArray,
    val spectralTag: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqEngineFrame) return false
        return preEqDb.contentEquals(other.preEqDb) &&
            postEqDb.contentEquals(other.postEqDb) &&
            totalEqDb.contentEquals(other.totalEqDb) &&
            adaptiveDeltaDb.contentEquals(other.adaptiveDeltaDb) &&
            inputGainDb == other.inputGainDb &&
            loudnessBoostDb == other.loudnessBoostDb &&
            engineStatus == other.engineStatus &&
            displayBins36.contentEquals(other.displayBins36) &&
            spectralTag == other.spectralTag
    }

    override fun hashCode(): Int {
        var result = preEqDb.contentHashCode()
        result = 31 * result + postEqDb.contentHashCode()
        result = 31 * result + totalEqDb.contentHashCode()
        result = 31 * result + adaptiveDeltaDb.contentHashCode()
        result = 31 * result + inputGainDb.hashCode()
        result = 31 * result + loudnessBoostDb.hashCode()
        result = 31 * result + engineStatus.hashCode()
        result = 31 * result + displayBins36.contentHashCode()
        result = 31 * result + spectralTag.hashCode()
        return result
    }
}

class AdaptiveEqEngine {

    private val smoothedDelta = FloatArray(BAND_COUNT)
    private val smoothedDisplay = FloatArray(DISPLAY_BIN_COUNT)
    private var silentSinceMs: Long? = null
    private var inProfileFallback: Boolean = false

    fun processFrame(
        fftBytes: ByteArray?,
        nowMs: Long,
        isMediaPlaying: Boolean,
        powerEnabled: Boolean,
        bypassed: Boolean,
        mode: PleasureMode,
        intensityPercent: Int,
        speed: ResponseSpeed,
        bassMacroDb: Float,
        airMacroDb: Float,
        dynamicLoudness: Boolean,
        volumeRatio: Float,
        headphoneProfile: HeadphoneProfile,
        manualTrimsDb: List<Float>,
        fftCaptureAvailable: Boolean = true,
    ): EqEngineFrame {
        if (!powerEnabled) {
            reset()
            return EqEngineFrame(
                preEqDb = FloatArray(BAND_COUNT),
                postEqDb = FloatArray(BAND_COUNT),
                totalEqDb = FloatArray(BAND_COUNT),
                adaptiveDeltaDb = FloatArray(BAND_COUNT),
                inputGainDb = 0f,
                loudnessBoostDb = 0f,
                engineStatus = EngineStatus.IDLE,
                displayBins36 = FloatArray(DISPLAY_BIN_COUNT),
                spectralTag = "Balanced",
            )
        }

        val spectral = if (fftCaptureAvailable && fftBytes != null) {
            FftAnalyzer.analyze(fftBytes)
        } else {
            EMPTY_SPECTRAL
        }
        updateDisplayBins(spectral.displayBins36)

        if (bypassed) {
            return EqEngineFrame(
                preEqDb = FloatArray(BAND_COUNT),
                postEqDb = FloatArray(BAND_COUNT),
                totalEqDb = FloatArray(BAND_COUNT),
                adaptiveDeltaDb = FloatArray(BAND_COUNT),
                inputGainDb = 0f,
                loudnessBoostDb = 0f,
                engineStatus = EngineStatus.BYPASSED,
                displayBins36 = smoothedDisplay.copyOf(),
                spectralTag = spectral.spectralTag,
            )
        }

        val intensityScale = intensityPercent.coerceIn(0, 100) / 100f
        val maxScaledDelta = MAX_ADAPTIVE_DELTA_DB * intensityScale
        val (attackAlpha, releaseAlpha) = smoothingRatesFor(speed)

        val hasActiveSignal = fftCaptureAvailable &&
            fftBytes != null &&
            spectral.broadbandRms >= SILENT_RMS_THRESHOLD
        val engineStatus = updateWatchdogState(
            fftCaptureAvailable = fftCaptureAvailable,
            hasActiveSignal = hasActiveSignal,
            isMediaPlaying = isMediaPlaying,
            nowMs = nowMs,
        )

        updateAdaptiveDelta(
            hasActiveSignal = hasActiveSignal,
            bandEnergiesNorm = spectral.bandEnergiesNorm,
            intensityScale = intensityScale,
            maxScaledDelta = maxScaledDelta,
            attackAlpha = attackAlpha,
            releaseAlpha = releaseAlpha,
            isMediaPlaying = isMediaPlaying,
        )

        val compensationCurve = PleasureProfiles.compensationCurveFor(headphoneProfile)
        val preEqDb = FloatArray(BAND_COUNT) { i ->
            val trim = sanitizeFinite(manualTrimsDb.getOrElse(i) { 0f })
            sanitizeFinite(compensationCurve[i] + trim).coerceIn(MIN_EQ_DB, MAX_EQ_DB)
        }

        val safeVolume = sanitizeFinite(volumeRatio, default = 1f).coerceIn(0f, 1f)
        val loudnessScale = if (dynamicLoudness && safeVolume < LOUDNESS_VOLUME_THRESHOLD) {
            ((LOUDNESS_VOLUME_THRESHOLD - safeVolume) / LOUDNESS_VOLUME_THRESHOLD).coerceIn(0f, 1f)
        } else {
            0f
        }
        val loudnessBoostDb = MAX_LOW_LOUDNESS_DB * loudnessScale

        val modeTarget = PleasureProfiles.targetCurveFor(mode)
        val safeBass = sanitizeFinite(bassMacroDb)
        val safeAir = sanitizeFinite(airMacroDb)
        val postEqDb = FloatArray(BAND_COUNT) { i ->
            val rawPost = modeTarget[i] * intensityScale +
                smoothedDelta[i] +
                safeBass * BASS_MACRO_WEIGHTS[i] +
                safeAir * AIR_MACRO_WEIGHTS[i] +
                LOUDNESS_WEIGHTS[i] * loudnessScale
            sanitizeFinite(rawPost).coerceIn(MIN_EQ_DB, MAX_EQ_DB)
        }

        var peakPositiveCascadeDb = 0f
        val totalEqDb = FloatArray(BAND_COUNT) { i ->
            val combined = preEqDb[i] + postEqDb[i]
            if (combined > peakPositiveCascadeDb) {
                peakPositiveCascadeDb = combined
            }
            sanitizeFinite(combined).coerceIn(MIN_EQ_DB, MAX_EQ_DB)
        }

        val extraHeadroomDb = PleasureProfiles.extraHeadroomDbFor(headphoneProfile)
        val inputGainDb = -max(0f, peakPositiveCascadeDb - HEADROOM_ALLOWANCE_DB) - extraHeadroomDb

        return EqEngineFrame(
            preEqDb = preEqDb,
            postEqDb = postEqDb,
            totalEqDb = totalEqDb,
            adaptiveDeltaDb = smoothedDelta.copyOf(),
            inputGainDb = sanitizeFinite(inputGainDb),
            loudnessBoostDb = sanitizeFinite(loudnessBoostDb),
            engineStatus = engineStatus,
            displayBins36 = smoothedDisplay.copyOf(),
            spectralTag = spectral.spectralTag,
        )
    }

    fun reset() {
        smoothedDelta.fill(0f)
        smoothedDisplay.fill(0f)
        silentSinceMs = null
        inProfileFallback = false
    }

    private fun updateWatchdogState(
        fftCaptureAvailable: Boolean,
        hasActiveSignal: Boolean,
        isMediaPlaying: Boolean,
        nowMs: Long,
    ): EngineStatus {
        if (!fftCaptureAvailable) {
            silentSinceMs = null
            inProfileFallback = true
            return EngineStatus.PROFILE_FALLBACK
        }

        if (hasActiveSignal) {
            silentSinceMs = null
            inProfileFallback = false
            return EngineStatus.AUTO_FFT_LOCKED
        }

        if (!isMediaPlaying) {
            silentSinceMs = null
            return if (inProfileFallback) EngineStatus.PROFILE_FALLBACK else EngineStatus.AUTO_FFT_LOCKED
        }

        val existingStart = silentSinceMs
        val startMs = if (existingStart == null || nowMs < existingStart) {
            silentSinceMs = nowMs
            nowMs
        } else {
            existingStart
        }

        if (nowMs - startMs >= DRM_FALLBACK_DELAY_MS) {
            inProfileFallback = true
        }
        return if (inProfileFallback) EngineStatus.PROFILE_FALLBACK else EngineStatus.AUTO_FFT_LOCKED
    }

    private fun updateAdaptiveDelta(
        hasActiveSignal: Boolean,
        bandEnergiesNorm: FloatArray,
        intensityScale: Float,
        maxScaledDelta: Float,
        attackAlpha: Float,
        releaseAlpha: Float,
        isMediaPlaying: Boolean,
    ) {
        if (!hasActiveSignal) {
            if (inProfileFallback || !isMediaPlaying) {
                for (i in 0 until BAND_COUNT) {
                    val decayed = smoothedDelta[i] * (1f - releaseAlpha)
                    smoothedDelta[i] = if (abs(decayed) < ZERO_SNAP_EPSILON) {
                        0f
                    } else {
                        decayed.coerceIn(-maxScaledDelta, maxScaledDelta)
                    }
                }
            } else {
                for (i in 0 until BAND_COUNT) {
                    smoothedDelta[i] = smoothedDelta[i].coerceIn(-maxScaledDelta, maxScaledDelta)
                }
            }
            return
        }

        for (i in 0 until BAND_COUNT) {
            val rawTarget = computePsychoacousticTargetDelta(i, bandEnergiesNorm[i]) * intensityScale
            val clampedTarget = rawTarget.coerceIn(-maxScaledDelta, maxScaledDelta)
            val current = smoothedDelta[i]
            val isAttacking = abs(clampedTarget) > abs(current) || (clampedTarget * current < 0f)
            val alpha = if (isAttacking) attackAlpha else releaseAlpha
            val updated = current + alpha * (clampedTarget - current)
            smoothedDelta[i] = if (abs(updated) < ZERO_SNAP_EPSILON) {
                0f
            } else {
                sanitizeFinite(updated).coerceIn(-maxScaledDelta, maxScaledDelta)
            }
        }
    }

    private fun computePsychoacousticTargetDelta(bandIndex: Int, bandNorm: Float): Float {
        val diff = sanitizeFinite(bandNorm) - PINK_REFERENCE_NORM[bandIndex]
        val excess = when {
            diff > DEADBAND -> diff - DEADBAND
            diff < -DEADBAND -> diff + DEADBAND
            else -> 0f
        }
        if (excess == 0f) return 0f

        val sensitivity = when (bandIndex) {
            0, 1 -> if (excess < 0f) 9.5f else 8.5f
            2, 3 -> if (excess > 0f) 11.0f else 4.5f
            4, 5 -> if (excess > 0f) 5.0f else 4.0f
            6, 7 -> if (excess > 0f) 10.5f else 5.0f
            else -> if (excess < 0f) 8.5f else 7.5f
        }
        return -excess * sensitivity
    }

    private fun updateDisplayBins(rawBins: FloatArray) {
        for (i in 0 until DISPLAY_BIN_COUNT) {
            val target = sanitizeFinite(rawBins.getOrElse(i) { 0f }).coerceIn(0f, 1f)
            val current = smoothedDisplay[i]
            val alpha = if (target > current) DISPLAY_ATTACK_ALPHA else DISPLAY_RELEASE_ALPHA
            val updated = current + alpha * (target - current)
            smoothedDisplay[i] = if (updated < ZERO_SNAP_EPSILON) 0f else updated.coerceIn(0f, 1f)
        }
    }

    private fun smoothingRatesFor(speed: ResponseSpeed): Pair<Float, Float> = when (speed) {
        ResponseSpeed.CHILL -> 0.06f to 0.03f
        ResponseSpeed.BALANCED -> 0.12f to 0.05f
        ResponseSpeed.REACTIVE -> 0.22f to 0.09f
    }

    private fun sanitizeFinite(value: Float, default: Float = 0f): Float =
        if (value.isFinite()) value else default

    private companion object {
        const val BAND_COUNT = 10
        const val DISPLAY_BIN_COUNT = 36
        const val MIN_EQ_DB = -15f
        const val MAX_EQ_DB = 15f
        const val MAX_ADAPTIVE_DELTA_DB = 5.5f
        const val SILENT_RMS_THRESHOLD = 1e-4f
        const val DRM_FALLBACK_DELAY_MS = 1500L
        const val LOUDNESS_VOLUME_THRESHOLD = 0.65f
        const val MAX_LOW_LOUDNESS_DB = 4.0f
        const val HEADROOM_ALLOWANCE_DB = 1.5f
        const val DEADBAND = 0.05f
        const val DISPLAY_ATTACK_ALPHA = 0.50f
        const val DISPLAY_RELEASE_ALPHA = 0.25f
        const val ZERO_SNAP_EPSILON = 1e-4f

        val BASS_MACRO_WEIGHTS = floatArrayOf(1.0f, 0.70f, 0.35f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val AIR_MACRO_WEIGHTS = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.35f, 0.70f, 1.0f)
        val LOUDNESS_WEIGHTS = floatArrayOf(4.0f, 4.0f, 1.6f, 0.4f, 0f, 0f, 0.3f, 1.2f, 2.8f, 2.8f)
        val PINK_REFERENCE_NORM = floatArrayOf(0.42f, 0.48f, 0.48f, 0.46f, 0.46f, 0.46f, 0.44f, 0.42f, 0.38f, 0.34f)

        val EMPTY_SPECTRAL = SpectralFrame(
            bandEnergiesNorm = FloatArray(BAND_COUNT),
            displayBins36 = FloatArray(DISPLAY_BIN_COUNT),
            broadbandRms = 0f,
            spectralTag = "Balanced",
        )
    }
}
