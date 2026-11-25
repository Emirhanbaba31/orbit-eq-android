package com.orbiteq.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqCatalogTest {

    @Test
    fun catalogProvidesSevenCuratedValid31BandHarmanProfiles() {
        val entries = AutoEqCatalog.entries
        assertEquals(7, entries.size)

        val ids = entries.map { it.catalogId }
        val names = entries.map { it.name }
        assertTrue(ids.all { it.isNotBlank() })
        assertTrue(names.all { it.isNotBlank() })
        assertEquals(7, ids.toSet().size)
        assertEquals(7, names.toSet().size)

        assertTrue(names.contains("Soundcore Life Q30 (Harman 31-Band)"))
        assertTrue(names.contains("Soundcore Space Q45 (Harman)"))
        assertTrue(names.contains("Sony WH-1000XM4 (Harman)"))
        assertTrue(names.contains("Sony WH-1000XM5 (Harman)"))
        assertTrue(names.contains("Apple AirPods Pro 2 (Harman)"))
        assertTrue(names.contains("Samsung Galaxy Buds2 Pro (Harman)"))
        assertTrue(names.contains("Sennheiser HD 600 (Harman)"))

        entries.forEach { entry ->
            assertEquals(31, entry.gains31Db.size)
            assertTrue(
                "${entry.name} gains must be finite and within [-12, +12]",
                entry.gains31Db.all { it.isFinite() && it in -12f..12f },
            )
            assertTrue(entry.extraHeadroomDb.isFinite() && entry.extraHeadroomDb >= 0f)

            val customProfile = AutoEqCatalog.toCustomProfile(entry)
            assertEquals(entry.catalogId, customProfile.id)
            assertEquals(entry.name, customProfile.name)
            assertEquals(entry.gains31Db, customProfile.gains31Db)
            assertEquals(entry.extraHeadroomDb, customProfile.extraHeadroomDb, 0.0001f)
            assertTrue(customProfile.sourceLabel.isNotBlank())
        }
    }
}
