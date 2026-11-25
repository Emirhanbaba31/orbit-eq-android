package com.orbiteq.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.orbiteq.app.MainActivity
import com.orbiteq.app.R
import com.orbiteq.app.data.OrbitEngineRepository
import com.orbiteq.app.model.EngineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OrbitWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val appContext = context.applicationContext
        OrbitEngineRepository.init(appContext)
        if (OrbitEngineRepository.isInitialSettingsLoaded()) {
            updateWidgets(appContext, appWidgetManager, appWidgetIds)
            return
        }
        val pendingResult = goAsync()
        if (pendingResult == null) {
            updateWidgets(appContext, appWidgetManager, appWidgetIds)
            return
        }
        widgetScope.launch {
            try {
                OrbitEngineRepository.awaitInitialSettingsLoaded(PREFS_LOAD_TIMEOUT_MS)
                updateWidgets(appContext, appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        val action = intent?.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val appContext = context.applicationContext
        OrbitEngineRepository.init(appContext)
        if (OrbitEngineRepository.isInitialSettingsLoaded()) {
            handleWidgetAction(appContext, action)
            return
        }
        val pendingResult = goAsync()
        if (pendingResult == null) {
            handleWidgetAction(appContext, action)
            return
        }
        widgetScope.launch {
            try {
                OrbitEngineRepository.awaitInitialSettingsLoaded(PREFS_LOAD_TIMEOUT_MS)
                handleWidgetAction(appContext, action)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleWidgetAction(appContext: Context, action: String) {
        when (action) {
            ACTION_WIDGET_TOGGLE_POWER -> {
                val powerOn = OrbitEngineRepository.uiState.value.settings.powerEnabled
                if (!powerOn) {
                    val started = OrbitAudioService.startBackgroundSafe(appContext)
                    if (started) {
                        OrbitEngineRepository.setPowerEnabled(true)
                    } else {
                        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
                            )
                            putExtra(OrbitAudioService.EXTRA_START_ENGINE, true)
                        }
                        try {
                            appContext.startActivity(launchIntent)
                        } catch (_: RuntimeException) {
                        }
                    }
                } else {
                    OrbitAudioService.stopEngine(appContext)
                }
            }

            ACTION_WIDGET_TOGGLE_BYPASS -> {
                val bypassed = OrbitEngineRepository.uiState.value.settings.bypassed
                OrbitEngineRepository.setBypassed(!bypassed)
            }

            ACTION_WIDGET_NEXT_MODE -> {
                OrbitEngineRepository.cycleNextMode()
            }
        }

        OrbitWidgetUpdater.requestUpdate(appContext)
    }

    companion object {
        const val ACTION_WIDGET_TOGGLE_POWER = "com.orbiteq.app.widget.TOGGLE_POWER"
        const val ACTION_WIDGET_TOGGLE_BYPASS = "com.orbiteq.app.widget.TOGGLE_BYPASS"
        const val ACTION_WIDGET_NEXT_MODE = "com.orbiteq.app.widget.NEXT_MODE"

        private val HANDLED_ACTIONS = setOf(
            ACTION_WIDGET_TOGGLE_POWER,
            ACTION_WIDGET_TOGGLE_BYPASS,
            ACTION_WIDGET_NEXT_MODE,
        )

        private const val REQ_OPEN_APP = 200
        private const val REQ_TOGGLE_POWER = 201
        private const val REQ_TOGGLE_BYPASS = 202
        private const val REQ_NEXT_MODE = 203
        private const val PREFS_LOAD_TIMEOUT_MS = 1500L

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val COLOR_ION_CYAN = 0xFF38BDF8.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFFF8FAFC.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF94A3B8.toInt()
        private const val COLOR_AMBER = 0xFFF59E0B.toInt()

        internal fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
        ) {
            val state = OrbitEngineRepository.uiState.value
            val powerOn = state.settings.powerEnabled
            val manuallyBypassed = state.settings.bypassed
            val autoBypassed = state.autoBypassedByRule
            val effectiveBypass = manuallyBypassed || autoBypassed

            val statusText: String
            val statusColor: Int
            when {
                !powerOn -> {
                    statusText = context.getString(R.string.widget_status_standby)
                    statusColor = COLOR_TEXT_SECONDARY
                }

                effectiveBypass -> {
                    statusText = context.getString(R.string.widget_status_bypassed)
                    statusColor = COLOR_AMBER
                }

                state.telemetry.engineStatus == EngineStatus.AUTO_FFT_LOCKED -> {
                    statusText = context.getString(R.string.widget_status_live_fft)
                    statusColor = COLOR_ION_CYAN
                }

                state.telemetry.engineStatus == EngineStatus.PROFILE_FALLBACK -> {
                    statusText = context.getString(R.string.widget_status_fallback)
                    statusColor = COLOR_ION_CYAN
                }

                else -> {
                    statusText = context.getString(R.string.widget_status_active)
                    statusColor = COLOR_ION_CYAN
                }
            }

            val subtitleText = if (state.nowPlaying.trackTitle.isNotBlank()) {
                if (state.nowPlaying.artist.isNotBlank()) {
                    "${state.nowPlaying.trackTitle} - ${state.nowPlaying.artist}"
                } else {
                    state.nowPlaying.trackTitle
                }
            } else {
                state.connectedDevice.name.ifBlank {
                    context.getString(R.string.device_speaker_default)
                }
            }

            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                REQ_OPEN_APP,
                openAppIntent,
                pendingFlags,
            )

            val powerPendingIntent = widgetBroadcastPendingIntent(
                context = context,
                requestCode = REQ_TOGGLE_POWER,
                action = ACTION_WIDGET_TOGGLE_POWER,
                flags = pendingFlags,
            )
            val bypassPendingIntent = widgetBroadcastPendingIntent(
                context = context,
                requestCode = REQ_TOGGLE_BYPASS,
                action = ACTION_WIDGET_TOGGLE_BYPASS,
                flags = pendingFlags,
            )
            val nextModePendingIntent = widgetBroadcastPendingIntent(
                context = context,
                requestCode = REQ_NEXT_MODE,
                action = ACTION_WIDGET_NEXT_MODE,
                flags = pendingFlags,
            )

            val views = RemoteViews(context.packageName, R.layout.widget_orbit_eq).apply {
                setTextViewText(R.id.widget_mode_pill, state.activeMode.name)
                setTextColor(
                    R.id.widget_mode_pill,
                    if (powerOn && !effectiveBypass) COLOR_ION_CYAN else COLOR_TEXT_SECONDARY,
                )
                setInt(
                    R.id.widget_mode_pill,
                    "setBackgroundResource",
                    if (powerOn && !effectiveBypass) R.drawable.bg_widget_button_active else R.drawable.bg_widget_button,
                )

                setTextViewText(R.id.widget_status_text, statusText)
                setTextColor(R.id.widget_status_text, statusColor)
                setTextViewText(R.id.widget_track_or_device, subtitleText)

                setTextViewText(
                    R.id.btn_widget_power,
                    context.getString(
                        if (powerOn) R.string.widget_btn_power_on else R.string.widget_btn_power_off,
                    ),
                )
                setTextColor(
                    R.id.btn_widget_power,
                    if (powerOn) COLOR_ION_CYAN else COLOR_TEXT_SECONDARY,
                )
                setInt(
                    R.id.btn_widget_power,
                    "setBackgroundResource",
                    if (powerOn) R.drawable.bg_widget_button_active else R.drawable.bg_widget_button,
                )
                setContentDescription(R.id.btn_widget_power, context.getString(R.string.widget_cd_power))

                setTextViewText(
                    R.id.btn_widget_bypass,
                    context.getString(
                        if (autoBypassed && !manuallyBypassed) {
                            R.string.widget_btn_ab_auto
                        } else {
                            R.string.widget_btn_ab
                        },
                    ),
                )
                setTextColor(
                    R.id.btn_widget_bypass,
                    when {
                        effectiveBypass -> COLOR_AMBER
                        powerOn -> COLOR_TEXT_PRIMARY
                        else -> COLOR_TEXT_SECONDARY
                    },
                )
                setInt(
                    R.id.btn_widget_bypass,
                    "setBackgroundResource",
                    if (effectiveBypass) R.drawable.bg_widget_button_active else R.drawable.bg_widget_button,
                )
                setContentDescription(R.id.btn_widget_bypass, context.getString(R.string.widget_cd_bypass))

                setTextViewText(R.id.btn_widget_next_mode, context.getString(R.string.widget_btn_next_mode))
                setTextColor(R.id.btn_widget_next_mode, COLOR_TEXT_PRIMARY)
                setInt(R.id.btn_widget_next_mode, "setBackgroundResource", R.drawable.bg_widget_button)
                setContentDescription(R.id.btn_widget_next_mode, context.getString(R.string.widget_cd_next_mode))

                setOnClickPendingIntent(R.id.widget_header_area, openAppPendingIntent)
                setOnClickPendingIntent(R.id.btn_widget_power, powerPendingIntent)
                setOnClickPendingIntent(R.id.btn_widget_bypass, bypassPendingIntent)
                setOnClickPendingIntent(R.id.btn_widget_next_mode, nextModePendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetIds, views)
        }

        private fun widgetBroadcastPendingIntent(
            context: Context,
            requestCode: Int,
            action: String,
            flags: Int,
        ): PendingIntent {
            val intent = Intent(context, OrbitWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }
    }
}
