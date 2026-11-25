package com.orbiteq.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orbiteq.app.dsp.AutoEqCatalog
import com.orbiteq.app.dsp.AutoEqParser
import com.orbiteq.app.dsp.CatalogHeadphoneEntry
import com.orbiteq.app.model.MbcMode
import com.orbiteq.app.model.OrbitSettings
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.ui.OrbitPermissionState
import com.orbiteq.app.ui.screens.ActivityScreen
import com.orbiteq.app.ui.screens.MixerScreen
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

        val mbcOff = str(R.string.mbc_mode_off)
        val mbcSubtle = str(R.string.mbc_mode_subtle)
        val mbcPunchy = str(R.string.mbc_mode_punchy)
        composeRule.onNodeWithTag("mixer_list")
            .performScrollToNode(hasText(mbcPunchy))
        composeRule.onNodeWithText(mbcOff).assertIsDisplayed()
        composeRule.onNodeWithText(mbcSubtle).assertIsDisplayed()
        composeRule.onNodeWithText(mbcPunchy).assertIsDisplayed().performClick()
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
        composeRule.onNodeWithText(firstCatalog.name).assertIsDisplayed().performClick()
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
        composeRule.onNodeWithText(exportLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(importLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(copyLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(shareLabel).assertIsDisplayed().performClick()

        assertTrue(exportClicked)
        assertTrue(importClicked)
        assertTrue(copyClicked)
        assertTrue(shareClicked)
    }
}
