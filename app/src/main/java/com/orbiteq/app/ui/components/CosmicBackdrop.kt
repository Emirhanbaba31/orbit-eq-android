package com.orbiteq.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.ui.LocalReducedMotion
import com.orbiteq.app.ui.theme.DeepSpaceOrbitColors
import com.orbiteq.app.ui.theme.LunarMonochromeOrbitColors
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

@Immutable
private data class StarNode(
    val xNorm: Float,
    val yNorm: Float,
    val radiusDp: Float,
    val depthSteps: Int,
    val baseAlpha: Float,
    val twinkleCycles: Int,
    val twinklePhaseOffset: Float,
    val tintedByMode: Boolean,
)

@Composable
fun CosmicBackdrop(
    activeMode: PleasureMode,
    powerEnabled: Boolean,
    themeMode: OrbitThemeMode,
    modifier: Modifier = Modifier,
) {
    val colors = when (themeMode) {
        OrbitThemeMode.DEEP_SPACE_HUD -> DeepSpaceOrbitColors
        OrbitThemeMode.LUNAR_MONOCHROME -> LunarMonochromeOrbitColors
    }
    val reducedMotion = LocalReducedMotion.current

    val targetNebula = colors.modeTint(activeMode)
    val animatedNebulaState = animateColorAsState(
        targetValue = targetNebula,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 550),
        label = "cosmicNebulaTint",
    )
    val powerFactorState = animateFloatAsState(
        targetValue = if (powerEnabled) 1f else 0.35f,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 450),
        label = "cosmicPowerFactor",
    )

    // Keep State<Float> un-read in composition so 60fps star drift invalidates ONLY Draw.
    val driftPhaseState: State<Float>? = if (powerEnabled && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "cosmicStarDrift")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 44_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "driftPhase",
        )
    } else {
        null
    }

    val colorsState = rememberUpdatedState(colors)
    val themeModeState = rememberUpdatedState(themeMode)
    val powerEnabledState = rememberUpdatedState(powerEnabled)
    val reducedMotionState = rememberUpdatedState(reducedMotion)
    val stars = remember { generateDeterministicStarfield(count = 76) }

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val width = size.width
                val height = size.height
                val currentColors = colorsState.value
                val currentTheme = themeModeState.value
                val nebula = animatedNebulaState.value
                val pFactor = powerFactorState.value

                val maxDim = max(width, height).coerceAtLeast(1f)
                val primaryCenter = Offset(width * 0.52f, height * 0.19f)
                val primaryRadius = maxDim * 0.62f
                val secondaryCenter = Offset(width * 0.86f, height * 0.68f)
                val secondaryRadius = maxDim * 0.45f
                val lunarCenter = Offset(width * 0.5f, height * 0.18f)
                val lunarRadius = maxDim * 0.52f

                val primaryNebulaBrush = Brush.radialGradient(
                    colors = listOf(
                        nebula.copy(alpha = 0.17f * pFactor),
                        currentColors.deepAccent.copy(alpha = 0.08f * pFactor),
                        Color.Transparent,
                    ),
                    center = primaryCenter,
                    radius = primaryRadius,
                )
                val secondaryNebulaBrush = Brush.radialGradient(
                    colors = listOf(
                        currentColors.ionAccent.copy(alpha = 0.08f * pFactor),
                        Color.Transparent,
                    ),
                    center = secondaryCenter,
                    radius = secondaryRadius,
                )
                val lunarNebulaBrush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.055f * pFactor),
                        Color.Transparent,
                    ),
                    center = lunarCenter,
                    radius = lunarRadius,
                )

                val reticleCenter = Offset(width * 0.5f, height * 0.24f)
                val reticleStroke = Stroke(width = 0.85.dp.toPx())
                val reticleAlpha = if (currentTheme == OrbitThemeMode.DEEP_SPACE_HUD) {
                    0.06f * pFactor
                } else {
                    0.05f * pFactor
                }
                val reticleColorOuter = if (currentTheme == OrbitThemeMode.DEEP_SPACE_HUD) {
                    currentColors.ionAccent.copy(alpha = reticleAlpha)
                } else {
                    Color.White.copy(alpha = reticleAlpha)
                }
                val reticleColorInner = reticleColorOuter.copy(alpha = reticleAlpha * 0.7f)
                val starRadiiPx = FloatArray(stars.size) { idx -> stars[idx].radiusDp.dp.toPx() }
                val twoPi = (2.0 * PI).toFloat()

                onDrawBehind {
                    if (width <= 0f || height <= 0f) return@onDrawBehind

                    val driftPhase = driftPhaseState?.value ?: 0f
                    val isPowered = powerEnabledState.value
                    val isReduced = reducedMotionState.value

                    // Layer 1: True OLED / Void Black base
                    drawRect(color = currentColors.background)

                    // Layer 2: Cached mode-tinted atmospheric nebula radial glows
                    if (currentTheme == OrbitThemeMode.DEEP_SPACE_HUD) {
                        drawCircle(
                            brush = primaryNebulaBrush,
                            center = primaryCenter,
                            radius = primaryRadius,
                        )
                        drawCircle(
                            brush = secondaryNebulaBrush,
                            center = secondaryCenter,
                            radius = secondaryRadius,
                        )
                    } else {
                        drawCircle(
                            brush = lunarNebulaBrush,
                            center = lunarCenter,
                            radius = lunarRadius,
                        )
                    }

                    // Layer 3: Subtle concentric aerospace HUD orbital reticles
                    drawCircle(
                        color = reticleColorOuter,
                        radius = width * 0.44f,
                        center = reticleCenter,
                        style = reticleStroke,
                    )
                    drawCircle(
                        color = reticleColorInner,
                        radius = width * 0.72f,
                        center = reticleCenter,
                        style = reticleStroke,
                    )

                    // Layer 4: Multi-depth parallax starfield (zero allocations per frame)
                    val twinkleActive = isPowered && !isReduced
                    val powerBrightness = 0.55f + 0.45f * pFactor
                    for (i in stars.indices) {
                        val star = stars[i]
                        val yWrapped = ((star.yNorm - driftPhase * star.depthSteps) % 1f + 1f) % 1f
                        val xWrapped = ((star.xNorm + driftPhase * (if (star.depthSteps >= 3) 1f else 0f)) % 1f + 1f) % 1f
                        val twinkleWave = if (twinkleActive) {
                            0.72f + 0.28f * sin(twoPi * (driftPhase * star.twinkleCycles + star.twinklePhaseOffset))
                        } else {
                            0.85f
                        }
                        val starAlpha = (star.baseAlpha * twinkleWave * powerBrightness).coerceIn(0f, 1f)
                        val starColor = if (star.tintedByMode && currentTheme == OrbitThemeMode.DEEP_SPACE_HUD) {
                            nebula.copy(alpha = starAlpha)
                        } else {
                            currentColors.textPrimary.copy(alpha = starAlpha)
                        }
                        drawCircle(
                            color = starColor,
                            radius = starRadiiPx[i],
                            center = Offset(xWrapped * width, yWrapped * height),
                        )
                    }
                }
            },
    )
}

private fun generateDeterministicStarfield(count: Int): List<StarNode> {
    var seed = 0x5A17_9C3B
    fun nextUnit(): Float {
        seed = seed * 1664525 + 1013904223
        val bits = (seed ushr 8) and 0x00FF_FFFF
        return bits / 16777216f
    }

    return List(count) { idx ->
        val tierRoll = idx % 6
        val depthSteps = when {
            tierRoll == 0 -> 3
            tierRoll <= 2 -> 2
            else -> 1
        }
        val radiusDp = when (depthSteps) {
            3 -> 1.25f + nextUnit() * 0.55f
            2 -> 0.85f + nextUnit() * 0.45f
            else -> 0.55f + nextUnit() * 0.35f
        }
        val baseAlpha = when (depthSteps) {
            3 -> 0.45f + nextUnit() * 0.32f
            2 -> 0.26f + nextUnit() * 0.24f
            else -> 0.14f + nextUnit() * 0.18f
        }
        StarNode(
            xNorm = nextUnit(),
            yNorm = nextUnit(),
            radiusDp = radiusDp,
            depthSteps = depthSteps,
            baseAlpha = baseAlpha,
            twinkleCycles = 2 + (idx % 3),
            twinklePhaseOffset = nextUnit(),
            tintedByMode = (idx % 5 == 0),
        )
    }
}
