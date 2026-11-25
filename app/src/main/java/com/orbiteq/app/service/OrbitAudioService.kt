package com.orbiteq.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.orbiteq.app.R
import com.orbiteq.app.data.OrbitEngineRepository
import com.orbiteq.app.dsp.AdaptiveEqEngine
import com.orbiteq.app.dsp.PleasureProfiles
import com.orbiteq.app.model.ConnectedOutputDevice
import com.orbiteq.app.model.DspBackend
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.LiveAudioTelemetry
import com.orbiteq.app.model.OrbitUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class OrbitAudioService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dspController = DspEffectController()
    private val visualizerController = VisualizerController()
    private val adaptiveEngine = AdaptiveEqEngine()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val routeReattachPending = AtomicBoolean(false)
    private val sessionReattachPending = AtomicBoolean(false)

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    private var dspLoopJob: Job? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var dynamicReceiver: BroadcastReceiver? = null

    @Volatile
    private var currentTier: FgsTier = FgsTier.TIER2_PROFILE_ONLY

    @Volatile
    private var currentVolumeRatio: Float = 1.0f

    @Volatile
    private var isForegroundPromoted: Boolean = false

    @Volatile
    private var lastNotificationSignature: String? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        OrbitEngineRepository.init(applicationContext)
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
        refreshMusicVolumeRatio()
        registerAudioDeviceMonitor()
        registerDynamicAudioReceiver()
        refreshConnectedOutputDevice(reattachDsp = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_ENGINE) {
            // Satisfy startForegroundService timing contract if not already foregrounded.
            if (!isForegroundPromoted) {
                promoteForegroundSafe(FgsTier.TIER2_PROFILE_ONLY)
            }
            shutdownEngineAndStop()
            return START_NOT_STICKY
        }

        if (action == ACTION_START_FOREGROUND_FULL && !OrbitEngineRepository.uiState.value.settings.powerEnabled) {
            OrbitEngineRepository.setPowerEnabled(true)
        }

        when (action) {
            ACTION_TOGGLE_BYPASS -> {
                val bypassed = OrbitEngineRepository.uiState.value.settings.bypassed
                OrbitEngineRepository.setBypassed(!bypassed)
            }

            ACTION_START_BACKGROUND_SAFE,
            ACTION_UPDATE_SESSION -> {
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                val isOpen = intent.getBooleanExtra(EXTRA_SESSION_OPEN, true)
                val pkg = intent.getStringExtra(EXTRA_SESSION_PACKAGE)
                val routeRefresh = intent.getBooleanExtra(EXTRA_ROUTE_REFRESH, false)
                if (sessionId > 0) {
                    if (isOpen) {
                        AudioSessionRegistry.onSessionOpened(sessionId, pkg)
                    } else {
                        AudioSessionRegistry.onSessionClosed(sessionId)
                    }
                    sessionReattachPending.set(true)
                }
                if (routeRefresh) {
                    refreshConnectedOutputDevice(reattachDsp = true)
                }
            }
        }

        if (FgsTierPlanner.shouldPromoteForeground(isForegroundPromoted, action)) {
            val hasRecordAudio = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            val plannedTier = FgsTierPlanner.planTier(
                startedFromForeground = FgsTierPlanner.isForegroundEligibleAction(action),
                hasRecordAudioPermission = hasRecordAudio,
            )
            promoteForegroundSafe(plannedTier)
        }

        ensureDspLoopRunning()
        wakeSignal.trySend(Unit)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        isForegroundPromoted = false
        dspLoopJob?.cancel()
        dspLoopJob = null

        unregisterAudioDeviceMonitor()
        unregisterDynamicAudioReceiver()

        visualizerController.stop()
        dspController.release()
        adaptiveEngine.reset()

        OrbitEngineRepository.updateTelemetry(
            LiveAudioTelemetry(
                engineStatus = EngineStatus.IDLE,
                dspBackend = DspBackend.NONE,
                activeSessionId = 0,
            ),
        )

        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteForegroundSafe(plannedTier: FgsTier) {
        val state = OrbitEngineRepository.uiState.value
        var effectiveTier = plannedTier
        val initialNotification = buildNotification(
            state = state,
            status = state.telemetry.engineStatus,
            tier = effectiveTier,
        )
        try {
            startForegroundWithTier(effectiveTier, initialNotification)
            isForegroundPromoted = true
        } catch (_: SecurityException) {
            effectiveTier = FgsTier.TIER2_PROFILE_ONLY
            try {
                val fallbackNotification = buildNotification(
                    state = state,
                    status = state.telemetry.engineStatus,
                    tier = effectiveTier,
                )
                startForegroundWithTier(effectiveTier, fallbackNotification)
                isForegroundPromoted = true
            } catch (_: RuntimeException) {
            }
        } catch (_: RuntimeException) {
        }

        currentTier = effectiveTier
        if (effectiveTier == FgsTier.TIER2_PROFILE_ONLY) {
            visualizerController.stop()
        }
    }

    private fun startForegroundWithTier(tier: FgsTier, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val serviceType = if (tier == FgsTier.TIER1_LIVE_FFT) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureDspLoopRunning() {
        if (dspLoopJob?.isActive == true) return
        dspLoopJob = serviceScope.launch {
            var lastAttemptedSessionId: Int? = null
            var lastVisualizerSessionId: Int? = null
            var lastBackendAttachAttemptMs = 0L
            var lastVisualizerAttachAttemptMs = 0L
            val loopStartedAtMs = SystemClock.elapsedRealtime()

            try {
                while (isActive && isRunning) {
                    val loopStartMs = SystemClock.elapsedRealtime()
                    refreshMusicVolumeRatio()
                    val state = OrbitEngineRepository.uiState.value
                    val powerOn = state.settings.powerEnabled
                    val effectiveBypass = state.settings.bypassed || state.autoBypassedByRule

                    if (!powerOn) {
                        if (loopStartMs - loopStartedAtMs >= STARTUP_POWER_GRACE_MS) {
                            stopServiceCleanly()
                            break
                        }
                        dspController.apply(
                            frame = adaptiveEngine.processFrame(
                                fftBytes = null,
                                nowMs = loopStartMs,
                                isMediaPlaying = false,
                                powerEnabled = false,
                                bypassed = true,
                                mode = state.activeMode,
                                intensityPercent = state.settings.intensityPercent,
                                speed = state.settings.responseSpeed,
                                bassMacroDb = state.settings.bassMacroDb,
                                airMacroDb = state.settings.airMacroDb,
                                dynamicLoudness = state.settings.dynamicLoudness,
                                volumeRatio = currentVolumeRatio,
                                headphoneProfile = state.activeHeadphoneProfile,
                                manualTrimsDb = state.settings.manualTrimsDb,
                            ),
                            mbcEnabled = state.settings.mbcEnabled,
                            active = false,
                        )
                        visualizerController.stop()
                        lastVisualizerSessionId = null
                    } else {
                        val externalSession = AudioSessionRegistry.resolveActiveSessionId(
                            preferredPackage = state.nowPlaying.packageName,
                        )
                        val targetSessionId = FgsTierPlanner.resolveSessionId(
                            mode = state.settings.sessionMode,
                            activeExternalSessionId = externalSession,
                        )

                        val routeDirty = routeReattachPending.getAndSet(false)
                        val sessionDirty = sessionReattachPending.getAndSet(false)
                        val retryDue = dspController.currentBackend == DspBackend.NONE &&
                            (loopStartMs - lastBackendAttachAttemptMs) >= BACKEND_RETRY_INTERVAL_MS

                        if (routeDirty || sessionDirty || lastAttemptedSessionId != targetSessionId || retryDue) {
                            lastAttemptedSessionId = targetSessionId
                            lastBackendAttachAttemptMs = loopStartMs
                            dspController.attach(targetSessionId)
                        }

                        if (currentTier == FgsTier.TIER1_LIVE_FFT) {
                            val visRetryDue = !visualizerController.isRunning &&
                                (loopStartMs - lastVisualizerAttachAttemptMs) >= BACKEND_RETRY_INTERVAL_MS
                            val needsVisAttach = routeDirty ||
                                sessionDirty ||
                                lastVisualizerSessionId != targetSessionId ||
                                visRetryDue
                            if (needsVisAttach) {
                                lastVisualizerSessionId = targetSessionId
                                lastVisualizerAttachAttemptMs = loopStartMs
                                visualizerController.start(targetSessionId)
                            }
                        } else if (visualizerController.isRunning || lastVisualizerSessionId != null) {
                            visualizerController.stop()
                            lastVisualizerSessionId = null
                        }

                        val fftBytes = if (currentTier == FgsTier.TIER1_LIVE_FFT) {
                            visualizerController.captureFft()
                        } else {
                            null
                        }

                        val frame = adaptiveEngine.processFrame(
                            fftBytes = fftBytes,
                            nowMs = loopStartMs,
                            isMediaPlaying = state.nowPlaying.isPlaying,
                            powerEnabled = true,
                            bypassed = effectiveBypass,
                            mode = state.activeMode,
                            intensityPercent = state.settings.intensityPercent,
                            speed = state.settings.responseSpeed,
                            bassMacroDb = state.settings.bassMacroDb,
                            airMacroDb = state.settings.airMacroDb,
                            dynamicLoudness = state.settings.dynamicLoudness,
                            volumeRatio = currentVolumeRatio,
                            headphoneProfile = state.activeHeadphoneProfile,
                            manualTrimsDb = state.settings.manualTrimsDb,
                        )

                        dspController.apply(
                            frame = frame,
                            mbcEnabled = state.settings.mbcEnabled,
                            active = !effectiveBypass,
                        )

                        OrbitEngineRepository.updateTelemetry(
                            LiveAudioTelemetry(
                                engineStatus = frame.engineStatus,
                                dspBackend = dspController.currentBackend,
                                activeSessionId = dspController.currentSessionId,
                                sampleRateHz = visualizerController.sampleRateHz,
                                spectrumBins36 = frame.displayBins36.toList(),
                                liveEqCurveDb = frame.totalEqDb.toList(),
                                adaptiveDeltaDb = frame.adaptiveDeltaDb.toList(),
                                preampAttenuationDb = frame.inputGainDb,
                                loudnessBoostDb = frame.loudnessBoostDb,
                                limiterCeilingDb = LIMITER_CEILING_DB,
                                dominantSpectralTag = frame.spectralTag,
                            ),
                        )

                        updateNotificationIfChanged(state, frame.engineStatus)
                    }

                    withTimeoutOrNull(DSP_LOOP_INTERVAL_MS) {
                        wakeSignal.receive()
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                visualizerController.stop()
                dspController.release()
            }
        }
    }

    private fun shutdownEngineAndStop() {
        OrbitEngineRepository.setPowerEnabled(false)
        stopServiceCleanly()
    }

    private fun stopServiceCleanly() {
        dspLoopJob?.cancel()
        dspLoopJob = null
        visualizerController.stop()
        dspController.release()
        adaptiveEngine.reset()
        OrbitEngineRepository.updateTelemetry(
            LiveAudioTelemetry(
                engineStatus = EngineStatus.IDLE,
                dspBackend = DspBackend.NONE,
                activeSessionId = 0,
            ),
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForegroundPromoted = false
        stopSelf()
    }

    private fun registerAudioDeviceMonitor() {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val relevant = addedDevices.any { isOutputRouteDevice(it) }
                refreshConnectedOutputDevice(reattachDsp = relevant)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val relevant = removedDevices.any { isOutputRouteDevice(it) }
                refreshConnectedOutputDevice(reattachDsp = relevant)
            }
        }
        deviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun unregisterAudioDeviceMonitor() {
        val callback = deviceCallback ?: return
        deviceCallback = null
        try {
            audioManager.unregisterAudioDeviceCallback(callback)
        } catch (_: RuntimeException) {
        }
    }

    private fun registerDynamicAudioReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
                        if (sessionId > 0 && pkg != packageName) {
                            AudioSessionRegistry.onSessionOpened(sessionId, pkg)
                            sessionReattachPending.set(true)
                            wakeSignal.trySend(Unit)
                        }
                    }

                    AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                        if (sessionId > 0) {
                            AudioSessionRegistry.onSessionClosed(sessionId)
                            sessionReattachPending.set(true)
                            wakeSignal.trySend(Unit)
                        }
                    }

                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        refreshConnectedOutputDevice(reattachDsp = true)
                    }

                    ACTION_VOLUME_CHANGED -> {
                        val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                        if (streamType == -1 || streamType == AudioManager.STREAM_MUSIC) {
                            refreshMusicVolumeRatio()
                            wakeSignal.trySend(Unit)
                        }
                    }
                }
            }
        }
        dynamicReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(ACTION_VOLUME_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun unregisterDynamicAudioReceiver() {
        val receiver = dynamicReceiver ?: return
        dynamicReceiver = null
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        } catch (_: RuntimeException) {
        }
    }

    private fun refreshConnectedOutputDevice(reattachDsp: Boolean) {
        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: RuntimeException) {
            emptyArray()
        }
        val detected = selectConnectedOutputDevice(outputs)
        val previous = OrbitEngineRepository.uiState.value.connectedDevice
        OrbitEngineRepository.updateConnectedDevice(detected)
        if (reattachDsp || detected != previous) {
            routeReattachPending.set(true)
            wakeSignal.trySend(Unit)
        }
    }

    private fun selectConnectedOutputDevice(devices: Array<out AudioDeviceInfo>): ConnectedOutputDevice {
        val a2dp = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val ble = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        val btDevice = a2dp ?: ble
        if (btDevice != null) {
            val rawName = btDevice.productName?.toString()?.trim().orEmpty()
            val displayName = rawName.ifEmpty { getString(R.string.device_bluetooth_default) }
            return ConnectedOutputDevice(
                name = displayName,
                isBluetooth = true,
                isSpeaker = false,
                isSoundcoreQ30 = PleasureProfiles.detectSoundcoreQ30(displayName),
            )
        }

        val wiredOrUsb = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID
        }
        if (wiredOrUsb != null) {
            val rawName = wiredOrUsb.productName?.toString()?.trim().orEmpty()
            val displayName = rawName.ifEmpty { getString(R.string.device_wired_default) }
            return ConnectedOutputDevice(
                name = displayName,
                isBluetooth = false,
                isSpeaker = false,
                isSoundcoreQ30 = PleasureProfiles.detectSoundcoreQ30(displayName),
            )
        }

        return ConnectedOutputDevice(
            name = getString(R.string.device_speaker_default),
            isBluetooth = false,
            isSpeaker = true,
            isSoundcoreQ30 = false,
        )
    }

    private fun isOutputRouteDevice(device: AudioDeviceInfo): Boolean {
        if (!device.isSink) return false
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> true
            else -> false
        }
    }

    private fun refreshMusicVolumeRatio() {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume)
            currentVolumeRatio = (current.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f)
        } catch (_: RuntimeException) {
        }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotificationIfChanged(state: OrbitUiState, status: EngineStatus) {
        val signature = buildString {
            append(state.activeMode.name)
            append('|').append(status.name)
            append('|').append(currentTier.name)
            append('|').append(state.nowPlaying.trackTitle)
            append('|').append(state.nowPlaying.artist)
            append('|').append(state.connectedDevice.name)
            append('|').append(state.settings.bypassed || state.autoBypassedByRule)
        }
        if (signature == lastNotificationSignature) return
        lastNotificationSignature = signature
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(state, status, currentTier))
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }
    }

    private fun buildNotification(
        state: OrbitUiState,
        status: EngineStatus,
        tier: FgsTier,
    ): Notification {
        val effectiveBypass = state.settings.bypassed || state.autoBypassedByRule
        val stateLabel = when {
            status == EngineStatus.BYPASSED || effectiveBypass -> getString(R.string.notif_state_bypassed)
            status == EngineStatus.AUTO_FFT_LOCKED -> getString(R.string.notif_state_live)
            status == EngineStatus.PROFILE_FALLBACK -> getString(R.string.notif_state_fallback)
            tier == FgsTier.TIER1_LIVE_FFT -> getString(R.string.notif_state_live)
            else -> getString(R.string.notif_state_fallback)
        }
        val title = getString(R.string.notif_title_fmt, state.activeMode.name, stateLabel)

        val contentText = if (state.nowPlaying.trackTitle.isNotBlank()) {
            if (state.nowPlaying.artist.isNotBlank()) {
                "${state.nowPlaying.trackTitle} - ${state.nowPlaying.artist}"
            } else {
                state.nowPlaying.trackTitle
            }
        } else {
            getString(R.string.now_playing_idle_sub)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                REQ_OPEN_APP,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val bypassIntent = Intent(this, OrbitAudioService::class.java).setAction(ACTION_TOGGLE_BYPASS)
        val bypassPendingIntent = PendingIntent.getService(
            this,
            REQ_TOGGLE_BYPASS,
            bypassIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, OrbitAudioService::class.java).setAction(ACTION_STOP_ENGINE)
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQ_STOP_ENGINE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_orbit)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notif_action_bypass), bypassPendingIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopPendingIntent)

        state.connectedDevice.name.takeIf { it.isNotBlank() }?.let { deviceName ->
            builder.setSubText(deviceName)
        }
        if (contentPendingIntent != null) {
            builder.setContentIntent(contentPendingIntent)
        }

        return builder.build()
    }

    companion object {
        const val ACTION_START_FOREGROUND_FULL = FgsTierPlanner.ACTION_START_FOREGROUND_FULL
        const val ACTION_START_BACKGROUND_SAFE = FgsTierPlanner.ACTION_START_BACKGROUND_SAFE
        const val ACTION_PROMOTE_TO_FULL = FgsTierPlanner.ACTION_PROMOTE_TO_FULL
        const val ACTION_TOGGLE_BYPASS = FgsTierPlanner.ACTION_TOGGLE_BYPASS
        const val ACTION_STOP_ENGINE = FgsTierPlanner.ACTION_STOP_ENGINE
        const val ACTION_UPDATE_SESSION = FgsTierPlanner.ACTION_UPDATE_SESSION

        const val EXTRA_SESSION_ID = "com.orbiteq.app.extra.SESSION_ID"
        const val EXTRA_SESSION_OPEN = "com.orbiteq.app.extra.SESSION_OPEN"
        const val EXTRA_SESSION_PACKAGE = "com.orbiteq.app.extra.SESSION_PACKAGE"
        const val EXTRA_ROUTE_REFRESH = "com.orbiteq.app.extra.ROUTE_REFRESH"

        private const val NOTIFICATION_CHANNEL_ID = "orbit_eq_engine_channel"
        private const val NOTIFICATION_ID = 1001
        private const val REQ_OPEN_APP = 100
        private const val REQ_TOGGLE_BYPASS = 101
        private const val REQ_STOP_ENGINE = 102

        private const val DSP_LOOP_INTERVAL_MS = 55L
        private const val BACKEND_RETRY_INTERVAL_MS = 3000L
        private const val STARTUP_POWER_GRACE_MS = 2500L
        private const val LIMITER_CEILING_DB = -0.5f

        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startForegroundFull(context: Context) {
            val intent = Intent(context, OrbitAudioService::class.java)
                .setAction(ACTION_START_FOREGROUND_FULL)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startBackgroundSafe(
            context: Context,
            sessionId: Int? = null,
            sessionPackage: String? = null,
        ) {
            if (isRunning) return
            val intent = Intent(context, OrbitAudioService::class.java).apply {
                action = ACTION_START_BACKGROUND_SAFE
                if (sessionId != null && sessionId > 0) {
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_SESSION_OPEN, true)
                    if (sessionPackage != null) {
                        putExtra(EXTRA_SESSION_PACKAGE, sessionPackage)
                    }
                }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun promoteToFull(context: Context) {
            val intent = Intent(context, OrbitAudioService::class.java)
                .setAction(ACTION_PROMOTE_TO_FULL)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopEngine(context: Context) {
            val intent = Intent(context, OrbitAudioService::class.java)
                .setAction(ACTION_STOP_ENGINE)
            if (isRunning) {
                try {
                    context.startService(intent)
                } catch (_: RuntimeException) {
                }
            } else {
                OrbitEngineRepository.setPowerEnabled(false)
            }
        }
    }
}
