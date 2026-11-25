package com.orbiteq.app.dsp

import kotlin.math.abs

sealed interface AutoEqParseResult {
    data class Success(
        val gains31Db: List<Float>,
        val pointCount: Int,
    ) : AutoEqParseResult

    data class Error(
        val reason: String,
    ) : AutoEqParseResult
}

object AutoEqParser {

    private const val MIN_FREQ_HZ = 10f
    private const val MAX_FREQ_HZ = 24000f
    private const val MIN_GAIN_DB = -15f
    private const val MAX_GAIN_DB = 15f
    private const val MIN_POINTS = 3
    private const val DUPLICATE_FREQ_EPSILON_HZ = 1e-3f
    private val TOKEN_SPLIT_REGEX = Regex("[,\\s]+")

    private data class EqPoint(
        val freqHz: Float,
        val gainDb: Float,
    )

    fun parse(rawText: String): AutoEqParseResult {
        val normalizedInput = rawText.trimStart('\uFEFF')
        if (normalizedInput.isBlank()) {
            return AutoEqParseResult.Error("AutoEQ input is empty")
        }

        val parsedPoints = ArrayList<EqPoint>()
        for (rawLine in normalizedInput.lineSequence()) {
            val withoutComment = rawLine.substringBefore('#').trim()
            if (withoutComment.isEmpty()) continue
            if (withoutComment.startsWith("preamp:", ignoreCase = true)) continue

            val payload = if (withoutComment.startsWith("graphiceq:", ignoreCase = true)) {
                withoutComment.substringAfter(':').trim()
            } else {
                withoutComment
            }
            if (payload.isEmpty()) continue

            for (rawSegment in payload.split(';')) {
                val segment = rawSegment.trim()
                if (segment.isEmpty()) continue

                val parts = segment.split(TOKEN_SPLIT_REGEX)
                if (parts.size == 2 && parsedPoints.isEmpty() && isCsvHeader(parts[0], parts[1])) {
                    continue
                }
                if (parts.size != 2) {
                    return AutoEqParseResult.Error("Expected 'frequency gain' pair, got '$segment'")
                }

                val freq = parts[0].toFloatOrNull()
                if (freq == null || !freq.isFinite() || freq !in MIN_FREQ_HZ..MAX_FREQ_HZ) {
                    return AutoEqParseResult.Error("Invalid frequency '${parts[0]}' (expected 10..24000 Hz)")
                }

                val gain = parts[1].toFloatOrNull()
                if (gain == null || !gain.isFinite()) {
                    return AutoEqParseResult.Error("Non-finite or invalid gain '${parts[1]}' at $freq Hz")
                }

                parsedPoints.add(EqPoint(freq, gain))
            }
        }

        if (parsedPoints.size < MIN_POINTS) {
            return AutoEqParseResult.Error(
                "At least $MIN_POINTS EQ points are required (found ${parsedPoints.size})",
            )
        }

        parsedPoints.sortBy { it.freqHz }
        val deduplicated = deduplicatePoints(parsedPoints)
        val sourceFreqs = FloatArray(deduplicated.size) { i -> deduplicated[i].freqHz }
        val sourceGains = FloatArray(deduplicated.size) { i -> deduplicated[i].gainDb }

        val resampled = PleasureProfiles.resampleLog2(
            sourceFreqsHz = sourceFreqs,
            sourceGainsDb = sourceGains,
            targetFreqsHz = PleasureProfiles.ISO_31_FREQUENCIES_HZ,
        )

        val clamped31 = List(resampled.size) { i ->
            val v = resampled[i]
            val clamped = (if (v.isFinite()) v else 0f).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            if (clamped == 0f) 0f else clamped
        }

        return AutoEqParseResult.Success(
            gains31Db = clamped31,
            pointCount = deduplicated.size,
        )
    }

    private fun deduplicatePoints(sortedPoints: List<EqPoint>): List<EqPoint> {
        val result = ArrayList<EqPoint>(sortedPoints.size)
        var index = 0
        while (index < sortedPoints.size) {
            val baseFreq = sortedPoints[index].freqHz
            var freqSum = baseFreq.toDouble()
            var gainSum = sortedPoints[index].gainDb.toDouble()
            var count = 1
            var next = index + 1
            while (next < sortedPoints.size &&
                abs(sortedPoints[next].freqHz - baseFreq) < DUPLICATE_FREQ_EPSILON_HZ
            ) {
                freqSum += sortedPoints[next].freqHz.toDouble()
                gainSum += sortedPoints[next].gainDb.toDouble()
                count++
                next++
            }
            result.add(
                EqPoint(
                    freqHz = (freqSum / count).toFloat(),
                    gainDb = (gainSum / count).toFloat(),
                ),
            )
            index = next
        }
        return result
    }

    private fun isCsvHeader(first: String, second: String): Boolean {
        val f = first.lowercase()
        val s = second.lowercase()
        return f.contains("freq") && (s.contains("gain") || s.contains("raw") || s.contains("db"))
    }
}
