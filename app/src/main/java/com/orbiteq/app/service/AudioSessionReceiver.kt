package com.orbiteq.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import com.orbiteq.app.data.OrbitEngineRepository
import com.orbiteq.app.data.OrbitPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

internal object AudioSessionRegistry {

    private val lock = Any()
    private val openSessions = LinkedHashMap<Int, String>()

    fun onSessionOpened(sessionId: Int, packageName: String?) {
        if (sessionId <= 0) return
        val normalizedPkg = packageName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        synchronized(lock) {
            openSessions.remove(sessionId)
            openSessions[sessionId] = normalizedPkg
        }
    }

    fun onSessionClosed(sessionId: Int) {
        if (sessionId <= 0) return
        synchronized(lock) {
            openSessions.remove(sessionId)
        }
    }

    fun resolveActiveSessionId(preferredPackage: String?): Int? {
        val normalizedPreferred = preferredPackage?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            if (openSessions.isEmpty()) return null
            if (normalizedPreferred != null) {
                return openSessions.entries.lastOrNull { (_, pkg) -> pkg == normalizedPreferred }?.key
            }
            return openSessions.keys.lastOrNull()
        }
    }

    internal fun resetForTesting() {
        synchronized(lock) {
            openSessions.clear()
        }
    }
}

class AudioSessionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext

        when (action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
                if (pkg == appContext.packageName || sessionId <= 0) return

                AudioSessionRegistry.onSessionOpened(sessionId, pkg)
                // When OrbitAudioService is already running, its in-process dynamicReceiver handles session updates directly.
                if (!OrbitAudioService.isRunning) {
                    maybeStartBackgroundService(appContext, sessionId, pkg)
                }
            }

            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                if (sessionId <= 0) return
                AudioSessionRegistry.onSessionClosed(sessionId)
            }

            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                // Handled directly by OrbitAudioService.dynamicReceiver when running.
            }
        }
    }

    private fun maybeStartBackgroundService(
        appContext: Context,
        sessionId: Int,
        packageName: String?,
    ) {
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                OrbitEngineRepository.init(appContext)
                val persisted = withTimeoutOrNull(PREFS_LOAD_TIMEOUT_MS) {
                    OrbitPreferencesStore(appContext).settingsFlow.first()
                }
                if (persisted?.powerEnabled == true && !OrbitAudioService.isRunning) {
                    OrbitAudioService.startBackgroundSafe(appContext, sessionId, packageName)
                }
            } catch (_: RuntimeException) {
                // Background FGS start may be blocked by OS policy on Android 12+; ignore safely.
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val PREFS_LOAD_TIMEOUT_MS = 1500L
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
