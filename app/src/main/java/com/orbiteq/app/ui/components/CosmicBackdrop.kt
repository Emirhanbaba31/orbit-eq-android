package com.orbiteq.app.ui.components

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.PleasureMode
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
internal fun rememberReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    var reduced by remember(context) {
        mutableStateOf(readAnimatorScaleZero(context))
    }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readAnimatorScaleZero(context)
            }
        }
        try {
            resolver.registerContentObserver(uri, false, observer)
        } catch (_: SecurityException) {
            // Keep initial read value if global settings observer is restricted.
        }
        onDispose {
            try {
                resolver.unregisterContentObserver(observer)
            } catch (_: IllegalStateException) {
                // Ignore if already unregistered.
            }
        }
    }

    return reduced
}

private fun readAnimatorScaleZero(context: Context): Boolean {
    return try {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f,
        )
        scale <= 0f
    } catch (_: SecurityException) {
        false
    }
}

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
    val reducedMotion = rememberReducedMotionEnabled()

    val targetNebula = colors.modeTint(activeMode)
    val animatedNebula by animateColorAsState(
        targetValue = targetNebula,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 550),
        label = "cosmicNebulaTint",
    )
    val powerFactor by animateFloatAsState(
        targetValue = if (powerEnabled) 1f else 0.35f,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 450),
        label = "cosmicPowerFactor",
    )

    val driftPhase = if (powerEnabled && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "cosmicStarDrift")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 44_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "driftPhase",
        ).value
    } else {
        0f
    }

    val stars = remember { generateDeterministicStarfield(count = 76) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        // Layer 1: True OLED / Void Black base
        drawRect(color = colors.background)

        // Layer 2: Mode-tinted atmospheric nebula radial glows
        val maxDim = max(width, height)
        if (themeMode == OrbitThemeMode.DEEP_SPACE_HUD) {
            val primaryCenter = Offset(width * 0.52f, height * 0.19f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        animatedNebula.copy(alpha = 0.17f * powerFactor),
                        colors.deepAccent.copy(alpha = 0.08f * powerFactor),
                        Color.Transparent,
                    ),
                    center = primaryCenter,
                    radius = maxDim * 0.62f,
                ),
                center = primaryCenter,
                radius = maxDim * 0.62f,
            )

            val secondaryCenter = Offset(width * 0.86f, height * 0.68f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.ionAccent.copy(alpha = 0.08f * powerFactor),
                        Color.Transparent,
                    ),
                    center = secondaryCenter,
                    radius = maxDim * 0.45f,
                ),
                center = secondaryCenter,
                radius = maxDim * 0.45f,
            )
        } else {
            // Lunar Monochrome keeps Pure OLED Black and subtle neutral silver luminance only
            val lunarCenter = Offset(width * 0.5f, height * 0.18f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.055f * powerFactor),
                        Color.Transparent,
                    ),
                    center = lunarCenter,
                    radius = maxDim * 0.52f,
                ),
                center = lunarCenter,
                radius = maxDim * 0.52f,
            )
        }

        // Layer 3: Subtle concentric aerospace HUD orbital reticles
        val reticleCenter = Offset(width * 0.5f, height * 0.24f)
        val reticleStroke = Stroke(width = 0.85.dp.toPx())
        val reticleAlpha = if (themeMode == OrbitThemeMode.DEEP_SPACE_HUD) 0.06f * powerFactor else 0.05f * powerFactor
        val reticleColor = if (themeMode == OrbitThemeMode.DEEP_SPACE_HUD) {
            colors.ionAccent.copy(alpha = reticleAlpha)
        } else {
            Color.White.copy(alpha = reticleAlpha)
        }
        drawCircle(
            color = reticleColor,
            radius = width * 0.44f,
            center = reticleCenter,
            style = reticleStroke,
        )
        drawCircle(
            color = reticleColor.copy(alpha = reticleAlpha * 0.7f),
            radius = width * 0.72f,
            center = reticleCenter,
            style = reticleStroke,
        )

        // Layer 4: Multi-depth parallax starfield (integer step/cycle multipliers ensure seamless loop wrap)
        val twoPi = (2.0 * PI).toFloat()
        for (i in stars.indices) {
            val star = stars[i]
            val yWrapped = ((star.yNorm - driftPhase * star.depthSteps) % 1f + 1f) % 1f
            val xWrapped = ((star.xNorm + driftPhase * (if (star.depthSteps >= 3) 1f else 0f)) % 1f + 1f) % 1f
            val twinkleWave = if (powerEnabled && !reducedMotion) {
                0.72f + 0.28f * sin(twoPi * (driftPhase * star.twinkleCycles + star.twinklePhaseOffset))
            } else {
                0.85f
            }
            val starAlpha = (star.baseAlpha * twinkleWave * (0.55f + 0.45f * powerFactor)).coerceIn(0f, 1f)
            val starColor = if (star.tintedByMode && themeMode == OrbitThemeMode.DEEP_SPACE_HUD) {
                animatedNebula.copy(alpha = starAlpha)
            } else {
                colors.textPrimary.copy(alpha = starAlpha)
            }
            drawCircle(
                color = starColor,
                radius = star.radiusDp.dp.toPx(),
                center = Offset(xWrapped * width, yWrapped * height),
            )
        }
    }
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
