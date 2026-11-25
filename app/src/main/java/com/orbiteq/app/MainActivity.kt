package com.orbiteq.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.orbiteq.app.ui.OrbitApp
import com.orbiteq.app.ui.OrbitViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: OrbitViewModel by viewModels()

    private var hasPromptedRecordAudioThisSession = false
    private var manualRecordAudioRequestPending = false
    private var manualPostNotifRequestPending = false

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val wasManualRequest = manualRecordAudioRequestPending
        manualRecordAudioRequestPending = false
        viewModel.onRecordAudioPermissionResult(granted)

        if (!granted &&
            wasManualRequest &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        ) {
            openAppDetailsSettings()
        }
    }

    private val postNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val wasManualRequest = manualPostNotifRequestPending
        manualPostNotifRequestPending = false
        viewModel.refreshSystemState()

        if (!granted &&
            wasManualRequest &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            openAppNotificationSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.uiState
                    .map { it.settings.powerEnabled }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        if (enabled) {
                            viewModel.onInitialPersistedPowerActive()
                        }
                    }
            }
        }

        setContent {
            OrbitApp(
                viewModel = viewModel,
                onTogglePower = ::handlePowerToggle,
                onRequestRecordAudio = { launchRecordAudioPermissionRequest(manual = true) },
                onRequestPostNotifications = { launchPostNotificationsPermissionRequest() },
                onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                onRequestBatteryOptimizationExemption = ::requestBatteryOptimizationExemption,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }

    private fun handlePowerToggle(enabled: Boolean) {
        viewModel.setPowerEnabled(enabled)
        if (enabled && !viewModel.permissionState.value.hasRecordAudio && !hasPromptedRecordAudioThisSession) {
            hasPromptedRecordAudioThisSession = true
            launchRecordAudioPermissionRequest(manual = false)
        }
    }

    private fun launchRecordAudioPermissionRequest(manual: Boolean) {
        if (viewModel.permissionState.value.hasRecordAudio) {
            viewModel.onRecordAudioPermissionResult(true)
            return
        }
        manualRecordAudioRequestPending = manual
        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchPostNotificationsPermissionRequest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            viewModel.refreshSystemState()
            return
        }
        if (viewModel.permissionState.value.hasPostNotifications) {
            viewModel.refreshSystemState()
            return
        }
        manualPostNotifRequestPending = true
        postNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        launchSettingsIntentSafely(intent) {
            launchSettingsIntentSafely(Intent(Settings.ACTION_SETTINGS))
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            viewModel.refreshSystemState()
            return
        }
        val directIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        launchSettingsIntentSafely(directIntent) {
            launchSettingsIntentSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) {
                openAppDetailsSettings()
            }
        }
    }

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        launchSettingsIntentSafely(intent) {
            openAppDetailsSettings()
        }
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        launchSettingsIntentSafely(intent)
    }

    private fun launchSettingsIntentSafely(
        intent: Intent,
        fallback: (() -> Unit)? = null,
    ) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            fallback?.invoke()
        } catch (_: SecurityException) {
            fallback?.invoke()
        }
    }
}
