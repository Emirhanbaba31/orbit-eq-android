package com.orbiteq.app.dsp

import java.util.Locale
import kotlin.math.abs

sealed interface AutoEqParseResult {
    data class Success(
        val gains31Db: List<Float>,
        val pointCount: Int,
        val sourceLabel: String = "AutoEQ",
    ) : AutoEqParseResult

    data class Error(
        val reason: String,
    ) : AutoEqParseResult
}

object AutoEqParser {

    private const val MIN_FREQ_HZ = 10f
    private const val MAX_FREQ_HZ = 24000f
    private const val MIN_PARAMETRIC_Q = 0.05
    private const val MAX_PARAMETRIC_Q = 30.0
    private const val MIN_GAIN_DB = -15f
    private const val MAX_GAIN_DB = 15f
    private const val MIN_POINTS = 3
    private const val DUPLICATE_FREQ_EPSILON_HZ = 1e-3f
    private val TOKEN_SPLIT_REGEX = Regex("[,\\s]+")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val FILTER_PREFIX_REGEX = Regex("^filter(?=\\s|\\d|:|$)", RegexOption.IGNORE_CASE)
    private val PARAMETRIC_LINE_REGEX = Regex("^filter(?:\\s*\\d+)?\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)

    private data class EqPoint(
        val freqHz: Float,
        val gainDb: Float,
    )

    fun parse(rawText: String): AutoEqParseResult {
        val normalizedInput = rawText.trimStart('\uFEFF')
        if (normalizedInput.isBlank()) {
            return AutoEqParseResult.Error("AutoEQ input is empty")
        }

        val contentLines = normalizedInput.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("preamp:", ignoreCase = true) }
            .toList()

        if (contentLines.isEmpty()) {
            return AutoEqParseResult.Error("At least $MIN_POINTS EQ points are required (found 0)")
        }

        if (contentLines.any { FILTER_PREFIX_REGEX.containsMatchIn(it) }) {
            return parseParametricEq(contentLines)
        }
        return parseGraphicEq(contentLines)
    }

    private fun parseParametricEq(contentLines: List<String>): AutoEqParseResult {
        val filters = ArrayList<ParametricFilter>(contentLines.size)

        for (line in contentLines) {
            val match = PARAMETRIC_LINE_REGEX.matchEntire(line)
                ?: return AutoEqParseResult.Error("Malformed parametric filter line: '$line'")
            val tokens = match.groupValues[1].trim().split(WHITESPACE_REGEX)
            if (tokens.isEmpty() || tokens[0].isEmpty()) {
                return AutoEqParseResult.Error("Empty parametric filter definition in '$line'")
            }

            when (tokens[0].uppercase(Locale.ROOT)) {
                "OFF" -> continue
                "ON" -> Unit
                else -> return AutoEqParseResult.Error(
                    "Expected ON or OFF state in parametric filter line: '$line'",
                )
            }

            if (tokens.size < 2) {
                return AutoEqParseResult.Error("Missing parametric filter type in '$line'")
            }

            val filterType = when (tokens[1].uppercase(Locale.ROOT)) {
                "PK" -> ParametricFilterType.PK
                "LS", "LSC", "LSQ" -> ParametricFilterType.LS
                "HS", "HSC", "HSQ" -> ParametricFilterType.HS
                else -> return AutoEqParseResult.Error(
                    "Unsupported parametric filter type '${tokens[1]}' in '$line'",
                )
            }

            var freqHz: Double? = null
            var gainDb: Double? = null
            var qVal: Double? = null
            var idx = 2

            while (idx < tokens.size) {
                when (tokens[idx].lowercase(Locale.ROOT)) {
                    "fc" -> {
                        if (freqHz != null) {
                            return AutoEqParseResult.Error("Duplicate Fc parameter in '$line'")
                        }
                        if (idx + 1 >= tokens.size) {
                            return AutoEqParseResult.Error("Missing Fc value in '$line'")
                        }
                        val (rawValue, nextIdx) = consumeValueAndOptionalUnit(tokens, idx + 1, "hz")
                        val parsed = rawValue.toDoubleOrNull()
                        if (parsed == null ||
                            !parsed.isFinite() ||
                            parsed !in MIN_FREQ_HZ.toDouble()..MAX_FREQ_HZ.toDouble()
                        ) {
                            return AutoEqParseResult.Error(
                                "Invalid Fc '$rawValue' in '$line' (expected 10..24000 Hz)",
                            )
                        }
                        freqHz = parsed
                        idx = nextIdx
                    }

                    "gain" -> {
                        if (gainDb != null) {
                            return AutoEqParseResult.Error("Duplicate Gain parameter in '$line'")
                        }
                        if (idx + 1 >= tokens.size) {
                            return AutoEqParseResult.Error("Missing Gain value in '$line'")
                        }
                        val (rawValue, nextIdx) = consumeValueAndOptionalUnit(tokens, idx + 1, "db")
                        val parsed = rawValue.toDoubleOrNull()
                        if (parsed == null || !parsed.isFinite()) {
                            return AutoEqParseResult.Error(
                                "Invalid or non-finite Gain '$rawValue' in '$line'",
                            )
                        }
                        gainDb = parsed
                        idx = nextIdx
                    }

                    "q" -> {
                        if (qVal != null) {
                            return AutoEqParseResult.Error("Duplicate Q parameter in '$line'")
                        }
                        if (idx + 1 >= tokens.size) {
                            return AutoEqParseResult.Error("Missing Q value in '$line'")
                        }
                        val rawValue = tokens[idx + 1]
                        val parsed = rawValue.toDoubleOrNull()
                        if (parsed == null ||
                            !parsed.isFinite() ||
                            parsed !in MIN_PARAMETRIC_Q..MAX_PARAMETRIC_Q
                        ) {
                            return AutoEqParseResult.Error(
                                "Invalid Q '$rawValue' in '$line' (expected $MIN_PARAMETRIC_Q..$MAX_PARAMETRIC_Q)",
                            )
                        }
                        qVal = parsed
                        idx += 2
                    }

                    else -> return AutoEqParseResult.Error(
                        "Unexpected parameter '${tokens[idx]}' in '$line'",
                    )
                }
            }

            if (freqHz == null || gainDb == null) {
                return AutoEqParseResult.Error("Parametric filter must specify both Fc and Gain: '$line'")
            }
            if (filterType == ParametricFilterType.PK && qVal == null) {
                return AutoEqParseResult.Error("Peaking filter (PK) must specify Q: '$line'")
            }

            filters.add(
                ParametricFilter(
                    type = filterType,
                    freqHz = freqHz,
                    gainDb = gainDb,
                    q = qVal ?: ParametricBiquadEvaluator.DEFAULT_SHELF_Q,
                ),
            )
        }

        if (filters.isEmpty()) {
            return AutoEqParseResult.Error("At least 1 active ON parametric filter is required")
        }

        val gains31 = ParametricBiquadEvaluator.evaluateOnIso31(filters)
        return AutoEqParseResult.Success(
            gains31Db = gains31,
            pointCount = filters.size,
            sourceLabel = "Parametric EQ",
        )
    }

    private fun consumeValueAndOptionalUnit(
        tokens: List<String>,
        valueIdx: Int,
        unit: String,
    ): Pair<String, Int> {
        val raw = tokens[valueIdx]
        return if (raw.length > unit.length && raw.endsWith(unit, ignoreCase = true)) {
            raw.dropLast(unit.length) to (valueIdx + 1)
        } else if (valueIdx + 1 < tokens.size && tokens[valueIdx + 1].equals(unit, ignoreCase = true)) {
            raw to (valueIdx + 2)
        } else {
            raw to (valueIdx + 1)
        }
    }

    private fun parseGraphicEq(contentLines: List<String>): AutoEqParseResult {
        val parsedPoints = ArrayList<EqPoint>()
        for (line in contentLines) {
            val payload = if (line.startsWith("graphiceq:", ignoreCase = true)) {
                line.substringAfter(':').trim()
            } else {
                line
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

        parsedPoints.sortBy { it.freqHz }
        val deduplicated = deduplicatePoints(parsedPoints)
        if (deduplicated.size < MIN_POINTS) {
            return AutoEqParseResult.Error(
                "At least $MIN_POINTS distinct EQ points are required (found ${deduplicated.size})",
            )
        }

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
            sourceLabel = "AutoEQ",
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
        val f = first.lowercase(Locale.ROOT)
        val s = second.lowercase(Locale.ROOT)
        return f.contains("freq") && (s.contains("gain") || s.contains("raw") || s.contains("db"))
    }
}
