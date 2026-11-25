package com.orbiteq.app.service

import com.orbiteq.app.model.SessionAttachmentMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class AudioSessionRegistryTest {

    @Before
    fun setUp() {
        AudioSessionRegistry.resetForTesting()
    }

    @After
    fun tearDown() {
        AudioSessionRegistry.resetForTesting()
    }

    @Test
    fun resolveActiveSessionIdDoesNotHijackPausedSpotifySessionWhenYoutubePlays() {
        AudioSessionRegistry.onSessionOpened(101, "com.spotify.music")

        // Spotify session 101 is still open (paused), while YouTube is now active without its own effect session.
        val resolvedForYoutube = AudioSessionRegistry.resolveActiveSessionId("com.google.android.youtube")
        assertNull(resolvedForYoutube)
        assertEquals(
            0,
            FgsTierPlanner.resolveSessionId(SessionAttachmentMode.AUTO, resolvedForYoutube),
        )

        // Same for Audible when only Spotify has an open session.
        val resolvedForAudible = AudioSessionRegistry.resolveActiveSessionId("com.audible.application")
        assertNull(resolvedForAudible)
        assertEquals(
            0,
            FgsTierPlanner.resolveSessionId(SessionAttachmentMode.AUTO, resolvedForAudible),
        )

        // When Spotify resumes, its matching session is still resolved cleanly.
        assertEquals(101, AudioSessionRegistry.resolveActiveSessionId("com.spotify.music"))
    }

    @Test
    fun resolveActiveSessionIdNormalizesPackageNameAndFallsBackOnlyWhenPreferredIsNull() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            AudioSessionRegistry.onSessionOpened(101, "com.spotify.music")
            AudioSessionRegistry.onSessionOpened(202, "  COM.TIDAL.MUSIC  ")
            AudioSessionRegistry.onSessionOpened(303, "com.spotify.music")

            assertEquals(303, AudioSessionRegistry.resolveActiveSessionId("  COM.SPOTIFY.MUSIC "))
            assertEquals(202, AudioSessionRegistry.resolveActiveSessionId("com.tidal.music"))

            // Null or blank preferredPackage falls back to the latest open session.
            assertEquals(303, AudioSessionRegistry.resolveActiveSessionId(null))
            assertEquals(303, AudioSessionRegistry.resolveActiveSessionId("   "))

            AudioSessionRegistry.onSessionClosed(303)
            assertEquals(101, AudioSessionRegistry.resolveActiveSessionId("com.spotify.music"))
            assertEquals(202, AudioSessionRegistry.resolveActiveSessionId(null))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
