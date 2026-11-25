package com.orbiteq.app.service

import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import com.orbiteq.app.data.OrbitEngineRepository
import com.orbiteq.app.data.OrbitPreferencesStore
import com.orbiteq.app.model.NowPlayingInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class OrbitNotificationListenerService : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private lateinit var listenerComponent: ComponentName
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceScope: CoroutineScope? = null
    private val appLabelCache = ConcurrentHashMap<String, String>()
    private val controllerLock = Any()
    private val trackedControllers = mutableMapOf<MediaController, MediaController.Callback>()
    private var currentExternalControllers: List<MediaController> = emptyList()
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        OrbitEngineRepository.init(applicationContext)
        listenerComponent = ComponentName(this, OrbitNotificationListenerService::class.java)
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        registerSessionsListener()
        refreshActiveSessions()
    }

    override fun onListenerDisconnected() {
        unregisterAllListeners()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unregisterAllListeners()
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    private fun registerSessionsListener() {
        val manager = mediaSessionManager ?: return
        if (sessionsListener != null) return
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onControllersUpdated(controllers.orEmpty())
        }
        try {
            manager.addOnActiveSessionsChangedListener(listener, listenerComponent, mainHandler)
            sessionsListener = listener
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }
    }

    private fun refreshActiveSessions() {
        val manager = mediaSessionManager ?: return
        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
        onControllersUpdated(controllers)
    }

    private fun onControllersUpdated(controllers: List<MediaController>) {
        val externalControllers = controllers.filter { it.packageName != packageName }
        synchronized(controllerLock) {
            clearControllerCallbacksLocked()
            currentExternalControllers = externalControllers
            for (controller in externalControllers) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        val snapshot = synchronized(controllerLock) { currentExternalControllers }
                        publishNowPlayingFromControllers(snapshot)
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        val snapshot = synchronized(controllerLock) { currentExternalControllers }
                        publishNowPlayingFromControllers(snapshot)
                    }

                    override fun onSessionDestroyed() {
                        refreshActiveSessions()
                    }
                }
                try {
                    controller.registerCallback(callback, mainHandler)
                    trackedControllers[controller] = callback
                } catch (_: RuntimeException) {
                }
            }
        }
        publishNowPlayingFromControllers(externalControllers)
    }

    private fun clearControllerCallbacksLocked() {
        for ((controller, callback) in trackedControllers) {
            try {
                controller.unregisterCallback(callback)
            } catch (_: RuntimeException) {
            }
        }
        trackedControllers.clear()
        currentExternalControllers = emptyList()
    }

    private fun unregisterAllListeners() {
        val manager = mediaSessionManager
        val listener = sessionsListener
        sessionsListener = null
        if (manager != null && listener != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(listener)
            } catch (_: RuntimeException) {
            }
        }
        synchronized(controllerLock) {
            clearControllerCallbacksLocked()
        }
    }

    private fun publishNowPlayingFromControllers(controllers: List<MediaController>) {
        val selected = selectPrimaryController(controllers)
        if (selected == null) {
            OrbitEngineRepository.updateNowPlaying(NowPlayingInfo())
            return
        }

        val pkg = selected.packageName
        val metadata = try {
            selected.metadata
        } catch (_: RuntimeException) {
            null
        }
        val playbackState = try {
            selected.playbackState
        } catch (_: RuntimeException) {
            null
        }

        val title = metadata?.let {
            it.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        }?.trim().orEmpty()

        val artist = metadata?.let {
            it.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: it.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        }?.trim().orEmpty()

        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val appName = resolveAppDisplayName(pkg)

        val info = NowPlayingInfo(
            packageName = pkg,
            appName = appName,
            trackTitle = title,
            artist = artist,
            isPlaying = isPlaying,
        )
        OrbitEngineRepository.updateNowPlaying(info)

        if (isPlaying && !OrbitAudioService.isRunning) {
            maybeStartBackgroundAudioService()
        }
    }

    private fun selectPrimaryController(controllers: List<MediaController>): MediaController? {
        if (controllers.isEmpty()) return null
        val playing = controllers.firstOrNull { controller ->
            val state = try {
                controller.playbackState?.state
            } catch (_: RuntimeException) {
                null
            }
            state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        }
        if (playing != null) return playing

        return controllers.firstOrNull { controller ->
            val meta = try {
                controller.metadata
            } catch (_: RuntimeException) {
                null
            }
            !meta?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
        } ?: controllers.firstOrNull()
    }

    private fun maybeStartBackgroundAudioService() {
        val scope = serviceScope ?: return
        scope.launch {
            try {
                val currentSettings = OrbitEngineRepository.uiState.value.settings
                val powerEnabled = if (currentSettings.powerEnabled) {
                    true
                } else {
                    withTimeoutOrNull(PREFS_LOAD_TIMEOUT_MS) {
                        OrbitPreferencesStore(applicationContext).settingsFlow.first().powerEnabled
                    } ?: false
                }
                if (powerEnabled && !OrbitAudioService.isRunning) {
                    OrbitAudioService.startBackgroundSafe(applicationContext)
                }
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun resolveAppDisplayName(packageName: String): String {
        appLabelCache[packageName]?.let { return it }
        val resolved = try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(appInfo).toString().trim().ifEmpty { packageName }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: RuntimeException) {
            packageName
        }
        appLabelCache[packageName] = resolved
        return resolved
    }

    private companion object {
        const val PREFS_LOAD_TIMEOUT_MS = 1200L
    }
}
