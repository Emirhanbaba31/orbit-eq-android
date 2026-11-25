package com.orbiteq.app.service

enum class SpectrumPublishAction {
    PUBLISH_LIVE,
    CLEAR_TO_ZERO,
    PRESERVE_PREVIOUS,
}

object DspLoopCadencePlanner {

    const val RAMP_INTERVAL_MS = 35L
    const val LIVE_VISIBLE_INTERVAL_MS = 55L
    const val LIVE_BACKGROUND_INTERVAL_MS = 110L
    const val FALLBACK_SETTLED_INTERVAL_MS = 500L
    const val IDLE_SETTLED_INTERVAL_MS = 1000L

    fun computeIntervalMs(
        isMediaPlaying: Boolean,
        isLiveFftActive: Boolean,
        isUiVisible: Boolean,
        isRampActive: Boolean,
        hasNonZeroAdaptiveDelta: Boolean,
    ): Long {
        return when {
            isRampActive -> RAMP_INTERVAL_MS
            (isMediaPlaying && isLiveFftActive) || hasNonZeroAdaptiveDelta ->
                if (isUiVisible) LIVE_VISIBLE_INTERVAL_MS else LIVE_BACKGROUND_INTERVAL_MS
            isMediaPlaying -> FALLBACK_SETTLED_INTERVAL_MS
            else -> IDLE_SETTLED_INTERVAL_MS
        }
    }

    fun shouldPublishSpectrumBins(
        isUiVisible: Boolean,
        isLiveFftActive: Boolean,
    ): Boolean = isUiVisible && isLiveFftActive

    fun spectrumPublishAction(
        isUiVisible: Boolean,
        isLiveFftActive: Boolean,
    ): SpectrumPublishAction = when {
        shouldPublishSpectrumBins(isUiVisible, isLiveFftActive) -> SpectrumPublishAction.PUBLISH_LIVE
        !isLiveFftActive -> SpectrumPublishAction.CLEAR_TO_ZERO
        else -> SpectrumPublishAction.PRESERVE_PREVIOUS
    }
}
