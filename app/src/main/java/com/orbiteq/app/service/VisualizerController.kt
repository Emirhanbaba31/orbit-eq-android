package com.orbiteq.app.service

import android.media.audiofx.Visualizer

class VisualizerController {

    private var visualizer: Visualizer? = null
    private var fftBuffer: ByteArray = ByteArray(DEFAULT_CAPTURE_SIZE)

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var activeSessionId: Int = 0
        private set

    @Volatile
    var sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ
        private set

    @Synchronized
    fun start(sessionId: Int): Boolean {
        stop()
        val requested = sessionId.coerceAtLeast(0)
        return startInternal(requested) || (requested > 0 && startInternal(0))
    }

    @Synchronized
    fun captureFft(): ByteArray? {
        val current = visualizer ?: return null
        if (!isRunning) return null
        return try {
            val status = current.getFft(fftBuffer)
            if (status == Visualizer.SUCCESS) {
                fftBuffer
            } else {
                if (status == Visualizer.ERROR_DEAD_OBJECT || status == Visualizer.ERROR_NO_INIT) {
                    stop()
                }
                null
            }
        } catch (_: SecurityException) {
            stop()
            null
        } catch (_: RuntimeException) {
            stop()
            null
        }
    }

    @Synchronized
    fun stop() {
        val existing = visualizer
        visualizer = null
        isRunning = false
        activeSessionId = 0
        if (existing != null) {
            try {
                existing.enabled = false
            } catch (_: RuntimeException) {
            }
            try {
                existing.release()
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun startInternal(sessionId: Int): Boolean {
        var created: Visualizer? = null
        return try {
            created = Visualizer(sessionId)
            val captureSize = chooseCaptureSize()
            created.captureSize = captureSize
            try {
                created.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            } catch (_: RuntimeException) {
            }
            val enableStatus = created.setEnabled(true)
            if (enableStatus != Visualizer.SUCCESS) {
                created.release()
                return false
            }

            val reportedRate = try {
                val raw = created.samplingRate
                val hz = if (raw > 1_000_000) raw / 1000 else raw
                if (hz in 8000..192000) hz else DEFAULT_SAMPLE_RATE_HZ
            } catch (_: RuntimeException) {
                DEFAULT_SAMPLE_RATE_HZ
            }

            fftBuffer = ByteArray(captureSize)
            visualizer = created
            activeSessionId = sessionId
            sampleRateHz = reportedRate
            isRunning = true
            true
        } catch (_: SecurityException) {
            safeRelease(created)
            false
        } catch (_: UnsupportedOperationException) {
            safeRelease(created)
            false
        } catch (_: RuntimeException) {
            safeRelease(created)
            false
        }
    }

    private fun chooseCaptureSize(): Int {
        return try {
            val range = Visualizer.getCaptureSizeRange()
            val minSize = range.getOrElse(0) { 128 }.coerceAtLeast(128)
            val maxSize = range.getOrElse(1) { DEFAULT_CAPTURE_SIZE }.coerceAtLeast(minSize)
            var candidate = DEFAULT_CAPTURE_SIZE.coerceIn(minSize, maxSize)
            // Visualizer capture size must be a power of 2.
            var pow2 = Integer.highestOneBit(candidate)
            if (pow2 < minSize) {
                pow2 = minSize
            }
            pow2
        } catch (_: RuntimeException) {
            DEFAULT_CAPTURE_SIZE
        }
    }

    private fun safeRelease(instance: Visualizer?) {
        if (instance == null) return
        try {
            instance.enabled = false
        } catch (_: RuntimeException) {
        }
        try {
            instance.release()
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        const val DEFAULT_CAPTURE_SIZE = 1024
        const val DEFAULT_SAMPLE_RATE_HZ = 48000
    }
}
