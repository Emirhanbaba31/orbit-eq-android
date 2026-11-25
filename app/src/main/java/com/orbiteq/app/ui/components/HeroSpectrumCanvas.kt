package com.orbiteq.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbiteq.app.R
import com.orbiteq.app.dsp.PleasureProfiles
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.ui.icons.OrbitIcons
import com.orbiteq.app.ui.theme.LocalOrbitColors
import com.orbiteq.app.ui.theme.SpaceMonoTabularStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

private const val PLOT_MIN_DB = -15f
private const val PLOT_MAX_DB = 15f
private const val PREAMP_BAR_MAX_CUT_DB = 12f
private const val SPECTRUM_BIN_COUNT = 36
private const val EQ_BAND_COUNT = 10

private class SpectrumAnimBuffer {
    val displayBins = FloatArray(SPECTRUM_BIN_COUNT)
    val displayLive = FloatArray(EQ_BAND_COUNT)
    val displayTarget = FloatArray(EQ_BAND_COUNT)

    val targetBins = FloatArray(SPECTRUM_BIN_COUNT)
    val targetLive = FloatArray(EQ_BAND_COUNT)
    val targetCurve = FloatArray(EQ_BAND_COUNT)

    var dataVersion by mutableLongStateOf(0L)
    var frameTick by mutableLongStateOf(0L)
    private var lastFrameNanos: Long = 0L

    fun updateTargets(
        bins: List<Float>,
        live: List<Float>,
        target: List<Float>,
        activeSignal: Boolean,
    ) {
        var changed = false
        for (i in 0 until SPECTRUM_BIN_COUNT) {
            val next = if (activeSignal) sanitizeFinite(bins.getOrElse(i) { 0f }).coerceIn(0f, 1f) else 0f
            if (abs(next - targetBins[i]) > 1e-4f) {
                targetBins[i] = next
                changed = true
            }
        }
        for (i in 0 until EQ_BAND_COUNT) {
            val nextLive = if (activeSignal) {
                sanitizeFinite(live.getOrElse(i) { 0f }).coerceIn(PLOT_MIN_DB, PLOT_MAX_DB)
            } else {
                0f
            }
            if (abs(nextLive - targetLive[i]) > 1e-4f) {
                targetLive[i] = nextLive
                changed = true
            }
            val nextTarget = sanitizeFinite(target.getOrElse(i) { 0f }).coerceIn(PLOT_MIN_DB, PLOT_MAX_DB)
            if (abs(nextTarget - targetCurve[i]) > 1e-4f) {
                targetCurve[i] = nextTarget
                changed = true
            }
        }
        if (changed) {
            dataVersion++
        }
    }

    fun snapAllWithoutTick() {
        targetBins.copyInto(displayBins)
        targetLive.copyInto(displayLive)
        targetCurve.copyInto(displayTarget)
        lastFrameNanos = 0L
    }

    fun isSettled(): Boolean {
        for (i in 0 until SPECTRUM_BIN_COUNT) {
            if (abs(targetBins[i] - displayBins[i]) > 0.002f) return false
        }
        for (i in 0 until EQ_BAND_COUNT) {
            if (abs(targetLive[i] - displayLive[i]) > 0.02f) return false
            if (abs(targetCurve[i] - displayTarget[i]) > 0.02f) return false
        }
        return true
    }

    fun stepFrame(frameNanos: Long) {
        val dtSec = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        }
        lastFrameNanos = frameNanos

        val binAttackAlpha = 1f - exp(-dtSec * 24f)
        val binReleaseAlpha = 1f - exp(-dtSec * 12f)
        val curveAlpha = 1f - exp(-dtSec * 16f)

        for (i in 0 until SPECTRUM_BIN_COUNT) {
            val target = targetBins[i]
            val current = displayBins[i]
            val alpha = if (target > current) binAttackAlpha else binReleaseAlpha
            val next = current + alpha * (target - current)
            displayBins[i] = if (abs(target - next) < 0.0015f) target else next
        }
        for (i in 0 until EQ_BAND_COUNT) {
            val curLive = displayLive[i]
            val nextLive = curLive + curveAlpha * (targetLive[i] - curLive)
            displayLive[i] = if (abs(targetLive[i] - nextLive) < 0.015f) targetLive[i] else nextLive

            val curTarget = displayTarget[i]
            val nextTarget = curTarget + curveAlpha * (targetCurve[i] - curTarget)
            displayTarget[i] = if (abs(targetCurve[i] - nextTarget) < 0.015f) targetCurve[i] else nextTarget
        }
        frameTick++
    }
}

@Composable
fun HeroSpectrumCanvas(
    spectrumBins36: List<Float>,
    targetCurveDb: List<Float>,
    liveCurveDb: List<Float>,
    manualTrimsDb: List<Float>,
    engineStatus: EngineStatus,
    activeMode: PleasureMode,
    preampAttenuationDb: Float,
    loudnessBoostDb: Float,
    limiterCeilingDb: Float,
    powerEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOrbitColors.current
    val reducedMotion = rememberReducedMotionEnabled()

    val effectiveStatus = if (!powerEnabled) EngineStatus.IDLE else engineStatus
    val isLiveProcessing = powerEnabled && effectiveStatus != EngineStatus.BYPASSED && effectiveStatus != EngineStatus.IDLE

    val animBuffer = remember { SpectrumAnimBuffer() }
    SideEffect {
        animBuffer.updateTargets(
            bins = spectrumBins36,
            live = liveCurveDb,
            target = targetCurveDb,
            activeSignal = isLiveProcessing,
        )
        if (reducedMotion) {
            animBuffer.snapAllWithoutTick()
        }
    }

    if (!reducedMotion) {
        LaunchedEffect(animBuffer) {
            while (isActive) {
                if (animBuffer.isSettled()) {
                    snapshotFlow { animBuffer.dataVersion }.first { !animBuffer.isSettled() }
                }
                withInfiniteAnimationFrameNanos { frameNanos ->
                    animBuffer.stepFrame(frameNanos)
                }
            }
        }
    }

    val statusText = when (effectiveStatus) {
        EngineStatus.AUTO_FFT_LOCKED -> stringResource(R.string.hud_status_fft_locked)
        EngineStatus.PROFILE_FALLBACK -> stringResource(R.string.hud_status_profile_fallback)
        EngineStatus.BYPASSED -> stringResource(R.string.hud_status_bypassed)
        EngineStatus.IDLE -> stringResource(R.string.hud_status_idle)
    }
    val statusDotColor = when (effectiveStatus) {
        EngineStatus.AUTO_FFT_LOCKED -> colors.liveAudioPrimary
        EngineStatus.PROFILE_FALLBACK -> colors.textSecondary
        EngineStatus.BYPASSED -> colors.danger
        EngineStatus.IDLE -> colors.textMuted
    }
    val pulseAlpha = if (effectiveStatus == EngineStatus.AUTO_FFT_LOCKED && !reducedMotion) {
        val pulseTransition = rememberInfiniteTransition(label = "hudStatusPulse")
        pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.38f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1050),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusDotPulse",
        ).value
    } else {
        1f
    }

    val loudLabel = stringResource(R.string.hud_loud_label)
    val loudOff = stringResource(R.string.hud_loud_off)
    val safeLoudness = sanitizeFinite(loudnessBoostDb).coerceAtLeast(0f)
    val loudValueText = if (isLiveProcessing && safeLoudness > 0.05f) {
        "$loudLabel +${formatOneDecimal(safeLoudness)} dB"
    } else {
        "$loudLabel $loudOff"
    }

    val modeLabel = stringResource(R.string.hud_mode_label)
    val modeName = localizedModeName(activeMode)

    val safePreampCut = abs(sanitizeFinite(preampAttenuationDb))
    val preampLabel = stringResource(R.string.hud_preamp_label)
    val preampValueText = if (isLiveProcessing && safePreampCut >= 0.05f) {
        String.format(Locale.US, "-%.1f dB", safePreampCut)
    } else {
        "0.0 dB"
    }
    val preampFillFraction = if (isLiveProcessing) {
        (safePreampCut / PREAMP_BAR_MAX_CUT_DB).coerceIn(0f, 1f)
    } else {
        0f
    }

    val safeCeiling = sanitizeFinite(limiterCeilingDb, default = -0.5f)
    val limiterBadgeText = if (abs(safeCeiling - (-0.5f)) < 0.05f) {
        stringResource(R.string.hud_limiter_badge)
    } else {
        String.format(Locale.US, "LIM %.1f dB", safeCeiling)
    }

    val cardShape = RoundedCornerShape(20.dp)
    val borderColor = if (isLiveProcessing) colors.borderActive else colors.borderHairline
    val a11yDescription = "$statusText, $loudValueText, $modeLabel $modeName, $preampLabel $preampValueText, $limiterBadgeText"

    val targetPath = remember { Path() }
    val livePath = remember { Path() }
    val liveFillPath = remember { Path() }
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = a11yDescription
            },
        shape = cardShape,
        color = colors.surfaceElevated.copy(alpha = colors.cardGlassAlpha),
        border = BorderStroke(1.dp, borderColor),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val boundedHeight = constraints.hasBoundedHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // Top HUD Telemetry Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(colors.surface.copy(alpha = 0.85f))
                                .border(1.dp, colors.borderHairline, RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor.copy(alpha = pulseAlpha)),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusText,
                                style = SpaceMonoTabularStyle.copy(
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    HudTelemetryChip(
                        text = loudValueText,
                        textColor = if (isLiveProcessing && safeLoudness > 0.05f) {
                            colors.liveAudioPrimary
                        } else {
                            colors.textSecondary
                        },
                        borderColor = colors.borderHairline,
                        bgColor = colors.surface.copy(alpha = 0.72f),
                    )

                    HudTelemetryChip(
                        text = "$modeLabel $modeName",
                        textColor = colors.textPrimary,
                        borderColor = if (isLiveProcessing) colors.borderActive else colors.borderHairline,
                        bgColor = colors.surface.copy(alpha = 0.85f),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 3-Layer 60fps Audio Spectrum & Cubic Bezier EQ Canvas
                val plotModifier = if (boundedHeight) {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .height(172.dp)
                }

                Column(modifier = plotModifier) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        // Read frame tick so only Draw invalidates at 60fps
                        @Suppress("UNUSED_VARIABLE")
                        val frame = animBuffer.frameTick

                        val w = size.width
                        val h = size.height
                        if (w <= 4f || h <= 4f) return@Canvas

                        val padH = 4.dp.toPx()
                        val padV = 6.dp.toPx()
                        val plotLeft = padH
                        val plotRight = w - padH
                        val plotTop = padV
                        val plotBottom = h - padV
                        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
                        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

                        fun dbToY(db: Float): Float {
                            val norm = ((PLOT_MAX_DB - db) / (PLOT_MAX_DB - PLOT_MIN_DB)).coerceIn(0f, 1f)
                            return plotTop + norm * plotH
                        }

                        val zeroY = dbToY(0f)

                        // Layer 1: 3-Layer Aerospace HUD Grid (dB horizontals + 10 ISO log-frequency verticals + corner reticles)
                        val minorStroke = 0.75.dp.toPx()
                        val majorStroke = 1.dp.toPx()
                        for (dbLevel in listOf(-12f, -6f, 6f, 12f)) {
                            val y = dbToY(dbLevel)
                            drawLine(
                                color = colors.gridMinor,
                                start = Offset(plotLeft, y),
                                end = Offset(plotRight, y),
                                strokeWidth = minorStroke,
                            )
                        }
                        drawLine(
                            color = colors.gridMajor,
                            start = Offset(plotLeft, zeroY),
                            end = Offset(plotRight, zeroY),
                            strokeWidth = majorStroke,
                        )
                        for (b in 0 until EQ_BAND_COUNT) {
                            val x = plotLeft + (b / (EQ_BAND_COUNT - 1).toFloat()) * plotW
                            drawLine(
                                color = colors.gridMinor,
                                start = Offset(x, plotTop),
                                end = Offset(x, plotBottom),
                                strokeWidth = minorStroke,
                            )
                        }

                        // Subtle HUD corner framing ticks
                        val tickLen = 7.dp.toPx()
                        val tickColor = colors.textMuted.copy(alpha = 0.45f)
                        drawLine(tickColor, Offset(0f, 0f), Offset(tickLen, 0f), majorStroke)
                        drawLine(tickColor, Offset(0f, 0f), Offset(0f, tickLen), majorStroke)
                        drawLine(tickColor, Offset(w, 0f), Offset(w - tickLen, 0f), majorStroke)
                        drawLine(tickColor, Offset(w, 0f), Offset(w, tickLen), majorStroke)
                        drawLine(tickColor, Offset(0f, h), Offset(tickLen, h), majorStroke)
                        drawLine(tickColor, Offset(0f, h), Offset(0f, h - tickLen), majorStroke)
                        drawLine(tickColor, Offset(w, h), Offset(w - tickLen, h), majorStroke)
                        drawLine(tickColor, Offset(w, h), Offset(w, h - tickLen), majorStroke)

                        // Layer 2: 36-bin FFT spectrum bars
                        val activeBinsSource = if (reducedMotion) {
                            if (isLiveProcessing) spectrumBins36 else null
                        } else {
                            null
                        }
                        val slotW = plotW / SPECTRUM_BIN_COUNT.toFloat()
                        val barW = (slotW * 0.62f).coerceAtLeast(1.5.dp.toPx())
                        val barCorner = CornerRadius(barW * 0.4f, barW * 0.4f)
                        val barAlphaScale = if (isLiveProcessing) 1f else 0.25f

                        for (i in 0 until SPECTRUM_BIN_COUNT) {
                            val rawBin = if (reducedMotion) {
                                sanitizeFinite(activeBinsSource?.getOrElse(i) { 0f } ?: 0f)
                            } else {
                                animBuffer.displayBins[i]
                            }
                            val binVal = rawBin.coerceIn(0f, 1f)
                            if (binVal <= 0.01f) continue
                            val barH = (binVal * plotH * 0.88f).coerceAtLeast(2.dp.toPx())
                            val barX = plotLeft + i * slotW + (slotW - barW) * 0.5f
                            val barTop = plotBottom - barH
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        colors.liveAudioPrimary.copy(alpha = 0.44f * barAlphaScale),
                                        colors.liveAudioSecondary.copy(alpha = 0.20f * barAlphaScale),
                                        colors.liveAudioDeep.copy(alpha = 0.04f * barAlphaScale),
                                    ),
                                    startY = barTop,
                                    endY = plotBottom,
                                ),
                                topLeft = Offset(barX, barTop),
                                size = Size(barW, barH),
                                cornerRadius = barCorner,
                            )
                            drawLine(
                                color = colors.liveAudioPrimary.copy(alpha = 0.85f * barAlphaScale),
                                start = Offset(barX, barTop),
                                end = Offset(barX + barW, barTop),
                                strokeWidth = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }

                        // Compute 10 ISO curve coordinates
                        val bandXs = FloatArray(EQ_BAND_COUNT) { b ->
                            plotLeft + (b / (EQ_BAND_COUNT - 1).toFloat()) * plotW
                        }
                        val targetYs = FloatArray(EQ_BAND_COUNT) { b ->
                            val db = if (reducedMotion) {
                                sanitizeFinite(targetCurveDb.getOrElse(b) { 0f }).coerceIn(PLOT_MIN_DB, PLOT_MAX_DB)
                            } else {
                                animBuffer.displayTarget[b]
                            }
                            dbToY(db)
                        }
                        val liveYs = FloatArray(EQ_BAND_COUNT) { b ->
                            val db = if (reducedMotion) {
                                if (isLiveProcessing) {
                                    sanitizeFinite(liveCurveDb.getOrElse(b) { 0f }).coerceIn(PLOT_MIN_DB, PLOT_MAX_DB)
                                } else {
                                    0f
                                }
                            } else {
                                animBuffer.displayLive[b]
                            }
                            dbToY(db)
                        }

                        // Layer 3a: Dashed Target Curve
                        buildCatmullRomPath(
                            xs = bandXs,
                            ys = targetYs,
                            minY = plotTop,
                            maxY = plotBottom,
                            outPath = targetPath,
                        )
                        drawPath(
                            path = targetPath,
                            color = colors.textSecondary.copy(alpha = if (isLiveProcessing) 0.58f else 0.38f),
                            style = Stroke(
                                width = 1.4.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                                pathEffect = dashEffect,
                            ),
                        )

                        // Layer 3b: Glowing Live EQ Curve + Fill + Orbital Nodes
                        buildCatmullRomPath(
                            xs = bandXs,
                            ys = liveYs,
                            minY = plotTop,
                            maxY = plotBottom,
                            outPath = livePath,
                        )
                        val curveAlpha = if (isLiveProcessing) 1f else 0.32f

                        liveFillPath.reset()
                        liveFillPath.addPath(livePath)
                        liveFillPath.lineTo(bandXs[EQ_BAND_COUNT - 1], zeroY)
                        liveFillPath.lineTo(bandXs[0], zeroY)
                        liveFillPath.close()

                        drawPath(
                            path = liveFillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    colors.liveAudioPrimary.copy(alpha = 0.22f * curveAlpha),
                                    colors.liveAudioSecondary.copy(alpha = 0.04f * curveAlpha),
                                    Color.Transparent,
                                ),
                                startY = plotTop,
                                endY = plotBottom,
                            ),
                        )

                        if (isLiveProcessing) {
                            drawPath(
                                path = livePath,
                                color = colors.liveAudioPrimary.copy(alpha = 0.24f),
                                style = Stroke(
                                    width = 6.5.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                            drawPath(
                                path = livePath,
                                color = colors.liveAudioSecondary.copy(alpha = 0.48f),
                                style = Stroke(
                                    width = 3.2.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }

                        drawPath(
                            path = livePath,
                            color = colors.liveAudioPrimary.copy(alpha = curveAlpha),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )

                        // Orbital nodes on each of the 10 ISO bands
                        val haloR = 4.5.dp.toPx()
                        val coreR = 2.4.dp.toPx()
                        val trimRingR = 5.8.dp.toPx()
                        for (b in 0 until EQ_BAND_COUNT) {
                            val center = Offset(bandXs[b], liveYs[b])
                            val trimActive = abs(sanitizeFinite(manualTrimsDb.getOrElse(b) { 0f })) > 0.05f
                            if (trimActive && isLiveProcessing) {
                                drawCircle(
                                    color = colors.textPrimary.copy(alpha = 0.65f),
                                    radius = trimRingR,
                                    center = center,
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                            drawCircle(
                                color = colors.liveAudioPrimary.copy(alpha = 0.28f * curveAlpha),
                                radius = haloR,
                                center = center,
                            )
                            drawCircle(
                                color = colors.textPrimary.copy(alpha = curveAlpha),
                                radius = coreR,
                                center = center,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // ISO Frequency Axis Labels
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PleasureProfiles.ISO_LABELS.forEach { label ->
                            Text(
                                text = label,
                                style = SpaceMonoTabularStyle.copy(
                                    fontSize = 9.sp,
                                    color = colors.textMuted,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Honest Preamp Headroom Bar + True-Peak Limiter Ceiling Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = preampLabel,
                                style = SpaceMonoTabularStyle.copy(
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textMuted,
                                ),
                                maxLines = 1,
                            )
                            Text(
                                text = preampValueText,
                                style = SpaceMonoTabularStyle.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isLiveProcessing && safePreampCut >= 0.05f) {
                                        colors.liveAudioPrimary
                                    } else {
                                        colors.textSecondary
                                    },
                                ),
                                maxLines = 1,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(colors.gridMinor),
                        ) {
                            if (preampFillFraction > 0.005f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(preampFillFraction)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(
                                                    colors.liveAudioSecondary,
                                                    colors.liveAudioPrimary,
                                                ),
                                            ),
                                        ),
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.surface.copy(alpha = 0.85f))
                            .border(
                                width = 1.dp,
                                color = if (isLiveProcessing) colors.borderActive else colors.borderHairline,
                                shape = RoundedCornerShape(999.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = OrbitIcons.ShieldLimiter,
                            contentDescription = null,
                            tint = if (isLiveProcessing) colors.ionAccent else colors.textMuted,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = limiterBadgeText,
                            style = SpaceMonoTabularStyle.copy(
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isLiveProcessing) colors.textPrimary else colors.textSecondary,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModeMiniCurveIcon(
    mode: PleasureMode,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val curveDb = remember(mode) { PleasureProfiles.targetCurveFor(mode) }
    val curvePath = remember { Path() }
    val fillPath = remember { Path() }

    Canvas(
        modifier = modifier
            .size(width = 44.dp, height = 22.dp),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 2f || h <= 2f) return@Canvas

        val padX = 2.dp.toPx()
        val padY = 2.5.dp.toPx()
        val plotW = (w - padX * 2f).coerceAtLeast(1f)
        val plotH = (h - padY * 2f).coerceAtLeast(1f)
        val minDb = -5.5f
        val maxDb = 6.5f

        fun dbToY(db: Float): Float {
            val norm = ((maxDb - db) / (maxDb - minDb)).coerceIn(0f, 1f)
            return padY + norm * plotH
        }

        val zeroY = dbToY(0f)
        drawLine(
            color = tint.copy(alpha = 0.25f),
            start = Offset(padX, zeroY),
            end = Offset(w - padX, zeroY),
            strokeWidth = 0.75.dp.toPx(),
        )

        val xs = FloatArray(EQ_BAND_COUNT) { i ->
            padX + (i / (EQ_BAND_COUNT - 1).toFloat()) * plotW
        }
        val ys = FloatArray(EQ_BAND_COUNT) { i ->
            dbToY(curveDb[i])
        }

        buildCatmullRomPath(
            xs = xs,
            ys = ys,
            minY = padY,
            maxY = h - padY,
            outPath = curvePath,
        )

        fillPath.reset()
        fillPath.addPath(curvePath)
        fillPath.lineTo(xs[EQ_BAND_COUNT - 1], zeroY)
        fillPath.lineTo(xs[0], zeroY)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    tint.copy(alpha = 0.24f),
                    Color.Transparent,
                ),
                startY = padY,
                endY = h - padY,
            ),
        )

        drawPath(
            path = curvePath,
            color = tint,
            style = Stroke(
                width = 1.75.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // Subtle peak marker dot to reinforce each mode's sonic signature
        var peakIdx = 0
        var bestAbs = -1f
        for (i in 0 until EQ_BAND_COUNT) {
            val mag = abs(curveDb[i])
            if (mag > bestAbs) {
                bestAbs = mag
                peakIdx = i
            }
        }
        drawCircle(
            color = tint,
            radius = 1.9.dp.toPx(),
            center = Offset(xs[peakIdx], ys[peakIdx]),
        )
    }
}

@Composable
private fun HudTelemetryChip(
    text: String,
    textColor: Color,
    borderColor: Color,
    bgColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = SpaceMonoTabularStyle.copy(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun buildCatmullRomPath(
    xs: FloatArray,
    ys: FloatArray,
    minY: Float,
    maxY: Float,
    outPath: Path,
) {
    outPath.reset()
    val count = minOf(xs.size, ys.size)
    if (count == 0) return
    outPath.moveTo(xs[0], ys[0].coerceIn(minY, maxY))
    if (count == 1) return

    for (i in 0 until count - 1) {
        val p0x = xs[(i - 1).coerceAtLeast(0)]
        val p0y = ys[(i - 1).coerceAtLeast(0)]
        val p1x = xs[i]
        val p1y = ys[i]
        val p2x = xs[i + 1]
        val p2y = ys[i + 1]
        val p3x = xs[(i + 2).coerceAtMost(count - 1)]
        val p3y = ys[(i + 2).coerceAtMost(count - 1)]

        val cp1x = p1x + (p2x - p0x) / 6f
        val cp1y = (p1y + (p2y - p0y) / 6f).coerceIn(minY, maxY)
        val cp2x = p2x - (p3x - p1x) / 6f
        val cp2y = (p2y - (p3y - p1y) / 6f).coerceIn(minY, maxY)
        outPath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y.coerceIn(minY, maxY))
    }
}

@Composable
private fun localizedModeName(mode: PleasureMode): String = when (mode) {
    PleasureMode.NOVA -> stringResource(R.string.mode_nova_name)
    PleasureMode.GRAVITY -> stringResource(R.string.mode_gravity_name)
    PleasureMode.HORIZON -> stringResource(R.string.mode_horizon_name)
    PleasureMode.ECLIPSE -> stringResource(R.string.mode_eclipse_name)
    PleasureMode.COMMS -> stringResource(R.string.mode_comms_name)
}

private fun sanitizeFinite(value: Float, default: Float = 0f): Float =
    if (value.isFinite()) value else default

private fun formatOneDecimal(value: Float): String =
    String.format(Locale.US, "%.1f", value)
