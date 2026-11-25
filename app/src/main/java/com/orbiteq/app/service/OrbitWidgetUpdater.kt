package com.orbiteq.app.service

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

object OrbitWidgetUpdater {

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
