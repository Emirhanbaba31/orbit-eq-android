package com.orbiteq.app.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.orbiteq.app.MainActivity
import com.orbiteq.app.R
import com.orbiteq.app.data.OrbitEngineRepository

enum class TileClickDecision {
    START_ENGINE,
    CLEAR_BYPASS,
    STOP_ENGINE,
}

object TileActionPlanner {

    fun decideAction(
        powerEnabled: Boolean,
        manuallyBypassed: Boolean,
    ): TileClickDecision = when {
        !powerEnabled -> TileClickDecision.START_ENGINE
        manuallyBypassed -> TileClickDecision.CLEAR_BYPASS
        else -> TileClickDecision.STOP_ENGINE
    }
}

class OrbitQuickSettingsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        OrbitEngineRepository.init(applicationContext)
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        OrbitEngineRepository.init(applicationContext)
        val state = OrbitEngineRepository.uiState.value
        when (
            TileActionPlanner.decideAction(
                powerEnabled = state.settings.powerEnabled,
                manuallyBypassed = state.settings.bypassed,
            )
        ) {
            TileClickDecision.START_ENGINE -> {
                OrbitEngineRepository.setPowerEnabled(true)
                val started = OrbitAudioService.startBackgroundSafe(applicationContext)
                if (!started) {
                    launchMainActivityForStart()
                }
            }

            TileClickDecision.CLEAR_BYPASS -> {
                OrbitEngineRepository.setBypassed(false)
            }

            TileClickDecision.STOP_ENGINE -> {
                OrbitAudioService.stopEngine(applicationContext)
            }
        }
        syncTile()
        OrbitWidgetUpdater.requestUpdate(applicationContext)
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val state = OrbitEngineRepository.uiState.value
        val powerOn = state.settings.powerEnabled
        val manuallyBypassed = state.settings.bypassed
        val effectiveBypass = manuallyBypassed || state.autoBypassedByRule

        val label = getString(R.string.qs_tile_label)
        val subtitleText = when {
            !powerOn -> getString(R.string.qs_tile_sub_off)
            manuallyBypassed -> getString(R.string.qs_tile_sub_bypass)
            state.autoBypassedByRule -> getString(R.string.qs_tile_sub_auto_bypass)
            else -> getString(R.string.qs_tile_sub_active_fmt, state.activeMode.name)
        }

        tile.label = label
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_orbit)
        tile.state = if (powerOn && !effectiveBypass) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.contentDescription = "$label - $subtitleText"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitleText
        }
        tile.updateTile()
    }

    private fun launchMainActivityForStart() {
        val launchTask = {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                putExtra(OrbitAudioService.EXTRA_START_ENGINE, true)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        REQ_START_ENGINE_ACTIVITY,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(launchIntent)
                }
            } catch (_: RuntimeException) {
            }
        }
        if (isLocked) {
            try {
                unlockAndRun(launchTask)
            } catch (_: RuntimeException) {
                launchTask()
            }
        } else {
            launchTask()
        }
    }

    private companion object {
        const val REQ_START_ENGINE_ACTIVITY = 301
    }
}
