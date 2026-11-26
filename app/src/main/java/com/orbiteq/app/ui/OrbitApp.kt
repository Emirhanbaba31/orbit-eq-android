package com.orbiteq.app.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orbiteq.app.R
import com.orbiteq.app.model.ConnectedOutputDevice
import com.orbiteq.app.model.ModeResolutionSource
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.ui.components.CosmicBackdrop
import com.orbiteq.app.ui.icons.OrbitIcons
import com.orbiteq.app.ui.screens.ActivityScreen
import com.orbiteq.app.ui.screens.MixerScreen
import com.orbiteq.app.ui.screens.StudioScreen
import com.orbiteq.app.ui.theme.LocalOrbitColors
import com.orbiteq.app.ui.theme.OrbitTheme
import com.orbiteq.app.ui.theme.SpaceMonoTabularStyle
import java.util.Locale
import kotlin.math.abs

enum class OrbitTab {
    STUDIO,
    MIXER,
    ACTIVITY,
}

internal val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun OrbitApp(
    viewModel: OrbitViewModel,
    onTogglePower: (Boolean) -> Unit,
    onRequestRecordAudio: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onExportBackupJson: () -> Unit,
    onImportBackupJson: () -> Unit,
    onPickAutoEqFile: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onShareDiagnostics: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionState by viewModel.permissionState.collectAsStateWithLifecycle()
    val nowPlayingArtwork by viewModel.nowPlayingArtwork.collectAsStateWithLifecycle()
    val statusBannerMessage by viewModel.statusBannerMessage.collectAsStateWithLifecycle()
    var activeTab by rememberSaveable { mutableStateOf(OrbitTab.STUDIO) }
    val reducedMotion = rememberReducedMotionEnabled()

    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        OrbitTheme(themeMode = uiState.settings.themeMode) {
            Box(modifier = Modifier.fillMaxSize()) {
                CosmicBackdrop(
                    activeMode = uiState.activeMode,
                    powerEnabled = uiState.settings.powerEnabled,
                    themeMode = uiState.settings.themeMode,
                    modifier = Modifier.fillMaxSize(),
                )

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        OrbitBottomNavBar(
                            activeTab = activeTab,
                            onSelectTab = { activeTab = it },
                        )
                    },
                ) { innerPadding ->
                    val screenContentPadding = rememberOrbitContentPadding(
                        bottomBarPadding = innerPadding.calculateBottomPadding(),
                    )
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        when (activeTab) {
                            OrbitTab.STUDIO -> StudioScreen(
                                uiState = uiState,
                                contentPadding = screenContentPadding,
                                onTogglePower = onTogglePower,
                                onToggleBypass = { viewModel.setBypassed(!uiState.settings.bypassed) },
                                onSelectMode = viewModel::selectMode,
                                onSaveForActiveApp = viewModel::saveCurrentModeForActiveApp,
                                onIntensityChange = viewModel::setIntensityPercent,
                                onBassMacroChange = viewModel::setBassMacroDb,
                                onAirMacroChange = viewModel::setAirMacroDb,
                                onDynamicLoudnessChange = viewModel::setDynamicLoudness,
                                nowPlayingArtwork = nowPlayingArtwork,
                            )

                            OrbitTab.MIXER -> MixerScreen(
                                uiState = uiState,
                                contentPadding = screenContentPadding,
                                onBandTrimChange = viewModel::setBandTrim,
                                onResetTrims = viewModel::resetManualTrims,
                                onMbcModeChange = viewModel::setMbcMode,
                                onSubsonicFilterChange = viewModel::setSubsonicFilterEnabled,
                                onTrueHeadroomChange = viewModel::setTrueHeadroomMode,
                                onToneTiltChange = viewModel::setToneTiltDbPerOct,
                                onDeEsserModeChange = viewModel::setDeEsserMode,
                                onLoudnessMatchedBypassChange = viewModel::setLoudnessMatchedBypass,
                                onChannelBalanceChange = viewModel::setChannelBalance,
                                onResponseSpeedChange = viewModel::setResponseSpeed,
                                onSavePreset = viewModel::saveCustomPreset,
                                onApplyPreset = viewModel::applyCustomPreset,
                                onDeletePreset = viewModel::deleteCustomPreset,
                            )

                            OrbitTab.ACTIVITY -> ActivityScreen(
                                uiState = uiState,
                                permissionState = permissionState,
                                contentPadding = screenContentPadding,
                                onHeadphoneProfileChange = viewModel::setHeadphoneProfile,
                                onDismissQ30Tip = viewModel::dismissQ30Tip,
                                onSessionModeChange = viewModel::setSessionMode,
                                onSetAppRule = viewModel::setAppRule,
                                onRemoveAppRule = viewModel::removeAppRule,
                                onTogglePinTrack = viewModel::togglePinTrack,
                                onThemeModeChange = viewModel::setThemeMode,
                                onRequestRecordAudio = onRequestRecordAudio,
                                onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                                onRequestPostNotifications = onRequestPostNotifications,
                                onRequestBatteryOptimizationExemption = onRequestBatteryOptimizationExemption,
                                statusBannerMessage = statusBannerMessage,
                                onDismissStatusBanner = viewModel::clearStatusBanner,
                                onPostStatusBanner = viewModel::postStatusBanner,
                                onSelectCustomHeadphoneProfile = viewModel::selectCustomHeadphoneProfile,
                                onDeleteCustomHeadphoneProfile = viewModel::deleteCustomHeadphoneProfile,
                                onImportCatalogHeadphone = viewModel::importCatalogHeadphone,
                                onImportAutoEqText = viewModel::importAutoEqText,
                                onPickAutoEqFile = onPickAutoEqFile,
                                onExportBackupJson = onExportBackupJson,
                                onImportBackupJson = onImportBackupJson,
                                onCopyDiagnostics = onCopyDiagnostics,
                                onShareDiagnostics = onShareDiagnostics,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrbitBottomNavBar(
    activeTab: OrbitTab,
    onSelectTab: (OrbitTab) -> Unit,
) {
    val colors = LocalOrbitColors.current
    val haptic = LocalHapticFeedback.current
    val navItems = listOf(
        Triple(OrbitTab.STUDIO, OrbitIcons.WaveStudio, stringResource(R.string.nav_studio)),
        Triple(OrbitTab.MIXER, OrbitIcons.MixerSliders, stringResource(R.string.nav_mixer)),
        Triple(OrbitTab.ACTIVITY, OrbitIcons.ActivityOrbit, stringResource(R.string.nav_activity)),
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface.copy(alpha = 0.94f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(thickness = 1.dp, color = colors.borderHairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.union(
                            WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navItems.forEach { (tab, icon, label) ->
                    val selected = tab == activeTab
                    val containerColor = when {
                        !selected -> Color.Transparent
                        colors.themeMode == OrbitThemeMode.LUNAR_MONOCHROME -> colors.surfaceElevated
                        else -> colors.primaryAccent.copy(alpha = 0.16f)
                    }
                    val borderColor = if (selected) colors.borderActive else Color.Transparent
                    val contentColor = if (selected) {
                        if (colors.themeMode == OrbitThemeMode.LUNAR_MONOCHROME) {
                            colors.textPrimary
                        } else {
                            colors.ionAccent
                        }
                    } else {
                        colors.textSecondary
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(containerColor)
                            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = {
                                    if (!selected) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectTab(tab)
                                    }
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(21.dp),
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 11.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) colors.textPrimary else colors.textSecondary,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberOrbitContentPadding(bottomBarPadding: Dp): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    val topInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues()
    val horizontalInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
    return PaddingValues(
        start = horizontalInsets.calculateStartPadding(layoutDirection),
        end = horizontalInsets.calculateEndPadding(layoutDirection),
        top = topInsets.calculateTopPadding() + 12.dp,
        bottom = bottomBarPadding + 20.dp,
    )
}

@Composable
private fun rememberReducedMotionEnabled(): Boolean {
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
internal fun OrbitGlassCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    cornerRadius: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalOrbitColors.current
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp),
        shape = shape,
        color = colors.surfaceElevated.copy(alpha = colors.cardGlassAlpha),
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) colors.borderActive else colors.borderHairline,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
internal fun SectionLabelText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    val colors = LocalOrbitColors.current
    Text(
        text = text,
        modifier = modifier,
        style = SpaceMonoTabularStyle.copy(
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = colors.textMuted,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun OrbitStatusChip(
    text: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val colors = LocalOrbitColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (filled) accentColor else accentColor.copy(alpha = 0.14f),
            )
            .border(
                width = 1.dp,
                color = if (filled) accentColor else accentColor.copy(alpha = 0.45f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = SpaceMonoTabularStyle.copy(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (filled) colors.onPrimaryAccent else colors.textPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun modeDisplayName(mode: PleasureMode): String = when (mode) {
    PleasureMode.NOVA -> stringResource(R.string.mode_nova_name)
    PleasureMode.GRAVITY -> stringResource(R.string.mode_gravity_name)
    PleasureMode.HORIZON -> stringResource(R.string.mode_horizon_name)
    PleasureMode.ECLIPSE -> stringResource(R.string.mode_eclipse_name)
    PleasureMode.COMMS -> stringResource(R.string.mode_comms_name)
}

@Composable
internal fun modeTagline(mode: PleasureMode): String = when (mode) {
    PleasureMode.NOVA -> stringResource(R.string.mode_nova_tagline)
    PleasureMode.GRAVITY -> stringResource(R.string.mode_gravity_tagline)
    PleasureMode.HORIZON -> stringResource(R.string.mode_horizon_tagline)
    PleasureMode.ECLIPSE -> stringResource(R.string.mode_eclipse_tagline)
    PleasureMode.COMMS -> stringResource(R.string.mode_comms_tagline)
}

@Composable
internal fun modeSourceLabel(source: ModeResolutionSource): String = when (source) {
    ModeResolutionSource.MANUAL_OVERRIDE -> stringResource(R.string.mode_source_manual)
    ModeResolutionSource.PINNED_TRACK -> stringResource(R.string.mode_source_pinned)
    ModeResolutionSource.APP_RULE -> stringResource(R.string.mode_source_app)
    ModeResolutionSource.DEVICE_DEFAULT -> stringResource(R.string.mode_source_device)
    ModeResolutionSource.GLOBAL_DEFAULT -> stringResource(R.string.mode_source_global)
}

@Composable
internal fun localizedSpectralTag(rawTag: String): String = when (rawTag.trim()) {
    "Sub-Heavy" -> stringResource(R.string.spectral_sub_heavy)
    "Wide Stage" -> stringResource(R.string.spectral_wide_stage)
    "Vocal" -> stringResource(R.string.spectral_vocal)
    "Warm" -> stringResource(R.string.spectral_warm)
    else -> stringResource(R.string.spectral_balanced)
}

@Composable
internal fun resolveOutputDeviceName(device: ConnectedOutputDevice): String {
    if (device.name.isNotBlank()) return device.name
    return when {
        device.isBluetooth -> stringResource(R.string.device_bluetooth_default)
        device.isSpeaker -> stringResource(R.string.device_speaker_default)
        else -> stringResource(R.string.device_wired_default)
    }
}

internal fun outputDeviceIcon(device: ConnectedOutputDevice): ImageVector = when {
    device.isBluetooth -> OrbitIcons.Bluetooth
    device.isSpeaker -> OrbitIcons.Speaker
    else -> OrbitIcons.Headphones
}

internal fun formatSignedDbWithUnit(db: Float): String {
    if (!db.isFinite() || abs(db) < 0.05f) return "0.0 dB"
    return String.format(Locale.US, "%+.1f dB", db)
}

internal fun formatPreampAttenuation(preampDb: Float, isActive: Boolean): String {
    val cut = abs(if (preampDb.isFinite()) preampDb else 0f)
    return if (isActive && cut >= 0.05f) {
        String.format(Locale.US, "-%.1f dB", cut)
    } else {
        "0.0 dB"
    }
}
