package com.orbiteq.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbiteq.app.R
import com.orbiteq.app.dsp.AppRuleResolver
import com.orbiteq.app.dsp.AutoEqCatalog
import com.orbiteq.app.dsp.AutoEqParseResult
import com.orbiteq.app.dsp.AutoEqParser
import com.orbiteq.app.dsp.CatalogCategory
import com.orbiteq.app.dsp.CatalogHeadphoneEntry
import com.orbiteq.app.model.AppRule
import com.orbiteq.app.model.AppRuleAction
import com.orbiteq.app.model.DspBackend
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.OrbitThemeMode
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.SessionAttachmentMode
import com.orbiteq.app.model.TrackActivityEntry
import com.orbiteq.app.ui.OrbitGlassCard
import com.orbiteq.app.ui.OrbitPermissionState
import com.orbiteq.app.ui.OrbitStatusChip
import com.orbiteq.app.ui.SectionLabelText
import com.orbiteq.app.ui.components.ISO_31_LOG_FRACTIONS
import com.orbiteq.app.ui.components.buildCatmullRomPath
import com.orbiteq.app.ui.icons.OrbitIcons
import com.orbiteq.app.ui.localizedSpectralTag
import com.orbiteq.app.ui.modeDisplayName
import com.orbiteq.app.ui.outputDeviceIcon
import com.orbiteq.app.ui.resolveOutputDeviceName
import com.orbiteq.app.ui.theme.LocalOrbitColors
import com.orbiteq.app.ui.theme.SpaceMonoTabularStyle
import java.util.Locale
import kotlin.math.abs

private enum class AutoEqImportTab {
    CATALOG,
    FILE,
    PASTE,
}

@Composable
fun ActivityScreen(
    uiState: OrbitUiState,
    permissionState: OrbitPermissionState,
    contentPadding: PaddingValues,
    onHeadphoneProfileChange: (HeadphoneProfile) -> Unit,
    onDismissQ30Tip: () -> Unit,
    onSessionModeChange: (SessionAttachmentMode) -> Unit,
    onSetAppRule: (AppRule) -> Unit,
    onRemoveAppRule: (String) -> Unit,
    onTogglePinTrack: (String, String, PleasureMode) -> Unit,
    onThemeModeChange: (OrbitThemeMode) -> Unit,
    onRequestRecordAudio: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onRequestBatteryOptimizationExemption: () -> Unit,
    statusBannerMessage: String? = null,
    onDismissStatusBanner: () -> Unit = {},
    onPostStatusBanner: (String) -> Unit = {},
    onSelectCustomHeadphoneProfile: (String) -> Unit = {},
    onDeleteCustomHeadphoneProfile: (String) -> Unit = {},
    onImportCatalogHeadphone: (CatalogHeadphoneEntry) -> Unit = {},
    onImportAutoEqText: (String, String) -> AutoEqParseResult = { _, text -> AutoEqParser.parse(text) },
    onPickAutoEqFile: () -> Unit = {},
    onExportBackupJson: () -> Unit = {},
    onImportBackupJson: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
    onShareDiagnostics: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val layoutDirection = LocalLayoutDirection.current
    var editingRulePkg by rememberSaveable { mutableStateOf<String?>(null) }
    var showAutoEqImportDialog by rememberSaveable { mutableStateOf(false) }

    val sortedRules = remember(uiState.settings.appRules, uiState.nowPlaying.packageName) {
        val activePkg = uiState.nowPlaying.packageName?.trim()?.lowercase(Locale.ROOT)
        uiState.settings.appRules.values.sortedWith(
            compareByDescending<AppRule> { it.packageName.lowercase(Locale.ROOT) == activePkg }
                .thenBy { it.displayName.lowercase(Locale.ROOT) },
        )
    }

    val currentNowPlayingKey = remember(uiState.nowPlaying.packageName, uiState.nowPlaying.trackTitle) {
        val pkg = uiState.nowPlaying.packageName?.trim().orEmpty()
        val title = uiState.nowPlaying.trackTitle.trim()
        if (pkg.isNotEmpty() && title.isNotEmpty()) AppRuleResolver.trackKey(pkg, title) else null
    }

    val historyByTrackKey = remember(uiState.recentActivity) {
        uiState.recentActivity.associateBy { AppRuleResolver.trackKey(it.packageName, it.trackTitle) }
    }

    val startPad = contentPadding.calculateStartPadding(layoutDirection) + 16.dp
    val endPad = contentPadding.calculateEndPadding(layoutDirection) + 16.dp
    val topPad = contentPadding.calculateTopPadding()
    val bottomPad = contentPadding.calculateBottomPadding()
    val hasBanner = !statusBannerMessage.isNullOrBlank()
    var lastBannerMessage by rememberSaveable { mutableStateOf("") }
    if (!statusBannerMessage.isNullOrBlank()) {
        lastBannerMessage = statusBannerMessage
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 720.dp),
    ) {
        AnimatedVisibility(visible = hasBanner) {
            if (lastBannerMessage.isNotBlank()) {
                StatusFeedbackBanner(
                    message = lastBannerMessage,
                    onDismiss = onDismissStatusBanner,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = startPad,
                            end = endPad,
                            top = topPad,
                            bottom = 10.dp,
                        ),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("activity_list"),
            contentPadding = PaddingValues(
                start = startPad,
                end = endPad,
                top = if (hasBanner) 2.dp else topPad,
                bottom = bottomPad,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "live_engine_telemetry") {
                LiveEngineTelemetryCard(
                    uiState = uiState,
                    onSessionModeChange = { mode ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSessionModeChange(mode)
                    },
                )
            }

            item(key = "q30_headphone_compensation") {
                HeadphoneCompensationCard(
                    uiState = uiState,
                    onSelectProfile = { profile ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onHeadphoneProfileChange(profile)
                    },
                    onSelectCustomProfile = { profileId ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectCustomHeadphoneProfile(profileId)
                    },
                    onDeleteCustomProfile = { profileId ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDeleteCustomHeadphoneProfile(profileId)
                    },
                    onOpenAutoEqImport = { showAutoEqImportDialog = true },
                    onDismissQ30Tip = onDismissQ30Tip,
                )
            }

            item(key = "guided_permissions") {
                GuidedSetupCard(
                    permissionState = permissionState,
                    onRequestRecordAudio = onRequestRecordAudio,
                    onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                    onRequestPostNotifications = onRequestPostNotifications,
                    onRequestBatteryOptimizationExemption = onRequestBatteryOptimizationExemption,
                )
            }

            item(key = "app_rules") {
                AppSmartRulesCard(
                    uiState = uiState,
                    rules = sortedRules,
                    onAddActiveAppRule = { pkg, appName ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSetAppRule(
                            AppRule(
                                packageName = pkg,
                                displayName = appName,
                                action = AppRuleAction.USE_MODE,
                                mode = uiState.activeMode,
                            ),
                        )
                    },
                    onEditRule = { rule -> editingRulePkg = rule.packageName },
                )
            }

            if (uiState.settings.pinnedTracks.isNotEmpty()) {
                item(key = "pinned_tracks") {
                    PinnedTracksCard(
                        pinnedTracks = uiState.settings.pinnedTracks,
                        historyByTrackKey = historyByTrackKey,
                        appRules = uiState.settings.appRules,
                        onUnpin = { pkg, title, mode ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTogglePinTrack(pkg, title, mode)
                        },
                    )
                }
            }

            item(key = "recent_activity_header") {
                SectionLabelText(
                    text = stringResource(R.string.activity_recent_title),
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                )
            }

            if (uiState.recentActivity.isEmpty()) {
                item(key = "recent_activity_empty") {
                    OrbitGlassCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = OrbitIcons.MusicNote,
                                contentDescription = null,
                                tint = LocalOrbitColors.current.textMuted,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.activity_empty),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = LocalOrbitColors.current.textSecondary,
                                ),
                            )
                        }
                    }
                }
            } else {
                items(
                    items = uiState.recentActivity,
                    key = { it.id },
                ) { entry ->
                    val trackKey = AppRuleResolver.trackKey(entry.packageName, entry.trackTitle)
                    val pinnedMode = uiState.settings.pinnedTracks[trackKey]
                    val isPinned = pinnedMode != null
                    val pinTargetMode = if (trackKey == currentNowPlayingKey) {
                        uiState.activeMode
                    } else {
                        entry.modeUsed
                    }
                    RecentActivityRowCard(
                        entry = entry,
                        pinnedMode = pinnedMode,
                        isPinned = isPinned,
                        onTogglePin = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTogglePinTrack(
                                entry.packageName,
                                entry.trackTitle,
                                pinnedMode ?: pinTargetMode,
                            )
                        },
                    )
                }
            }

            item(key = "appearance_theme_card") {
                AppearanceThemeCard(
                    selectedTheme = uiState.settings.themeMode,
                    onThemeModeChange = { theme ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onThemeModeChange(theme)
                    },
                )
            }

            item(key = "backup_diagnostics_card") {
                BackupDiagnosticsCard(
                    onExportBackupJson = onExportBackupJson,
                    onImportBackupJson = onImportBackupJson,
                    onCopyDiagnostics = onCopyDiagnostics,
                    onShareDiagnostics = onShareDiagnostics,
                )
            }
        }
    }

    if (showAutoEqImportDialog) {
        AutoEqImportDialog(
            onDismiss = { showAutoEqImportDialog = false },
            onSelectCatalogEntry = { entry ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onImportCatalogHeadphone(entry)
                onPostStatusBanner(context.getString(R.string.autoeq_catalog_selected_fmt, entry.name))
                showAutoEqImportDialog = false
            },
            onPickFile = {
                showAutoEqImportDialog = false
                onPickAutoEqFile()
            },
            onImportPastedText = { name, rawText ->
                val result = onImportAutoEqText(name, rawText)
                if (result is AutoEqParseResult.Success) {
                    onPostStatusBanner(context.getString(R.string.autoeq_import_success_fmt, name))
                    showAutoEqImportDialog = false
                }
                result
            },
        )
    }

    val editingRule = editingRulePkg?.let { pkg ->
        uiState.settings.appRules[pkg]
            ?: sortedRules.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }
    }
    editingRule?.let { rule ->
        AppRuleEditorDialog(
            rule = rule,
            onDismiss = { editingRulePkg = null },
            onSelectMode = { mode ->
                onSetAppRule(rule.copy(action = AppRuleAction.USE_MODE, mode = mode))
                editingRulePkg = null
            },
            onSelectBypass = {
                onSetAppRule(rule.copy(action = AppRuleAction.BYPASS))
                editingRulePkg = null
            },
            onRemoveRule = {
                onRemoveAppRule(rule.packageName)
                editingRulePkg = null
            },
        )
    }
}

@Composable
private fun StatusFeedbackBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOrbitColors.current
    val dismissCd = stringResource(R.string.banner_dismiss_cd)

    OrbitGlassCard(
        modifier = modifier,
        highlighted = true,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = OrbitIcons.CheckCircle,
                contentDescription = null,
                tint = colors.ionAccent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.textPrimary,
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        role = Role.Button,
                        onClick = onDismiss,
                    )
                    .semantics {
                        contentDescription = dismissCd
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = OrbitIcons.Close,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveEngineTelemetryCard(
    uiState: OrbitUiState,
    onSessionModeChange: (SessionAttachmentMode) -> Unit,
) {
    val colors = LocalOrbitColors.current
    val telemetry = uiState.telemetry
    val effectiveBypass = uiState.settings.bypassed || uiState.autoBypassedByRule
    val effectiveStatus = when {
        !uiState.settings.powerEnabled -> EngineStatus.IDLE
        effectiveBypass -> EngineStatus.BYPASSED
        else -> telemetry.engineStatus
    }

    val statusText = when (effectiveStatus) {
        EngineStatus.AUTO_FFT_LOCKED -> stringResource(R.string.hud_status_fft_locked)
        EngineStatus.PROFILE_FALLBACK -> stringResource(R.string.hud_status_profile_fallback)
        EngineStatus.BYPASSED -> stringResource(R.string.hud_status_bypassed)
        EngineStatus.IDLE -> stringResource(R.string.hud_status_idle)
    }
    val statusAccent = when (effectiveStatus) {
        EngineStatus.AUTO_FFT_LOCKED -> colors.liveAudioPrimary
        EngineStatus.PROFILE_FALLBACK -> colors.textSecondary
        EngineStatus.BYPASSED -> colors.danger
        EngineStatus.IDLE -> colors.textMuted
    }

    val sessionLabel = if (telemetry.activeSessionId == 0) {
        stringResource(R.string.telemetry_session_global)
    } else {
        stringResource(R.string.telemetry_session_fmt, telemetry.activeSessionId)
    }

    val sampleRateLabel = remember(telemetry.sampleRateHz) {
        val khz = telemetry.sampleRateHz.coerceAtLeast(8000) / 1000f
        if (khz % 1f == 0f) {
            String.format(Locale.US, "%d kHz", khz.toInt())
        } else {
            String.format(Locale.US, "%.1f kHz", khz)
        }
    }

    val (backendTitle, backendSub) = when (telemetry.dspBackend) {
        DspBackend.DYNAMICS_PROCESSING -> {
            val title = if (telemetry.mbcBandCount > 0) {
                stringResource(
                    R.string.telemetry_backend_dp_fmt,
                    telemetry.preEqBandCount,
                    telemetry.mbcBandCount,
                )
            } else if (telemetry.preEqBandCount >= 31) {
                stringResource(R.string.telemetry_backend_dp_31)
            } else {
                stringResource(R.string.telemetry_backend_dp)
            }
            title to stringResource(R.string.telemetry_backend_dp_sub)
        }
        DspBackend.LEGACY_EQ -> stringResource(R.string.telemetry_backend_legacy) to
            stringResource(R.string.telemetry_backend_legacy_sub)
        DspBackend.NONE -> stringResource(R.string.telemetry_backend_none) to
            stringResource(R.string.telemetry_backend_none_sub)
    }

    val fftSub = if (effectiveStatus == EngineStatus.AUTO_FFT_LOCKED) {
        stringResource(R.string.telemetry_fft_sub_live)
    } else {
        stringResource(R.string.telemetry_fft_sub_fallback)
    }

    OrbitGlassCard(highlighted = effectiveStatus == EngineStatus.AUTO_FFT_LOCKED) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabelText(
                text = stringResource(R.string.activity_telemetry_title),
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            OrbitStatusChip(text = statusText, accentColor = statusAccent)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TelemetryStatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.telemetry_output_label),
                value = resolveOutputDeviceName(uiState.connectedDevice),
                icon = outputDeviceIcon(uiState.connectedDevice),
                accent = if (uiState.connectedDevice.isSoundcoreQ30) colors.ionAccent else colors.textSecondary,
            )
            TelemetryStatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.telemetry_sample_rate_label),
                value = "$sessionLabel · $sampleRateLabel",
                icon = OrbitIcons.ActivityOrbit,
                accent = colors.textSecondary,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.borderHairline),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = backendTitle,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        ),
                    )
                    Text(
                        text = backendSub,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = colors.textSecondary,
                        ),
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = colors.borderHairline)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.telemetry_fft_title),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        ),
                    )
                    Text(
                        text = fftSub,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = colors.textSecondary,
                        ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.telemetry_session_mode_label),
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.sp,
                color = colors.textSecondary,
            ),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                SessionAttachmentMode.AUTO to stringResource(R.string.session_mode_auto),
                SessionAttachmentMode.GLOBAL_ONLY to stringResource(R.string.session_mode_global),
            ).forEach { (mode, label) ->
                val selected = uiState.settings.sessionMode == mode
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
                            onClick = { onSessionModeChange(mode) },
                        )
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 12.sp,
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
private fun TelemetryStatTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOrbitColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.borderHairline),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        color = colors.textMuted,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = SpaceMonoTabularStyle.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HeadphoneCompensationCard(
    uiState: OrbitUiState,
    onSelectProfile: (HeadphoneProfile) -> Unit,
    onSelectCustomProfile: (String) -> Unit,
    onDeleteCustomProfile: (String) -> Unit,
    onOpenAutoEqImport: () -> Unit,
    onDismissQ30Tip: () -> Unit,
) {
    val colors = LocalOrbitColors.current
    val deleteCd = stringResource(R.string.autoeq_delete_cd)
    val showQ30Banner = (
        uiState.connectedDevice.isSoundcoreQ30 ||
            uiState.activeHeadphoneProfile == HeadphoneProfile.Q30_CLEAN_BASE ||
            uiState.activeHeadphoneProfile == HeadphoneProfile.Q30_SAFE_STACK
        ) && !uiState.settings.q30TipDismissed

    val profiles = listOf(
        Triple(
            HeadphoneProfile.Q30_CLEAN_BASE,
            stringResource(R.string.profile_q30_clean_base),
            stringResource(R.string.profile_q30_clean_base_desc),
        ),
        Triple(
            HeadphoneProfile.Q30_SAFE_STACK,
            stringResource(R.string.profile_q30_safe_stack),
            stringResource(R.string.profile_q30_safe_stack_desc),
        ),
        Triple(
            HeadphoneProfile.FLAT_GENERIC,
            stringResource(R.string.profile_flat_generic),
            stringResource(R.string.profile_flat_generic_desc),
        ),
        Triple(
            HeadphoneProfile.PHONE_SPEAKER_SAFE,
            stringResource(R.string.profile_phone_speaker),
            stringResource(R.string.profile_phone_speaker_desc),
        ),
    )

    OrbitGlassCard(highlighted = uiState.connectedDevice.isSoundcoreQ30) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabelText(
                text = stringResource(R.string.headphone_profile_title),
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            if (uiState.connectedDevice.isSoundcoreQ30) {
                OrbitStatusChip(
                    text = stringResource(R.string.q30_detected_badge),
                    accentColor = colors.ionAccent,
                )
            }
        }

        AnimatedVisibility(visible = showQ30Banner) {
            Column {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = colors.primaryAccent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, colors.borderActive),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = OrbitIcons.Headphones,
                                contentDescription = null,
                                tint = colors.ionAccent,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.q30_tip_title),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary,
                                ),
                            )
                        }
                        Text(
                            text = stringResource(R.string.q30_tip_body),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = colors.textSecondary,
                            ),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(
                                onClick = onDismissQ30Tip,
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, colors.borderActive),
                            ) {
                                Text(
                                    text = stringResource(R.string.q30_tip_dismiss),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontSize = 12.sp,
                                        color = colors.textPrimary,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            profiles.forEach { (profile, title, desc) ->
                val selected = uiState.activeCustomHeadphoneProfile == null &&
                    uiState.activeHeadphoneProfile == profile
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.surfaceElevated else colors.surface)
                        .border(
                            width = 1.dp,
                            color = if (selected) colors.borderActive else colors.borderHairline,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelectProfile(profile) },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            ),
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = colors.textSecondary,
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (selected) OrbitIcons.CheckCircle else OrbitIcons.ChevronRight,
                        contentDescription = null,
                        tint = if (selected) colors.ionAccent else colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            uiState.settings.customHeadphoneProfiles.forEach { custom ->
                val selected = uiState.activeCustomHeadphoneProfile?.id == custom.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.surfaceElevated else colors.surface)
                        .border(
                            width = 1.dp,
                            color = if (selected) colors.borderActive else colors.borderHairline,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelectCustomProfile(custom.id) },
                        )
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = custom.name,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OrbitStatusChip(
                            text = stringResource(R.string.autoeq_custom_badge, custom.sourceLabel),
                            accentColor = if (selected) colors.ionAccent else colors.textSecondary,
                        )
                        if (selected && custom.gains31Db.size == 31) {
                            HeadphoneCurveSparkline(
                                gains31Db = custom.gains31Db,
                                profileName = custom.name,
                                accentColor = colors.liveAudioPrimary,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    if (selected) {
                        Icon(
                            imageVector = OrbitIcons.CheckCircle,
                            contentDescription = null,
                            tint = colors.ionAccent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                role = Role.Button,
                                onClick = { onDeleteCustomProfile(custom.id) },
                            )
                            .semantics {
                                contentDescription = "$deleteCd ${custom.name}"
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

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onOpenAutoEqImport,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colors.borderActive),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = OrbitIcons.Import,
                contentDescription = null,
                tint = colors.ionAccent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.autoeq_import_btn),
                style = MaterialTheme.typography.labelLarge.copy(
                    color = colors.textPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HeadphoneCurveSparkline(
    gains31Db: List<Float>,
    profileName: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOrbitColors.current
    val sparklineCd = stringResource(R.string.autoeq_sparkline_cd_fmt, profileName)
    val xs = remember { FloatArray(31) }
    val ys = remember { FloatArray(31) }
    val curvePath = remember { Path() }
    val fillPath = remember { Path() }

    val maxAbsDb = remember(gains31Db) {
        var peak = 6f
        for (i in 0 until minOf(31, gains31Db.size)) {
            val v = gains31Db[i]
            if (v.isFinite()) {
                val mag = abs(v).coerceAtMost(15f)
                if (mag > peak) peak = mag
            }
        }
        peak
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.background.copy(alpha = 0.55f))
            .border(1.dp, colors.borderHairline, RoundedCornerShape(8.dp))
            .testTag("headphone_curve_sparkline")
            .semantics {
                contentDescription = sparklineCd
            },
    ) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f) return@Canvas

        val padX = 4.dp.toPx()
        val padY = 4.dp.toPx()
        val plotW = (w - padX * 2f).coerceAtLeast(1f)
        val plotH = (h - padY * 2f).coerceAtLeast(1f)
        val zeroY = padY + plotH * 0.5f

        for (i in 0 until 31) {
            xs[i] = padX + ISO_31_LOG_FRACTIONS[i] * plotW
            val raw = gains31Db.getOrElse(i) { 0f }
            val safeDb = if (raw.isFinite()) raw.coerceIn(-maxAbsDb, maxAbsDb) else 0f
            val norm = ((maxAbsDb - safeDb) / (2f * maxAbsDb)).coerceIn(0f, 1f)
            ys[i] = padY + norm * plotH
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
        fillPath.lineTo(xs[30], zeroY)
        fillPath.lineTo(xs[0], zeroY)
        fillPath.close()

        drawLine(
            color = colors.gridMajor,
            start = Offset(padX, zeroY),
            end = Offset(w - padX, zeroY),
            strokeWidth = 0.75.dp.toPx(),
        )
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.24f),
                    Color.Transparent,
                ),
                startY = padY,
                endY = h - padY,
            ),
        )
        drawPath(
            path = curvePath,
            color = accentColor,
            style = Stroke(
                width = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
private fun AutoEqImportDialog(
    onDismiss: () -> Unit,
    onSelectCatalogEntry: (CatalogHeadphoneEntry) -> Unit,
    onPickFile: () -> Unit,
    onImportPastedText: (String, String) -> AutoEqParseResult,
) {
    val colors = LocalOrbitColors.current
    var selectedTab by rememberSaveable { mutableStateOf(AutoEqImportTab.CATALOG) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var nameInput by rememberSaveable { mutableStateOf("") }
    var rawTextInput by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedCategory = remember(selectedCategoryName) {
        selectedCategoryName?.let { name ->
            CatalogCategory.entries.firstOrNull { it.name == name }
        }
    }
    val filteredCatalogEntries = remember(searchQuery, selectedCategory) {
        AutoEqCatalog.filter(searchQuery, selectedCategory)
    }

    val emptyNameError = stringResource(R.string.autoeq_error_empty_name)
    val invalidFormatError = stringResource(R.string.autoeq_error_invalid_format)

    val tabs = listOf(
        AutoEqImportTab.CATALOG to stringResource(R.string.autoeq_tab_catalog),
        AutoEqImportTab.FILE to stringResource(R.string.autoeq_tab_file),
        AutoEqImportTab.PASTE to stringResource(R.string.autoeq_tab_paste),
    )
    val categoryFilters = listOf(
        null to stringResource(R.string.autoeq_cat_all),
        CatalogCategory.Q30 to stringResource(R.string.autoeq_cat_q30),
        CatalogCategory.OVER_EAR to stringResource(R.string.autoeq_cat_over_ear),
        CatalogCategory.TWS to stringResource(R.string.autoeq_cat_tws),
        CatalogCategory.IEM to stringResource(R.string.autoeq_cat_iem),
        CatalogCategory.STUDIO to stringResource(R.string.autoeq_cat_studio),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = stringResource(R.string.autoeq_dialog_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    color = colors.textPrimary,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tabs.forEach { (tab, label) ->
                        val selected = tab == selectedTab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) colors.primaryAccent else colors.surface)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) colors.borderActive else colors.borderHairline,
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .selectable(
                                    selected = selected,
                                    role = Role.Tab,
                                    onClick = {
                                        selectedTab = tab
                                        errorMessage = null
                                    },
                                )
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 13.sp,
                                    color = if (selected) colors.onPrimaryAccent else colors.textSecondary,
                                ),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Keep validation feedback pinned above scroll area so it remains visible with IME open
                val currentError = errorMessage
                if (selectedTab == AutoEqImportTab.PASTE && !currentError.isNullOrBlank()) {
                    Text(
                        text = currentError,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("autoeq_paste_error"),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = colors.danger,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }

                when (selectedTab) {
                    AutoEqImportTab.CATALOG -> {
                        val clearSearchCd = stringResource(R.string.autoeq_catalog_clear_search_cd)
                        Text(
                            text = stringResource(R.string.autoeq_catalog_hint),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = colors.textSecondary,
                            ),
                        )

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { if (it.length <= 60) searchQuery = it },
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.autoeq_catalog_search_hint),
                                    color = colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable(
                                                role = Role.Button,
                                                onClick = { searchQuery = "" },
                                            )
                                            .semantics {
                                                contentDescription = clearSearchCd
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = OrbitIcons.Close,
                                            contentDescription = null,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("catalog_search_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                focusedBorderColor = colors.ionAccent,
                                unfocusedBorderColor = colors.borderHairline,
                                cursorColor = colors.ionAccent,
                            ),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            categoryFilters.forEach { (category, label) ->
                                val selected = category == selectedCategory
                                val chipTag = "catalog_cat_${category?.name?.lowercase(Locale.ROOT) ?: "all"}"
                                Box(
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag(chipTag)
                                        .selectable(
                                            selected = selected,
                                            role = Role.RadioButton,
                                            onClick = { selectedCategoryName = category?.name },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (selected) colors.primaryAccent else colors.surface)
                                            .border(
                                                width = 1.dp,
                                                color = if (selected) colors.borderActive else colors.borderHairline,
                                                shape = RoundedCornerShape(999.dp),
                                            )
                                            .padding(horizontal = 12.dp, vertical = 7.dp),
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
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = stringResource(R.string.autoeq_catalog_count_fmt, filteredCatalogEntries.size),
                            style = SpaceMonoTabularStyle.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textMuted,
                            ),
                            maxLines = 1,
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 210.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (filteredCatalogEntries.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.autoeq_catalog_empty),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = colors.textSecondary,
                                    ),
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            } else {
                                filteredCatalogEntries.forEach { entry ->
                                    val categoryLabel = catalogCategoryLabel(entry.category)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 52.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(colors.surface)
                                            .border(1.dp, colors.borderHairline, RoundedCornerShape(12.dp))
                                            .clickable(
                                                role = Role.Button,
                                                onClick = { onSelectCatalogEntry(entry) },
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
                                                text = entry.name,
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.textPrimary,
                                                ),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${entry.brand} · $categoryLabel",
                                                style = SpaceMonoTabularStyle.copy(
                                                    fontSize = 10.sp,
                                                    color = colors.textMuted,
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = OrbitIcons.ChevronRight,
                                            contentDescription = null,
                                            tint = colors.ionAccent,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AutoEqImportTab.FILE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.autoeq_file_hint),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colors.textSecondary,
                                ),
                            )
                            Button(
                                onClick = onPickFile,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primaryAccent,
                                    contentColor = colors.onPrimaryAccent,
                                ),
                            ) {
                                Icon(
                                    imageVector = OrbitIcons.Import,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.autoeq_file_pick_btn),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    AutoEqImportTab.PASTE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.autoeq_paste_helper),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colors.textSecondary,
                                ),
                            )

                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = {
                                    if (it.length <= 60) {
                                        nameInput = it
                                        errorMessage = null
                                    }
                                },
                                singleLine = true,
                                label = {
                                    Text(text = stringResource(R.string.autoeq_name_label))
                                },
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.autoeq_name_hint),
                                        color = colors.textMuted,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary,
                                    focusedBorderColor = colors.ionAccent,
                                    unfocusedBorderColor = colors.borderHairline,
                                    focusedLabelColor = colors.ionAccent,
                                    unfocusedLabelColor = colors.textSecondary,
                                    cursorColor = colors.ionAccent,
                                ),
                            )

                            OutlinedTextField(
                                value = rawTextInput,
                                onValueChange = {
                                    rawTextInput = it
                                    errorMessage = null
                                },
                                minLines = 4,
                                maxLines = 7,
                                label = {
                                    Text(text = stringResource(R.string.autoeq_text_label))
                                },
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.autoeq_text_hint),
                                        color = colors.textMuted,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary,
                                    focusedBorderColor = colors.ionAccent,
                                    unfocusedBorderColor = colors.borderHairline,
                                    focusedLabelColor = colors.ionAccent,
                                    unfocusedLabelColor = colors.textSecondary,
                                    cursorColor = colors.ionAccent,
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedTab == AutoEqImportTab.PASTE) {
                Button(
                    onClick = {
                        val trimmedName = nameInput.trim()
                        if (trimmedName.isEmpty()) {
                            errorMessage = emptyNameError
                            return@Button
                        }
                        val result = onImportPastedText(trimmedName, rawTextInput)
                        if (result is AutoEqParseResult.Error) {
                            errorMessage = invalidFormatError
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryAccent,
                        contentColor = colors.onPrimaryAccent,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.autoeq_import_save),
                        maxLines = 1,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.autoeq_import_cancel),
                    color = colors.textSecondary,
                )
            }
        },
    )
}

@Composable
private fun catalogCategoryLabel(category: CatalogCategory): String = when (category) {
    CatalogCategory.Q30 -> stringResource(R.string.autoeq_cat_q30)
    CatalogCategory.OVER_EAR -> stringResource(R.string.autoeq_cat_over_ear)
    CatalogCategory.TWS -> stringResource(R.string.autoeq_cat_tws)
    CatalogCategory.IEM -> stringResource(R.string.autoeq_cat_iem)
    CatalogCategory.STUDIO -> stringResource(R.string.autoeq_cat_studio)
}

@Composable
private fun GuidedSetupCard(
    permissionState: OrbitPermissionState,
    onRequestRecordAudio: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onRequestBatteryOptimizationExemption: () -> Unit,
) {
    OrbitGlassCard {
        SectionLabelText(text = stringResource(R.string.setup_section_title))
        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PermissionSetupRow(
                title = stringResource(R.string.setup_fft_title),
                description = stringResource(R.string.setup_fft_desc),
                isGranted = permissionState.hasRecordAudio,
                grantedLabel = stringResource(R.string.status_granted),
                actionLabel = stringResource(R.string.action_grant),
                onAction = onRequestRecordAudio,
            )
            PermissionSetupRow(
                title = stringResource(R.string.setup_notif_listener_title),
                description = stringResource(R.string.setup_notif_listener_desc),
                isGranted = permissionState.hasNotificationAccess,
                grantedLabel = stringResource(R.string.status_granted),
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = onOpenNotificationListenerSettings,
            )
            PermissionSetupRow(
                title = stringResource(R.string.setup_notifications_title),
                description = stringResource(R.string.setup_notifications_desc),
                isGranted = permissionState.hasPostNotifications,
                grantedLabel = stringResource(R.string.status_granted),
                actionLabel = stringResource(R.string.action_grant),
                onAction = onRequestPostNotifications,
            )
            PermissionSetupRow(
                title = stringResource(R.string.setup_battery_title),
                description = stringResource(R.string.setup_battery_desc),
                isGranted = permissionState.isBatteryOptimizationIgnored,
                grantedLabel = stringResource(R.string.status_exempt),
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = onRequestBatteryOptimizationExemption,
            )
        }
    }
}

@Composable
private fun PermissionSetupRow(
    title: String,
    description: String,
    isGranted: Boolean,
    grantedLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val colors = LocalOrbitColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isGranted) colors.borderHairline else colors.borderActive,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (isGranted) OrbitIcons.CheckCircle else OrbitIcons.WarningInfo,
                        contentDescription = null,
                        tint = if (isGranted) colors.ionAccent else colors.primaryAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        ),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OrbitStatusChip(
                    text = if (isGranted) grantedLabel else stringResource(R.string.status_required),
                    accentColor = if (isGranted) colors.ionAccent else colors.danger,
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.textSecondary,
                ),
            )

            if (!isGranted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onAction,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primaryAccent,
                            contentColor = colors.onPrimaryAccent,
                        ),
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSmartRulesCard(
    uiState: OrbitUiState,
    rules: List<AppRule>,
    onAddActiveAppRule: (String, String) -> Unit,
    onEditRule: (AppRule) -> Unit,
) {
    val colors = LocalOrbitColors.current
    val activePkg = uiState.nowPlaying.packageName
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
    val activePkgToAdd = activePkg?.takeIf { pkg ->
        uiState.settings.appRules.keys.none { it.trim().lowercase(Locale.ROOT) == pkg }
    }
    val activeDisplayName = uiState.nowPlaying.appName.trim().ifEmpty { activePkg.orEmpty() }

    OrbitGlassCard {
        SectionLabelText(text = stringResource(R.string.rules_section_title))
        Spacer(modifier = Modifier.height(10.dp))

        if (activePkgToAdd != null) {
            Button(
                onClick = { onAddActiveAppRule(activePkgToAdd, activeDisplayName) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primaryAccent,
                    contentColor = colors.onPrimaryAccent,
                ),
            ) {
                Icon(
                    imageVector = OrbitIcons.Save,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.rules_add_active_app_fmt, activeDisplayName),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rules.forEach { rule ->
                val isBypass = rule.action == AppRuleAction.BYPASS
                val badgeText = if (isBypass) {
                    stringResource(R.string.rule_action_bypass)
                } else {
                    modeDisplayName(rule.mode)
                }
                val ruleCd = stringResource(R.string.rules_mode_cd_fmt, rule.displayName)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.borderHairline, RoundedCornerShape(12.dp))
                        .clickable(
                            role = Role.Button,
                            onClick = { onEditRule(rule) },
                        )
                        .semantics {
                            contentDescription = "$ruleCd: $badgeText"
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = rule.displayName,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = rule.packageName,
                            style = SpaceMonoTabularStyle.copy(
                                fontSize = 10.sp,
                                color = colors.textMuted,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OrbitStatusChip(
                        text = badgeText,
                        accentColor = if (isBypass) colors.danger else colors.ionAccent,
                    )
                }
            }
        }
    }
}

@Composable
private fun PinnedTracksCard(
    pinnedTracks: Map<String, PleasureMode>,
    historyByTrackKey: Map<String, TrackActivityEntry>,
    appRules: Map<String, AppRule>,
    onUnpin: (String, String, PleasureMode) -> Unit,
) {
    val colors = LocalOrbitColors.current
    val unpinCd = stringResource(R.string.pinned_unpin_cd)

    OrbitGlassCard {
        SectionLabelText(text = stringResource(R.string.pinned_tracks_title))
        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pinnedTracks.entries.forEach { (key, mode) ->
                val parts = key.split('|', limit = 2)
                val pkg = parts.getOrElse(0) { "" }
                val rawTitle = parts.getOrElse(1) { key }
                val matchedEntry = historyByTrackKey[key]
                val displayTitle = matchedEntry?.trackTitle ?: rawTitle
                val normalizedPkg = pkg.trim().lowercase(Locale.ROOT)
                val ruleDisplayName = (appRules[pkg] ?: appRules[normalizedPkg] ?: appRules.entries.firstOrNull { (rulePkg, _) ->
                    rulePkg.trim().lowercase(Locale.ROOT) == normalizedPkg
                }?.value)?.displayName?.takeIf { it.isNotBlank() }
                val displaySub = matchedEntry?.appName?.takeIf { it.isNotBlank() }
                    ?: ruleDisplayName
                    ?: pkg

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.borderActive, RoundedCornerShape(12.dp))
                        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = displaySub,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = colors.textSecondary,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    OrbitStatusChip(
                        text = modeDisplayName(mode),
                        accentColor = colors.ionAccent,
                    )
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                role = Role.Button,
                                onClick = { onUnpin(pkg, rawTitle, mode) },
                            )
                            .semantics {
                                contentDescription = "$unpinCd: $displayTitle"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = OrbitIcons.PinFilled,
                            contentDescription = null,
                            tint = colors.ionAccent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentActivityRowCard(
    entry: TrackActivityEntry,
    pinnedMode: PleasureMode?,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
) {
    val colors = LocalOrbitColors.current
    val pinCd = if (isPinned) {
        stringResource(R.string.pinned_unpin_cd)
    } else {
        stringResource(R.string.pin_track_cd)
    }
    val subtitle = listOf(entry.artist, entry.appName)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { entry.packageName }

    OrbitGlassCard(
        highlighted = isPinned,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.trackTitle,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = colors.textSecondary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OrbitStatusChip(
                        text = modeDisplayName(pinnedMode ?: entry.modeUsed),
                        accentColor = colors.ionAccent,
                    )
                    OrbitStatusChip(
                        text = localizedSpectralTag(entry.spectralTag),
                        accentColor = colors.textSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isPinned) colors.primaryAccent.copy(alpha = 0.16f) else colors.surface,
                    )
                    .border(
                        width = 1.dp,
                        color = if (isPinned) colors.borderActive else colors.borderHairline,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable(
                        role = Role.Button,
                        onClick = onTogglePin,
                    )
                    .semantics {
                        contentDescription = "$pinCd: ${entry.trackTitle}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPinned) OrbitIcons.PinFilled else OrbitIcons.PinOutline,
                    contentDescription = null,
                    tint = if (isPinned) colors.ionAccent else colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AppearanceThemeCard(
    selectedTheme: OrbitThemeMode,
    onThemeModeChange: (OrbitThemeMode) -> Unit,
) {
    val colors = LocalOrbitColors.current
    val options = listOf(
        Triple(
            OrbitThemeMode.DEEP_SPACE_HUD,
            stringResource(R.string.theme_deep_space),
            stringResource(R.string.theme_deep_space_desc),
        ),
        Triple(
            OrbitThemeMode.LUNAR_MONOCHROME,
            stringResource(R.string.theme_lunar_mono),
            stringResource(R.string.theme_lunar_mono_desc),
        ),
    )

    OrbitGlassCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = OrbitIcons.Palette,
                contentDescription = null,
                tint = colors.ionAccent,
                modifier = Modifier.size(16.dp),
            )
            SectionLabelText(text = stringResource(R.string.appearance_section_title))
        }
        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (mode, title, desc) ->
                val selected = mode == selectedTheme
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.surfaceElevated else colors.surface)
                        .border(
                            width = 1.dp,
                            color = if (selected) colors.borderActive else colors.borderHairline,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onThemeModeChange(mode) },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            ),
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = colors.textSecondary,
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (selected) OrbitIcons.CheckCircle else OrbitIcons.ChevronRight,
                        contentDescription = null,
                        tint = if (selected) colors.ionAccent else colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupDiagnosticsCard(
    onExportBackupJson: () -> Unit,
    onImportBackupJson: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onShareDiagnostics: () -> Unit,
) {
    val colors = LocalOrbitColors.current
    OrbitGlassCard {
        SectionLabelText(text = stringResource(R.string.backup_section_title))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.backup_section_desc),
            style = MaterialTheme.typography.bodySmall.copy(
                color = colors.textSecondary,
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackupActionButton(
                label = stringResource(R.string.backup_export_btn),
                icon = OrbitIcons.Export,
                onClick = onExportBackupJson,
                modifier = Modifier.weight(1f),
            )
            BackupActionButton(
                label = stringResource(R.string.backup_import_btn),
                icon = OrbitIcons.Import,
                onClick = onImportBackupJson,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackupActionButton(
                label = stringResource(R.string.diagnostics_copy_btn),
                icon = OrbitIcons.Copy,
                onClick = onCopyDiagnostics,
                modifier = Modifier.weight(1f),
            )
            BackupActionButton(
                label = stringResource(R.string.diagnostics_share_btn),
                icon = OrbitIcons.Share,
                onClick = onShareDiagnostics,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BackupActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOrbitColors.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.borderHairline),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.ionAccent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.sp,
                lineHeight = 14.sp,
                color = colors.textPrimary,
            ),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppRuleEditorDialog(
    rule: AppRule,
    onDismiss: () -> Unit,
    onSelectMode: (PleasureMode) -> Unit,
    onSelectBypass: () -> Unit,
    onRemoveRule: () -> Unit,
) {
    val colors = LocalOrbitColors.current
    val isBypassSelected = rule.action == AppRuleAction.BYPASS

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = stringResource(R.string.rules_edit_title_fmt, rule.displayName),
                style = MaterialTheme.typography.titleLarge.copy(
                    color = colors.textPrimary,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PleasureMode.entries.forEach { mode ->
                    val selected = !isBypassSelected && rule.mode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
                                onClick = { onSelectMode(mode) },
                            )
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = modeDisplayName(mode),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (selected) colors.onPrimaryAccent else colors.textPrimary,
                            ),
                        )
                        if (selected) {
                            Icon(
                                imageVector = OrbitIcons.CheckCircle,
                                contentDescription = null,
                                tint = colors.onPrimaryAccent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isBypassSelected) colors.danger.copy(alpha = 0.22f) else colors.surface,
                        )
                        .border(
                            width = if (isBypassSelected) 1.5.dp else 1.dp,
                            color = if (isBypassSelected) colors.danger else colors.borderHairline,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .selectable(
                            selected = isBypassSelected,
                            role = Role.RadioButton,
                            onClick = onSelectBypass,
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.rule_action_bypass),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        ),
                    )
                    if (isBypassSelected) {
                        Icon(
                            imageVector = OrbitIcons.CheckCircle,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onRemoveRule,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.rules_remove_action),
                    color = colors.danger,
                )
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
