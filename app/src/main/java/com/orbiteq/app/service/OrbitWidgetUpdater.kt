package com.orbiteq.app.service

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.orbiteq.app.model.OrbitUiState
import com.orbiteq.app.model.PleasureMode

internal data class WidgetTileSignature(
    val powerEnabled: Boolean,
    val bypassed: Boolean,
    val autoBypassedByRule: Boolean,
    val activeMode: PleasureMode,
    val trackTitle: String,
    val artist: String,
    val connectedDeviceName: String,
)

object OrbitWidgetUpdater {

    internal fun signatureFor(state: OrbitUiState): WidgetTileSignature =
        WidgetTileSignature(
            powerEnabled = state.settings.powerEnabled,
            bypassed = state.settings.bypassed,
            autoBypassedByRule = state.autoBypassedByRule,
            activeMode = state.activeMode,
            trackTitle = state.nowPlaying.trackTitle,
            artist = state.nowPlaying.artist,
            connectedDeviceName = state.connectedDevice.name,
        )

    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        try {
            val manager = AppWidgetManager.getInstance(appContext)
            val widgetIds = manager.getAppWidgetIds(
                ComponentName(appContext, OrbitWidgetProvider::class.java),
            )
            if (widgetIds.isNotEmpty()) {
                OrbitWidgetProvider.updateWidgets(appContext, manager, widgetIds)
            }
        } catch (_: RuntimeException) {
        }

        try {
            TileService.requestListeningState(
                appContext,
                ComponentName(appContext, OrbitQuickSettingsTileService::class.java),
            )
        } catch (_: RuntimeException) {
        }
    }
}
