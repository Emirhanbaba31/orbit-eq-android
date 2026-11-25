package com.orbiteq.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.orbiteq.app.R
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.PleasureMode

val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

val DmSansFamily = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_semibold, FontWeight.SemiBold),
)

val SpaceMonoFamily = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

val SpaceMonoTabularStyle = TextStyle(
    fontFamily = SpaceMonoFamily,
    fontWeight = FontWeight.Normal,
    fontFeatureSettings = "tnum",
)

val OrbitTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.8).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = SpaceMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp,
        fontFeatureSettings = "tnum",
    ),
    labelSmall = TextStyle(
        fontFamily = SpaceMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = "tnum",
    ),
)

@Immutable
data class OrbitColors(
    val themeMode: OrbitThemeMode,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val cardGlassAlpha: Float,
    val borderHairline: Color,
    val borderActive: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val primaryAccent: Color,
    val onPrimaryAccent: Color,
    val ionAccent: Color,
    val deepAccent: Color,
    val liveAudioPrimary: Color,
    val liveAudioSecondary: Color,
    val liveAudioDeep: Color,
    val danger: Color,
    val onDanger: Color,
    val gridMajor: Color,
    val gridMinor: Color,
    val scrim: Color,
) {
    fun modeTint(mode: PleasureMode): Color = when (themeMode) {
        OrbitThemeMode.DEEP_SPACE_HUD -> when (mode) {
            PleasureMode.NOVA -> Color(0xFF3B82F6)
            PleasureMode.GRAVITY -> Color(0xFF1D4ED8)
            PleasureMode.HORIZON -> Color(0xFF38BDF8)
            PleasureMode.ECLIPSE -> Color(0xFF2563EB)
            PleasureMode.COMMS -> Color(0xFF60A5FA)
        }

        OrbitThemeMode.LUNAR_MONOCHROME -> when (mode) {
            PleasureMode.NOVA -> Color(0xFFFFFFFF)
            PleasureMode.GRAVITY -> Color(0xFFD4D4D8)
            PleasureMode.HORIZON -> Color(0xFFF4F4F5)
            PleasureMode.ECLIPSE -> Color(0xFFA1A1AA)
            PleasureMode.COMMS -> Color(0xFFE4E4E7)
        }
    }
}

val DeepSpaceOrbitColors = OrbitColors(
    themeMode = OrbitThemeMode.DEEP_SPACE_HUD,
    background = Color(0xFF03050A),
    surface = Color(0xFF0B0F1A),
    surfaceElevated = Color(0xFF0D1322),
    cardGlassAlpha = 0.78f,
    borderHairline = Color(0xFF1E293B),
    borderActive = Color(0xFF38BDF8).copy(alpha = 0.55f),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF8093AC),
    primaryAccent = Color(0xFF3B82F6),
    onPrimaryAccent = Color(0xFF03050A),
    ionAccent = Color(0xFF38BDF8),
    deepAccent = Color(0xFF1D4ED8),
    liveAudioPrimary = Color(0xFF38BDF8),
    liveAudioSecondary = Color(0xFF3B82F6),
    liveAudioDeep = Color(0xFF1D4ED8),
    danger = Color(0xFFEF4444),
    onDanger = Color(0xFF03050A),
    gridMajor = Color(0xFF1E293B).copy(alpha = 0.85f),
    gridMinor = Color(0xFF1E293B).copy(alpha = 0.42f),
    scrim = Color(0xFF03050A).copy(alpha = 0.82f),
)

val LunarMonochromeOrbitColors = OrbitColors(
    themeMode = OrbitThemeMode.LUNAR_MONOCHROME,
    background = Color(0xFF000000),
    surface = Color(0xFF09090B),
    surfaceElevated = Color(0xFF121216),
    cardGlassAlpha = 0.90f,
    borderHairline = Color(0xFFFFFFFF).copy(alpha = 0.18f),
    borderActive = Color(0xFFFFFFFF),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA1A1AA),
    textMuted = Color(0xFF8E8E98),
    primaryAccent = Color(0xFFFFFFFF),
    onPrimaryAccent = Color(0xFF000000),
    ionAccent = Color(0xFFFFFFFF),
    deepAccent = Color(0xFF27272A),
    liveAudioPrimary = Color(0xFF60A5FA),
    liveAudioSecondary = Color(0xFF2563EB),
    liveAudioDeep = Color(0xFF1E3A8A),
    danger = Color(0xFFEF4444),
    onDanger = Color(0xFF000000),
    gridMajor = Color(0xFFFFFFFF).copy(alpha = 0.18f),
    gridMinor = Color(0xFFFFFFFF).copy(alpha = 0.09f),
    scrim = Color(0xFF000000).copy(alpha = 0.86f),
)

val LocalOrbitColors = staticCompositionLocalOf { DeepSpaceOrbitColors }

@Composable
fun OrbitTheme(
    themeMode: OrbitThemeMode = OrbitThemeMode.DEEP_SPACE_HUD,
    content: @Composable () -> Unit,
) {
    val orbitColors = when (themeMode) {
        OrbitThemeMode.DEEP_SPACE_HUD -> DeepSpaceOrbitColors
        OrbitThemeMode.LUNAR_MONOCHROME -> LunarMonochromeOrbitColors
    }

    val materialColors = darkColorScheme(
        primary = orbitColors.primaryAccent,
        onPrimary = orbitColors.onPrimaryAccent,
        secondary = orbitColors.ionAccent,
        onSecondary = orbitColors.onPrimaryAccent,
        tertiary = orbitColors.liveAudioPrimary,
        onTertiary = orbitColors.onPrimaryAccent,
        background = orbitColors.background,
        onBackground = orbitColors.textPrimary,
        surface = orbitColors.surface,
        onSurface = orbitColors.textPrimary,
        surfaceVariant = orbitColors.surfaceElevated,
        onSurfaceVariant = orbitColors.textSecondary,
        outline = orbitColors.borderHairline,
        outlineVariant = orbitColors.gridMajor,
        error = orbitColors.danger,
        onError = orbitColors.onDanger,
    )

    CompositionLocalProvider(
        LocalOrbitColors provides orbitColors,
        LocalContentColor provides orbitColors.textPrimary,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = OrbitTypography,
            content = content,
        )
    }
}
