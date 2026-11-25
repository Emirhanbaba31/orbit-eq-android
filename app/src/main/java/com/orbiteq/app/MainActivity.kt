package com.orbiteq.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
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
import com.orbiteq.app.data.OrbitEngineRepository
import com.orbiteq.app.dsp.AutoEqParseResult
import com.orbiteq.app.service.OrbitAudioService
import com.orbiteq.app.ui.OrbitApp
import com.orbiteq.app.ui.OrbitViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val json = viewModel.exportBackupJson()
            val success = withContext(Dispatchers.IO) {
                writeTextToUri(uri, json)
            }
            viewModel.postStatusBanner(
                getString(
                    if (success) R.string.backup_export_success else R.string.backup_export_error,
                ),
            )
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                readTextFromUri(uri, MAX_IMPORT_BYTES)
            }
            val ok = content != null && viewModel.importBackupJson(content)
            viewModel.postStatusBanner(
                getString(
                    if (ok) R.string.backup_import_success else R.string.backup_import_error,
                ),
            )
        }
    }

    private val importAutoEqLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val (profileName, content) = withContext(Dispatchers.IO) {
                resolveCleanProfileNameFromUri(uri) to readTextFromUri(uri, MAX_IMPORT_BYTES)
            }
            if (content == null) {
                viewModel.postStatusBanner(getString(R.string.autoeq_file_read_error))
                return@launch
            }
            when (viewModel.importAutoEqText(profileName, content)) {
                is AutoEqParseResult.Success -> {
                    viewModel.postStatusBanner(
                        getString(R.string.autoeq_import_success_fmt, profileName),
                    )
                }

                is AutoEqParseResult.Error -> {
                    viewModel.postStatusBanner(getString(R.string.autoeq_error_invalid_format))
                }
            }
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

        if (savedInstanceState == null) {
            consumeStartEngineExtra(intent)
        }

        setContent {
            OrbitApp(
                viewModel = viewModel,
                onTogglePower = ::handlePowerToggle,
                onRequestRecordAudio = { launchRecordAudioPermissionRequest(manual = true) },
                onRequestPostNotifications = { launchPostNotificationsPermissionRequest() },
                onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                onRequestBatteryOptimizationExemption = ::requestBatteryOptimizationExemption,
                onExportBackupJson = ::launchExportBackupPicker,
                onImportBackupJson = ::launchImportBackupPicker,
                onPickAutoEqFile = ::launchImportAutoEqPicker,
                onCopyDiagnostics = ::copyDiagnosticsToClipboard,
                onShareDiagnostics = ::shareDiagnosticsReport,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeStartEngineExtra(intent)
    }

    override fun onStart() {
        super.onStart()
        OrbitEngineRepository.setUiVisible(true)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }

    override fun onStop() {
        OrbitEngineRepository.setUiVisible(false)
        super.onStop()
    }

    private fun consumeStartEngineExtra(targetIntent: Intent?) {
        if (targetIntent?.getBooleanExtra(OrbitAudioService.EXTRA_START_ENGINE, false) == true) {
            targetIntent.removeExtra(OrbitAudioService.EXTRA_START_ENGINE)
            handlePowerToggle(true)
        }
    }

    private fun handlePowerToggle(enabled: Boolean) {
        viewModel.setPowerEnabled(enabled)
        if (enabled && !viewModel.permissionState.value.hasRecordAudio && !hasPromptedRecordAudioThisSession) {
            hasPromptedRecordAudioThisSession = true
            launchRecordAudioPermissionRequest(manual = false)
        }
    }

    private fun launchExportBackupPicker() {
        val dateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        try {
            exportBackupLauncher.launch("orbit-eq-backup-$dateStamp.json")
        } catch (_: ActivityNotFoundException) {
            viewModel.postStatusBanner(getString(R.string.backup_export_error))
        }
    }

    private fun launchImportBackupPicker() {
        try {
            importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        } catch (_: ActivityNotFoundException) {
            viewModel.postStatusBanner(getString(R.string.backup_import_error))
        }
    }

    private fun launchImportAutoEqPicker() {
        try {
            importAutoEqLauncher.launch(arrayOf("text/plain", "text/csv", "text/*", "*/*"))
        } catch (_: ActivityNotFoundException) {
            viewModel.postStatusBanner(getString(R.string.autoeq_file_read_error))
        }
    }

    private fun copyDiagnosticsToClipboard() {
        val report = viewModel.buildDiagnosticsReport()
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.diagnostics_share_subject), report),
            )
            viewModel.postStatusBanner(getString(R.string.diagnostics_copied_msg))
        }
    }

    private fun shareDiagnosticsReport() {
        val report = viewModel.buildDiagnosticsReport()
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_share_subject))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        val chooser = Intent.createChooser(sendIntent, getString(R.string.diagnostics_share_btn))
        launchSettingsIntentSafely(chooser)
    }

    private fun writeTextToUri(uri: Uri, text: String): Boolean {
        val payload = text.toByteArray(Charsets.UTF_8)
        return try {
            contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(payload)
                output.flush()
                true
            } ?: false
        } catch (_: Exception) {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(payload)
                    output.flush()
                    true
                } ?: false
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun readTextFromUri(uri: Uri, maxBytes: Int): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        return null
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveCleanProfileNameFromUri(uri: Uri): String {
        val rawName = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment.orEmpty()

        val fileName = rawName.substringAfterLast('/').substringAfterLast(':').trim()
        val withoutExt = fileName.substringBeforeLast('.', missingDelimiterValue = fileName).trim()
        return withoutExt.ifEmpty { "AutoEQ Profile" }
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

    private companion object {
        const val MAX_IMPORT_BYTES = 1024 * 1024
    }
}
