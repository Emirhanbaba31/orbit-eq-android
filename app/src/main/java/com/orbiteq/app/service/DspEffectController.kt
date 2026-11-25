package com.orbiteq.app.service

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import com.orbiteq.app.dsp.EqEngineFrame
import com.orbiteq.app.dsp.PleasureProfiles
import com.orbiteq.app.model.DspBackend
import kotlin.math.log2
import kotlin.math.roundToInt

interface DspSessionHandle {
    val backend: DspBackend
    val sessionId: Int
    fun apply(frame: EqEngineFrame, mbcEnabled: Boolean, active: Boolean)
    fun release()
}

interface DspBackendFactory {
    fun createDynamicsProcessing(sessionId: Int): DspSessionHandle
    fun createLegacyEqualizer(sessionId: Int): DspSessionHandle
}

class DspEffectController(
    private val factory: DspBackendFactory = AndroidDspBackendFactory,
) {
    private var activeHandle: DspSessionHandle? = null

    var currentBackend: DspBackend = DspBackend.NONE
        private set

    var currentSessionId: Int = 0
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
        return currentBackend
    }

    @Synchronized
    fun apply(frame: EqEngineFrame, mbcEnabled: Boolean, active: Boolean) {
        val handle = activeHandle ?: return
        try {
            handle.apply(frame, mbcEnabled, active)
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
        if (existing != null) {
            try {
                existing.release()
            } catch (_: RuntimeException) {
                // Ignore native release failure on already-closed sessions.
            }
        }
    }

    private fun attachWithFallback(sessionId: Int): DspSessionHandle? {
        try {
            return factory.createDynamicsProcessing(sessionId)
        } catch (_: RuntimeException) {
            // Fall through to legacy Equalizer backend.
        }
        return try {
            factory.createLegacyEqualizer(sessionId)
        } catch (_: RuntimeException) {
            null
        }
    }
}

object AndroidDspBackendFactory : DspBackendFactory {

    private const val EFFECT_PRIORITY = 0
    private const val CHANNEL_COUNT = 2
    private const val EQ_BAND_COUNT = 10
    private const val MBC_BAND_COUNT = 4

    // Upper band edges centered geometrically around ISO_FREQUENCIES_HZ (31..16000 Hz)
    private val EQ_UPPER_CUTOFFS_HZ = floatArrayOf(
        44f, 88f, 177f, 355f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f,
    )
    private val MBC_CUTOFFS_HZ = floatArrayOf(180f, 800f, 4000f, 20000f)
    private val MBC_RATIOS = floatArrayOf(1.8f, 1.5f, 1.5f, 1.8f)
    private val MBC_ATTACKS_MS = floatArrayOf(25f, 18f, 14f, 12f)
    private val MBC_RELEASES_MS = floatArrayOf(140f, 120f, 100f, 90f)
    private val MBC_THRESHOLDS_DB = floatArrayOf(-16f, -14f, -14f, -15f)
    private const val MBC_KNEE_DB = 6f
    private const val MBC_NOISE_GATE_DB = -90f
    private const val MBC_EXPANDER_RATIO = 1.0f

    private const val LIMITER_CEILING_DB = -0.5f
    private const val LIMITER_RATIO = 10.0f
    private const val LIMITER_ATTACK_MS = 1.0f
    private const val LIMITER_RELEASE_MS = 60.0f
    private const val LIMITER_POST_GAIN_DB = 0.0f

    override fun createDynamicsProcessing(sessionId: Int): DspSessionHandle {
        val preEq = createIsoEqStage()
        val postEq = createIsoEqStage()
        val mbc = createMbcStage()
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
            EQ_BAND_COUNT,
            true,
            MBC_BAND_COUNT,
            true,
            EQ_BAND_COUNT,
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
            // Probe enablement up front so OEMs that return an error code trigger LEGACY_EQ fallback.
            val probeStatus = dp.setEnabled(true)
            if (probeStatus != AudioEffect.SUCCESS) {
                throw IllegalStateException("DynamicsProcessing enable probe failed with status $probeStatus")
            }
            dp.setEnabled(false)
            return AndroidDynamicsProcessingHandle(
                dp = dp,
                sessionId = sessionId,
                preEq = preEq,
                postEq = postEq,
                mbc = mbc,
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

    private fun createIsoEqStage(): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, true, EQ_BAND_COUNT)
        for (i in 0 until EQ_BAND_COUNT) {
            eq.setBand(
                i,
                DynamicsProcessing.EqBand(
                    /* enabled = */ true,
                    /* cutoffFrequency = */ EQ_UPPER_CUTOFFS_HZ[i],
                    /* gain = */ 0f,
                ),
            )
        }
        return eq
    }

    private fun createMbcStage(): DynamicsProcessing.Mbc {
        val mbc = DynamicsProcessing.Mbc(true, true, MBC_BAND_COUNT)
        for (i in 0 until MBC_BAND_COUNT) {
            mbc.setBand(
                i,
                DynamicsProcessing.MbcBand(
                    /* enabled = */ true,
                    /* cutoffFrequency = */ MBC_CUTOFFS_HZ[i],
                    /* attackTime = */ MBC_ATTACKS_MS[i],
                    /* releaseTime = */ MBC_RELEASES_MS[i],
                    /* ratio = */ MBC_RATIOS[i],
                    /* threshold = */ MBC_THRESHOLDS_DB[i],
                    /* kneeWidth = */ MBC_KNEE_DB,
                    /* noiseGateThreshold = */ MBC_NOISE_GATE_DB,
                    /* expanderRatio = */ MBC_EXPANDER_RATIO,
                    /* preGain = */ 0f,
                    /* postGain = */ 0f,
                ),
            )
        }
        return mbc
    }

    private class AndroidDynamicsProcessingHandle(
        private val dp: DynamicsProcessing,
        override val sessionId: Int,
        private val preEq: DynamicsProcessing.Eq,
        private val postEq: DynamicsProcessing.Eq,
        private val mbc: DynamicsProcessing.Mbc,
    ) : DspSessionHandle {

        override val backend: DspBackend = DspBackend.DYNAMICS_PROCESSING

        private val preBands = Array(EQ_BAND_COUNT) { i ->
            DynamicsProcessing.EqBand(true, EQ_UPPER_CUTOFFS_HZ[i], 0f)
        }
        private val postBands = Array(EQ_BAND_COUNT) { i ->
            DynamicsProcessing.EqBand(true, EQ_UPPER_CUTOFFS_HZ[i], 0f)
        }
        private var lastEnabled: Boolean? = null
        private var lastMbcEnabled: Boolean? = null
        private var lastInputGainDb: Float = Float.NaN

        override fun apply(frame: EqEngineFrame, mbcEnabled: Boolean, active: Boolean) {
            if (!active) {
                if (lastEnabled != false) {
                    setDpEnabled(false)
                }
                return
            }

            val inputGain = sanitizeDb(frame.inputGainDb)
            if (inputGain != lastInputGainDb) {
                dp.setInputGainAllChannelsTo(inputGain)
                lastInputGainDb = inputGain
            }

            for (i in 0 until EQ_BAND_COUNT) {
                preBands[i].gain = sanitizeDb(frame.preEqDb.getOrElse(i) { 0f })
                preEq.setBand(i, preBands[i])
                postBands[i].gain = sanitizeDb(frame.postEqDb.getOrElse(i) { 0f })
                postEq.setBand(i, postBands[i])
            }
            dp.setPreEqAllChannelsTo(preEq)
            dp.setPostEqAllChannelsTo(postEq)

            if (lastMbcEnabled != mbcEnabled) {
                mbc.isEnabled = mbcEnabled
                dp.setMbcAllChannelsTo(mbc)
                lastMbcEnabled = mbcEnabled
            }

            if (lastEnabled != true) {
                setDpEnabled(true)
            }
        }

        private fun setDpEnabled(enabled: Boolean) {
            val status = dp.setEnabled(enabled)
            if (status != AudioEffect.SUCCESS) {
                throw IllegalStateException("DynamicsProcessing setEnabled($enabled) failed: $status")
            }
            lastEnabled = enabled
        }

        override fun release() {
            try {
                dp.enabled = false
            } catch (_: RuntimeException) {
            }
            dp.release()
        }

        private fun sanitizeDb(value: Float): Float = if (value.isFinite()) value else 0f
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

        override fun apply(frame: EqEngineFrame, mbcEnabled: Boolean, active: Boolean) {
            if (!active) {
                equalizer.enabled = false
                loudnessEnhancer?.enabled = false
                return
            }

            val preampShiftDb = frame.inputGainDb.takeIf { it.isFinite() }?.coerceAtMost(0f) ?: 0f
            for (band in bandCenterHz.indices) {
                val interpolatedDb = interpolateIsoCurveDb(frame.totalEqDb, bandCenterHz[band])
                val targetDb = interpolatedDb + preampShiftDb
                val levelMb = (targetDb * 100f).roundToInt().coerceIn(minMb, maxMb).toShort()
                equalizer.setBandLevel(band.toShort(), levelMb)
            }
            equalizer.enabled = true

            // totalEqDb already includes dynamic loudness weighting; keep LoudnessEnhancer neutral.
            loudnessEnhancer?.let { enhancer ->
                try {
                    enhancer.setTargetGain(0)
                    enhancer.enabled = false
                } catch (_: RuntimeException) {
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
            if (targetHz <= iso.first()) return finiteOrZero(curveDb.getOrElse(0) { 0f })
            if (targetHz >= iso.last()) return finiteOrZero(curveDb.getOrElse(iso.lastIndex) { 0f })

            val logTarget = log2(targetHz)
            for (i in 0 until iso.lastIndex) {
                val f0 = iso[i].toFloat()
                val f1 = iso[i + 1].toFloat()
                if (targetHz <= f1) {
                    val log0 = log2(f0)
                    val log1 = log2(f1)
                    val t = ((logTarget - log0) / (log1 - log0)).coerceIn(0f, 1f)
                    val g0 = finiteOrZero(curveDb.getOrElse(i) { 0f })
                    val g1 = finiteOrZero(curveDb.getOrElse(i + 1) { 0f })
                    return g0 + t * (g1 - g0)
                }
            }
            return finiteOrZero(curveDb.getOrElse(iso.lastIndex) { 0f })
        }

        private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f
    }
}
