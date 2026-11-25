package com.orbiteq.app.dsp

import com.orbiteq.app.model.CustomHeadphoneProfile
import com.orbiteq.app.model.DeEsserMode
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
    val totalEq31Db: FloatArray = FloatArray(31),
    val bypassMatchGainDb: Float = 0f,
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
            rightBalanceTrimDb == other.rightBalanceTrimDb &&
            totalEq31Db.contentEquals(other.totalEq31Db) &&
            bypassMatchGainDb == other.bypassMatchGainDb
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
        result = 31 * result + totalEq31Db.contentHashCode()
        result = 31 * result + bypassMatchGainDb.hashCode()
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
    private var lastActiveBypassMatchGainDb: Float = 0f

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
        toneTiltDbPerOct: Float = 0f,
        deEsserMode: DeEsserMode = DeEsserMode.OFF,
        loudnessMatchedBypass: Boolean = true,
        isAutoBypassed: Boolean = false,
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
                totalEq31Db = FloatArray(PRE_EQ_31_BAND_COUNT),
                bypassMatchGainDb = 0f,
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
            val matchGain = if (
                loudnessMatchedBypass &&
                !isAutoBypassed &&
                lastActiveBypassMatchGainDb < BYPASS_MATCH_ACTIVATION_THRESHOLD_DB
            ) {
                lastActiveBypassMatchGainDb
            } else {
                0f
            }
            return EqEngineFrame(
                preEqDb = FloatArray(BAND_COUNT),
                postEqDb = FloatArray(BAND_COUNT),
                totalEqDb = FloatArray(BAND_COUNT),
                adaptiveDeltaDb = FloatArray(BAND_COUNT),
                inputGainDb = matchGain,
                loudnessBoostDb = 0f,
                engineStatus = EngineStatus.BYPASSED,
                displayBins36 = smoothedDisplay.copyOf(),
                spectralTag = spectral.spectralTag,
                preEq31Db = FloatArray(PRE_EQ_31_BAND_COUNT),
                leftBalanceTrimDb = 0f,
                rightBalanceTrimDb = 0f,
                totalEq31Db = FloatArray(PRE_EQ_31_BAND_COUNT),
                bypassMatchGainDb = matchGain,
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
            mode = mode,
            intensityScale = intensityScale,
            maxScaledDelta = maxScaledDelta,
            attackAlpha = attackAlpha,
            releaseAlpha = releaseAlpha,
            isMediaPlaying = isMediaPlaying,
            deEsserMode = deEsserMode,
        )

        val preEq31Db = buildPreEq31Db(
            headphoneProfile = headphoneProfile,
            customHeadphoneProfile = customHeadphoneProfile,
            manualTrimsDb = manualTrimsDb,
            subsonicFilterEnabled = subsonicFilterEnabled,
            toneTiltDbPerOct = toneTiltDbPerOct,
        )
        val sampledPre10 = PleasureProfiles.sample31To10(preEq31Db)
        val preEqDb = FloatArray(BAND_COUNT) { i ->
            normalizeZero(sanitizeFinite(sampledPre10[i]).coerceIn(MIN_EQ_DB, MAX_EQ_DB))
        }

        val safeVolume = sanitizeFinite(volumeRatio, default = 1f).coerceIn(0f, 1f)
        val loudnessScale = if (dynamicLoudness && safeVolume < LOUDNESS_VOLUME_THRESHOLD) {
            ((LOUDNESS_VOLUME_THRESHOLD - safeVolume) / LOUDNESS_VOLUME_THRESHOLD)
                .pow(LOUDNESS_EXPONENT)
                .coerceIn(0f, 1f)
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
            normalizeZero(sanitizeFinite(rawPost).coerceIn(MIN_EQ_DB, MAX_EQ_DB))
        }

        val postEq31Db = PleasureProfiles.interpolate10To31(postEqDb)
        var peakPositiveCascadeDb = 0f
        val totalEq31Db = FloatArray(PRE_EQ_31_BAND_COUNT) { i ->
            val cascade31 = preEq31Db[i] + postEq31Db[i]
            if (cascade31.isFinite() && cascade31 > peakPositiveCascadeDb) {
                peakPositiveCascadeDb = cascade31
            }
            normalizeZero(sanitizeFinite(cascade31).coerceIn(MIN_EQ_DB, MAX_EQ_DB))
        }

        val totalEqDb = FloatArray(BAND_COUNT) { i ->
            val combined = preEqDb[i] + postEqDb[i]
            if (combined.isFinite() && combined > peakPositiveCascadeDb) {
                peakPositiveCascadeDb = combined
            }
            normalizeZero(sanitizeFinite(combined).coerceIn(MIN_EQ_DB, MAX_EQ_DB))
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

        lastActiveBypassMatchGainDb = normalizeZero(
            computePerceivedNetGainDb(totalEq31Db, inputGainDb)
                .coerceIn(MIN_BYPASS_MATCH_GAIN_DB, 0.0f),
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
            totalEq31Db = totalEq31Db,
            bypassMatchGainDb = 0f,
        )
    }

    /**
     * Estimates perceived midband-weighted net gain in dB so loudness-matched bypass
     * can attenuate bypass to match active EQ without letting sub-bass or 20 kHz extremes skew it.
     */
    internal fun computePerceivedNetGainDb(
        totalEq31Db: FloatArray,
        inputGainDb: Float,
    ): Float {
        var weightedEqDb = 0f
        for (i in 0 until PRE_EQ_31_BAND_COUNT) {
            val eqDb = sanitizeFinite(totalEq31Db.getOrElse(i) { 0f })
            weightedEqDb += eqDb * PERCEPTUAL_LOUDNESS_WEIGHTS_31[i]
        }
        return normalizeZero(sanitizeFinite(inputGainDb) + sanitizeFinite(weightedEqDb))
    }

    fun reset() {
        smoothedDelta.fill(0f)
        smoothedDisplay.fill(0f)
        lastAppliedTotalEqDb.fill(0f)
        lastTrackKey = null
        silentSinceMs = null
        inProfileFallback = false
        lastActiveBypassMatchGainDb = 0f
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
        toneTiltDbPerOct: Float,
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
        val tilt31 = PleasureProfiles.analogTiltCurve31(toneTiltDbPerOct)

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
            normalizeZero(
                sanitizeFinite(baseCurve31[i] + trims31[i] + subsonic + tilt31[i])
                    .coerceIn(MIN_EQ_DB, MAX_EQ_DB),
            )
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
        mode: PleasureMode,
        intensityScale: Float,
        maxScaledDelta: Float,
        attackAlpha: Float,
        releaseAlpha: Float,
        isMediaPlaying: Boolean,
        deEsserMode: DeEsserMode,
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

        val modeRefShift = PleasureProfiles.adaptiveReferenceShiftFor(mode)
        for (i in 0 until BAND_COUNT) {
            val rawTarget = computePsychoacousticTargetDelta(
                bandIndex = i,
                bandNorm = bandEnergiesNorm[i],
                mode = mode,
                intensityScale = intensityScale,
                deEsserMode = deEsserMode,
                modeRefShift = modeRefShift,
            ) * intensityScale
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

    private fun computePsychoacousticTargetDelta(
        bandIndex: Int,
        bandNorm: Float,
        mode: PleasureMode,
        intensityScale: Float,
        deEsserMode: DeEsserMode,
        modeRefShift: FloatArray = PleasureProfiles.adaptiveReferenceShiftFor(mode),
    ): Float {
        val shift = modeRefShift[bandIndex]
        val effectiveRef = (PINK_REFERENCE_NORM[bandIndex] + shift * intensityScale)
            .coerceIn(MIN_EFFECTIVE_REF_NORM, MAX_EFFECTIVE_REF_NORM)
        val diff = sanitizeFinite(bandNorm) - effectiveRef
        val excess = when {
            diff > DEADBAND -> diff - DEADBAND
            diff < -DEADBAND -> diff + DEADBAND
            else -> 0f
        }
        if (excess == 0f) return 0f

        val baseSensitivity = when (bandIndex) {
            0, 1 -> if (excess < 0f) 9.5f else 8.5f
            2, 3 -> if (excess > 0f) 11.0f else 4.5f
            4, 5 -> if (excess > 0f) 5.0f else 4.0f
            6, 7 -> if (excess > 0f) 10.5f else 5.0f
            else -> if (excess < 0f) 8.5f else 7.5f
        }
        // Extra harshness damping in 2-8 kHz; band 9 (16 kHz) is intentionally excluded to preserve air.
        val harshnessMultiplier = if (excess > 0f) {
            when (deEsserMode) {
                DeEsserMode.OFF -> 1.0f
                DeEsserMode.GENTLE -> if (bandIndex == 7 || bandIndex == 8) 1.15f else 1.0f
                DeEsserMode.STRONG -> when (bandIndex) {
                    6 -> 1.10f
                    7, 8 -> 1.35f
                    else -> 1.0f
                }
            }
        } else {
            1.0f
        }
        return -excess * baseSensitivity * harshnessMultiplier
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

    internal companion object {
        private const val BAND_COUNT = 10
        private const val PRE_EQ_31_BAND_COUNT = 31
        private const val DISPLAY_BIN_COUNT = 36
        private const val MIN_EQ_DB = -15f
        private const val MAX_EQ_DB = 15f
        private const val MAX_ADAPTIVE_DELTA_DB = 5.5f
        private const val SILENT_RMS_THRESHOLD = 1e-4f
        private const val NOISE_FLOOR_FLATNESS_THRESHOLD = 0.72f
        private const val DEEMBED_DAMPING = 0.70f
        private const val DEEMBED_CLAMP_DB = 9f
        private const val TRACK_CHANGE_DECAY_FACTOR = 0.35f
        private const val SUBSONIC_20HZ_CUT_DB = -6.0f
        private const val SUBSONIC_25HZ_CUT_DB = -2.5f
        private const val DRM_FALLBACK_DELAY_MS = 1500L
        private const val LOUDNESS_VOLUME_THRESHOLD = 0.70f
        private const val LOUDNESS_EXPONENT = 1.35f
        private const val MAX_LOW_LOUDNESS_DB = 4.8f
        private const val HEADROOM_ALLOWANCE_DB = 1.5f
        private const val MAX_CUSTOM_EXTRA_HEADROOM_DB = 6f
        private const val MAX_CHANNEL_BALANCE_ATTEN_DB = 12f
        private const val DEADBAND = 0.05f
        private const val MIN_EFFECTIVE_REF_NORM = 0.20f
        private const val MAX_EFFECTIVE_REF_NORM = 0.65f
        private const val MIN_BYPASS_MATCH_GAIN_DB = -9.0f
        private const val BYPASS_MATCH_ACTIVATION_THRESHOLD_DB = -0.05f
        private const val DISPLAY_ATTACK_ALPHA = 0.50f
        private const val DISPLAY_RELEASE_ALPHA = 0.25f
        private const val ZERO_SNAP_EPSILON = 1e-4f

        private val BASS_MACRO_WEIGHTS = floatArrayOf(1.0f, 0.70f, 0.35f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        private val AIR_MACRO_WEIGHTS = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.35f, 0.70f, 1.0f)
        private val LOUDNESS_WEIGHTS = floatArrayOf(4.8f, 4.4f, 2.0f, 0.6f, 0.0f, 0.0f, 0.2f, 1.0f, 2.4f, 2.2f)
        private val PINK_REFERENCE_NORM = floatArrayOf(0.42f, 0.48f, 0.48f, 0.46f, 0.46f, 0.46f, 0.44f, 0.42f, 0.38f, 0.34f)

        internal val PERCEPTUAL_LOUDNESS_WEIGHTS_31: FloatArray = run {
            val raw = floatArrayOf(
                0.10f, 0.14f, 0.19f, 0.25f, 0.32f, 0.40f, 0.47f, 0.54f, 0.65f, 0.76f,
                0.86f, 0.96f, 1.05f, 1.14f, 1.25f, 1.31f, 1.37f, 1.42f, 1.45f, 1.47f,
                1.47f, 1.45f, 1.40f, 1.33f, 1.18f, 1.03f, 0.88f, 0.72f, 0.57f, 0.43f, 0.31f,
            )
            val sum = raw.sum()
            FloatArray(PRE_EQ_31_BAND_COUNT) { i -> raw[i] / sum }
        }

        private val EMPTY_SPECTRAL = SpectralFrame(
            bandEnergiesNorm = FloatArray(BAND_COUNT),
            displayBins36 = FloatArray(DISPLAY_BIN_COUNT),
            broadbandRms = 0f,
            spectralTag = "Balanced",
            spectralFlatness = 0f,
        )
    }
}
