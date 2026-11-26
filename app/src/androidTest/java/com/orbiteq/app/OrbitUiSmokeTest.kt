package com.orbiteq.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbiteq.app.dsp.AutoEqCatalog
import com.orbiteq.app.dsp.AutoEqParser
import com.orbiteq.app.dsp.CatalogHeadphoneEntry
import com.orbiteq.app.model.CustomHeadphoneProfile
import com.orbiteq.app.model.DeEsserMode
import com.orbiteq.app.model.DspBackend
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.LiveAudioTelemetry
import com.orbiteq.app.model.MbcMode
import com.orbiteq.app.model.OrbitSettings
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.ui.OrbitPermissionState
import com.orbiteq.app.ui.screens.ActivityScreen
import com.orbiteq.app.ui.screens.MixerScreen
import com.orbiteq.app.ui.screens.StudioScreen
import com.orbiteq.app.ui.theme.OrbitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbitUiSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun str(resId: Int, vararg args: Any): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
    }

    @Test
    fun mixerScreen_rendersV2ControlsAndInvokesCallbacks() {
        var latestMbcMode: MbcMode? = null
        var latestTrueHeadroom: Boolean? = null
        var latestSubsonic: Boolean? = null
        var latestBalance: Float? = null

        val uiState = OrbitUiState(
            settings = OrbitSettings(
                powerEnabled = true,
                mbcMode = MbcMode.SUBTLE,
                trueHeadroomMode = false,
                subsonicFilterEnabled = true,
                channelBalance = 0.4f,
            ),
        )

        composeRule.setContent {
            OrbitTheme {
                MixerScreen(
                    uiState = uiState,
                    contentPadding = PaddingValues(16.dp),
                    onBandTrimChange = { _, _ -> },
                    onResetTrims = {},
                    onMbcModeChange = { latestMbcMode = it },
                    onSubsonicFilterChange = { latestSubsonic = it },
                    onTrueHeadroomChange = { latestTrueHeadroom = it },
                    onChannelBalanceChange = { latestBalance = it },
                    onResponseSpeedChange = {},
                    onSavePreset = {},
                    onApplyPreset = {},
                    onDeletePreset = {},
                )
            }
        }

        val trueHeadroomTitle = str(R.string.dsp_true_headroom_title)
        composeRule.onNodeWithTag("mixer_list")
            .performScrollToNode(hasText(trueHeadroomTitle))
        composeRule.onNodeWithText(trueHeadroomTitle).assertIsDisplayed().performClick()
        assertEquals(true, latestTrueHeadroom)

        val subsonicTitle = str(R.string.dsp_subsonic_title)
        composeRule.onNodeWithTag("mixer_list")
            .performScrollToNode(hasText(subsonicTitle))
        composeRule.onNodeWithText(subsonicTitle).assertIsDisplayed().performClick()
        assertEquals(false, latestSubsonic)

        composeRule.onNodeWithTag("mixer_list")
            .performScrollToNode(hasTestTag("mbc_mode_punchy"))
        composeRule.onNodeWithTag("mbc_mode_off").assertIsDisplayed()
        composeRule.onNodeWithTag("mbc_mode_subtle").assertIsDisplayed()
        composeRule.onNodeWithTag("mbc_mode_punchy").assertIsDisplayed().performClick()
        assertEquals(MbcMode.PUNCHY, latestMbcMode)

        val balanceTitle = str(R.string.dsp_balance_title)
        val balanceReadout = str(R.string.dsp_balance_right_fmt, 40)
        val balanceReset = str(R.string.dsp_balance_reset)
        composeRule.onNodeWithTag("mixer_list")
            .performScrollToNode(hasText(balanceReset))
        composeRule.onNodeWithText(balanceTitle).assertIsDisplayed()
        composeRule.onNodeWithText(balanceReadout).assertIsDisplayed()
        composeRule.onNodeWithText(balanceReset).assertIsDisplayed().performClick()
        assertEquals(0f, latestBalance ?: -1f, 0.0001f)
    }

    @Test
    fun activityScreen_autoEqDialogAndBackupDiagnosticsCallbacksWork() {
        var selectedCatalogEntry: CatalogHeadphoneEntry? = null
        var importedPasteName: String? = null
        var exportClicked = false
        var importClicked = false
        var copyClicked = false
        var shareClicked = false
        var bannerDismissed = false

        composeRule.setContent {
            OrbitTheme {
                ActivityScreen(
                    uiState = OrbitUiState(),
                    permissionState = OrbitPermissionState(),
                    contentPadding = PaddingValues(16.dp),
                    onHeadphoneProfileChange = {},
                    onDismissQ30Tip = {},
                    onSessionModeChange = {},
                    onSetAppRule = {},
                    onRemoveAppRule = {},
                    onTogglePinTrack = { _, _, _ -> },
                    onThemeModeChange = {},
                    onRequestRecordAudio = {},
                    onOpenNotificationListenerSettings = {},
                    onRequestPostNotifications = {},
                    onRequestBatteryOptimizationExemption = {},
                    statusBannerMessage = "Ready",
                    onDismissStatusBanner = { bannerDismissed = true },
                    onImportCatalogHeadphone = { selectedCatalogEntry = it },
                    onImportAutoEqText = { name, rawText ->
                        importedPasteName = name
                        AutoEqParser.parse(rawText)
                    },
                    onExportBackupJson = { exportClicked = true },
                    onImportBackupJson = { importClicked = true },
                    onCopyDiagnostics = { copyClicked = true },
                    onShareDiagnostics = { shareClicked = true },
                )
            }
        }

        composeRule.onNodeWithText("Ready").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(str(R.string.banner_dismiss_cd)).performClick()
        assertTrue(bannerDismissed)

        val importAutoEqBtn = str(R.string.autoeq_import_btn)
        composeRule.onNodeWithTag("activity_list")
            .performScrollToNode(hasText(importAutoEqBtn))
        composeRule.onNodeWithText(importAutoEqBtn).assertIsDisplayed().performClick()

        composeRule.onNodeWithText(str(R.string.autoeq_dialog_title)).assertIsDisplayed()
        val firstCatalog = AutoEqCatalog.entries.first()
        composeRule.onNodeWithTag("catalog_entry_${firstCatalog.catalogId}")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(firstCatalog, selectedCatalogEntry)

        composeRule.onNodeWithText(importAutoEqBtn).performClick()
        composeRule.onNodeWithText(str(R.string.autoeq_tab_paste)).performClick()
        composeRule.onNodeWithText(str(R.string.autoeq_name_label)).performTextInput("Test Q30")
        composeRule.onNodeWithText(str(R.string.autoeq_text_label))
            .performTextInput("GraphicEQ: 20 -2.0; 1000 0.0; 10000 2.0")
        composeRule.onNodeWithText(str(R.string.autoeq_import_save)).performClick()
        assertEquals("Test Q30", importedPasteName)

        val exportLabel = str(R.string.backup_export_btn)
        val importLabel = str(R.string.backup_import_btn)
        val copyLabel = str(R.string.diagnostics_copy_btn)
        val shareLabel = str(R.string.diagnostics_share_btn)

        composeRule.onNodeWithTag("activity_list")
            .performScrollToNode(hasText(shareLabel))
        composeRule.onNodeWithText("Ready").assertIsDisplayed()
        composeRule.onNodeWithText(exportLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(importLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(copyLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(shareLabel).assertIsDisplayed().performClick()

        assertTrue(exportClicked)
        assertTrue(importClicked)
        assertTrue(copyClicked)
        assertTrue(shareClicked)
    }

    @Test
    fun studioScreen_renders31BandTelemetryAndMatchedBypassChip() {
        var bypassClicked = false
        var selectedMode: PleasureMode? = null
        val uiState = OrbitUiState(
            settings = OrbitSettings(
                powerEnabled = true,
                bypassed = true,
                loudnessMatchedBypass = true,
            ),
            autoBypassedByRule = false,
            telemetry = LiveAudioTelemetry(
                engineStatus = EngineStatus.BYPASSED,
                dspBackend = DspBackend.DYNAMICS_PROCESSING,
                preEqBandCount = 31,
                mbcBandCount = 5,
                bypassMatchGainDb = -3.1f,
                liveEqCurve31Db = List(31) { idx -> if (idx < 10) -2.0f else 1.5f },
            ),
        )

        composeRule.setContent {
            OrbitTheme {
                StudioScreen(
                    uiState = uiState,
                    contentPadding = PaddingValues(16.dp),
                    onTogglePower = {},
                    onToggleBypass = { bypassClicked = true },
                    onSelectMode = { selectedMode = it },
                    onSaveForActiveApp = {},
                    onIntensityChange = {},
                    onBassMacroChange = {},
                    onAirMacroChange = {},
                    onDynamicLoudnessChange = {},
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.hud_status_bypassed)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.ab_matched_chip_fmt, -3.1f)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(str(R.string.ab_bypass_cd)).performClick()
        assertTrue(bypassClicked)

        composeRule.onNodeWithText(str(R.string.mode_gravity_name)).performClick()
        assertEquals(PleasureMode.GRAVITY, selectedMode)
    }

    @Test
    fun mixerScreen_toneAndComfortControlsInvokeCallbacks() {
        var latestToneTilt: Float? = null
        var latestDeEsser: DeEsserMode? = null
        var latestLoudnessMatched: Boolean? = null

        val uiState = OrbitUiState(
            settings = OrbitSettings(
                powerEnabled = true,
                toneTiltDbPerOct = 0.8f,
                deEsserMode = DeEsserMode.GENTLE,
                loudnessMatchedBypass = true,
            ),
        )

        composeRule.setContent {
            OrbitTheme {
                MixerScreen(
                    uiState = uiState,
                    contentPadding = PaddingValues(16.dp),
                    onBandTrimChange = { _, _ -> },
                    onResetTrims = {},
                    onMbcModeChange = {},
                    onSubsonicFilterChange = {},
                    onTrueHeadroomChange = {},
                    onToneTiltChange = { latestToneTilt = it },
                    onDeEsserModeChange = { latestDeEsser = it },
                    onLoudnessMatchedBypassChange = { latestLoudnessMatched = it },
                    onChannelBalanceChange = {},
                    onResponseSpeedChange = {},
                    onSavePreset = {},
                    onApplyPreset = {},
                    onDeletePreset = {},
                )
            }
        }

        composeRule.onNodeWithTag("mixer_list")
            .performScrollToNode(hasText(str(R.string.dsp_tone_comfort_title)))
        composeRule.onNodeWithText(str(R.string.dsp_tone_comfort_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.dsp_tone_tilt_title)).assertIsDisplayed()

        composeRule.onNodeWithTag("tone_tilt_reset_btn").assertIsDisplayed().performClick()
        assertEquals(0f, latestToneTilt ?: -1f, 0.0001f)

        composeRule.onNodeWithTag("mixer_list")
            .performScrollToNode(hasText(str(R.string.dsp_loudness_ab_title)))
        composeRule.onNodeWithTag("deesser_mode_off").assertIsDisplayed()
        composeRule.onNodeWithTag("deesser_mode_gentle").assertIsDisplayed()
        composeRule.onNodeWithTag("deesser_mode_strong").assertIsDisplayed().performClick()
        assertEquals(DeEsserMode.STRONG, latestDeEsser)

        composeRule.onNodeWithTag("loudness_matched_ab_toggle").assertIsDisplayed().performClick()
        assertEquals(false, latestLoudnessMatched)
    }

    @Test
    fun activityScreen_catalogSearchCategoryFiltersAndSparklineWork() {
        var selectedCatalogEntry: CatalogHeadphoneEntry? = null
        val firstQ30 = AutoEqCatalog.entries.first()
        val activeProfile = CustomHeadphoneProfile(
            id = firstQ30.catalogId,
            name = firstQ30.name,
            gains31Db = firstQ30.gains31Db,
            sourceLabel = "AutoEQ Catalog",
        )
        val uiState = OrbitUiState(
            settings = OrbitSettings(
                powerEnabled = true,
                customHeadphoneProfiles = listOf(activeProfile),
                deviceCustomProfileIds = mapOf("speaker" to activeProfile.id),
            ),
            activeCustomHeadphoneProfile = activeProfile,
            telemetry = LiveAudioTelemetry(
                engineStatus = EngineStatus.AUTO_FFT_LOCKED,
                dspBackend = DspBackend.DYNAMICS_PROCESSING,
                preEqBandCount = 31,
                mbcBandCount = 5,
            ),
        )

        composeRule.setContent {
            OrbitTheme {
                ActivityScreen(
                    uiState = uiState,
                    permissionState = OrbitPermissionState(),
                    contentPadding = PaddingValues(16.dp),
                    onHeadphoneProfileChange = {},
                    onDismissQ30Tip = {},
                    onSessionModeChange = {},
                    onSetAppRule = {},
                    onRemoveAppRule = {},
                    onTogglePinTrack = { _, _, _ -> },
                    onThemeModeChange = {},
                    onRequestRecordAudio = {},
                    onOpenNotificationListenerSettings = {},
                    onRequestPostNotifications = {},
                    onRequestBatteryOptimizationExemption = {},
                    onImportCatalogHeadphone = { selectedCatalogEntry = it },
                )
            }
        }

        composeRule.onNodeWithText(str(R.string.telemetry_backend_dp_fmt, 31, 5)).assertIsDisplayed()

        composeRule.onNodeWithTag("activity_list")
            .performScrollToNode(hasTestTag("headphone_curve_sparkline"))
        composeRule.onNodeWithTag("headphone_curve_sparkline").assertIsDisplayed()

        val importAutoEqBtn = str(R.string.autoeq_import_btn)
        composeRule.onNodeWithTag("activity_list")
            .performScrollToNode(hasText(importAutoEqBtn))
        composeRule.onNodeWithText(importAutoEqBtn).performClick()

        composeRule.onNodeWithTag("catalog_search_input").performTextInput("Moondrop")
        composeRule.onNodeWithText("Moondrop Chu II (Harman)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sony WH-1000XM5 (Harman)").assertDoesNotExist()

        composeRule.onNodeWithContentDescription(str(R.string.autoeq_catalog_clear_search_cd)).performClick()
        composeRule.onNodeWithTag("catalog_cat_q30").performScrollTo().performClick()
        composeRule.onNodeWithTag("catalog_entry_catalog_soundcore_q30").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Moondrop Chu II (Harman)").assertDoesNotExist()

        composeRule.onNodeWithTag("catalog_cat_all").performScrollTo().performClick()
        composeRule.onNodeWithTag("catalog_search_input").performTextInput("Q30")
        composeRule.onNodeWithTag("catalog_entry_catalog_soundcore_q30_anc_off")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals("catalog_soundcore_q30_anc_off", selectedCatalogEntry?.catalogId)

        composeRule.onNodeWithText(importAutoEqBtn).performClick()
        composeRule.onNodeWithText(str(R.string.autoeq_tab_paste)).performClick()
        composeRule.onNodeWithText(str(R.string.autoeq_import_save)).performClick()
        composeRule.onNodeWithTag("autoeq_paste_error").assertIsDisplayed()
    }
}
