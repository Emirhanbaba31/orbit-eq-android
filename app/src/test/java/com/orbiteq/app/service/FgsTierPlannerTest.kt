package com.orbiteq.app.service

import com.orbiteq.app.model.SessionAttachmentMode
import org.junit.Assert.assertEquals
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
}
