package com.orbiteq.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqCatalogTest {

    @Test
    fun catalogProvides36CuratedValid31BandHarmanProfiles() {
        val entries = AutoEqCatalog.entries
        assertEquals(36, entries.size)

        val ids = entries.map { it.catalogId }
        val names = entries.map { it.name }
        assertTrue(ids.all { it.isNotBlank() })
        assertTrue(names.all { it.isNotBlank() })
        assertTrue(entries.all { it.brand.isNotBlank() })
        assertEquals(36, ids.toSet().size)
        assertEquals(36, names.toSet().size)

        // Preserve v2 legacy catalog IDs for backup compatibility
        val expectedLegacyIds = listOf(
            "catalog_soundcore_q30",
            "catalog_soundcore_q45",
            "catalog_sony_xm4",
            "catalog_sony_xm5",
            "catalog_airpods_pro_2",
            "catalog_galaxy_buds2_pro",
            "catalog_sennheiser_hd600",
        )
        expectedLegacyIds.forEach { legacyId ->
            assertTrue("Legacy ID $legacyId must be preserved", ids.contains(legacyId))
        }

        // 4 dedicated Soundcore Life Q30 models first
        val q30Entries = entries.filter { it.category == CatalogCategory.Q30 }
        assertEquals(4, q30Entries.size)
        assertEquals(q30Entries, entries.take(4))
        assertEquals(
            listOf(
                "Soundcore Life Q30 (ANC On · Oratory1990)",
                "Soundcore Life Q30 (ANC Off · Harman)",
                "Soundcore Life Q30 (Transparency · Harman)",
                "Soundcore Life Q30 (Warm Vocal · Zero-Fatigue)",
            ),
            q30Entries.map { it.name },
        )

        // All 5 categories represented
        CatalogCategory.entries.forEach { category ->
            assertTrue(
                "Category $category should contain entries",
                entries.any { it.category == category },
            )
        }

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

    @Test
    fun filterSupportsCaseInsensitiveQueryAndCategoryFiltering() {
        assertEquals(AutoEqCatalog.entries, AutoEqCatalog.filter(""))
        assertEquals(AutoEqCatalog.entries, AutoEqCatalog.filter("   "))

        val q30Matches = AutoEqCatalog.filter("q30")
        assertEquals(4, q30Matches.size)
        assertTrue(q30Matches.all { it.category == CatalogCategory.Q30 })
        assertEquals(q30Matches, AutoEqCatalog.filter("  Q30 "))

        val sonyTws = AutoEqCatalog.filter("sony", CatalogCategory.TWS)
        assertEquals(2, sonyTws.size)
        assertEquals(
            setOf("catalog_sony_wf_xm4", "catalog_sony_wf_xm5"),
            sonyTws.map { it.catalogId }.toSet(),
        )

        val iems = AutoEqCatalog.filter("", CatalogCategory.IEM)
        assertEquals(4, iems.size)
        assertTrue(iems.all { it.category == CatalogCategory.IEM })

        val moondrop = AutoEqCatalog.filter("moondrop")
        assertEquals(2, moondrop.size)
        assertTrue(moondrop.all { it.brand == "Moondrop" })

        assertTrue(AutoEqCatalog.filter("nonexistent-model").isEmpty())
    }
}
