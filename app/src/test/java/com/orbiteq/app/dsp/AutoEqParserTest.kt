package com.orbiteq.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log2

class AutoEqParserTest {

    @Test
    fun parsesSingleLineWaveletGraphicEqOntoAll31IsoBands() {
        val raw = "GraphicEQ: 20 -2.0; 100 -4.0; 1000 0.0; 4000 3.0; 20000 -1.0"
        val result = AutoEqParser.parse(raw)
        assertTrue("Expected Success, got $result", result is AutoEqParseResult.Success)
        val success = result as AutoEqParseResult.Success
        assertEquals(31, success.gains31Db.size)
        assertEquals(5, success.pointCount)
        assertTrue(success.gains31Db.all { it.isFinite() && it in -15f..15f })

        assertEquals(-2.0f, success.gains31Db[bandIndex(20f)], 0.001f)
        assertEquals(-4.0f, success.gains31Db[bandIndex(100f)], 0.001f)
        assertEquals(0.0f, success.gains31Db[bandIndex(1000f)], 0.001f)
        assertEquals(3.0f, success.gains31Db[bandIndex(4000f)], 0.001f)
        assertEquals(-1.0f, success.gains31Db[bandIndex(20000f)], 0.001f)

        val expected315 = -4.0f + 4.0f * ((log2(315f) - log2(100f)) / (log2(1000f) - log2(100f)))
        assertEquals(expected315, success.gains31Db[bandIndex(315f)], 0.005f)
    }

    @Test
    fun parsesMultilineWhitespaceAndCsvWhileSkippingCommentsAndPreamp() {
        val multiline = """
            # Soundcore Life Q30 AutoEQ export
            Preamp: -6.2 dB
            20 -1.5 # sub-bass
            100 -4.5
            1000 0.5
            4000 2.5
            20000 -0.5
        """.trimIndent()
        val multiResult = AutoEqParser.parse(multiline)
        assertTrue(multiResult is AutoEqParseResult.Success)
        val multiSuccess = multiResult as AutoEqParseResult.Success
        assertEquals(31, multiSuccess.gains31Db.size)
        assertEquals(-1.5f, multiSuccess.gains31Db[0], 0.001f)
        assertEquals(-4.5f, multiSuccess.gains31Db[7], 0.001f)
        assertEquals(-0.5f, multiSuccess.gains31Db[30], 0.001f)

        val csv = """
            # CSV EQ curve
            Preamp: -3.0 dB
            frequency,gain
            20,-2.0
            250,0.0
            2000,3.0
            16000,1.0
        """.trimIndent()
        val csvResult = AutoEqParser.parse(csv)
        assertTrue(csvResult is AutoEqParseResult.Success)
        val csvSuccess = csvResult as AutoEqParseResult.Success
        assertEquals(31, csvSuccess.gains31Db.size)
        assertEquals(-2.0f, csvSuccess.gains31Db[0], 0.001f)
        assertEquals(0.0f, csvSuccess.gains31Db[11], 0.001f)
        assertEquals(3.0f, csvSuccess.gains31Db[20], 0.001f)
        assertEquals(1.0f, csvSuccess.gains31Db[29], 0.001f)
        // Flat edge extension above 16000 Hz to 20000 Hz
        assertEquals(1.0f, csvSuccess.gains31Db[30], 0.001f)
    }

    @Test
    fun supportsCaseInsensitiveAndPrefixlessSemicolonGraphicEq() {
        val raw = """
            graphiceq: 50 -3.0; 500 0.0;
            5000 2.0; 16000 -1.0;
        """.trimIndent()
        val res1 = AutoEqParser.parse(raw)
        assertTrue(res1 is AutoEqParseResult.Success)
        val s1 = res1 as AutoEqParseResult.Success
        // Flat edge extension below 50 Hz
        assertEquals(-3.0f, s1.gains31Db[0], 0.001f)
        assertEquals(-3.0f, s1.gains31Db[4], 0.001f)
        assertEquals(-1.0f, s1.gains31Db[30], 0.001f)

        val noPrefix = "50 -3.0; 500 0.0; 5000 2.0; 16000 -1.0"
        val res2 = AutoEqParser.parse(noPrefix)
        assertTrue(res2 is AutoEqParseResult.Success)
        assertEquals(s1.gains31Db, (res2 as AutoEqParseResult.Success).gains31Db)
    }

    @Test
    fun handlesOutOfOrderPointsDuplicateAveragingAndExtremeGainClamping() {
        val unordered = "GraphicEQ: 20000 -1.0; 100 -4.0; 4000 3.0; 20 -2.0; 1000 0.0"
        val ordered = "GraphicEQ: 20 -2.0; 100 -4.0; 1000 0.0; 4000 3.0; 20000 -1.0"
        val resUnordered = AutoEqParser.parse(unordered) as AutoEqParseResult.Success
        val resOrdered = AutoEqParser.parse(ordered) as AutoEqParseResult.Success
        assertEquals(resOrdered.gains31Db, resUnordered.gains31Db)

        val dupAndExtreme = "GraphicEQ: 20 40.0; 100 -6.0; 100 -2.0; 1000 0.0; 20000 -40.0"
        val resDup = AutoEqParser.parse(dupAndExtreme) as AutoEqParseResult.Success
        assertEquals(15.0f, resDup.gains31Db[bandIndex(20f)], 0.001f)
        assertEquals(-4.0f, resDup.gains31Db[bandIndex(100f)], 0.001f)
        assertEquals(0.0f, resDup.gains31Db[bandIndex(1000f)], 0.001f)
        assertEquals(-15.0f, resDup.gains31Db[bandIndex(20000f)], 0.001f)
        assertTrue(resDup.gains31Db.all { it.isFinite() && it in -15f..15f })
    }

    private fun bandIndex(freqHz: Float): Int =
        PleasureProfiles.ISO_31_FREQUENCIES_HZ.indexOfFirst { it == freqHz }

    @Test
    fun rejectsMalformedOrInvalidInputsWithoutThrowing() {
        val invalidInputs = listOf(
            "",
            "   \n\t  ",
            "# comment only\nPreamp: -4.2 dB",
            "GraphicEQ: 100 -2.0",
            "GraphicEQ: 100 -2.0; 1000 1.0",
            "GraphicEQ: 0 -2.0; 100 0.0; 1000 1.0; 10000 0.0",
            "GraphicEQ: -50 -2.0; 100 0.0; 1000 1.0; 10000 0.0",
            "GraphicEQ: 5 -2.0; 100 0.0; 1000 1.0; 10000 0.0",
            "GraphicEQ: 20 -2.0; 100 0.0; 1000 1.0; 30000 0.0",
            "GraphicEQ: 20 NaN; 100 0.0; 1000 1.0; 10000 0.0",
            "GraphicEQ: 20 Infinity; 100 0.0; 1000 1.0; 10000 0.0",
            "GraphicEQ: 20 -Infinity; 100 0.0; 1000 1.0; 10000 0.0",
            "not a valid autoeq file at all",
            "20 abc\n100 0.0\n1000 1.0\n4000 2.0",
        )

        invalidInputs.forEach { input ->
            val result = AutoEqParser.parse(input)
            assertTrue("Expected Error for input <$input>, got $result", result is AutoEqParseResult.Error)
            assertTrue((result as AutoEqParseResult.Error).reason.isNotBlank())
        }
    }
}
