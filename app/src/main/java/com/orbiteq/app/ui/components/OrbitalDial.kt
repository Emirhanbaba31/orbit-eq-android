package com.orbiteq.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbiteq.app.R
import com.orbiteq.app.ui.theme.DmSansFamily
import com.orbiteq.app.ui.theme.LocalOrbitColors
import com.orbiteq.app.ui.theme.SpaceMonoTabularStyle
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DIAL_START_DEG = 135f
private const val DIAL_SWEEP_DEG = 270f
private const val DIAL_STEP_PERCENT = 5
private const val DIAL_TICK_COUNT = 21

@Composable
fun OrbitalDial(
    intensityPercent: Int,
    onIntensityChange: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOrbitColors.current
    val haptic = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotionEnabled()

    val clampedPercent = intensityPercent.coerceIn(0, 100)
    var isDragging by remember { mutableStateOf(false) }

    val animatedPercentState = animateFloatAsState(
        targetValue = clampedPercent.toFloat(),
        animationSpec = if (isDragging || reducedMotion) snap() else tween(durationMillis = 150),
        label = "orbitalDialPercent",
    )

    val currentPercent by rememberUpdatedState(clampedPercent)
    val currentOnChange by rememberUpdatedState(onIntensityChange)
    val currentEnabled by rememberUpdatedState(enabled)

    val dialCaption = stringResource(R.string.dial_caption)
    val dialSubLabel = stringResource(R.string.dial_sub_label)
    val activeAccent = if (enabled) colors.ionAccent else colors.textMuted
    val secondaryAccent = if (enabled) colors.primaryAccent else colors.textMuted
    val colorsState = rememberUpdatedState(colors)
    val activeAccentState = rememberUpdatedState(activeAccent)
    val secondaryAccentState = rememberUpdatedState(secondaryAccent)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(184.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = dialCaption
                    stateDescription = "$clampedPercent%"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = clampedPercent.toFloat(),
                        range = 0f..100f,
                    )
                    if (!enabled) {
                        disabled()
                    } else {
                        setProgress { target ->
                            val snapped = target.roundToInt().coerceIn(0, 100)
                            if (snapped != clampedPercent) {
                                currentOnChange(snapped)
                                true
                            } else {
                                false
                            }
                        }
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val touchSlop = viewConfiguration.touchSlop
                        var dragStarted = false
                        var trackedPercent = currentPercent
                        var lastDetent = trackedPercent / DIAL_STEP_PERCENT

                        drag(down.id) { change ->
                            val distFromDown = hypot(
                                change.position.x - down.position.x,
                                change.position.y - down.position.y,
                            )
                            if (!dragStarted && distFromDown > touchSlop) {
                                dragStarted = true
                                isDragging = true
                            }
                            if (dragStarted && currentEnabled) {
                                change.consume()
                                val mapped = pointToPercent(
                                    point = change.position,
                                    centerX = cx,
                                    centerY = cy,
                                    previousPercent = trackedPercent,
                                )
                                if (mapped != null && mapped != trackedPercent) {
                                    trackedPercent = mapped
                                    currentOnChange(mapped)
                                    val detent = mapped / DIAL_STEP_PERCENT
                                    if (detent != lastDetent) {
                                        lastDetent = detent
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        }

                        if (!dragStarted && currentEnabled) {
                            // Single tap along the arc steps to nearest 5% detent
                            val tapRadius = hypot(down.position.x - cx, down.position.y - cy)
                            val minRingRadius = size.width * 0.20f
                            if (tapRadius >= minRingRadius) {
                                val mapped = pointToTapPercent(
                                    point = down.position,
                                    centerX = cx,
                                    centerY = cy,
                                )
                                if (mapped != null) {
                                    val stepped = ((mapped / DIAL_STEP_PERCENT.toFloat()).roundToInt() * DIAL_STEP_PERCENT)
                                        .coerceIn(0, 100)
                                    if (stepped != currentPercent) {
                                        currentOnChange(stepped)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        }
                        isDragging = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Spacer(
                modifier = Modifier
                    .size(184.dp)
                    .drawWithCache {
                        val diameter = size.minDimension
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val arcStrokePx = 9.dp.toPx()
                        val ringRadius = diameter * 0.37f
                        val tickOuterR = diameter * 0.46f
                        val tickInnerMajorR = diameter * 0.415f
                        val tickInnerMinorR = diameter * 0.43f
                        val arcTopLeft = Offset(center.x - ringRadius, center.y - ringRadius)
                        val arcSize = Size(ringRadius * 2f, ringRadius * 2f)

                        val accent = activeAccentState.value
                        val secAccent = secondaryAccentState.value
                        val c = colorsState.value
                        val arcSweepBrush = Brush.sweepGradient(
                            colors = listOf(secAccent, accent, secAccent),
                            center = center,
                        )
                        val trackStroke = Stroke(width = arcStrokePx, cap = StrokeCap.Round)
                        val glowStroke = Stroke(width = arcStrokePx + 7.dp.toPx(), cap = StrokeCap.Round)
                        val hairlineStroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                        val majorTickStrokePx = 1.8.dp.toPx()
                        val minorTickStrokePx = 1.1.dp.toPx()

                        onDrawBehind {
                            val drawColors = colorsState.value
                            val drawAccent = activeAccentState.value
                            val isEnabled = currentEnabled
                            val pct = animatedPercentState.value

                            // Outer tick marks around the 270° cockpit arc
                            val activeSteps = (pct / 100f) * (DIAL_TICK_COUNT - 1)
                            for (i in 0 until DIAL_TICK_COUNT) {
                                val fraction = i / (DIAL_TICK_COUNT - 1).toFloat()
                                val deg = DIAL_START_DEG + fraction * DIAL_SWEEP_DEG
                                val rad = deg * (PI.toFloat() / 180f)
                                val cosA = cos(rad)
                                val sinA = sin(rad)
                                val isMajor = i % 5 == 0
                                val innerR = if (isMajor) tickInnerMajorR else tickInnerMinorR
                                val lit = i <= activeSteps + 0.25f
                                val tickColor = when {
                                    !isEnabled -> drawColors.gridMinor
                                    lit -> drawAccent.copy(alpha = if (isMajor) 0.95f else 0.72f)
                                    else -> drawColors.gridMajor
                                }
                                drawLine(
                                    color = tickColor,
                                    start = Offset(center.x + cosA * innerR, center.y + sinA * innerR),
                                    end = Offset(center.x + cosA * tickOuterR, center.y + sinA * tickOuterR),
                                    strokeWidth = if (isMajor) majorTickStrokePx else minorTickStrokePx,
                                    cap = StrokeCap.Round,
                                )
                            }

                            // Inner aerospace hairline reticle ring
                            drawCircle(
                                color = c.borderHairline,
                                radius = ringRadius - 12.dp.toPx(),
                                center = center,
                                style = hairlineStroke,
                            )

                            // Recessed track arc
                            drawArc(
                                color = c.surface,
                                startAngle = DIAL_START_DEG,
                                sweepAngle = DIAL_SWEEP_DEG,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = trackStroke,
                            )
                            drawArc(
                                color = c.gridMajor,
                                startAngle = DIAL_START_DEG,
                                sweepAngle = DIAL_SWEEP_DEG,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = hairlineStroke,
                            )

                            // Active progress arc + subtle halo glow
                            val activeSweep = (pct / 100f) * DIAL_SWEEP_DEG
                            if (activeSweep > 0.5f) {
                                if (isEnabled) {
                                    drawArc(
                                        color = drawAccent.copy(alpha = 0.22f),
                                        startAngle = DIAL_START_DEG,
                                        sweepAngle = activeSweep,
                                        useCenter = false,
                                        topLeft = arcTopLeft,
                                        size = arcSize,
                                        style = glowStroke,
                                    )
                                }
                                drawArc(
                                    brush = arcSweepBrush,
                                    startAngle = DIAL_START_DEG,
                                    sweepAngle = activeSweep,
                                    useCenter = false,
                                    topLeft = arcTopLeft,
                                    size = arcSize,
                                    style = trackStroke,
                                )
                            }

                            // Orbital knob thumb on active arc endpoint
                            val thumbDeg = DIAL_START_DEG + activeSweep
                            val thumbRad = thumbDeg * (PI.toFloat() / 180f)
                            val thumbPos = Offset(
                                center.x + cos(thumbRad) * ringRadius,
                                center.y + sin(thumbRad) * ringRadius,
                            )
                            drawCircle(
                                color = drawAccent.copy(alpha = if (isEnabled) 0.34f else 0.14f),
                                radius = 9.dp.toPx(),
                                center = thumbPos,
                            )
                            drawCircle(
                                color = if (isEnabled) c.textPrimary else c.textMuted,
                                radius = 5.5.dp.toPx(),
                                center = thumbPos,
                            )
                            drawCircle(
                                color = c.background,
                                radius = 2.dp.toPx(),
                                center = thumbPos,
                            )
                        }
                    },
            )

            // Center digital HUD telemetry readout
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "$clampedPercent%",
                    style = SpaceMonoTabularStyle.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 27.sp,
                        color = if (enabled) colors.textPrimary else colors.textMuted,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = dialSubLabel,
                    style = SpaceMonoTabularStyle.copy(
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = if (enabled) activeAccent else colors.textMuted,
                    ),
                    maxLines = 1,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Accessible >=48.dp detent stepper buttons flanking caption (dragging alternative)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DialStepButton(
                symbol = "-",
                contentDesc = "$dialCaption -$DIAL_STEP_PERCENT%",
                enabled = enabled && clampedPercent > 0,
                borderColor = colors.borderHairline,
                bgColor = colors.surfaceElevated.copy(alpha = colors.cardGlassAlpha),
                textColor = colors.textPrimary,
                disabledColor = colors.textMuted,
                onClick = {
                    val next = (clampedPercent - DIAL_STEP_PERCENT).coerceIn(0, 100)
                    if (next != clampedPercent) {
                        onIntensityChange(next)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
            )

            Text(
                text = dialCaption,
                modifier = Modifier.weight(1f, fill = false),
                style = TextStyle(
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = if (enabled) colors.textSecondary else colors.textMuted,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            DialStepButton(
                symbol = "+",
                contentDesc = "$dialCaption +$DIAL_STEP_PERCENT%",
                enabled = enabled && clampedPercent < 100,
                borderColor = colors.borderHairline,
                bgColor = colors.surfaceElevated.copy(alpha = colors.cardGlassAlpha),
                textColor = colors.textPrimary,
                disabledColor = colors.textMuted,
                onClick = {
                    val next = (clampedPercent + DIAL_STEP_PERCENT).coerceIn(0, 100)
                    if (next != clampedPercent) {
                        onIntensityChange(next)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
            )
        }
    }
}

@Composable
private fun DialStepButton(
    symbol: String,
    contentDesc: String,
    enabled: Boolean,
    borderColor: Color,
    bgColor: Color,
    textColor: Color,
    disabledColor: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = 22.dp),
                onClick = onClick,
            )
            .semantics {
                contentDescription = contentDesc
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(bgColor)
                .border(1.dp, borderColor, CircleShape)
                .padding(bottom = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = symbol,
                style = SpaceMonoTabularStyle.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) textColor else disabledColor.copy(alpha = 0.55f),
                ),
            )
        }
    }
}

private fun pointToPercent(
    point: Offset,
    centerX: Float,
    centerY: Float,
    previousPercent: Int,
): Int? {
    val dx = point.x - centerX
    val dy = point.y - centerY
    if (hypot(dx, dy) < 12f) return null

    val rawDeg = atan2(dy, dx) * (180f / PI.toFloat())
    val cwFromStart = ((rawDeg - DIAL_START_DEG) % 360f + 360f) % 360f
    val rawPercent = if (cwFromStart <= DIAL_SWEEP_DEG) {
        ((cwFromStart / DIAL_SWEEP_DEG) * 100f).roundToInt().coerceIn(0, 100)
    } else {
        // Bottom 90° dead-zone clamps cleanly to nearest end without wrap-around jump
        if (cwFromStart >= DIAL_SWEEP_DEG + (360f - DIAL_SWEEP_DEG) / 2f) 0 else 100
    }

    // Prevent crossing bottom dead-zone directly from 100% <-> 0%
    if (previousPercent >= 82 && rawPercent <= 18) return 100
    if (previousPercent <= 18 && rawPercent >= 82) return 0
    return rawPercent
}

private fun pointToTapPercent(
    point: Offset,
    centerX: Float,
    centerY: Float,
): Int? {
    val rawDeg = atan2(point.y - centerY, point.x - centerX) * (180f / PI.toFloat())
    val cwFromStart = ((rawDeg - DIAL_START_DEG) % 360f + 360f) % 360f
    if (cwFromStart > DIAL_SWEEP_DEG) return null
    return ((cwFromStart / DIAL_SWEEP_DEG) * 100f).roundToInt().coerceIn(0, 100)
}
