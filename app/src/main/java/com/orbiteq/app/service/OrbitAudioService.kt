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
import com.orbiteq.app.dsp.AppRuleResolver
import com.orbiteq.app.model.ConnectedOutputDevice
import com.orbiteq.app.model.CustomHeadphoneProfile
import com.orbiteq.app.model.DspBackend
import com.orbiteq.app.model.EngineStatus
import com.orbiteq.app.model.HeadphoneProfile
import com.orbiteq.app.model.LiveAudioTelemetry
import com.orbiteq.app.model.OrbitSettings
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.PleasureMode
import com.orbiteq.app.model.SessionAttachmentMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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

    private data class ObservedServiceState(
        val settings: OrbitSettings,
        val activeMode: PleasureMode,
        val activeHeadphoneProfile: HeadphoneProfile,
        val activeCustomHeadphoneProfile: CustomHeadphoneProfile?,
        val autoBypassedByRule: Boolean,
        val isPlaying: Boolean,
        val trackTitle: String,
        val artist: String,
        val connectedDevice: ConnectedOutputDevice,
    )

    private var dspLoopJob: Job? = null
    private var lastLaunchedDspJob: Job? = null
    private var stateObserverJob: Job? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var dynamicReceiver: BroadcastReceiver? = null

    @Volatile
    private var currentTier: FgsTier = FgsTier.TIER2_PROFILE_ONLY

    @Volatile
    private var currentVolumeRatio: Float = 1.0f

    @Volatile
    private var isForegroundPromoted: Boolean = false

    @Volatile
    private var isStopping: Boolean = false

    @Volatile
    private var lastNotificationSignature: String? = null

    override fun onCreate() {
        super.onCreate()
        isStopping = false
        isRunning = true
        OrbitEngineRepository.init(applicationContext)
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
        refreshMusicVolumeRatio()
        registerAudioDeviceMonitor()
        registerDynamicAudioReceiver()
        refreshConnectedOutputDevice(reattachDsp = false)
        startStateObserver()
    }

    private fun startStateObserver() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            OrbitEngineRepository.uiState
                .map { state ->
                    ObservedServiceState(
                        settings = state.settings,
                        activeMode = state.activeMode,
                        activeHeadphoneProfile = state.activeHeadphoneProfile,
                        activeCustomHeadphoneProfile = state.activeCustomHeadphoneProfile,
                        autoBypassedByRule = state.autoBypassedByRule,
                        isPlaying = state.nowPlaying.isPlaying,
                        trackTitle = state.nowPlaying.trackTitle,
                        artist = state.nowPlaying.artist,
                        connectedDevice = state.connectedDevice,
                    )
                }
                .distinctUntilChanged()
                .collect {
                    wakeSignal.trySend(Unit)
                    OrbitWidgetUpdater.requestUpdate(applicationContext)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_ENGINE) {
            isStopping = true
            // Satisfy startForegroundService timing contract if not already foregrounded.
            if (!isForegroundPromoted) {
                promoteForegroundSafe(FgsTier.TIER2_PROFILE_ONLY)
            }
            shutdownEngineAndStop()
            return START_NOT_STICKY
        }

        if (action == ACTION_START_FOREGROUND_FULL ||
            action == ACTION_PROMOTE_TO_FULL ||
            action == ACTION_START_BACKGROUND_SAFE
        ) {
            isStopping = false
            isRunning = true
        } else if (isStopping) {
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

            ACTION_NEXT_MODE -> {
                OrbitEngineRepository.cycleNextMode()
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
        isStopping = true
        isRunning = false
        isForegroundPromoted = false
        stateObserverJob?.cancel()
        stateObserverJob = null
        dspLoopJob?.cancel()
        dspLoopJob = null

        unregisterAudioDeviceMonitor()
        unregisterDynamicAudioReceiver()

        visualizerController.stop()
        dspController.release()

        OrbitEngineRepository.updateTelemetry(idleTelemetry())
        OrbitWidgetUpdater.requestUpdate(applicationContext)

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
        if (isStopping || dspLoopJob?.isActive == true) return
        val previousJob = lastLaunchedDspJob
        val job = serviceScope.launch {
            previousJob?.cancelAndJoin()
            var lastAttemptedSessionId: Int? = null
            var lastVisualizerSessionId: Int? = null
            var lastBackendAttachAttemptMs = 0L
            var lastVisualizerAttachAttemptMs = 0L
            var lastWidgetStatus: EngineStatus? = null
            val loopStartedAtMs = SystemClock.elapsedRealtime()

            try {
                while (isActive && isRunning && !isStopping) {
                    val loopStartMs = SystemClock.elapsedRealtime()
                    refreshMusicVolumeRatio()
                    val state = OrbitEngineRepository.uiState.value
                    val powerOn = state.settings.powerEnabled
                    val effectiveBypass = state.settings.bypassed || state.autoBypassedByRule
                    val isUiVisible = OrbitEngineRepository.isUiVisible
                    val pkg = state.nowPlaying.packageName?.trim().orEmpty()
                    val title = state.nowPlaying.trackTitle.trim()
                    val trackKey = if (pkg.isNotEmpty() && title.isNotEmpty()) {
                        AppRuleResolver.trackKey(pkg, title)
                    } else {
                        null
                    }

                    var isLiveFftActive = false
                    var hasNonZeroAdaptive = false

                    if (!powerOn) {
                        if (loopStartMs - loopStartedAtMs >= STARTUP_POWER_GRACE_MS) {
                            stopServiceCleanly()
                            break
                        }
                        val idleFrame = adaptiveEngine.processFrame(
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
                            fftCaptureAvailable = false,
                            sampleRateHz = visualizerController.sampleRateHz,
                            customHeadphoneProfile = state.activeCustomHeadphoneProfile,
                            subsonicFilterEnabled = state.settings.subsonicFilterEnabled,
                            trueHeadroomMode = state.settings.trueHeadroomMode,
                            channelBalance = state.settings.channelBalance,
                            trackKey = trackKey,
                        )
                        dspController.apply(
                            frame = idleFrame,
                            mbcMode = state.settings.mbcMode,
                            active = false,
                        )
                        visualizerController.stop()
                        lastVisualizerSessionId = null
                        if (idleFrame.engineStatus != lastWidgetStatus) {
                            lastWidgetStatus = idleFrame.engineStatus
                            OrbitWidgetUpdater.requestUpdate(applicationContext)
                        }
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
                        val effectiveSessionDirty = sessionDirty &&
                            state.settings.sessionMode == SessionAttachmentMode.AUTO
                        val retryDue = dspController.currentBackend == DspBackend.NONE &&
                            (loopStartMs - lastBackendAttachAttemptMs) >= BACKEND_RETRY_INTERVAL_MS

                        if (routeDirty || effectiveSessionDirty || lastAttemptedSessionId != targetSessionId || retryDue) {
                            lastAttemptedSessionId = targetSessionId
                            lastBackendAttachAttemptMs = loopStartMs
                            dspController.attach(targetSessionId)
                        }

                        val visualizerTargetSessionId = if (dspController.currentBackend != DspBackend.NONE) {
                            dspController.currentSessionId
                        } else {
                            targetSessionId
                        }

                        if (currentTier == FgsTier.TIER1_LIVE_FFT) {
                            val visRetryDue = !visualizerController.isRunning &&
                                (loopStartMs - lastVisualizerAttachAttemptMs) >= BACKEND_RETRY_INTERVAL_MS
                            val needsVisAttach = routeDirty ||
                                effectiveSessionDirty ||
                                lastVisualizerSessionId != visualizerTargetSessionId ||
                                visRetryDue
                            if (needsVisAttach) {
                                lastVisualizerSessionId = visualizerTargetSessionId
                                lastVisualizerAttachAttemptMs = loopStartMs
                                visualizerController.start(visualizerTargetSessionId)
                            }
                        } else if (visualizerController.isRunning || lastVisualizerSessionId != null) {
                            visualizerController.stop()
                            lastVisualizerSessionId = null
                        }

                        val canCaptureFft = currentTier == FgsTier.TIER1_LIVE_FFT &&
                            visualizerController.isRunning
                        val fftBytes = if (canCaptureFft) {
                            visualizerController.captureFft()
                        } else {
                            null
                        }
                        val fftCaptureAvailable = currentTier == FgsTier.TIER1_LIVE_FFT &&
                            visualizerController.isRunning

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
                            fftCaptureAvailable = fftCaptureAvailable,
                            sampleRateHz = visualizerController.sampleRateHz,
                            customHeadphoneProfile = state.activeCustomHeadphoneProfile,
                            subsonicFilterEnabled = state.settings.subsonicFilterEnabled,
                            trueHeadroomMode = state.settings.trueHeadroomMode,
                            channelBalance = state.settings.channelBalance,
                            trackKey = trackKey,
                        )

                        dspController.apply(
                            frame = frame,
                            mbcMode = state.settings.mbcMode,
                            active = !effectiveBypass,
                        )

                        isLiveFftActive = frame.engineStatus == EngineStatus.AUTO_FFT_LOCKED && fftCaptureAvailable
                        hasNonZeroAdaptive = frame.adaptiveDeltaDb.any { abs(it) > 0.05f }

                        if (isActive && !isStopping) {
                            val spectrumBins = if (
                                DspLoopCadencePlanner.shouldPublishSpectrumBins(isUiVisible, isLiveFftActive)
                            ) {
                                frame.displayBins36.toList()
                            } else {
                                state.telemetry.spectrumBins36
                            }
                            val activeCompensationName = state.activeCustomHeadphoneProfile?.name
                                ?: state.activeHeadphoneProfile.name

                            OrbitEngineRepository.updateTelemetry(
                                LiveAudioTelemetry(
                                    engineStatus = frame.engineStatus,
                                    dspBackend = dspController.currentBackend,
                                    activeSessionId = dspController.currentSessionId,
                                    sampleRateHz = visualizerController.sampleRateHz,
                                    spectrumBins36 = spectrumBins,
                                    liveEqCurveDb = frame.totalEqDb.toList(),
                                    adaptiveDeltaDb = frame.adaptiveDeltaDb.toList(),
                                    preampAttenuationDb = frame.inputGainDb,
                                    loudnessBoostDb = frame.loudnessBoostDb,
                                    limiterCeilingDb = LIMITER_CEILING_DB,
                                    dominantSpectralTag = frame.spectralTag,
                                    preEqBandCount = dspController.currentPreEqBandCount,
                                    activeCompensationName = activeCompensationName,
                                ),
                            )

                            updateNotificationIfChanged(state, frame.engineStatus)

                            if (frame.engineStatus != lastWidgetStatus) {
                                lastWidgetStatus = frame.engineStatus
                                OrbitWidgetUpdater.requestUpdate(applicationContext)
                            }
                        }
                    }

                    val intervalMs = DspLoopCadencePlanner.computeIntervalMs(
                        isMediaPlaying = powerOn && state.nowPlaying.isPlaying,
                        isLiveFftActive = isLiveFftActive,
                        isUiVisible = isUiVisible,
                        isRampActive = dspController.isRampActive,
                        hasNonZeroAdaptiveDelta = hasNonZeroAdaptive,
                    )
                    withTimeoutOrNull(intervalMs) {
                        wakeSignal.receive()
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                adaptiveEngine.reset()
                visualizerController.stop()
                dspController.release()
                if (isStopping || !isRunning) {
                    OrbitEngineRepository.updateTelemetry(idleTelemetry())
                    if (lastWidgetStatus != EngineStatus.IDLE) {
                        OrbitWidgetUpdater.requestUpdate(applicationContext)
                    }
                }
            }
        }
        dspLoopJob = job
        lastLaunchedDspJob = job
    }

    private fun shutdownEngineAndStop() {
        OrbitEngineRepository.setPowerEnabled(false)
        stopServiceCleanly()
    }

    private fun stopServiceCleanly() {
        isStopping = true
        isRunning = false
        val runningJob = dspLoopJob
        dspLoopJob = null
        runningJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForegroundPromoted = false
        OrbitEngineRepository.updateTelemetry(idleTelemetry())
        stopSelf()
    }

    private fun idleTelemetry(): LiveAudioTelemetry = LiveAudioTelemetry(
        engineStatus = EngineStatus.IDLE,
        dspBackend = DspBackend.NONE,
        activeSessionId = 0,
        preEqBandCount = 0,
    )

    private fun registerAudioDeviceMonitor() {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val relevant = addedDevices.any { it.isSink && AudioRouteResolver.isRelevantOutputType(it.type) }
                refreshConnectedOutputDevice(reattachDsp = relevant)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val relevant = removedDevices.any { it.isSink && AudioRouteResolver.isRelevantOutputType(it.type) }
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
        val detected = AudioRouteResolver.resolveFromSystem(
            devices = outputs,
            defaultBluetoothName = getString(R.string.device_bluetooth_default),
            defaultWiredName = getString(R.string.device_wired_default),
            defaultSpeakerName = getString(R.string.device_speaker_default),
        )
        val previous = OrbitEngineRepository.uiState.value.connectedDevice
        OrbitEngineRepository.updateConnectedDevice(detected)
        if (reattachDsp || detected != previous) {
            routeReattachPending.set(true)
            wakeSignal.trySend(Unit)
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

        val nextModeIntent = Intent(this, OrbitAudioService::class.java).setAction(ACTION_NEXT_MODE)
        val nextModePendingIntent = PendingIntent.getService(
            this,
            REQ_NEXT_MODE,
            nextModeIntent,
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
            .addAction(0, getString(R.string.notif_action_next_mode), nextModePendingIntent)
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
        const val ACTION_NEXT_MODE = FgsTierPlanner.ACTION_NEXT_MODE
        const val ACTION_STOP_ENGINE = FgsTierPlanner.ACTION_STOP_ENGINE
        const val ACTION_UPDATE_SESSION = FgsTierPlanner.ACTION_UPDATE_SESSION

        const val EXTRA_SESSION_ID = "com.orbiteq.app.extra.SESSION_ID"
        const val EXTRA_SESSION_OPEN = "com.orbiteq.app.extra.SESSION_OPEN"
        const val EXTRA_SESSION_PACKAGE = "com.orbiteq.app.extra.SESSION_PACKAGE"
        const val EXTRA_ROUTE_REFRESH = "com.orbiteq.app.extra.ROUTE_REFRESH"
        const val EXTRA_START_ENGINE = "com.orbiteq.app.extra.START_ENGINE"

        private const val NOTIFICATION_CHANNEL_ID = "orbit_eq_engine_channel"
        private const val NOTIFICATION_ID = 1001
        private const val REQ_OPEN_APP = 100
        private const val REQ_TOGGLE_BYPASS = 101
        private const val REQ_STOP_ENGINE = 102
        private const val REQ_NEXT_MODE = 103

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
        ): Boolean {
            if (isRunning) return true
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
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: SecurityException) {
                false
            } catch (_: RuntimeException) {
                // Covers ForegroundServiceStartNotAllowedException (Android 12-16) and OS background FGS blocks.
                false
            }
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
