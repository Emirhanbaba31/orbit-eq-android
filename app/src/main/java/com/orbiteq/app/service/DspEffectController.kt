package com.orbiteq.app.service

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import com.orbiteq.app.dsp.EqEngineFrame
import com.orbiteq.app.dsp.PleasureProfiles
import com.orbiteq.app.model.DeEsserMode
import com.orbiteq.app.model.DspBackend
import com.orbiteq.app.model.MbcMode
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

interface DspSessionHandle {
    val backend: DspBackend
    val sessionId: Int
    val preEqBandCount: Int
    val mbcBandCount: Int
    fun apply(
        frame: EqEngineFrame,
        mbcMode: MbcMode,
        deEsserMode: DeEsserMode = DeEsserMode.OFF,
        active: Boolean,
    )
    fun release()
}

interface DspBackendFactory {
    fun createDynamicsProcessing(
        sessionId: Int,
        preEqBandCount: Int,
        mbcBandCount: Int = DspEffectController.HIGH_RES_MBC_BAND_COUNT,
    ): DspSessionHandle
    fun createLegacyEqualizer(sessionId: Int): DspSessionHandle
}

class DspEffectController(
    private val factory: DspBackendFactory = AndroidDspBackendFactory,
) {
    private var activeHandle: DspSessionHandle? = null
    private val smoother = DspGainSmoother()

    @Volatile
    var currentBackend: DspBackend = DspBackend.NONE
        private set

    @Volatile
    var currentSessionId: Int = 0
        private set

    @Volatile
    var currentPreEqBandCount: Int = 0
        private set

    @Volatile
    var currentMbcBandCount: Int = 0
        private set

    @Volatile
    var isRampActive: Boolean = false
        private set

    @Synchronized
    fun attach(sessionId: Int): DspBackend {
        release()
        val requestedSession = sessionId.coerceAtLeast(0)
        val handle = attachWithFallback(requestedSession)
            ?: if (requestedSession > 0) attachWithFallback(0) else null

        activeHandle = handle
        currentBackend = handle?.backend ?: DspBackend.NONE
        currentSessionId = handle?.sessionId ?: 0
        currentPreEqBandCount = handle?.preEqBandCount ?: 0
        currentMbcBandCount = handle?.mbcBandCount ?: 0
        smoother.reset()
        isRampActive = false
        return currentBackend
    }

    @Synchronized
    fun apply(
        frame: EqEngineFrame,
        mbcMode: MbcMode,
        deEsserMode: DeEsserMode = DeEsserMode.OFF,
        active: Boolean,
    ) {
        val handle = activeHandle ?: return
        val isMatchedBypass = !active &&
            frame.bypassMatchGainDb.isFinite() &&
            frame.bypassMatchGainDb < BYPASS_MATCH_ACTIVATION_THRESHOLD_DB
        val smoothed = smoother.step(
            targetFrame = frame,
            active = active,
            matchedBypass = isMatchedBypass,
        )
        isRampActive = smoothed.isRampActive
        val effectiveMbcMode = if (smoothed.holdMbcOff) MbcMode.OFF else mbcMode
        val effectiveDeEsserMode = if (smoothed.holdMbcOff) DeEsserMode.OFF else deEsserMode
        try {
            handle.apply(
                frame = smoothed.frame,
                mbcMode = effectiveMbcMode,
                deEsserMode = effectiveDeEsserMode,
                active = smoothed.effectiveActive,
            )
        } catch (_: RuntimeException) {
            release()
        }
    }

    @Synchronized
    fun release() {
        val existing = activeHandle
        activeHandle = null
        currentBackend = DspBackend.NONE
        currentSessionId = 0
        currentPreEqBandCount = 0
        currentMbcBandCount = 0
        smoother.reset()
        isRampActive = false
        if (existing != null) {
            try {
                existing.release()
            } catch (_: RuntimeException) {
                // Ignore native release failure on already-closed sessions.
            }
        }
    }

    private fun attachWithFallback(sessionId: Int): DspSessionHandle? {
        for ((preEqBands, mbcBands) in DP_CANDIDATE_CONFIGS) {
            try {
                return factory.createDynamicsProcessing(sessionId, preEqBands, mbcBands)
            } catch (_: RuntimeException) {
                // Fall through to 10/4 DynamicsProcessing or legacy Equalizer.
            }
        }
        return try {
            factory.createLegacyEqualizer(sessionId)
        } catch (_: RuntimeException) {
            null
        }
    }

    companion object {
        const val HIGH_RES_PRE_EQ_BAND_COUNT = 31
        const val STANDARD_PRE_EQ_BAND_COUNT = 10
        const val HIGH_RES_MBC_BAND_COUNT = 5
        const val STANDARD_MBC_BAND_COUNT = 4
        internal const val BYPASS_MATCH_ACTIVATION_THRESHOLD_DB = -0.05f
        private val DP_CANDIDATE_CONFIGS = arrayOf(
            HIGH_RES_PRE_EQ_BAND_COUNT to HIGH_RES_MBC_BAND_COUNT,
            STANDARD_PRE_EQ_BAND_COUNT to STANDARD_MBC_BAND_COUNT,
        )

        internal fun effectiveChannelInputGains(frame: EqEngineFrame): Pair<Float, Float> {
            val baseInput = sanitizeDb(frame.inputGainDb)
            val leftTrim = sanitizeDb(frame.leftBalanceTrimDb).coerceAtMost(0f)
            val rightTrim = sanitizeDb(frame.rightBalanceTrimDb).coerceAtMost(0f)
            return (baseInput + leftTrim) to (baseInput + rightTrim)
        }
    }
}

internal data class SmoothedFrameResult(
    val frame: EqEngineFrame,
    val effectiveActive: Boolean,
    val isRampActive: Boolean,
    val holdMbcOff: Boolean = false,
)

internal class DspGainSmoother {

    private var bypassRamp: Float = 0f
    private var eqRamp: Float = 0f
    private var inMatchedBypass: Boolean = false
    private val currentPreEq31 = FloatArray(PRE_EQ_31_BAND_COUNT)
    private val currentPreEq10 = FloatArray(BAND_COUNT_10)
    private val currentPostEq10 = FloatArray(BAND_COUNT_10)
    private val currentTotalEq10 = FloatArray(BAND_COUNT_10)
    private var currentInputGainDb: Float = 0f
    private var currentLeftTrimDb: Float = 0f
    private var currentRightTrimDb: Float = 0f

    fun reset() {
        bypassRamp = 0f
        eqRamp = 0f
        inMatchedBypass = false
        currentPreEq31.fill(0f)
        currentPreEq10.fill(0f)
        currentPostEq10.fill(0f)
        currentTotalEq10.fill(0f)
        currentInputGainDb = 0f
        currentLeftTrimDb = 0f
        currentRightTrimDb = 0f
    }

    fun step(
        targetFrame: EqEngineFrame,
        active: Boolean,
        matchedBypass: Boolean = false,
    ): SmoothedFrameResult {
        if (active) {
            inMatchedBypass = false
        } else if (matchedBypass) {
            inMatchedBypass = true
        }
        val holdMbcOff = matchedBypass || inMatchedBypass

        val isEngaged = active || matchedBypass
        val prevBypassRamp = bypassRamp
        bypassRamp = if (isEngaged) {
            (prevBypassRamp + RAMP_STEP).coerceIn(0f, 1f)
        } else {
            (prevBypassRamp - RAMP_STEP).coerceIn(0f, 1f)
        }

        val prevEqRamp = eqRamp
        eqRamp = if (active) {
            (prevEqRamp + RAMP_STEP).coerceIn(0f, 1f)
        } else {
            (prevEqRamp - RAMP_STEP).coerceIn(0f, 1f)
        }

        if (!isEngaged && bypassRamp <= RAMP_DISABLE_THRESHOLD) {
            reset()
            return SmoothedFrameResult(
                frame = targetFrame.copy(
                    preEqDb = FloatArray(BAND_COUNT_10),
                    postEqDb = FloatArray(BAND_COUNT_10),
                    totalEqDb = FloatArray(BAND_COUNT_10),
                    inputGainDb = 0f,
                    preEq31Db = FloatArray(PRE_EQ_31_BAND_COUNT),
                    leftBalanceTrimDb = 0f,
                    rightBalanceTrimDb = 0f,
                    totalEq31Db = FloatArray(PRE_EQ_31_BAND_COUNT),
                ),
                effectiveActive = false,
                isRampActive = false,
                holdMbcOff = holdMbcOff,
            )
        }

        // Fade out proportionally from current smoothed state so pre-zeroed bypass frames still ramp smoothly.
        val rawFadeScale = if (!isEngaged && prevBypassRamp > RAMP_DISABLE_THRESHOLD) {
            (bypassRamp / prevBypassRamp).coerceIn(0f, 1f)
        } else {
            1f
        }

        // Fade EQ down proportionally during matched bypass while holding inputGain attenuation active.
        val eqFadeOutScale = if (
            !active &&
            matchedBypass &&
            prevEqRamp > RAMP_DISABLE_THRESHOLD &&
            eqRamp > RAMP_DISABLE_THRESHOLD
        ) {
            (eqRamp / prevEqRamp).coerceIn(0f, 1f)
        } else {
            1f
        }

        var needsAnotherTick = when {
            active -> bypassRamp < RAMP_SETTLED_THRESHOLD || eqRamp < RAMP_SETTLED_THRESHOLD
            matchedBypass -> bypassRamp < RAMP_SETTLED_THRESHOLD || eqRamp > RAMP_DISABLE_THRESHOLD
            else -> true
        }

        fun stepEqScalar(current: Float, rawTarget: Float): Float {
            if (!isEngaged) {
                return current * rawFadeScale
            }
            if (!active) {
                return if (eqRamp > RAMP_DISABLE_THRESHOLD && prevEqRamp > RAMP_DISABLE_THRESHOLD) {
                    current * eqFadeOutScale
                } else {
                    val next = slewToward(current, 0f, MAX_SLEW_STEP_DB)
                    if (abs(next) > SETTLE_EPSILON_DB) {
                        needsAnotherTick = true
                    }
                    next
                }
            }
            val desired = sanitizeDb(rawTarget) * eqRamp
            val next = slewToward(current, desired, MAX_SLEW_STEP_DB)
            if (abs(next - desired) > SETTLE_EPSILON_DB) {
                needsAnotherTick = true
            }
            return next
        }

        fun stepInputGain(current: Float, rawTarget: Float): Float {
            if (!isEngaged) {
                return current * rawFadeScale
            }
            val targetGain = if (active) {
                sanitizeDb(rawTarget)
            } else {
                sanitizeDb(targetFrame.bypassMatchGainDb).coerceAtMost(0f)
            }
            val desired = targetGain * bypassRamp
            val next = slewToward(current, desired, MAX_SLEW_STEP_DB)
            if (abs(next - desired) > SETTLE_EPSILON_DB) {
                needsAnotherTick = true
            }
            return next
        }

        fun slewArray(current: FloatArray, target: FloatArray, count: Int): FloatArray {
            for (i in 0 until count) {
                current[i] = stepEqScalar(current[i], target.getOrElse(i) { 0f })
            }
            return current.copyOf()
        }

        val outPre31 = slewArray(currentPreEq31, targetFrame.preEq31Db, PRE_EQ_31_BAND_COUNT)
        val outPre10 = slewArray(currentPreEq10, targetFrame.preEqDb, BAND_COUNT_10)
        val outPost10 = slewArray(currentPostEq10, targetFrame.postEqDb, BAND_COUNT_10)
        val outTotal10 = slewArray(currentTotalEq10, targetFrame.totalEqDb, BAND_COUNT_10)
        currentInputGainDb = stepInputGain(currentInputGainDb, targetFrame.inputGainDb)
        currentLeftTrimDb = stepEqScalar(currentLeftTrimDb, targetFrame.leftBalanceTrimDb)
        currentRightTrimDb = stepEqScalar(currentRightTrimDb, targetFrame.rightBalanceTrimDb)

        return SmoothedFrameResult(
            frame = targetFrame.copy(
                preEqDb = outPre10,
                postEqDb = outPost10,
                totalEqDb = outTotal10,
                inputGainDb = currentInputGainDb,
                preEq31Db = outPre31,
                leftBalanceTrimDb = currentLeftTrimDb,
                rightBalanceTrimDb = currentRightTrimDb,
            ),
            effectiveActive = true,
            isRampActive = needsAnotherTick,
            holdMbcOff = holdMbcOff,
        )
    }

    private fun slewToward(current: Float, target: Float, maxStep: Float): Float {
        val delta = target - current
        return when {
            delta > maxStep -> current + maxStep
            delta < -maxStep -> current - maxStep
            else -> target
        }
    }

    private companion object {
        const val PRE_EQ_31_BAND_COUNT = 31
        const val BAND_COUNT_10 = 10
        const val RAMP_STEP = 0.34f
        const val RAMP_DISABLE_THRESHOLD = 0.01f
        const val RAMP_SETTLED_THRESHOLD = 0.999f
        const val MAX_SLEW_STEP_DB = 3.0f
        const val SETTLE_EPSILON_DB = 0.02f
    }
}

internal data class DynamicsWritePlan(
    val writeEnabled: Boolean,
    val targetEnabled: Boolean,
    val writeLeftInputGain: Boolean,
    val writeRightInputGain: Boolean,
    val writePreEq: Boolean,
    val writePostEq: Boolean,
    val writeMbc: Boolean,
) {
    val hasAnyWrite: Boolean
        get() = writeEnabled ||
            writeLeftInputGain ||
            writeRightInputGain ||
            writePreEq ||
            writePostEq ||
            writeMbc
}

internal class DspDirtyTracker(
    private val preEqBandCount: Int,
    private val postEqBandCount: Int = 10,
    thresholdDb: Float = 0.05f,
) {
    private val lastPreEq = FloatArray(preEqBandCount)
    private val lastPostEq = FloatArray(postEqBandCount)
    private var hasPreEqWritten = false
    private var hasPostEqWritten = false
    private var lastLeftInputGainDb = Float.NaN
    private var lastRightInputGainDb = Float.NaN
    private var lastMbcMode: MbcMode? = null
    private var lastDeEsserMode: DeEsserMode? = null
    private var lastEnabled: Boolean? = null

    // Tiny IEEE-754 guard ensures exact 0.05 dB steps like 1.0f -> 1.05f do not miss the threshold.
    private val effectiveThreshold = (thresholdDb - 1e-5f).coerceAtLeast(0f)

    fun evaluate(
        preEq: FloatArray,
        postEq: FloatArray,
        leftInputGainDb: Float,
        rightInputGainDb: Float,
        mbcMode: MbcMode,
        deEsserMode: DeEsserMode = DeEsserMode.OFF,
        active: Boolean,
    ): DynamicsWritePlan {
        if (!active) {
            return DynamicsWritePlan(
                writeEnabled = lastEnabled != false,
                targetEnabled = false,
                writeLeftInputGain = false,
                writeRightInputGain = false,
                writePreEq = false,
                writePostEq = false,
                writeMbc = false,
            )
        }
        return DynamicsWritePlan(
            writeEnabled = lastEnabled != true,
            targetEnabled = true,
            writeLeftInputGain = !lastLeftInputGainDb.isFinite() ||
                abs(leftInputGainDb - lastLeftInputGainDb) >= effectiveThreshold,
            writeRightInputGain = !lastRightInputGainDb.isFinite() ||
                abs(rightInputGainDb - lastRightInputGainDb) >= effectiveThreshold,
            writePreEq = !hasPreEqWritten || arrayDiffers(preEq, lastPreEq, preEqBandCount),
            writePostEq = !hasPostEqWritten || arrayDiffers(postEq, lastPostEq, postEqBandCount),
            writeMbc = lastMbcMode != mbcMode || lastDeEsserMode != deEsserMode,
        )
    }

    fun markLeftInputGainWritten(gainDb: Float) {
        lastLeftInputGainDb = gainDb
    }

    fun markRightInputGainWritten(gainDb: Float) {
        lastRightInputGainDb = gainDb
    }

    fun markPreEqWritten(preEq: FloatArray) {
        for (i in 0 until preEqBandCount) {
            lastPreEq[i] = sanitizeDb(preEq.getOrElse(i) { 0f })
        }
        hasPreEqWritten = true
    }

    fun markPostEqWritten(postEq: FloatArray) {
        for (i in 0 until postEqBandCount) {
            lastPostEq[i] = sanitizeDb(postEq.getOrElse(i) { 0f })
        }
        hasPostEqWritten = true
    }

    fun markMbcWritten(mbcMode: MbcMode, deEsserMode: DeEsserMode = DeEsserMode.OFF) {
        lastMbcMode = mbcMode
        lastDeEsserMode = deEsserMode
    }

    fun markEnabledWritten(enabled: Boolean) {
        lastEnabled = enabled
    }

    fun evaluateAndCommit(
        preEq: FloatArray,
        postEq: FloatArray,
        leftInputGainDb: Float,
        rightInputGainDb: Float,
        mbcMode: MbcMode,
        deEsserMode: DeEsserMode = DeEsserMode.OFF,
        active: Boolean,
    ): DynamicsWritePlan {
        val plan = evaluate(
            preEq = preEq,
            postEq = postEq,
            leftInputGainDb = leftInputGainDb,
            rightInputGainDb = rightInputGainDb,
            mbcMode = mbcMode,
            deEsserMode = deEsserMode,
            active = active,
        )
        if (plan.writeLeftInputGain) markLeftInputGainWritten(leftInputGainDb)
        if (plan.writeRightInputGain) markRightInputGainWritten(rightInputGainDb)
        if (plan.writePreEq) markPreEqWritten(preEq)
        if (plan.writePostEq) markPostEqWritten(postEq)
        if (plan.writeMbc) markMbcWritten(mbcMode, deEsserMode)
        if (plan.writeEnabled) markEnabledWritten(plan.targetEnabled)
        return plan
    }

    private fun arrayDiffers(next: FloatArray, last: FloatArray, count: Int): Boolean {
        for (i in 0 until count) {
            val value = sanitizeDb(next.getOrElse(i) { 0f })
            if (abs(value - last[i]) >= effectiveThreshold) {
                return true
            }
        }
        return false
    }
}

internal data class LegacyWritePlan(
    val writeEnabled: Boolean,
    val targetEnabled: Boolean,
    val dirtyBands: List<Int>,
)

internal class LegacyDirtyTracker(
    private val bandCount: Int,
    private val thresholdMb: Int = 5,
) {
    private val lastBandsMb = IntArray(bandCount) { Int.MIN_VALUE }
    private var lastEnabled: Boolean? = null

    fun evaluate(targetBandsMb: IntArray, active: Boolean): LegacyWritePlan {
        if (!active) {
            return LegacyWritePlan(
                writeEnabled = lastEnabled != false,
                targetEnabled = false,
                dirtyBands = emptyList(),
            )
        }
        val dirty = mutableListOf<Int>()
        for (i in 0 until bandCount) {
            val target = targetBandsMb.getOrElse(i) { 0 }
            val previous = lastBandsMb[i]
            if (previous == Int.MIN_VALUE || abs(target - previous) >= thresholdMb) {
                dirty += i
            }
        }
        return LegacyWritePlan(
            writeEnabled = lastEnabled != true,
            targetEnabled = true,
            dirtyBands = dirty,
        )
    }

    fun markBandWritten(band: Int, levelMb: Int) {
        if (band in 0 until bandCount) {
            lastBandsMb[band] = levelMb
        }
    }

    fun markEnabledWritten(enabled: Boolean) {
        lastEnabled = enabled
    }

    fun evaluateAndCommit(targetBandsMb: IntArray, active: Boolean): LegacyWritePlan {
        val plan = evaluate(targetBandsMb, active)
        for (band in plan.dirtyBands) {
            markBandWritten(band, targetBandsMb.getOrElse(band) { 0 })
        }
        if (plan.writeEnabled) {
            markEnabledWritten(plan.targetEnabled)
        }
        return plan
    }
}

internal data class MbcProfileConfig(
    val enabled: Boolean,
    val ratios: FloatArray,
    val thresholdsDb: FloatArray,
    val kneesDb: FloatArray,
    val attacksMs: FloatArray,
    val releasesMs: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MbcProfileConfig) return false
        return enabled == other.enabled &&
            ratios.contentEquals(other.ratios) &&
            thresholdsDb.contentEquals(other.thresholdsDb) &&
            kneesDb.contentEquals(other.kneesDb) &&
            attacksMs.contentEquals(other.attacksMs) &&
            releasesMs.contentEquals(other.releasesMs)
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + ratios.contentHashCode()
        result = 31 * result + thresholdsDb.contentHashCode()
        result = 31 * result + kneesDb.contentHashCode()
        result = 31 * result + attacksMs.contentHashCode()
        result = 31 * result + releasesMs.contentHashCode()
        return result
    }
}

object AndroidDspBackendFactory : DspBackendFactory {

    private const val EFFECT_PRIORITY = 0
    private const val CHANNEL_COUNT = 2
    private const val POST_EQ_BAND_COUNT = 10
    private const val DE_ESSER_BAND_INDEX = 3

    // Upper band edges centered geometrically around ISO_FREQUENCIES_HZ (31..16000 Hz)
    private val EQ_UPPER_CUTOFFS_HZ = floatArrayOf(
        44f, 88f, 177f, 355f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f,
    )
    internal val MBC_5_CUTOFFS_HZ = floatArrayOf(180f, 800f, 3500f, 9500f, 20000f)
    internal val MBC_4_CUTOFFS_HZ = floatArrayOf(180f, 800f, 4000f, 20000f)

    private val OFF_5_RATIOS = floatArrayOf(1f, 1f, 1f, 1f, 1f)
    private val OFF_5_THRESHOLDS_DB = floatArrayOf(0f, 0f, 0f, 0f, 0f)
    private val OFF_5_KNEES_DB = floatArrayOf(0f, 0f, 0f, 0f, 0f)
    private val OFF_5_ATTACKS_MS = floatArrayOf(20f, 20f, 20f, 20f, 20f)
    private val OFF_5_RELEASES_MS = floatArrayOf(100f, 100f, 100f, 100f, 100f)

    private val SUBTLE_5_RATIOS = floatArrayOf(1.45f, 1.30f, 1.30f, 1.40f, 1.35f)
    private val SUBTLE_5_THRESHOLDS_DB = floatArrayOf(-18f, -16f, -16f, -17f, -17f)
    private val SUBTLE_5_KNEES_DB = floatArrayOf(8f, 8f, 8f, 8f, 8f)
    private val SUBTLE_5_ATTACKS_MS = floatArrayOf(25f, 18f, 14f, 12f, 12f)
    private val SUBTLE_5_RELEASES_MS = floatArrayOf(140f, 120f, 100f, 90f, 90f)

    private val PUNCHY_5_RATIOS = floatArrayOf(2.0f, 1.65f, 1.65f, 1.85f, 1.70f)
    private val PUNCHY_5_THRESHOLDS_DB = floatArrayOf(-15f, -13f, -13f, -14f, -14f)
    private val PUNCHY_5_KNEES_DB = floatArrayOf(5f, 5f, 5f, 5f, 5f)
    private val PUNCHY_5_ATTACKS_MS = floatArrayOf(18f, 14f, 10f, 9f, 9f)
    private val PUNCHY_5_RELEASES_MS = floatArrayOf(110f, 95f, 85f, 75f, 75f)

    private val OFF_4_RATIOS = floatArrayOf(1f, 1f, 1f, 1f)
    private val OFF_4_THRESHOLDS_DB = floatArrayOf(0f, 0f, 0f, 0f)
    private val OFF_4_KNEES_DB = floatArrayOf(0f, 0f, 0f, 0f)
    private val OFF_4_ATTACKS_MS = floatArrayOf(20f, 20f, 20f, 20f)
    private val OFF_4_RELEASES_MS = floatArrayOf(100f, 100f, 100f, 100f)

    private val SUBTLE_4_RATIOS = floatArrayOf(1.45f, 1.30f, 1.30f, 1.45f)
    private val SUBTLE_4_THRESHOLDS_DB = floatArrayOf(-18f, -16f, -16f, -17f)
    private val SUBTLE_4_KNEES_DB = floatArrayOf(8f, 8f, 8f, 8f)
    private val SUBTLE_4_ATTACKS_MS = floatArrayOf(25f, 18f, 14f, 12f)
    private val SUBTLE_4_RELEASES_MS = floatArrayOf(140f, 120f, 100f, 90f)

    private val PUNCHY_4_RATIOS = floatArrayOf(2.0f, 1.65f, 1.65f, 2.0f)
    private val PUNCHY_4_THRESHOLDS_DB = floatArrayOf(-15f, -13f, -13f, -14f)
    private val PUNCHY_4_KNEES_DB = floatArrayOf(5f, 5f, 5f, 5f)
    private val PUNCHY_4_ATTACKS_MS = floatArrayOf(18f, 14f, 10f, 9f)
    private val PUNCHY_4_RELEASES_MS = floatArrayOf(110f, 95f, 85f, 75f)

    private const val DE_ESSER_GENTLE_RATIO = 2.4f
    private const val DE_ESSER_GENTLE_THRESHOLD_DB = -20f
    private const val DE_ESSER_GENTLE_KNEE_DB = 6f
    private const val DE_ESSER_GENTLE_ATTACK_MS = 4f
    private const val DE_ESSER_GENTLE_RELEASE_MS = 55f

    private const val DE_ESSER_STRONG_RATIO = 3.6f
    private const val DE_ESSER_STRONG_THRESHOLD_DB = -24f
    private const val DE_ESSER_STRONG_KNEE_DB = 4f
    private const val DE_ESSER_STRONG_ATTACK_MS = 2.5f
    private const val DE_ESSER_STRONG_RELEASE_MS = 45f

    private const val MBC_NOISE_GATE_DB = -90f
    private const val MBC_EXPANDER_RATIO = 1.0f

    private const val LIMITER_CEILING_DB = -0.5f
    private const val LIMITER_RATIO = 10.0f
    private const val LIMITER_ATTACK_MS = 1.0f
    private const val LIMITER_RELEASE_MS = 60.0f
    private const val LIMITER_POST_GAIN_DB = 0.0f

    internal fun mbcConfigFor(
        mbcMode: MbcMode,
        deEsserMode: DeEsserMode = DeEsserMode.OFF,
        mbcBandCount: Int = DspEffectController.HIGH_RES_MBC_BAND_COUNT,
    ): MbcProfileConfig {
        val useFiveBands = when (mbcBandCount) {
            DspEffectController.HIGH_RES_MBC_BAND_COUNT -> true
            DspEffectController.STANDARD_MBC_BAND_COUNT -> false
            else -> throw IllegalArgumentException("Unsupported MBC band count: $mbcBandCount")
        }

        val base = when {
            useFiveBands && mbcMode == MbcMode.OFF -> MbcProfileConfig(
                enabled = deEsserMode != DeEsserMode.OFF,
                ratios = OFF_5_RATIOS.copyOf(),
                thresholdsDb = OFF_5_THRESHOLDS_DB.copyOf(),
                kneesDb = OFF_5_KNEES_DB.copyOf(),
                attacksMs = OFF_5_ATTACKS_MS.copyOf(),
                releasesMs = OFF_5_RELEASES_MS.copyOf(),
            )
            useFiveBands && mbcMode == MbcMode.SUBTLE -> MbcProfileConfig(
                enabled = true,
                ratios = SUBTLE_5_RATIOS.copyOf(),
                thresholdsDb = SUBTLE_5_THRESHOLDS_DB.copyOf(),
                kneesDb = SUBTLE_5_KNEES_DB.copyOf(),
                attacksMs = SUBTLE_5_ATTACKS_MS.copyOf(),
                releasesMs = SUBTLE_5_RELEASES_MS.copyOf(),
            )
            useFiveBands -> MbcProfileConfig(
                enabled = true,
                ratios = PUNCHY_5_RATIOS.copyOf(),
                thresholdsDb = PUNCHY_5_THRESHOLDS_DB.copyOf(),
                kneesDb = PUNCHY_5_KNEES_DB.copyOf(),
                attacksMs = PUNCHY_5_ATTACKS_MS.copyOf(),
                releasesMs = PUNCHY_5_RELEASES_MS.copyOf(),
            )
            mbcMode == MbcMode.OFF -> MbcProfileConfig(
                enabled = deEsserMode != DeEsserMode.OFF,
                ratios = OFF_4_RATIOS.copyOf(),
                thresholdsDb = OFF_4_THRESHOLDS_DB.copyOf(),
                kneesDb = OFF_4_KNEES_DB.copyOf(),
                attacksMs = OFF_4_ATTACKS_MS.copyOf(),
                releasesMs = OFF_4_RELEASES_MS.copyOf(),
            )
            mbcMode == MbcMode.SUBTLE -> MbcProfileConfig(
                enabled = true,
                ratios = SUBTLE_4_RATIOS.copyOf(),
                thresholdsDb = SUBTLE_4_THRESHOLDS_DB.copyOf(),
                kneesDb = SUBTLE_4_KNEES_DB.copyOf(),
                attacksMs = SUBTLE_4_ATTACKS_MS.copyOf(),
                releasesMs = SUBTLE_4_RELEASES_MS.copyOf(),
            )
            else -> MbcProfileConfig(
                enabled = true,
                ratios = PUNCHY_4_RATIOS.copyOf(),
                thresholdsDb = PUNCHY_4_THRESHOLDS_DB.copyOf(),
                kneesDb = PUNCHY_4_KNEES_DB.copyOf(),
                attacksMs = PUNCHY_4_ATTACKS_MS.copyOf(),
                releasesMs = PUNCHY_4_RELEASES_MS.copyOf(),
            )
        }

        // Override only Band 3 (3.5-9.5 kHz in 5-band mode) so Band 4 (9.5-20 kHz) air remains untouched.
        when (deEsserMode) {
            DeEsserMode.OFF -> Unit
            DeEsserMode.GENTLE -> {
                base.ratios[DE_ESSER_BAND_INDEX] = DE_ESSER_GENTLE_RATIO
                base.thresholdsDb[DE_ESSER_BAND_INDEX] = DE_ESSER_GENTLE_THRESHOLD_DB
                base.kneesDb[DE_ESSER_BAND_INDEX] = DE_ESSER_GENTLE_KNEE_DB
                base.attacksMs[DE_ESSER_BAND_INDEX] = DE_ESSER_GENTLE_ATTACK_MS
                base.releasesMs[DE_ESSER_BAND_INDEX] = DE_ESSER_GENTLE_RELEASE_MS
            }
            DeEsserMode.STRONG -> {
                base.ratios[DE_ESSER_BAND_INDEX] = DE_ESSER_STRONG_RATIO
                base.thresholdsDb[DE_ESSER_BAND_INDEX] = DE_ESSER_STRONG_THRESHOLD_DB
                base.kneesDb[DE_ESSER_BAND_INDEX] = DE_ESSER_STRONG_KNEE_DB
                base.attacksMs[DE_ESSER_BAND_INDEX] = DE_ESSER_STRONG_ATTACK_MS
                base.releasesMs[DE_ESSER_BAND_INDEX] = DE_ESSER_STRONG_RELEASE_MS
            }
        }
        return base
    }

    override fun createDynamicsProcessing(
        sessionId: Int,
        preEqBandCount: Int,
        mbcBandCount: Int,
    ): DspSessionHandle {
        val preCutoffs = when (preEqBandCount) {
            DspEffectController.HIGH_RES_PRE_EQ_BAND_COUNT -> PleasureProfiles.ISO_31_UPPER_CUTOFFS_HZ
            DspEffectController.STANDARD_PRE_EQ_BAND_COUNT -> EQ_UPPER_CUTOFFS_HZ
            else -> throw IllegalArgumentException("Unsupported PreEQ band count: $preEqBandCount")
        }
        val mbcCutoffs = when (mbcBandCount) {
            DspEffectController.HIGH_RES_MBC_BAND_COUNT -> MBC_5_CUTOFFS_HZ
            DspEffectController.STANDARD_MBC_BAND_COUNT -> MBC_4_CUTOFFS_HZ
            else -> throw IllegalArgumentException("Unsupported MBC band count: $mbcBandCount")
        }

        val preEq = createIsoEqStage(preCutoffs)
        val postEq = createIsoEqStage(EQ_UPPER_CUTOFFS_HZ)
        val mbcBands = createMbcBands(MbcMode.SUBTLE, DeEsserMode.OFF, mbcBandCount, mbcCutoffs)
        val mbc = DynamicsProcessing.Mbc(true, true, mbcBandCount).apply {
            for (i in 0 until mbcBandCount) {
                setBand(i, mbcBands[i])
            }
        }
        val limiter = DynamicsProcessing.Limiter(
            /* inUse = */ true,
            /* enabled = */ true,
            /* linkGroup = */ 0,
            /* attackTime = */ LIMITER_ATTACK_MS,
            /* releaseTime = */ LIMITER_RELEASE_MS,
            /* ratio = */ LIMITER_RATIO,
            /* threshold = */ LIMITER_CEILING_DB,
            /* postGain = */ LIMITER_POST_GAIN_DB,
        )

        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNEL_COUNT,
            true,
            preEqBandCount,
            true,
            mbcBandCount,
            true,
            POST_EQ_BAND_COUNT,
            true,
        )
            .setPreEqAllChannelsTo(preEq)
            .setMbcAllChannelsTo(mbc)
            .setPostEqAllChannelsTo(postEq)
            .setLimiterAllChannelsTo(limiter)
            .setInputGainAllChannelsTo(0f)
            .build()

        var dp: DynamicsProcessing? = null
        try {
            dp = DynamicsProcessing(EFFECT_PRIORITY, sessionId, config)
            // Probe enablement up front so OEMs that reject 31/5 or DP trigger fallback cleanly.
            val probeStatus = dp.setEnabled(true)
            if (probeStatus != AudioEffect.SUCCESS) {
                throw IllegalStateException("DynamicsProcessing enable probe failed with status $probeStatus")
            }
            dp.setEnabled(false)
            return AndroidDynamicsProcessingHandle(
                dp = dp,
                sessionId = sessionId,
                preEqBandCount = preEqBandCount,
                mbcBandCount = mbcBandCount,
                preCutoffsHz = preCutoffs,
                preEq = preEq,
                postEq = postEq,
                mbc = mbc,
                mbcBands = mbcBands,
            )
        } catch (e: RuntimeException) {
            try {
                dp?.release()
            } catch (_: RuntimeException) {
            }
            throw e
        }
    }

    override fun createLegacyEqualizer(sessionId: Int): DspSessionHandle {
        var equalizer: Equalizer? = null
        var loudnessEnhancer: LoudnessEnhancer? = null
        try {
            equalizer = Equalizer(EFFECT_PRIORITY, sessionId)
            val bandCount = equalizer.numberOfBands.toInt()
            if (bandCount <= 0) {
                throw UnsupportedOperationException("Legacy Equalizer exposed 0 bands")
            }
            val range = equalizer.bandLevelRange
            val minMb = range[0].toInt()
            val maxMb = range[1].toInt()
            val centerHz = FloatArray(bandCount) { b ->
                (equalizer.getCenterFreq(b.toShort()) / 1000f).coerceAtLeast(20f)
            }

            loudnessEnhancer = try {
                LoudnessEnhancer(sessionId).apply {
                    setTargetGain(0)
                    enabled = false
                }
            } catch (_: RuntimeException) {
                null
            }

            return AndroidLegacyEqualizerHandle(
                equalizer = equalizer,
                loudnessEnhancer = loudnessEnhancer,
                sessionId = sessionId,
                bandCenterHz = centerHz,
                minMb = minMb,
                maxMb = maxMb,
            )
        } catch (e: RuntimeException) {
            try {
                loudnessEnhancer?.release()
            } catch (_: RuntimeException) {
            }
            try {
                equalizer?.release()
            } catch (_: RuntimeException) {
            }
            throw e
        }
    }

    private fun createIsoEqStage(cutoffsHz: FloatArray): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, true, cutoffsHz.size)
        for (i in cutoffsHz.indices) {
            eq.setBand(
                i,
                DynamicsProcessing.EqBand(
                    /* enabled = */ true,
                    /* cutoffFrequency = */ cutoffsHz[i],
                    /* gain = */ 0f,
                ),
            )
        }
        return eq
    }

    private fun createMbcBands(
        mbcMode: MbcMode,
        deEsserMode: DeEsserMode,
        mbcBandCount: Int,
        mbcCutoffsHz: FloatArray,
    ): Array<DynamicsProcessing.MbcBand> {
        val profile = mbcConfigFor(mbcMode, deEsserMode, mbcBandCount)
        return Array(mbcBandCount) { i ->
            DynamicsProcessing.MbcBand(
                /* enabled = */ true,
                /* cutoffFrequency = */ mbcCutoffsHz[i],
                /* attackTime = */ profile.attacksMs[i],
                /* releaseTime = */ profile.releasesMs[i],
                /* ratio = */ profile.ratios[i],
                /* threshold = */ profile.thresholdsDb[i],
                /* kneeWidth = */ profile.kneesDb[i],
                /* noiseGateThreshold = */ MBC_NOISE_GATE_DB,
                /* expanderRatio = */ MBC_EXPANDER_RATIO,
                /* preGain = */ 0f,
                /* postGain = */ 0f,
            )
        }
    }

    private class AndroidDynamicsProcessingHandle(
        private val dp: DynamicsProcessing,
        override val sessionId: Int,
        override val preEqBandCount: Int,
        override val mbcBandCount: Int,
        preCutoffsHz: FloatArray,
        private val preEq: DynamicsProcessing.Eq,
        private val postEq: DynamicsProcessing.Eq,
        private val mbc: DynamicsProcessing.Mbc,
        private val mbcBands: Array<DynamicsProcessing.MbcBand>,
    ) : DspSessionHandle {

        override val backend: DspBackend = DspBackend.DYNAMICS_PROCESSING

        private val preBands = Array(preEqBandCount) { i ->
            DynamicsProcessing.EqBand(true, preCutoffsHz[i], 0f)
        }
        private val postBands = Array(POST_EQ_BAND_COUNT) { i ->
            DynamicsProcessing.EqBand(true, EQ_UPPER_CUTOFFS_HZ[i], 0f)
        }
        private val dirtyTracker = DspDirtyTracker(
            preEqBandCount = preEqBandCount,
            postEqBandCount = POST_EQ_BAND_COUNT,
        )

        override fun apply(
            frame: EqEngineFrame,
            mbcMode: MbcMode,
            deEsserMode: DeEsserMode,
            active: Boolean,
        ) {
            val preSource = if (preEqBandCount == DspEffectController.HIGH_RES_PRE_EQ_BAND_COUNT) {
                frame.preEq31Db
            } else {
                frame.preEqDb
            }
            val (leftInputGain, rightInputGain) = DspEffectController.effectiveChannelInputGains(frame)
            val plan = dirtyTracker.evaluate(
                preEq = preSource,
                postEq = frame.postEqDb,
                leftInputGainDb = leftInputGain,
                rightInputGainDb = rightInputGain,
                mbcMode = mbcMode,
                deEsserMode = deEsserMode,
                active = active,
            )

            if (!active) {
                if (plan.writeEnabled) {
                    setDpEnabled(false)
                    dirtyTracker.markEnabledWritten(false)
                }
                return
            }

            if (plan.writeLeftInputGain) {
                dp.setInputGainbyChannel(0, leftInputGain)
                dirtyTracker.markLeftInputGainWritten(leftInputGain)
            }
            if (plan.writeRightInputGain) {
                dp.setInputGainbyChannel(1, rightInputGain)
                dirtyTracker.markRightInputGainWritten(rightInputGain)
            }

            if (plan.writePreEq) {
                for (i in 0 until preEqBandCount) {
                    preBands[i].gain = sanitizeDb(preSource.getOrElse(i) { 0f })
                    preEq.setBand(i, preBands[i])
                }
                dp.setPreEqAllChannelsTo(preEq)
                dirtyTracker.markPreEqWritten(preSource)
            }

            if (plan.writePostEq) {
                for (i in 0 until POST_EQ_BAND_COUNT) {
                    postBands[i].gain = sanitizeDb(frame.postEqDb.getOrElse(i) { 0f })
                    postEq.setBand(i, postBands[i])
                }
                dp.setPostEqAllChannelsTo(postEq)
                dirtyTracker.markPostEqWritten(frame.postEqDb)
            }

            if (plan.writeMbc) {
                applyMbcMode(mbcMode, deEsserMode)
                dp.setMbcAllChannelsTo(mbc)
                dirtyTracker.markMbcWritten(mbcMode, deEsserMode)
            }

            if (plan.writeEnabled) {
                setDpEnabled(true)
                dirtyTracker.markEnabledWritten(true)
            }
        }

        private fun applyMbcMode(mbcMode: MbcMode, deEsserMode: DeEsserMode) {
            val profile = mbcConfigFor(mbcMode, deEsserMode, mbcBandCount)
            mbc.isEnabled = profile.enabled
            if (profile.enabled) {
                for (i in 0 until mbcBandCount) {
                    mbcBands[i].apply {
                        attackTime = profile.attacksMs[i]
                        releaseTime = profile.releasesMs[i]
                        ratio = profile.ratios[i]
                        threshold = profile.thresholdsDb[i]
                        kneeWidth = profile.kneesDb[i]
                    }
                    mbc.setBand(i, mbcBands[i])
                }
            }
        }

        private fun setDpEnabled(enabled: Boolean) {
            val status = dp.setEnabled(enabled)
            if (status != AudioEffect.SUCCESS) {
                throw IllegalStateException("DynamicsProcessing setEnabled($enabled) failed: $status")
            }
        }

        override fun release() {
            try {
                dp.enabled = false
            } catch (_: RuntimeException) {
            }
            dp.release()
        }
    }

    private class AndroidLegacyEqualizerHandle(
        private val equalizer: Equalizer,
        private val loudnessEnhancer: LoudnessEnhancer?,
        override val sessionId: Int,
        private val bandCenterHz: FloatArray,
        private val minMb: Int,
        private val maxMb: Int,
    ) : DspSessionHandle {

        override val backend: DspBackend = DspBackend.LEGACY_EQ
        override val preEqBandCount: Int = bandCenterHz.size
        override val mbcBandCount: Int = 0

        private val dirtyTracker = LegacyDirtyTracker(bandCount = bandCenterHz.size)
        private val targetBandsMb = IntArray(bandCenterHz.size)
        private var loudnessNeutralized = loudnessEnhancer == null

        override fun apply(
            frame: EqEngineFrame,
            mbcMode: MbcMode,
            deEsserMode: DeEsserMode,
            active: Boolean,
        ) {
            if (!active) {
                val plan = dirtyTracker.evaluate(targetBandsMb, active = false)
                if (plan.writeEnabled) {
                    equalizer.enabled = false
                    dirtyTracker.markEnabledWritten(false)
                }
                if (!loudnessNeutralized) {
                    try {
                        loudnessEnhancer?.enabled = false
                        loudnessNeutralized = true
                    } catch (_: RuntimeException) {
                    }
                }
                return
            }

            val preampShiftDb = frame.inputGainDb.takeIf { it.isFinite() }?.coerceAtMost(0f) ?: 0f
            for (band in bandCenterHz.indices) {
                val interpolatedDb = interpolateIsoCurveDb(frame.totalEqDb, bandCenterHz[band])
                val targetDb = interpolatedDb + preampShiftDb
                targetBandsMb[band] = (targetDb * 100f).roundToInt().coerceIn(minMb, maxMb)
            }

            val plan = dirtyTracker.evaluate(targetBandsMb, active = true)
            for (band in plan.dirtyBands) {
                val levelMb = targetBandsMb[band]
                equalizer.setBandLevel(band.toShort(), levelMb.toShort())
                dirtyTracker.markBandWritten(band, levelMb)
            }
            if (plan.writeEnabled) {
                equalizer.enabled = true
                dirtyTracker.markEnabledWritten(true)
            }

            // totalEqDb already includes dynamic loudness weighting; keep LoudnessEnhancer neutral.
            if (!loudnessNeutralized) {
                loudnessEnhancer?.let { enhancer ->
                    try {
                        enhancer.setTargetGain(0)
                        enhancer.enabled = false
                        loudnessNeutralized = true
                    } catch (_: RuntimeException) {
                    }
                }
            }
        }

        override fun release() {
            try {
                loudnessEnhancer?.enabled = false
            } catch (_: RuntimeException) {
            }
            try {
                loudnessEnhancer?.release()
            } catch (_: RuntimeException) {
            }
            try {
                equalizer.enabled = false
            } catch (_: RuntimeException) {
            }
            equalizer.release()
        }

        private fun interpolateIsoCurveDb(curveDb: FloatArray, targetHz: Float): Float {
            if (curveDb.isEmpty()) return 0f
            val iso = PleasureProfiles.ISO_FREQUENCIES_HZ
            if (targetHz <= iso.first()) return sanitizeDb(curveDb.getOrElse(0) { 0f })
            if (targetHz >= iso.last()) return sanitizeDb(curveDb.getOrElse(iso.lastIndex) { 0f })

            val logTarget = log2(targetHz)
            for (i in 0 until iso.lastIndex) {
                val f0 = iso[i].toFloat()
                val f1 = iso[i + 1].toFloat()
                if (targetHz <= f1) {
                    val log0 = log2(f0)
                    val log1 = log2(f1)
                    val t = ((logTarget - log0) / (log1 - log0)).coerceIn(0f, 1f)
                    val g0 = sanitizeDb(curveDb.getOrElse(i) { 0f })
                    val g1 = sanitizeDb(curveDb.getOrElse(i + 1) { 0f })
                    return g0 + t * (g1 - g0)
                }
            }
            return sanitizeDb(curveDb.getOrElse(iso.lastIndex) { 0f })
        }
    }
}

private fun sanitizeDb(value: Float): Float = if (value.isFinite()) value else 0f
