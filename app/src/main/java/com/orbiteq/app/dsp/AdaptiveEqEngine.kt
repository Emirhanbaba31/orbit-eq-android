package com.orbiteq.app.dsp

import com.orbiteq.app.model.CustomHeadphoneProfile
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.ResponseSpeed
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

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
    val preEq31Db: FloatArray = PleasureProfiles.interpolate10To31(preEqDb),
    val leftBalanceTrimDb: Float = 0f,
    val rightBalanceTrimDb: Float = 0f,
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
            spectralTag == other.spectralTag &&
            preEq31Db.contentEquals(other.preEq31Db) &&
            leftBalanceTrimDb == other.leftBalanceTrimDb &&
            rightBalanceTrimDb == other.rightBalanceTrimDb
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
        result = 31 * result + preEq31Db.contentHashCode()
        result = 31 * result + leftBalanceTrimDb.hashCode()
        result = 31 * result + rightBalanceTrimDb.hashCode()
        return result
    }
}

class AdaptiveEqEngine {

    private val smoothedDelta = FloatArray(BAND_COUNT)
    private val smoothedDisplay = FloatArray(DISPLAY_BIN_COUNT)
    private val lastAppliedTotalEqDb = FloatArray(BAND_COUNT)
    private var lastTrackKey: String? = null
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
        sampleRateHz: Int = 48000,
        customHeadphoneProfile: CustomHeadphoneProfile? = null,
        subsonicFilterEnabled: Boolean = false,
        trueHeadroomMode: Boolean = false,
        channelBalance: Float = 0f,
        trackKey: String? = null,
        deembedEnabled: Boolean = true,
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
                preEq31Db = FloatArray(PRE_EQ_31_BAND_COUNT),
                leftBalanceTrimDb = 0f,
                rightBalanceTrimDb = 0f,
            )
        }

        val spectral = if (fftCaptureAvailable && fftBytes != null) {
            FftAnalyzer.analyze(fftBytes, sampleRateHz)
        } else {
            EMPTY_SPECTRAL
        }
        updateDisplayBins(spectral.displayBins36)

        if (bypassed) {
            lastAppliedTotalEqDb.fill(0f)
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
                preEq31Db = FloatArray(PRE_EQ_31_BAND_COUNT),
                leftBalanceTrimDb = 0f,
                rightBalanceTrimDb = 0f,
            )
        }

        val intensityScale = intensityPercent.coerceIn(0, 100) / 100f
        val maxScaledDelta = MAX_ADAPTIVE_DELTA_DB * intensityScale
        val (attackAlpha, releaseAlpha) = smoothingRatesFor(speed)

        val hasActiveSignal = fftCaptureAvailable &&
            fftBytes != null &&
            spectral.broadbandRms >= SILENT_RMS_THRESHOLD
        val isNoiseFloor = hasActiveSignal && spectral.spectralFlatness >= NOISE_FLOOR_FLATNESS_THRESHOLD
        val engineStatus = updateWatchdogState(
            fftCaptureAvailable = fftCaptureAvailable,
            hasActiveSignal = hasActiveSignal,
            isMediaPlaying = isMediaPlaying,
            nowMs = nowMs,
        )

        applyTrackChangeDecayIfNeeded(trackKey)

        val effectiveBandEnergies = applyDeembedding(
            bandEnergiesNorm = spectral.bandEnergiesNorm,
            hasActiveSignal = hasActiveSignal,
            isNoiseFloor = isNoiseFloor,
            deembedEnabled = deembedEnabled,
        )

        updateAdaptiveDelta(
            hasActiveSignal = hasActiveSignal,
            isNoiseFloor = isNoiseFloor,
            bandEnergiesNorm = effectiveBandEnergies,
            intensityScale = intensityScale,
            maxScaledDelta = maxScaledDelta,
            attackAlpha = attackAlpha,
            releaseAlpha = releaseAlpha,
            isMediaPlaying = isMediaPlaying,
        )

        val preEq31Db = buildPreEq31Db(
            headphoneProfile = headphoneProfile,
            customHeadphoneProfile = customHeadphoneProfile,
            manualTrimsDb = manualTrimsDb,
            subsonicFilterEnabled = subsonicFilterEnabled,
        )
        val sampledPre10 = PleasureProfiles.sample31To10(preEq31Db)
        val preEqDb = FloatArray(BAND_COUNT) { i ->
            sanitizeFinite(sampledPre10[i]).coerceIn(MIN_EQ_DB, MAX_EQ_DB)
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

        val postEq31Db = PleasureProfiles.interpolate10To31(postEqDb)
        var peakPositiveCascadeDb = 0f
        for (i in 0 until PRE_EQ_31_BAND_COUNT) {
            val cascade31 = preEq31Db[i] + postEq31Db[i]
            if (cascade31.isFinite() && cascade31 > peakPositiveCascadeDb) {
                peakPositiveCascadeDb = cascade31
            }
        }

        val totalEqDb = FloatArray(BAND_COUNT) { i ->
            val combined = preEqDb[i] + postEqDb[i]
            if (combined.isFinite() && combined > peakPositiveCascadeDb) {
                peakPositiveCascadeDb = combined
            }
            sanitizeFinite(combined).coerceIn(MIN_EQ_DB, MAX_EQ_DB)
        }
        totalEqDb.copyInto(lastAppliedTotalEqDb)

        val allowanceDb = if (trueHeadroomMode) 0.0f else HEADROOM_ALLOWANCE_DB
        val extraHeadroomDb = if (customHeadphoneProfile != null) {
            sanitizeFinite(customHeadphoneProfile.extraHeadroomDb).coerceIn(0f, MAX_CUSTOM_EXTRA_HEADROOM_DB)
        } else {
            PleasureProfiles.extraHeadroomDbFor(headphoneProfile)
        }
        val inputGainDb = normalizeZero(
            sanitizeFinite(-max(0f, peakPositiveCascadeDb - allowanceDb) - extraHeadroomDb),
        )

        val safeBalance = sanitizeFinite(channelBalance).coerceIn(-1f, 1f)
        val leftBalanceTrimDb = if (safeBalance > 0f) -MAX_CHANNEL_BALANCE_ATTEN_DB * safeBalance else 0f
        val rightBalanceTrimDb = if (safeBalance < 0f) MAX_CHANNEL_BALANCE_ATTEN_DB * safeBalance else 0f

        return EqEngineFrame(
            preEqDb = preEqDb,
            postEqDb = postEqDb,
            totalEqDb = totalEqDb,
            adaptiveDeltaDb = smoothedDelta.copyOf(),
            inputGainDb = inputGainDb,
            loudnessBoostDb = sanitizeFinite(loudnessBoostDb),
            engineStatus = engineStatus,
            displayBins36 = smoothedDisplay.copyOf(),
            spectralTag = spectral.spectralTag,
            preEq31Db = preEq31Db,
            leftBalanceTrimDb = leftBalanceTrimDb,
            rightBalanceTrimDb = rightBalanceTrimDb,
        )
    }

    fun reset() {
        smoothedDelta.fill(0f)
        smoothedDisplay.fill(0f)
        lastAppliedTotalEqDb.fill(0f)
        lastTrackKey = null
        silentSinceMs = null
        inProfileFallback = false
    }

    private fun applyTrackChangeDecayIfNeeded(trackKey: String?) {
        if (trackKey == null) return
        val previous = lastTrackKey
        if (previous != null && trackKey != previous) {
            for (i in 0 until BAND_COUNT) {
                smoothedDelta[i] *= TRACK_CHANGE_DECAY_FACTOR
            }
        }
        lastTrackKey = trackKey
    }

    /**
     * Compensates for the previous frame's EQ curve using 0.70x damping and RMS preservation,
     * avoiding post-effect self-cancellation while staying contractive on pre-effect OEMs.
     */
    private fun applyDeembedding(
        bandEnergiesNorm: FloatArray,
        hasActiveSignal: Boolean,
        isNoiseFloor: Boolean,
        deembedEnabled: Boolean,
    ): FloatArray {
        if (!deembedEnabled || !hasActiveSignal || isNoiseFloor) {
            return bandEnergiesNorm
        }

        val raw = FloatArray(BAND_COUNT)
        var inputSumSq = 0.0
        var rawSumSq = 0.0
        for (b in 0 until BAND_COUNT) {
            val input = sanitizeFinite(bandEnergiesNorm.getOrElse(b) { 0f }).coerceIn(0f, 1f)
            inputSumSq += input.toDouble() * input.toDouble()
            val clampedEq = sanitizeFinite(lastAppliedTotalEqDb[b]).coerceIn(-DEEMBED_CLAMP_DB, DEEMBED_CLAMP_DB)
            val deembedGain = 10f.pow(-clampedEq * DEEMBED_DAMPING / 20f)
            val compensated = input * deembedGain
            raw[b] = compensated
            rawSumSq += compensated.toDouble() * compensated.toDouble()
        }

        val inputRms = sqrt(inputSumSq / BAND_COUNT.toDouble())
        val rawRms = sqrt(rawSumSq / BAND_COUNT.toDouble())
        if (inputRms <= 0.0 || rawRms <= 0.0 || !inputRms.isFinite() || !rawRms.isFinite()) {
            return FloatArray(BAND_COUNT) { b -> sanitizeFinite(raw[b]).coerceIn(0f, 1f) }
        }

        val scale = (inputRms / rawRms).toFloat()
        return FloatArray(BAND_COUNT) { b ->
            sanitizeFinite(raw[b] * scale).coerceIn(0f, 1f)
        }
    }

    private fun buildPreEq31Db(
        headphoneProfile: HeadphoneProfile,
        customHeadphoneProfile: CustomHeadphoneProfile?,
        manualTrimsDb: List<Float>,
        subsonicFilterEnabled: Boolean,
    ): FloatArray {
        val baseCurve31 = if (customHeadphoneProfile != null) {
            FloatArray(PRE_EQ_31_BAND_COUNT) { i ->
                sanitizeFinite(customHeadphoneProfile.gains31Db.getOrElse(i) { 0f })
                    .coerceIn(MIN_EQ_DB, MAX_EQ_DB)
            }
        } else {
            PleasureProfiles.compensationCurve31For(headphoneProfile)
        }

        val sanitizedTrims10 = FloatArray(BAND_COUNT) { i ->
            sanitizeFinite(manualTrimsDb.getOrElse(i) { 0f })
        }
        val trims31 = PleasureProfiles.interpolate10To31(sanitizedTrims10)

        return FloatArray(PRE_EQ_31_BAND_COUNT) { i ->
            val subsonic = if (subsonicFilterEnabled) {
                when (i) {
                    0 -> SUBSONIC_20HZ_CUT_DB
                    1 -> SUBSONIC_25HZ_CUT_DB
                    else -> 0f
                }
            } else {
                0f
            }
            sanitizeFinite(baseCurve31[i] + trims31[i] + subsonic).coerceIn(MIN_EQ_DB, MAX_EQ_DB)
        }
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
        isNoiseFloor: Boolean,
        bandEnergiesNorm: FloatArray,
        intensityScale: Float,
        maxScaledDelta: Float,
        attackAlpha: Float,
        releaseAlpha: Float,
        isMediaPlaying: Boolean,
    ) {
        if (isNoiseFloor) {
            decaySmoothedDelta(releaseAlpha = releaseAlpha, maxScaledDelta = maxScaledDelta)
            return
        }

        if (!hasActiveSignal) {
            if (inProfileFallback || !isMediaPlaying) {
                decaySmoothedDelta(releaseAlpha = releaseAlpha, maxScaledDelta = maxScaledDelta)
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

    private fun decaySmoothedDelta(releaseAlpha: Float, maxScaledDelta: Float) {
        for (i in 0 until BAND_COUNT) {
            val decayed = smoothedDelta[i] * (1f - releaseAlpha)
            smoothedDelta[i] = if (abs(decayed) < ZERO_SNAP_EPSILON) {
                0f
            } else {
                sanitizeFinite(decayed).coerceIn(-maxScaledDelta, maxScaledDelta)
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

    private fun normalizeZero(value: Float): Float = if (value == 0f) 0f else value

    private companion object {
        const val BAND_COUNT = 10
        const val PRE_EQ_31_BAND_COUNT = 31
        const val DISPLAY_BIN_COUNT = 36
        const val MIN_EQ_DB = -15f
        const val MAX_EQ_DB = 15f
        const val MAX_ADAPTIVE_DELTA_DB = 5.5f
        const val SILENT_RMS_THRESHOLD = 1e-4f
        const val NOISE_FLOOR_FLATNESS_THRESHOLD = 0.72f
        const val DEEMBED_DAMPING = 0.70f
        const val DEEMBED_CLAMP_DB = 9f
        const val TRACK_CHANGE_DECAY_FACTOR = 0.35f
        const val SUBSONIC_20HZ_CUT_DB = -6.0f
        const val SUBSONIC_25HZ_CUT_DB = -2.5f
        const val DRM_FALLBACK_DELAY_MS = 1500L
        const val LOUDNESS_VOLUME_THRESHOLD = 0.65f
        const val MAX_LOW_LOUDNESS_DB = 4.0f
        const val HEADROOM_ALLOWANCE_DB = 1.5f
        const val MAX_CUSTOM_EXTRA_HEADROOM_DB = 6f
        const val MAX_CHANNEL_BALANCE_ATTEN_DB = 12f
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
            spectralFlatness = 0f,
        )
    }
}
