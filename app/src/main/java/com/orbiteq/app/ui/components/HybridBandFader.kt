package com.orbiteq.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbiteq.app.ui.theme.LocalOrbitColors
import com.orbiteq.app.ui.theme.SpaceMonoTabularStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val FADER_MIN_TRIM_DB = -8.0f
private const val FADER_MAX_TRIM_DB = 8.0f
private const val FADER_LIVE_RANGE_DB = 15.0f
private const val ZERO_MAGNETIC_SNAP_DB = 0.35f
private val FADER_SCALE_TICKS = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)

internal data class FaderDragStepResult(
    val nextRawDb: Float,
    val nextSnappedDb: Float,
    val changed: Boolean,
    val crossedIntoZeroDetent: Boolean,
    val nowAtZero: Boolean,
)

@Composable
fun HybridBandFader(
    freqLabel: String,
    liveTotalDb: Float,
    manualTrimDb: Float,
    onTrimChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOrbitColors.current
    val haptic = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotionEnabled()

    val safeLiveDb = if (liveTotalDb.isFinite()) {
        liveTotalDb.coerceIn(-FADER_LIVE_RANGE_DB, FADER_LIVE_RANGE_DB)
    } else {
        0f
    }
    val clampedTrimDb = if (manualTrimDb.isFinite()) {
        manualTrimDb.coerceIn(FADER_MIN_TRIM_DB, FADER_MAX_TRIM_DB)
    } else {
        0f
    }

    var isDragging by remember { mutableStateOf(false) }

    val animatedLiveDbState = animateFloatAsState(
        targetValue = if (enabled) safeLiveDb else 0f,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 85),
        label = "faderLiveDb",
    )

    val currentTrimDb by rememberUpdatedState(clampedTrimDb)
    val currentOnTrimChange by rememberUpdatedState(onTrimChange)
    val currentEnabled by rememberUpdatedState(enabled)
    val colorsState = rememberUpdatedState(colors)
    val enabledState = rememberUpdatedState(enabled)
    val draggingState = rememberUpdatedState(isDragging)

    val liveReadout = formatSignedDb(if (enabled) safeLiveDb else 0f)
    val trimReadout = formatSignedDb(clampedTrimDb)
    val hasActiveTrim = abs(clampedTrimDb) > 0.05f

    Column(
        modifier = modifier
            .widthIn(min = 48.dp)
            .heightIn(min = 216.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$freqLabel Hz"
                stateDescription = "$trimReadout dB / $liveReadout dB"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = clampedTrimDb,
                    range = FADER_MIN_TRIM_DB..FADER_MAX_TRIM_DB,
                )
                if (!enabled) {
                    disabled()
                } else {
                    setProgress { target ->
                        val quantized = quantizeTenths(target).coerceIn(FADER_MIN_TRIM_DB, FADER_MAX_TRIM_DB)
                        val snapped = if (abs(quantized) <= ZERO_MAGNETIC_SNAP_DB * 0.5f) 0f else quantized
                        if (abs(snapped - clampedTrimDb) > 0.02f) {
                            currentOnTrimChange(snapped)
                            true
                        } else {
                            false
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Top live total dB readout (tabular Space Mono)
        Text(
            text = liveReadout,
            style = SpaceMonoTabularStyle.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    !enabled -> colors.textMuted
                    abs(safeLiveDb) > 0.05f -> colors.liveAudioPrimary
                    else -> colors.textSecondary
                },
            ),
            maxLines = 1,
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Interactive vertical fader track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 140.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = {
                            if (currentEnabled && abs(currentTrimDb) > 0.02f) {
                                currentOnTrimChange(0f)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                    )
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var rawDb = currentTrimDb
                        var lastReportedDb = currentTrimDb
                        var wasSnappedZero = abs(lastReportedDb) < 0.05f
                        var initialDeltaY = 0f

                        val slopChange = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            initialDeltaY = overSlop
                            change.consume()
                        } ?: return@awaitEachGesture

                        isDragging = true
                        val padV = 12.dp.toPx()
                        val usableHeight = (size.height - padV * 2f).coerceAtLeast(1f)

                        fun applyDelta(deltaY: Float) {
                            if (!currentEnabled || abs(deltaY) <= 0f) return
                            val step = stepFaderDrag(
                                currentRawDb = rawDb,
                                lastReportedDb = lastReportedDb,
                                wasSnappedZero = wasSnappedZero,
                                deltaYPx = deltaY,
                                usableTrackHeightPx = usableHeight,
                            )
                            rawDb = step.nextRawDb
                            if (step.changed) {
                                if (step.crossedIntoZeroDetent) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                wasSnappedZero = step.nowAtZero
                                lastReportedDb = step.nextSnappedDb
                                currentOnTrimChange(step.nextSnappedDb)
                            }
                        }

                        applyDelta(initialDeltaY)

                        verticalDrag(slopChange.id) { change ->
                            // Read positionChange BEFORE consuming so Compose does not zero the delta.
                            val dy = change.positionChange().y
                            change.consume()
                            applyDelta(dy)
                        }
                        isDragging = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp)
                    .drawWithCache {
                        val c = colorsState.value
                        val w = size.width
                        val h = size.height
                        val railW = 6.dp.toPx()
                        val railCorner = CornerRadius(railW * 0.5f, railW * 0.5f)
                        val railStroke = Stroke(width = 0.8.dp.toPx())
                        val positiveLiveBrush = Brush.verticalGradient(
                            colors = listOf(c.liveAudioPrimary, c.liveAudioSecondary),
                            startY = 0f,
                            endY = h * 0.5f,
                        )
                        val negativeLiveBrush = Brush.verticalGradient(
                            colors = listOf(c.liveAudioSecondary, c.liveAudioPrimary),
                            startY = h * 0.5f,
                            endY = h,
                        )

                        onDrawBehind {
                            if (w <= 2f || h <= 2f) return@onDrawBehind

                            val drawColors = colorsState.value
                            val isEnabled = enabledState.value
                            val draggingNow = draggingState.value
                            val centerX = w * 0.5f
                            val centerY = h * 0.5f
                            val halfH = h * 0.5f

                            // Recessed vertical rail
                            drawRoundRect(
                                color = drawColors.surface,
                                topLeft = Offset(centerX - railW * 0.5f, 0f),
                                size = Size(railW, h),
                                cornerRadius = railCorner,
                            )
                            drawRoundRect(
                                color = drawColors.borderHairline,
                                topLeft = Offset(centerX - railW * 0.5f, 0f),
                                size = Size(railW, h),
                                cornerRadius = railCorner,
                                style = railStroke,
                            )

                            // Scale ticks (+8, +4, 0, -4, -8)
                            for (i in FADER_SCALE_TICKS.indices) {
                                val step = FADER_SCALE_TICKS[i]
                                val y = centerY - step * halfH
                                val tickHalfW = if (step == 0f) 11.dp.toPx() else 6.dp.toPx()
                                drawLine(
                                    color = if (step == 0f) drawColors.gridMajor else drawColors.gridMinor,
                                    start = Offset(centerX - tickHalfW, y),
                                    end = Offset(centerX + tickHalfW, y),
                                    strokeWidth = if (step == 0f) 1.2.dp.toPx() else 0.8.dp.toPx(),
                                )
                            }

                            // Live animated blue auto-offset bar from 0 dB center datum
                            val liveFraction = (animatedLiveDbState.value / FADER_LIVE_RANGE_DB).coerceIn(-1f, 1f)
                            if (abs(liveFraction) > 0.01f) {
                                val barTargetY = centerY - liveFraction * halfH
                                val topY = minOf(centerY, barTargetY)
                                val barHeight = abs(centerY - barTargetY).coerceAtLeast(2.dp.toPx())
                                drawRoundRect(
                                    brush = if (liveFraction >= 0f) positiveLiveBrush else negativeLiveBrush,
                                    topLeft = Offset(centerX - railW * 0.5f, topY),
                                    size = Size(railW, barHeight),
                                    cornerRadius = railCorner,
                                )
                            }

                            // White manual trim thumb capsule
                            val trimDb = currentTrimDb
                            val trimActive = abs(trimDb) > 0.05f
                            val trimNorm = (trimDb / FADER_MAX_TRIM_DB).coerceIn(-1f, 1f)
                            val thumbY = centerY - trimNorm * halfH
                            val thumbW = 26.dp.toPx()
                            val thumbH = 13.dp.toPx()
                            val thumbRadius = CornerRadius(thumbH * 0.5f, thumbH * 0.5f)
                            val thumbTopLeft = Offset(centerX - thumbW * 0.5f, thumbY - thumbH * 0.5f)

                            if (draggingNow || (trimActive && isEnabled)) {
                                drawRoundRect(
                                    color = drawColors.ionAccent.copy(alpha = if (draggingNow) 0.42f else 0.24f),
                                    topLeft = Offset(thumbTopLeft.x - 2.dp.toPx(), thumbTopLeft.y - 2.dp.toPx()),
                                    size = Size(thumbW + 4.dp.toPx(), thumbH + 4.dp.toPx()),
                                    cornerRadius = CornerRadius(thumbH * 0.65f, thumbH * 0.65f),
                                )
                            }

                            val thumbFill = if (isEnabled) {
                                drawColors.textPrimary
                            } else {
                                drawColors.textMuted.copy(alpha = 0.45f)
                            }
                            drawRoundRect(
                                color = thumbFill,
                                topLeft = thumbTopLeft,
                                size = Size(thumbW, thumbH),
                                cornerRadius = thumbRadius,
                            )

                            // Hardware fader center grip line
                            val gripW = 12.dp.toPx()
                            drawLine(
                                color = if (trimActive && isEnabled) drawColors.primaryAccent else drawColors.background,
                                start = Offset(centerX - gripW * 0.5f, thumbY),
                                end = Offset(centerX + gripW * 0.5f, thumbY),
                                strokeWidth = 1.6.dp.toPx(),
                            )
                        }
                    },
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Manual trim numeric readout + ISO band frequency label
        Text(
            text = trimReadout,
            style = SpaceMonoTabularStyle.copy(
                fontSize = 9.sp,
                color = if (hasActiveTrim && enabled) colors.textPrimary else colors.textMuted,
            ),
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = freqLabel,
            style = SpaceMonoTabularStyle.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) colors.textPrimary else colors.textMuted,
            ),
            maxLines = 1,
        )
    }
}

internal fun stepFaderDrag(
    currentRawDb: Float,
    lastReportedDb: Float,
    wasSnappedZero: Boolean,
    deltaYPx: Float,
    usableTrackHeightPx: Float,
): FaderDragStepResult {
    val safeHeight = usableTrackHeightPx.coerceAtLeast(1f)
    val dbPerPx = (FADER_MAX_TRIM_DB - FADER_MIN_TRIM_DB) / safeHeight
    val nextRawDb = (currentRawDb - deltaYPx * dbPerPx)
        .coerceIn(FADER_MIN_TRIM_DB, FADER_MAX_TRIM_DB)
    val nextSnappedDb = applyZeroMagneticSnap(nextRawDb)
    val changed = abs(nextSnappedDb - lastReportedDb) >= 0.05f
    val nowAtZero = abs(nextSnappedDb) < 0.05f
    return FaderDragStepResult(
        nextRawDb = nextRawDb,
        nextSnappedDb = nextSnappedDb,
        changed = changed,
        crossedIntoZeroDetent = changed && nowAtZero && !wasSnappedZero,
        nowAtZero = nowAtZero,
    )
}

internal fun applyZeroMagneticSnap(rawDb: Float): Float {
    val clamped = rawDb.coerceIn(FADER_MIN_TRIM_DB, FADER_MAX_TRIM_DB)
    return if (abs(clamped) <= ZERO_MAGNETIC_SNAP_DB) {
        0f
    } else {
        quantizeTenths(clamped)
    }
}

private fun quantizeTenths(value: Float): Float =
    (value * 10f).roundToInt() / 10f

private fun formatSignedDb(db: Float): String {
    if (!db.isFinite() || abs(db) < 0.05f) return "0.0"
    return String.format(Locale.US, "%+.1f", db)
}
