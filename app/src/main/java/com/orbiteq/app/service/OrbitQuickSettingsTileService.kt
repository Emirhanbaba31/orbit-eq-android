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
import com.orbiteq.app.model.PleasureMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

    private data class TileDisplayState(
        val powerEnabled: Boolean,
        val manuallyBypassed: Boolean,
        val autoBypassedByRule: Boolean,
        val activeMode: PleasureMode,
    )

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null
    private var clickJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        OrbitEngineRepository.init(applicationContext)
        syncTile()
        listeningJob?.cancel()
        listeningJob = tileScope.launch {
            OrbitEngineRepository.uiState
                .map { state ->
                    TileDisplayState(
                        powerEnabled = state.settings.powerEnabled,
                        manuallyBypassed = state.settings.bypassed,
                        autoBypassedByRule = state.autoBypassedByRule,
                        activeMode = state.activeMode,
                    )
                }
                .distinctUntilChanged()
                .collect {
                    syncTile()
                }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        listeningJob?.cancel()
        listeningJob = null
        clickJob?.cancel()
        clickJob = null
        tileScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        OrbitEngineRepository.init(applicationContext)
        if (OrbitEngineRepository.isInitialSettingsLoaded()) {
            handleTileClick()
        } else if (clickJob?.isActive != true) {
            clickJob = tileScope.launch {
                OrbitEngineRepository.awaitInitialSettingsLoaded(PREFS_LOAD_TIMEOUT_MS)
                handleTileClick()
            }
        }
    }

    private fun handleTileClick() {
        val state = OrbitEngineRepository.uiState.value
        when (
            TileActionPlanner.decideAction(
                powerEnabled = state.settings.powerEnabled,
                manuallyBypassed = state.settings.bypassed,
            )
        ) {
            TileClickDecision.START_ENGINE -> {
                val started = OrbitAudioService.startBackgroundSafe(applicationContext)
                if (started) {
                    OrbitEngineRepository.setPowerEnabled(true)
                } else {
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
        const val PREFS_LOAD_TIMEOUT_MS = 1500L
    }
}
