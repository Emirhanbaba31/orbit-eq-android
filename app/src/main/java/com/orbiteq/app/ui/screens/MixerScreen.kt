package com.orbiteq.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.orbiteq.app.model.CustomPreset
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.ResponseSpeed
import com.orbiteq.app.ui.OrbitGlassCard
import com.orbiteq.app.ui.OrbitStatusChip
import com.orbiteq.app.ui.SectionLabelText
import com.orbiteq.app.ui.components.HybridBandFader
import com.orbiteq.app.ui.formatPreampAttenuation
import com.orbiteq.app.ui.icons.OrbitIcons
import com.orbiteq.app.ui.modeDisplayName
import com.orbiteq.app.ui.theme.LocalOrbitColors
import com.orbiteq.app.ui.theme.SpaceMonoTabularStyle
import kotlin.math.abs

private const val MAX_PREAMP_BAR_CUT_DB = 12f

@Composable
fun MixerScreen(
    uiState: OrbitUiState,
    contentPadding: PaddingValues,
    onBandTrimChange: (Int, Float) -> Unit,
    onResetTrims: () -> Unit,
    onMbcEnabledChange: (Boolean) -> Unit,
    onResponseSpeedChange: (ResponseSpeed) -> Unit,
    onSavePreset: (String) -> Unit,
    onApplyPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
) {
    val colors = LocalOrbitColors.current
    val haptic = LocalHapticFeedback.current
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }

    val hasAnyTrim = remember(uiState.settings.manualTrimsDb) {
        uiState.settings.manualTrimsDb.any { abs(it) > 0.05f }
    }
    val effectiveBypass = uiState.settings.bypassed || uiState.autoBypassedByRule
    val isLiveProcessing = uiState.settings.powerEnabled &&
        !effectiveBypass &&
        uiState.telemetry.engineStatus != EngineStatus.IDLE &&
        uiState.telemetry.engineStatus != EngineStatus.BYPASSED

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
        item(key = "ten_band_fader_bank") {
            OrbitGlassCard(
                highlighted = isLiveProcessing,
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        SectionLabelText(text = stringResource(R.string.mixer_section_faders))
                        Text(
                            text = stringResource(R.string.mixer_fader_hint),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = colors.textSecondary,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onResetTrims()
                        },
                        enabled = hasAnyTrim,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (hasAnyTrim) colors.borderActive else colors.borderHairline,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = OrbitIcons.Reset,
                            contentDescription = null,
                            tint = if (hasAnyTrim) colors.ionAccent else colors.textMuted,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.mixer_reset_trims),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 12.sp,
                                color = if (hasAnyTrim) colors.textPrimary else colors.textMuted,
                            ),
                            maxLines = 1,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val faderWidth = ((maxWidth - 20.dp) / 10f).coerceAtLeast(48.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        PleasureProfiles.ISO_LABELS.forEachIndexed { index, label ->
                            val liveDb = if (isLiveProcessing) {
                                uiState.telemetry.liveEqCurveDb.getOrElse(index) { 0f }
                            } else {
                                0f
                            }
                            val trimDb = uiState.settings.manualTrimsDb.getOrElse(index) { 0f }
                            HybridBandFader(
                                freqLabel = label,
                                liveTotalDb = liveDb,
                                manualTrimDb = trimDb,
                                onTrimChange = { nextDb -> onBandTrimChange(index, nextDb) },
                                enabled = true,
                                modifier = Modifier
                                    .width(faderWidth)
                                    .height(236.dp),
                            )
                        }
                    }
                }
            }
        }

        item(key = "limiter_preamp_card") {
            LimiterPreampCard(
                preampAttenuationDb = uiState.telemetry.preampAttenuationDb,
                isLiveProcessing = isLiveProcessing,
            )
        }

        item(key = "mbc_punch_card") {
            MbcPunchCard(
                mbcEnabled = uiState.settings.mbcEnabled,
                onMbcEnabledChange = { next ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMbcEnabledChange(next)
                },
            )
        }

        item(key = "response_speed_card") {
            ResponseSpeedCard(
                selectedSpeed = uiState.settings.responseSpeed,
                onSelectSpeed = { speed ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onResponseSpeedChange(speed)
                },
            )
        }

        item(key = "custom_presets_card") {
            CustomPresetsCard(
                presets = uiState.settings.customPresets,
                onOpenSaveDialog = { showSaveDialog = true },
                onApplyPreset = { preset ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onApplyPreset(preset.id)
                },
                onDeletePreset = { preset ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDeletePreset(preset.id)
                },
            )
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                onSavePreset(name)
                showSaveDialog = false
            },
        )
    }
}

@Composable
private fun LimiterPreampCard(
    preampAttenuationDb: Float,
    isLiveProcessing: Boolean,
) {
    val colors = LocalOrbitColors.current
    val safeCut = abs(if (preampAttenuationDb.isFinite()) preampAttenuationDb else 0f)
    val preampText = formatPreampAttenuation(preampAttenuationDb, isLiveProcessing)
    val fillFraction = if (isLiveProcessing) {
        (safeCut / MAX_PREAMP_BAR_CUT_DB).coerceIn(0f, 1f)
    } else {
        0f
    }
    val barBrush = remember(colors.liveAudioSecondary, colors.liveAudioPrimary) {
        Brush.horizontalGradient(
            colors = listOf(colors.liveAudioSecondary, colors.liveAudioPrimary),
        )
    }

    OrbitGlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = OrbitIcons.ShieldLimiter,
                    contentDescription = null,
                    tint = if (isLiveProcessing) colors.ionAccent else colors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.dsp_limiter_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = colors.textPrimary,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.dsp_limiter_desc),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = colors.textSecondary,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            OrbitStatusChip(
                text = if (isLiveProcessing) {
                    stringResource(R.string.dsp_limiter_active)
                } else {
                    stringResource(R.string.dsp_limiter_standby)
                },
                accentColor = if (isLiveProcessing) colors.ionAccent else colors.textMuted,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${stringResource(R.string.hud_preamp_label)} $preampText",
                style = SpaceMonoTabularStyle.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isLiveProcessing && safeCut >= 0.05f) {
                        colors.liveAudioPrimary
                    } else {
                        colors.textSecondary
                    },
                ),
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.dsp_limiter_ceiling),
                style = SpaceMonoTabularStyle.copy(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textMuted,
                ),
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.gridMinor),
        ) {
            if (fillFraction > 0.005f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fillFraction)
                        .clip(RoundedCornerShape(999.dp))
                        .background(barBrush),
                )
            }
        }
    }
}

@Composable
private fun MbcPunchCard(
    mbcEnabled: Boolean,
    onMbcEnabledChange: (Boolean) -> Unit,
) {
    val colors = LocalOrbitColors.current
    OrbitGlassCard(highlighted = mbcEnabled) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = mbcEnabled,
                    role = Role.Switch,
                    onValueChange = onMbcEnabledChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = OrbitIcons.SparkBolt,
                contentDescription = null,
                tint = if (mbcEnabled) colors.ionAccent else colors.textMuted,
                modifier = Modifier.size(22.dp),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.dsp_mbc_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = colors.textPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.dsp_mbc_desc),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = colors.textSecondary,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OrbitStatusChip(
                    text = if (mbcEnabled) {
                        stringResource(R.string.dsp_mbc_active)
                    } else {
                        stringResource(R.string.dsp_mbc_off)
                    },
                    accentColor = if (mbcEnabled) colors.ionAccent else colors.textMuted,
                )
            }

            Switch(
                checked = mbcEnabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimaryAccent,
                    checkedTrackColor = colors.primaryAccent,
                    checkedBorderColor = colors.ionAccent,
                    uncheckedThumbColor = colors.textSecondary,
                    uncheckedTrackColor = colors.surface,
                    uncheckedBorderColor = colors.borderHairline,
                ),
            )
        }
    }
}

@Composable
private fun ResponseSpeedCard(
    selectedSpeed: ResponseSpeed,
    onSelectSpeed: (ResponseSpeed) -> Unit,
) {
    val colors = LocalOrbitColors.current
    val options = listOf(
        ResponseSpeed.CHILL to stringResource(R.string.speed_chill),
        ResponseSpeed.BALANCED to stringResource(R.string.speed_balanced),
        ResponseSpeed.REACTIVE to stringResource(R.string.speed_reactive),
    )

    OrbitGlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabelText(text = stringResource(R.string.dsp_speed_title))
            Text(
                text = stringResource(R.string.dsp_speed_hint),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.textSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (speed, label) ->
                val selected = speed == selectedSpeed
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.primaryAccent else colors.surface)
                        .border(
                            width = 1.dp,
                            color = if (selected) colors.borderActive else colors.borderHairline,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelectSpeed(speed) },
                        )
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = SpaceMonoTabularStyle.copy(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) colors.onPrimaryAccent else colors.textSecondary,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomPresetsCard(
    presets: List<CustomPreset>,
    onOpenSaveDialog: () -> Unit,
    onApplyPreset: (CustomPreset) -> Unit,
    onDeletePreset: (CustomPreset) -> Unit,
) {
    val colors = LocalOrbitColors.current
    val deleteCd = stringResource(R.string.preset_delete_cd)

    OrbitGlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabelText(
                text = stringResource(R.string.presets_section_title),
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Button(
                onClick = onOpenSaveDialog,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryAccent,
                    contentColor = colors.onPrimaryAccent,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = OrbitIcons.Save,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.preset_save_btn),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 12.sp,
                    ),
                    maxLines = 1,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (presets.isEmpty()) {
            Text(
                text = stringResource(R.string.preset_empty),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.textSecondary,
                ),
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = presets,
                    key = { it.id },
                ) { preset ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.borderHairline),
                    ) {
                        Row(
                            modifier = Modifier.heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .clickable(
                                        role = Role.Button,
                                        onClick = { onApplyPreset(preset) },
                                    )
                                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.textPrimary,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${modeDisplayName(preset.mode)} · ${preset.intensity}%",
                                    style = SpaceMonoTabularStyle.copy(
                                        fontSize = 10.sp,
                                        color = colors.ionAccent,
                                    ),
                                    maxLines = 1,
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(
                                        role = Role.Button,
                                        onClick = { onDeletePreset(preset) },
                                    )
                                    .semantics {
                                        contentDescription = "$deleteCd ${preset.name}"
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = OrbitIcons.Delete,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = LocalOrbitColors.current
    var nameInput by rememberSaveable { mutableStateOf("") }
    val canSave = nameInput.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = stringResource(R.string.preset_dialog_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    color = colors.textPrimary,
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { if (it.length <= 40) nameInput = it },
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.preset_name_hint),
                        color = colors.textMuted,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedBorderColor = colors.ionAccent,
                    unfocusedBorderColor = colors.borderHairline,
                    cursorColor = colors.ionAccent,
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(nameInput.trim()) },
                enabled = canSave,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryAccent,
                    contentColor = colors.onPrimaryAccent,
                ),
            ) {
                Text(text = stringResource(R.string.preset_dialog_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.preset_dialog_cancel),
                    color = colors.textSecondary,
                )
            }
        },
    )
}
