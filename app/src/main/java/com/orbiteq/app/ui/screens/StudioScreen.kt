package com.orbiteq.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbiteq.app.R
import com.orbiteq.app.dsp.PleasureProfiles
import com.orbiteq.app.model.ModeResolutionSource
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.ui.OrbitGlassCard
import com.orbiteq.app.ui.OrbitStatusChip
import com.orbiteq.app.ui.SectionLabelText
import com.orbiteq.app.ui.components.HeroSpectrumCanvas
import com.orbiteq.app.ui.components.ModeMiniCurveIcon
import com.orbiteq.app.ui.components.OrbitalDial
import com.orbiteq.app.ui.components.rememberReducedMotionEnabled
import com.orbiteq.app.ui.formatSignedDbWithUnit
import com.orbiteq.app.ui.icons.OrbitIcons
import com.orbiteq.app.ui.modeDisplayName
import com.orbiteq.app.ui.modeSourceLabel
import com.orbiteq.app.ui.modeTagline
import com.orbiteq.app.ui.outputDeviceIcon
import com.orbiteq.app.ui.resolveOutputDeviceName
import com.orbiteq.app.ui.theme.LocalOrbitColors
import com.orbiteq.app.ui.theme.SpaceMonoTabularStyle
import kotlin.math.roundToInt

private const val MACRO_MIN_DB = -6.0f
private const val MACRO_MAX_DB = 8.0f
private const val MACRO_SLIDER_STEPS = 27

@Composable
fun StudioScreen(
    uiState: OrbitUiState,
    contentPadding: PaddingValues,
    onTogglePower: (Boolean) -> Unit,
    onToggleBypass: () -> Unit,
    onSelectMode: (PleasureMode) -> Unit,
    onSaveForActiveApp: () -> Unit,
    onIntensityChange: (Int) -> Unit,
    onBassMacroChange: (Float) -> Unit,
    onAirMacroChange: (Float) -> Unit,
    onDynamicLoudnessChange: (Boolean) -> Unit,
) {
    val colors = LocalOrbitColors.current
    val haptic = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotionEnabled()
    val effectiveBypass = uiState.settings.bypassed || uiState.autoBypassedByRule

    val targetCurveDb = remember(uiState.activeMode, uiState.settings.intensityPercent) {
        val scale = uiState.settings.intensityPercent.coerceIn(0, 100) / 100f
        PleasureProfiles.targetCurveFor(uiState.activeMode).map { it * scale }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 720.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "now_playing_pill") {
            NowPlayingTelemetryPill(
                uiState = uiState,
                effectiveBypass = effectiveBypass,
                reducedMotion = reducedMotion,
                onToggleBypass = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleBypass()
                },
                onTogglePower = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTogglePower(!uiState.settings.powerEnabled)
                },
            )
        }

        val showSaveForApp = uiState.modeSource == ModeResolutionSource.MANUAL_OVERRIDE &&
            !uiState.nowPlaying.packageName.isNullOrBlank()
        if (showSaveForApp || uiState.autoBypassedByRule) {
            item(key = "context_action_strip") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(visible = showSaveForApp) {
                        SaveForAppChip(
                            appName = uiState.nowPlaying.appName.ifBlank {
                                uiState.nowPlaying.packageName.orEmpty()
                            },
                            activeMode = uiState.activeMode,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSaveForActiveApp()
                            },
                        )
                    }
                    if (uiState.autoBypassedByRule) {
                        OrbitGlassCard(
                            cornerRadius = 12.dp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = OrbitIcons.WarningInfo,
                                    contentDescription = null,
                                    tint = colors.danger,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource(R.string.studio_auto_bypass_notice),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = colors.textSecondary,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "hero_spectrum_canvas") {
            HeroSpectrumCanvas(
                spectrumBins36 = uiState.telemetry.spectrumBins36,
                targetCurveDb = targetCurveDb,
                liveCurveDb = uiState.telemetry.liveEqCurveDb,
                manualTrimsDb = uiState.settings.manualTrimsDb,
                engineStatus = uiState.telemetry.engineStatus,
                activeMode = uiState.activeMode,
                preampAttenuationDb = uiState.telemetry.preampAttenuationDb,
                loudnessBoostDb = uiState.telemetry.loudnessBoostDb,
                limiterCeilingDb = uiState.telemetry.limiterCeilingDb,
                powerEnabled = uiState.settings.powerEnabled,
            )
        }

        item(key = "pleasure_modes_strip") {
            PleasureModeSelectorSection(
                activeMode = uiState.activeMode,
                onSelectMode = { mode ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectMode(mode)
                },
            )
        }

        item(key = "telemetry_deck") {
            TactileTelemetryDeck(
                intensityPercent = uiState.settings.intensityPercent,
                bassMacroDb = uiState.settings.bassMacroDb,
                airMacroDb = uiState.settings.airMacroDb,
                dynamicLoudness = uiState.settings.dynamicLoudness,
                onIntensityChange = onIntensityChange,
                onBassMacroChange = onBassMacroChange,
                onAirMacroChange = onAirMacroChange,
                onDynamicLoudnessChange = onDynamicLoudnessChange,
            )
        }
    }
}

@Composable
private fun NowPlayingTelemetryPill(
    uiState: OrbitUiState,
    effectiveBypass: Boolean,
    reducedMotion: Boolean,
    onToggleBypass: () -> Unit,
    onTogglePower: () -> Unit,
) {
    val colors = LocalOrbitColors.current
    val nowPlaying = uiState.nowPlaying
    val hasTrack = nowPlaying.trackTitle.isNotBlank()

    val powerBg by animateColorAsState(
        targetValue = if (uiState.settings.powerEnabled) colors.primaryAccent else colors.surface,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 180),
        label = "powerBg",
    )
    val powerBorder by animateColorAsState(
        targetValue = if (uiState.settings.powerEnabled) colors.ionAccent else colors.borderHairline,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 180),
        label = "powerBorder",
    )

    val titleText = if (hasTrack) {
        nowPlaying.trackTitle
    } else {
        stringResource(R.string.now_playing_idle_title)
    }

    val subtitleText = if (hasTrack) {
        listOf(nowPlaying.artist, nowPlaying.appName)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { stringResource(R.string.now_playing_idle_sub) }
    } else {
        stringResource(R.string.now_playing_idle_sub)
    }

    val deviceName = resolveOutputDeviceName(uiState.connectedDevice)
    val sourceLabel = modeSourceLabel(uiState.modeSource)

    OrbitGlassCard(
        highlighted = uiState.settings.powerEnabled && !effectiveBypass,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(
                        width = 1.dp,
                        color = if (hasTrack && nowPlaying.isPlaying) {
                            colors.borderActive
                        } else {
                            colors.borderHairline
                        },
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = OrbitIcons.MusicNote,
                    contentDescription = null,
                    tint = if (hasTrack && nowPlaying.isPlaying) {
                        colors.ionAccent
                    } else {
                        colors.textSecondary
                    },
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = colors.textSecondary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = outputDeviceIcon(uiState.connectedDevice),
                        contentDescription = null,
                        tint = if (uiState.connectedDevice.isSoundcoreQ30) {
                            colors.ionAccent
                        } else {
                            colors.textMuted
                        },
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "$deviceName · $sourceLabel",
                        style = SpaceMonoTabularStyle.copy(
                            fontSize = 10.sp,
                            color = colors.textMuted,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val abDesc = stringResource(R.string.ab_bypass_cd)
            val isManualBypass = uiState.settings.bypassed
            val abContainerColor = when {
                effectiveBypass -> colors.danger.copy(alpha = 0.22f)
                else -> colors.surface
            }
            val abBorderColor = if (effectiveBypass) colors.danger else colors.borderHairline

            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(abContainerColor)
                    .border(1.dp, abBorderColor, RoundedCornerShape(14.dp))
                    .toggleable(
                        value = isManualBypass,
                        role = Role.Switch,
                        onValueChange = { onToggleBypass() },
                    )
                    .semantics {
                        contentDescription = abDesc
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.ab_bypass_label),
                    style = SpaceMonoTabularStyle.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (effectiveBypass) colors.danger else colors.textPrimary,
                    ),
                    maxLines = 1,
                )
            }

            val powerDesc = stringResource(R.string.power_toggle_cd)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(powerBg)
                    .border(1.5.dp, powerBorder, CircleShape)
                    .toggleable(
                        value = uiState.settings.powerEnabled,
                        role = Role.Switch,
                        onValueChange = { onTogglePower() },
                    )
                    .semantics {
                        contentDescription = powerDesc
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = OrbitIcons.Power,
                    contentDescription = null,
                    tint = if (uiState.settings.powerEnabled) {
                        colors.onPrimaryAccent
                    } else {
                        colors.textSecondary
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun SaveForAppChip(
    appName: String,
    activeMode: PleasureMode,
    onClick: () -> Unit,
) {
    val colors = LocalOrbitColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = colors.primaryAccent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, colors.borderActive),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = OrbitIcons.Save,
                    contentDescription = null,
                    tint = colors.ionAccent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "$appName · ${stringResource(R.string.save_for_app_chip)}",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 13.sp,
                        color = colors.textPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            OrbitStatusChip(
                text = modeDisplayName(activeMode),
                accentColor = colors.ionAccent,
            )
        }
    }
}

@Composable
private fun PleasureModeSelectorSection(
    activeMode: PleasureMode,
    onSelectMode: (PleasureMode) -> Unit,
) {
    val colors = LocalOrbitColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabelText(text = stringResource(R.string.studio_section_modes))
            Text(
                text = modeDisplayName(activeMode),
                style = SpaceMonoTabularStyle.copy(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ionAccent,
                ),
                maxLines = 1,
            )
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = PleasureMode.entries,
                key = { it.name },
            ) { mode ->
                val selected = mode == activeMode
                val modeTint = colors.modeTint(mode)
                val curveTint = if (selected) {
                    if (colors.themeMode == OrbitThemeMode.LUNAR_MONOCHROME) {
                        colors.textPrimary
                    } else {
                        colors.ionAccent
                    }
                } else {
                    colors.textSecondary
                }
                val cardBg = if (selected) {
                    colors.surfaceElevated
                } else {
                    colors.surface.copy(alpha = 0.85f)
                }
                val cardBorder = if (selected) {
                    if (colors.themeMode == OrbitThemeMode.LUNAR_MONOCHROME) {
                        colors.borderActive
                    } else {
                        modeTint.copy(alpha = 0.85f)
                    }
                } else {
                    colors.borderHairline
                }

                Box(
                    modifier = Modifier
                        .widthIn(min = 88.dp)
                        .heightIn(min = 76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(cardBg)
                        .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelectMode(mode) },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ModeMiniCurveIcon(
                            mode = mode,
                            tint = curveTint,
                        )
                        Text(
                            text = modeDisplayName(mode),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) colors.textPrimary else colors.textSecondary,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.ionAccent),
            )
            Text(
                text = modeTagline(activeMode),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.textSecondary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TactileTelemetryDeck(
    intensityPercent: Int,
    bassMacroDb: Float,
    airMacroDb: Float,
    dynamicLoudness: Boolean,
    onIntensityChange: (Int) -> Unit,
    onBassMacroChange: (Float) -> Unit,
    onAirMacroChange: (Float) -> Unit,
    onDynamicLoudnessChange: (Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val sideBySide = maxWidth >= 460.dp
        if (sideBySide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OrbitGlassCard(
                    modifier = Modifier.widthIn(min = 200.dp, max = 230.dp),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        OrbitalDial(
                            intensityPercent = intensityPercent,
                            onIntensityChange = onIntensityChange,
                            enabled = true,
                        )
                    }
                }
                MacroControlsCard(
                    modifier = Modifier.weight(1f),
                    bassMacroDb = bassMacroDb,
                    airMacroDb = airMacroDb,
                    dynamicLoudness = dynamicLoudness,
                    onBassMacroChange = onBassMacroChange,
                    onAirMacroChange = onAirMacroChange,
                    onDynamicLoudnessChange = onDynamicLoudnessChange,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OrbitGlassCard(
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        OrbitalDial(
                            intensityPercent = intensityPercent,
                            onIntensityChange = onIntensityChange,
                            enabled = true,
                        )
                    }
                }
                MacroControlsCard(
                    modifier = Modifier.fillMaxWidth(),
                    bassMacroDb = bassMacroDb,
                    airMacroDb = airMacroDb,
                    dynamicLoudness = dynamicLoudness,
                    onBassMacroChange = onBassMacroChange,
                    onAirMacroChange = onAirMacroChange,
                    onDynamicLoudnessChange = onDynamicLoudnessChange,
                )
            }
        }
    }
}

@Composable
private fun MacroControlsCard(
    bassMacroDb: Float,
    airMacroDb: Float,
    dynamicLoudness: Boolean,
    onBassMacroChange: (Float) -> Unit,
    onAirMacroChange: (Float) -> Unit,
    onDynamicLoudnessChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOrbitColors.current
    val haptic = LocalHapticFeedback.current

    OrbitGlassCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        MacroDbSlider(
            label = stringResource(R.string.macro_sub_impact),
            valueDb = bassMacroDb,
            onValueChange = onBassMacroChange,
        )

        Spacer(modifier = Modifier.height(6.dp))

        MacroDbSlider(
            label = stringResource(R.string.macro_starlight_air),
            valueDb = airMacroDb,
            onValueChange = onAirMacroChange,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface.copy(alpha = 0.72f))
                .border(1.dp, colors.borderHairline, RoundedCornerShape(12.dp))
                .toggleable(
                    value = dynamicLoudness,
                    role = Role.Switch,
                    onValueChange = { next ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDynamicLoudnessChange(next)
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.toggle_dynamic_loudness),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.toggle_dynamic_loudness_sub),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = colors.textSecondary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = dynamicLoudness,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimaryAccent,
                    checkedTrackColor = colors.primaryAccent,
                    checkedBorderColor = colors.ionAccent,
                    uncheckedThumbColor = colors.textSecondary,
                    uncheckedTrackColor = colors.surfaceElevated,
                    uncheckedBorderColor = colors.borderHairline,
                ),
            )
        }
    }
}

@Composable
private fun MacroDbSlider(
    label: String,
    valueDb: Float,
    onValueChange: (Float) -> Unit,
) {
    val colors = LocalOrbitColors.current
    val haptic = LocalHapticFeedback.current
    val clamped = valueDb.coerceIn(MACRO_MIN_DB, MACRO_MAX_DB)
    val quantized = ((clamped * 2f).roundToInt() / 2f).coerceIn(MACRO_MIN_DB, MACRO_MAX_DB)
    val valueText = formatSignedDbWithUnit(quantized)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(
                    color = colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = valueText,
                style = SpaceMonoTabularStyle.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (kotlin.math.abs(quantized) > 0.05f) {
                        colors.ionAccent
                    } else {
                        colors.textSecondary
                    },
                ),
                maxLines = 1,
            )
        }

        Slider(
            value = quantized,
            onValueChange = { raw ->
                val stepped = ((raw * 2f).roundToInt() / 2f).coerceIn(MACRO_MIN_DB, MACRO_MAX_DB)
                if (stepped != quantized) {
                    if (stepped == 0f) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onValueChange(stepped)
                }
            },
            valueRange = MACRO_MIN_DB..MACRO_MAX_DB,
            steps = MACRO_SLIDER_STEPS,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = "$label $valueText"
                },
            colors = SliderDefaults.colors(
                thumbColor = colors.textPrimary,
                activeTrackColor = colors.primaryAccent,
                inactiveTrackColor = colors.gridMajor,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}
