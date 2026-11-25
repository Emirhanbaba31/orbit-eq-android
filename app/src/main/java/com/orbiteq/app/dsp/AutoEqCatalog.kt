package com.orbiteq.app.dsp

import com.orbiteq.app.model.CustomHeadphoneProfile

data class CatalogHeadphoneEntry(
    val catalogId: String,
    val name: String,
    val gains31Db: List<Float>,
    val extraHeadroomDb: Float = 0f,
)

object AutoEqCatalog {

    val entries: List<CatalogHeadphoneEntry> = listOf(
        CatalogHeadphoneEntry(
            catalogId = "catalog_soundcore_q30",
            name = "Soundcore Life Q30 (Harman 31-Band)",
            gains31Db = listOf(
                -0.5f, -1.0f, -1.5f, -2.2f, -3.0f, -3.8f, -4.5f, -5.2f, -5.5f, -5.0f,
                -4.2f, -3.2f, -2.0f, -1.0f, -0.5f, 0.0f, 0.4f, 0.6f, 1.0f, 1.5f,
                2.2f, 3.0f, 3.4f, 2.8f, 0.8f, -1.8f, -2.5f, -0.8f, 0.4f, 0.6f, 0.2f,
            ),
            extraHeadroomDb = 0f,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_soundcore_q45",
            name = "Soundcore Space Q45 (Harman)",
            gains31Db = listOf(
                -1.0f, -1.5f, -2.0f, -2.5f, -3.0f, -3.2f, -3.2f, -3.0f, -2.5f, -1.8f,
                -1.0f, -0.4f, 0.2f, 0.5f, 0.6f, 0.6f, 0.5f, 0.4f, 0.8f, 1.4f,
                2.2f, 3.0f, 3.2f, 2.2f, 0.4f, -1.5f, -3.2f, -2.8f, -1.0f, 0.5f, 0.0f,
            ),
            extraHeadroomDb = 0f,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_sony_xm4",
            name = "Sony WH-1000XM4 (Harman)",
            gains31Db = listOf(
                0.5f, 0.0f, -0.6f, -1.4f, -2.2f, -3.2f, -4.0f, -4.6f, -4.8f, -4.4f,
                -3.6f, -2.6f, -1.6f, -0.8f, -0.2f, 0.3f, 0.7f, 1.0f, 1.4f, 1.8f,
                2.2f, 2.8f, 3.2f, 2.6f, 1.2f, -0.6f, -1.4f, -1.8f, -0.5f, 1.0f, 0.5f,
            ),
            extraHeadroomDb = 0f,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_sony_xm5",
            name = "Sony WH-1000XM5 (Harman)",
            gains31Db = listOf(
                1.0f, 0.5f, 0.0f, -0.6f, -1.2f, -1.8f, -2.4f, -2.8f, -3.0f, -2.8f,
                -2.4f, -1.8f, -1.0f, -0.3f, 0.2f, 0.5f, 0.6f, 0.5f, 0.8f, 1.2f,
                1.6f, 2.0f, 2.2f, 2.8f, 2.2f, 0.2f, -1.2f, 0.0f, 1.2f, 1.5f, 0.5f,
            ),
            extraHeadroomDb = 0f,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_airpods_pro_2",
            name = "Apple AirPods Pro 2 (Harman)",
            gains31Db = listOf(
                1.2f, 1.0f, 0.8f, 0.5f, 0.2f, 0.0f, -0.2f, -0.4f, -0.6f, -0.8f,
                -1.0f, -0.8f, -0.4f, 0.0f, 0.2f, 0.3f, 0.2f, 0.0f, 0.3f, 0.6f,
                0.8f, 1.0f, 1.2f, 0.8f, 0.2f, -0.5f, -0.4f, 0.6f, 1.2f, 1.0f, 0.4f,
            ),
            extraHeadroomDb = 0f,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_galaxy_buds2_pro",
            name = "Samsung Galaxy Buds2 Pro (Harman)",
            gains31Db = listOf(
                0.8f, 0.6f, 0.4f, 0.2f, 0.0f, -0.2f, -0.4f, -0.6f, -0.8f, -0.8f,
                -0.6f, -0.4f, 0.0f, 0.2f, 0.3f, 0.2f, 0.0f, -0.2f, 0.0f, 0.4f,
                0.8f, 0.6f, 0.2f, -0.4f, -1.4f, -2.2f, -2.4f, -0.8f, 0.5f, 0.8f, 0.3f,
            ),
            extraHeadroomDb = 0f,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_sennheiser_hd600",
            name = "Sennheiser HD 600 (Harman)",
            gains31Db = listOf(
                6.0f, 5.6f, 5.0f, 4.2f, 3.2f, 2.2f, 1.2f, 0.4f, -0.2f, -0.5f,
                -0.6f, -0.5f, -0.2f, 0.0f, 0.2f, 0.3f, 0.2f, 0.0f, -0.3f, -0.8f,
                -1.2f, -1.8f, -2.6f, -2.0f, -0.6f, 0.6f, 1.0f, 1.2f, 1.6f, 1.8f, 1.0f,
            ),
            extraHeadroomDb = 0f,
        ),
    )

    fun toCustomProfile(entry: CatalogHeadphoneEntry): CustomHeadphoneProfile = CustomHeadphoneProfile(
        id = entry.catalogId,
        name = entry.name,
        gains31Db = List(31) { idx ->
            val v = entry.gains31Db.getOrElse(idx) { 0f }
            (if (v.isFinite()) v else 0f).coerceIn(-15f, 15f)
        },
        extraHeadroomDb = if (entry.extraHeadroomDb.isFinite()) entry.extraHeadroomDb.coerceAtLeast(0f) else 0f,
        sourceLabel = "AutoEQ Catalog",
    )
}
