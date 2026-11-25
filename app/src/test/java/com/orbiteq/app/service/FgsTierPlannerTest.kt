package com.orbiteq.app.service

import com.orbiteq.app.model.SessionAttachmentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FgsTierPlannerTest {

    @Test
    fun backgroundStartAlwaysSelectsTier2EvenWithRecordAudioPermission() {
        assertEquals(
            FgsTier.TIER2_PROFILE_ONLY,
            FgsTierPlanner.planTier(startedFromForeground = false, hasRecordAudioPermission = true),
        )
        assertEquals(
            FgsTier.TIER2_PROFILE_ONLY,
            FgsTierPlanner.planTier(startedFromForeground = false, hasRecordAudioPermission = false),
        )
    }

    @Test
    fun foregroundStartWithRecordAudioPermissionSelectsTier1LiveFft() {
        assertEquals(
            FgsTier.TIER1_LIVE_FFT,
            FgsTierPlanner.planTier(startedFromForeground = true, hasRecordAudioPermission = true),
        )
    }

    @Test
    fun foregroundStartWithoutRecordAudioPermissionSelectsTier2ProfileOnly() {
        assertEquals(
            FgsTier.TIER2_PROFILE_ONLY,
            FgsTierPlanner.planTier(startedFromForeground = true, hasRecordAudioPermission = false),
        )
    }

    @Test
    fun resolveSessionIdRespectsAutoAndGlobalOnlyModesAndNonPositiveIds() {
        assertEquals(4821, FgsTierPlanner.resolveSessionId(SessionAttachmentMode.AUTO, 4821))
        assertEquals(0, FgsTierPlanner.resolveSessionId(SessionAttachmentMode.AUTO, null))
        assertEquals(0, FgsTierPlanner.resolveSessionId(SessionAttachmentMode.AUTO, 0))
        assertEquals(0, FgsTierPlanner.resolveSessionId(SessionAttachmentMode.AUTO, -7))
        assertEquals(0, FgsTierPlanner.resolveSessionId(SessionAttachmentMode.GLOBAL_ONLY, 4821))
        assertEquals(0, FgsTierPlanner.resolveSessionId(SessionAttachmentMode.GLOBAL_ONLY, null))
    }

    @Test
    fun backgroundCommandsNeverRePromoteAlreadyRunningForegroundService() {
        // Initial cold commands must satisfy startForeground, but non-foreground actions remain Tier 2 only.
        assertTrue(FgsTierPlanner.shouldPromoteForeground(isForegroundPromoted = false, action = FgsTierPlanner.ACTION_TOGGLE_BYPASS))
        assertTrue(FgsTierPlanner.shouldPromoteForeground(isForegroundPromoted = false, action = FgsTierPlanner.ACTION_UPDATE_SESSION))
        assertFalse(FgsTierPlanner.isForegroundEligibleAction(FgsTierPlanner.ACTION_TOGGLE_BYPASS))
        assertFalse(FgsTierPlanner.isForegroundEligibleAction(FgsTierPlanner.ACTION_UPDATE_SESSION))
        assertFalse(FgsTierPlanner.isForegroundEligibleAction(FgsTierPlanner.ACTION_START_BACKGROUND_SAFE))
        assertFalse(FgsTierPlanner.isForegroundEligibleAction(null))

        // Once promoted, background-safe starts, notification A/B bypass, & session updates must NOT downgrade or re-promote.
        assertFalse(FgsTierPlanner.shouldPromoteForeground(isForegroundPromoted = true, action = FgsTierPlanner.ACTION_START_BACKGROUND_SAFE))
        assertFalse(FgsTierPlanner.shouldPromoteForeground(isForegroundPromoted = true, action = FgsTierPlanner.ACTION_TOGGLE_BYPASS))
        assertFalse(FgsTierPlanner.shouldPromoteForeground(isForegroundPromoted = true, action = FgsTierPlanner.ACTION_UPDATE_SESSION))
        assertFalse(FgsTierPlanner.shouldPromoteForeground(isForegroundPromoted = true, action = null))

        // Explicit foreground start/promote actions remain eligible for promotion.
        assertTrue(FgsTierPlanner.shouldPromoteForeground(isForegroundPromoted = true, action = FgsTierPlanner.ACTION_START_FOREGROUND_FULL))
        assertTrue(FgsTierPlanner.shouldPromoteForeground(isForegroundPromoted = true, action = FgsTierPlanner.ACTION_PROMOTE_TO_FULL))
        assertTrue(FgsTierPlanner.isForegroundEligibleAction(FgsTierPlanner.ACTION_START_FOREGROUND_FULL))
        assertTrue(FgsTierPlanner.isForegroundEligibleAction(FgsTierPlanner.ACTION_PROMOTE_TO_FULL))
    }

    @Test
    fun nextModeActionIsNotForegroundEligibleAndDoesNotRePromote() {
        assertEquals("com.orbiteq.app.action.NEXT_MODE", FgsTierPlanner.ACTION_NEXT_MODE)
        assertFalse(FgsTierPlanner.isForegroundEligibleAction(FgsTierPlanner.ACTION_NEXT_MODE))
        assertFalse(
            FgsTierPlanner.shouldPromoteForeground(
                isForegroundPromoted = true,
                action = FgsTierPlanner.ACTION_NEXT_MODE,
            ),
        )
        assertTrue(
            FgsTierPlanner.shouldPromoteForeground(
                isForegroundPromoted = false,
                action = FgsTierPlanner.ACTION_NEXT_MODE,
            ),
        )
    }

    @Test
    fun tileActionPlannerDecidesStartClearBypassOrStopFromState() {
        assertEquals(
            TileClickDecision.START_ENGINE,
            TileActionPlanner.decideAction(powerEnabled = false, manuallyBypassed = false),
        )
        assertEquals(
            TileClickDecision.START_ENGINE,
            TileActionPlanner.decideAction(powerEnabled = false, manuallyBypassed = true),
        )
        assertEquals(
            TileClickDecision.CLEAR_BYPASS,
            TileActionPlanner.decideAction(powerEnabled = true, manuallyBypassed = true),
        )
        assertEquals(
            TileClickDecision.STOP_ENGINE,
            TileActionPlanner.decideAction(powerEnabled = true, manuallyBypassed = false),
        )
    }
}
