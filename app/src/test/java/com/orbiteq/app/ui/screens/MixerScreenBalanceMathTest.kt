package com.orbiteq.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class MixerScreenBalanceMathTest {

    @Test
    fun normalizeDisplayedChannelBalanceAlignsWithCenterDeadbandInclusive() {
        assertEquals(0f, normalizeDisplayedChannelBalance(0f), 1e-6f)
        assertEquals(0f, normalizeDisplayedChannelBalance(0.04f), 1e-6f)
        assertEquals(0f, normalizeDisplayedChannelBalance(-0.04f), 1e-6f)
        assertEquals(0f, normalizeDisplayedChannelBalance(Float.NaN), 1e-6f)
        assertEquals(0f, normalizeDisplayedChannelBalance(Float.POSITIVE_INFINITY), 1e-6f)

        assertEquals(0.06f, normalizeDisplayedChannelBalance(0.06f), 1e-6f)
        assertEquals(0.25f, normalizeDisplayedChannelBalance(0.25f), 1e-6f)
        assertEquals(-1.0f, normalizeDisplayedChannelBalance(-1.5f), 1e-6f)
        assertEquals(1.0f, normalizeDisplayedChannelBalance(1.5f), 1e-6f)
    }

    @Test
    fun snapAndQuantizeChannelBalanceSnapsDeadbandAndQuantizesToTwoPercentSteps() {
        assertEquals(0f, snapAndQuantizeChannelBalance(0f), 1e-6f)
        assertEquals(0f, snapAndQuantizeChannelBalance(0.04f), 1e-6f)
        assertEquals(0f, snapAndQuantizeChannelBalance(-0.04f), 1e-6f)
        // 0.045 rounds to 0.04 on the 0.02 grid, which must still collapse into the 0.04 deadband
        assertEquals(0f, snapAndQuantizeChannelBalance(0.045f), 1e-6f)
        assertEquals(0f, snapAndQuantizeChannelBalance(-0.045f), 1e-6f)

        assertEquals(0.06f, snapAndQuantizeChannelBalance(0.055f), 1e-6f)
        assertEquals(-0.06f, snapAndQuantizeChannelBalance(-0.055f), 1e-6f)
        assertEquals(0.40f, snapAndQuantizeChannelBalance(0.409f), 1e-6f)
        assertEquals(-0.42f, snapAndQuantizeChannelBalance(-0.415f), 1e-6f)
        assertEquals(1.0f, snapAndQuantizeChannelBalance(1.4f), 1e-6f)
        assertEquals(-1.0f, snapAndQuantizeChannelBalance(-1.4f), 1e-6f)
        assertEquals(0f, snapAndQuantizeChannelBalance(Float.NaN), 1e-6f)
    }
}
