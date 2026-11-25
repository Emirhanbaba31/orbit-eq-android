package com.orbiteq.app.service

import com.orbiteq.app.model.SessionAttachmentMode

enum class FgsTier {
    TIER1_LIVE_FFT,
    TIER2_PROFILE_ONLY,
}

object FgsTierPlanner {

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
