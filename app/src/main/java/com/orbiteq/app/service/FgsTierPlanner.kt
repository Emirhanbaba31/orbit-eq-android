package com.orbiteq.app.service

import com.orbiteq.app.model.SessionAttachmentMode

enum class FgsTier {
    TIER1_LIVE_FFT,
    TIER2_PROFILE_ONLY,
}

object FgsTierPlanner {

    internal const val ACTION_START_FOREGROUND_FULL = "com.orbiteq.app.action.START_FOREGROUND_FULL"
    internal const val ACTION_START_BACKGROUND_SAFE = "com.orbiteq.app.action.START_BACKGROUND_SAFE"
    internal const val ACTION_PROMOTE_TO_FULL = "com.orbiteq.app.action.PROMOTE_TO_FULL"
    internal const val ACTION_TOGGLE_BYPASS = "com.orbiteq.app.action.TOGGLE_BYPASS"
    internal const val ACTION_STOP_ENGINE = "com.orbiteq.app.action.STOP_ENGINE"
    internal const val ACTION_UPDATE_SESSION = "com.orbiteq.app.action.UPDATE_SESSION"

    fun planTier(
        startedFromForeground: Boolean,
        hasRecordAudioPermission: Boolean,
    ): FgsTier {
        return if (startedFromForeground && hasRecordAudioPermission) {
            FgsTier.TIER1_LIVE_FFT
        } else {
            FgsTier.TIER2_PROFILE_ONLY
        }
    }

    fun isForegroundEligibleAction(action: String?): Boolean =
        action == ACTION_START_FOREGROUND_FULL || action == ACTION_PROMOTE_TO_FULL

    fun shouldPromoteForeground(
        isForegroundPromoted: Boolean,
        action: String?,
    ): Boolean {
        if (!isForegroundPromoted) return true
        return action == ACTION_START_FOREGROUND_FULL ||
            action == ACTION_START_BACKGROUND_SAFE ||
            action == ACTION_PROMOTE_TO_FULL
    }

    fun resolveSessionId(
        mode: SessionAttachmentMode,
        activeExternalSessionId: Int?,
    ): Int {
        return when (mode) {
            SessionAttachmentMode.GLOBAL_ONLY -> 0
            SessionAttachmentMode.AUTO -> activeExternalSessionId?.takeIf { it > 0 } ?: 0
        }
    }
}
