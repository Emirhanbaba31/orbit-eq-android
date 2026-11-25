package com.orbiteq.app.dsp

import com.orbiteq.app.model.CustomHeadphoneProfile
import java.util.Locale

enum class CatalogCategory {
    Q30,
    OVER_EAR,
    TWS,
    IEM,
    STUDIO,
}

data class CatalogHeadphoneEntry(
    val catalogId: String,
    val name: String,
    val gains31Db: List<Float>,
    val extraHeadroomDb: Float = 0f,
    val brand: String,
    val category: CatalogCategory,
)

object AutoEqCatalog {

    val entries: List<CatalogHeadphoneEntry> = listOf(
        // 4 Dedicated Soundcore Life Q30 Modes
        CatalogHeadphoneEntry(
            catalogId = "catalog_soundcore_q30",
            name = "Soundcore Life Q30 (ANC On · Oratory1990)",
            gains31Db = listOf(
                0.0f, -0.3f, -0.8f, -1.6f, -2.5f, -3.5f, -4.4f, -5.0f, -5.2f, -4.7f,
                -3.9f, -3.0f, -1.8f, -0.9f, -0.3f, 0.1f, 0.4f, 0.6f, 0.9f, 1.5f,
                2.4f, 3.2f, 3.5f, 2.6f, 0.6f, -2.2f, -2.8f, -0.8f, 0.8f, 1.0f, 0.4f,
            ),
            brand = "Soundcore",
            category = CatalogCategory.Q30,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_soundcore_q30_anc_off",
            name = "Soundcore Life Q30 (ANC Off · Harman)",
            gains31Db = listOf(
                2.0f, 1.8f, 1.4f, 0.8f, 0.0f, -0.8f, -1.6f, -2.2f, -2.6f, -2.5f,
                -2.1f, -1.5f, -0.9f, -0.4f, 0.0f, 0.2f, 0.4f, 0.6f, 0.9f, 1.4f,
                2.2f, 3.0f, 3.2f, 2.2f, 0.5f, -1.8f, -2.2f, -0.5f, 0.7f, 0.9f, 0.3f,
            ),
            brand = "Soundcore",
            category = CatalogCategory.Q30,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_soundcore_q30_transparency",
            name = "Soundcore Life Q30 (Transparency · Harman)",
            gains31Db = listOf(
                2.4f, 2.1f, 1.6f, 0.9f, 0.1f, -0.7f, -1.4f, -1.9f, -2.1f, -1.9f,
                -1.5f, -1.0f, -0.5f, -0.1f, 0.2f, 0.4f, 0.5f, 0.5f, 0.7f, 1.1f,
                1.6f, 2.1f, 2.0f, 1.2f, -0.2f, -2.2f, -2.6f, -0.9f, 0.4f, 0.6f, 0.2f,
            ),
            brand = "Soundcore",
            category = CatalogCategory.Q30,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_soundcore_q30_warm_vocal",
            name = "Soundcore Life Q30 (Warm Vocal · Zero-Fatigue)",
            gains31Db = listOf(
                0.6f, 0.4f, 0.0f, -0.6f, -1.4f, -2.3f, -3.2f, -3.8f, -4.2f, -3.7f,
                -2.8f, -1.9f, -0.9f, -0.3f, 0.2f, 0.5f, 0.7f, 0.9f, 1.2f, 1.6f,
                2.1f, 2.5f, 2.4f, 1.3f, -0.5f, -2.8f, -3.2f, -1.6f, -0.8f, -0.6f, -0.8f,
            ),
            brand = "Soundcore",
            category = CatalogCategory.Q30,
        ),
        // Soundcore Additional Models
        CatalogHeadphoneEntry(
            catalogId = "catalog_soundcore_q45",
            name = "Soundcore Space Q45 (Harman)",
            gains31Db = listOf(
                -1.0f, -1.5f, -2.0f, -2.5f, -3.0f, -3.2f, -3.2f, -3.0f, -2.5f, -1.8f,
                -1.0f, -0.4f, 0.2f, 0.5f, 0.6f, 0.6f, 0.5f, 0.4f, 0.8f, 1.4f,
                2.2f, 3.0f, 3.2f, 2.2f, 0.4f, -1.5f, -3.2f, -2.8f, -1.0f, 0.5f, 0.0f,
            ),
            brand = "Soundcore",
            category = CatalogCategory.OVER_EAR,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_soundcore_space_one",
            name = "Soundcore Space One (Harman)",
            gains31Db = listOf(
                -0.6f, -1.1f, -1.7f, -2.4f, -3.1f, -3.8f, -4.3f, -4.6f, -4.5f, -4.0f,
                -3.2f, -2.3f, -1.4f, -0.6f, 0.0f, 0.4f, 0.7f, 1.0f, 1.3f, 1.8f,
                2.4f, 2.9f, 3.1f, 2.4f, 0.9f, -1.2f, -2.0f, -0.8f, 0.5f, 0.8f, 0.3f,
            ),
            brand = "Soundcore",
            category = CatalogCategory.OVER_EAR,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_soundcore_q35",
            name = "Soundcore Life Q35 (Harman)",
            gains31Db = listOf(
                -0.4f, -0.9f, -1.5f, -2.3f, -3.1f, -3.9f, -4.6f, -5.0f, -4.9f, -4.3f,
                -3.5f, -2.6f, -1.6f, -0.8f, -0.2f, 0.2f, 0.5f, 0.8f, 1.1f, 1.6f,
                2.3f, 3.0f, 3.3f, 2.5f, 0.7f, -1.6f, -2.3f, -0.6f, 0.6f, 0.8f, 0.3f,
            ),
            brand = "Soundcore",
            category = CatalogCategory.OVER_EAR,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_soundcore_q20",
            name = "Soundcore Life Q20 (Harman)",
            gains31Db = listOf(
                -0.8f, -1.4f, -2.0f, -2.8f, -3.6f, -4.4f, -5.0f, -5.2f, -4.8f, -4.0f,
                -3.0f, -2.0f, -1.0f, -0.3f, 0.2f, 0.6f, 0.8f, 1.0f, 1.4f, 2.0f,
                2.6f, 3.2f, 3.0f, 2.0f, 0.4f, -1.4f, -1.8f, -0.4f, 0.8f, 1.2f, 0.5f,
            ),
            brand = "Soundcore",
            category = CatalogCategory.OVER_EAR,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_soundcore_liberty_4_nc",
            name = "Soundcore Liberty 4 NC (Harman)",
            gains31Db = listOf(
                -0.5f, -1.0f, -1.5f, -2.1f, -2.6f, -3.0f, -3.2f, -3.0f, -2.5f, -1.9f,
                -1.2f, -0.5f, 0.1f, 0.5f, 0.7f, 0.8f, 0.6f, 0.4f, 0.7f, 1.2f,
                1.7f, 2.0f, 1.8f, 0.9f, -0.6f, -2.1f, -2.5f, -1.1f, 0.3f, 0.6f, 0.2f,
            ),
            brand = "Soundcore",
            category = CatalogCategory.TWS,
        ),
        // Sony
        CatalogHeadphoneEntry(
            catalogId = "catalog_sony_xm3",
            name = "Sony WH-1000XM3 (Harman)",
            gains31Db = listOf(
                0.2f, -0.4f, -1.1f, -2.0f, -2.9f, -3.8f, -4.5f, -5.0f, -5.1f, -4.6f,
                -3.8f, -2.8f, -1.7f, -0.8f, -0.2f, 0.3f, 0.7f, 1.0f, 1.3f, 1.7f,
                2.2f, 2.7f, 3.1f, 2.6f, 1.2f, -0.5f, -1.2f, -1.4f, -0.2f, 0.9f, 0.4f,
            ),
            brand = "Sony",
            category = CatalogCategory.OVER_EAR,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_sony_xm4",
            name = "Sony WH-1000XM4 (Harman)",
            gains31Db = listOf(
                0.5f, 0.0f, -0.6f, -1.4f, -2.2f, -3.2f, -4.0f, -4.6f, -4.8f, -4.4f,
                -3.6f, -2.6f, -1.6f, -0.8f, -0.2f, 0.3f, 0.7f, 1.0f, 1.4f, 1.8f,
                2.2f, 2.8f, 3.2f, 2.6f, 1.2f, -0.6f, -1.4f, -1.8f, -0.5f, 1.0f, 0.5f,
            ),
            brand = "Sony",
            category = CatalogCategory.OVER_EAR,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_sony_xm5",
            name = "Sony WH-1000XM5 (Harman)",
            gains31Db = listOf(
                1.0f, 0.5f, 0.0f, -0.6f, -1.2f, -1.8f, -2.4f, -2.8f, -3.0f, -2.8f,
                -2.4f, -1.8f, -1.0f, -0.3f, 0.2f, 0.5f, 0.6f, 0.5f, 0.8f, 1.2f,
                1.6f, 2.0f, 2.2f, 2.8f, 2.2f, 0.2f, -1.2f, 0.0f, 1.2f, 1.5f, 0.5f,
            ),
            brand = "Sony",
            category = CatalogCategory.OVER_EAR,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_sony_wf_xm4",
            name = "Sony WF-1000XM4 (Harman)",
            gains31Db = listOf(
                1.4f, 1.1f, 0.8f, 0.4f, 0.0f, -0.4f, -0.8f, -1.0f, -1.0f, -0.8f,
                -0.5f, -0.2f, 0.1f, 0.3f, 0.4f, 0.3f, 0.1f, -0.2f, 0.1f, 0.6f,
                1.2f, 1.8f, 2.4f, 2.6f, 2.0f, 1.2f, 0.8f, 1.4f, 2.0f, 1.8f, 0.8f,
            ),
            brand = "Sony",
            category = CatalogCategory.TWS,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_sony_wf_xm5",
            name = "Sony WF-1000XM5 (Harman)",
            gains31Db = listOf(
                0.8f, 0.6f, 0.3f, 0.0f, -0.4f, -0.7f, -1.0f, -1.1f, -1.0f, -0.8f,
                -0.5f, -0.2f, 0.1f, 0.3f, 0.4f, 0.4f, 0.3f, 0.2f, 0.4f, 0.8f,
                1.2f, 1.6f, 1.9f, 1.6f, 0.8f, 0.0f, -0.4f, 0.4f, 1.1f, 1.2f, 0.5f,
            ),
            brand = "Sony",
            category = CatalogCategory.TWS,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_sony_ult_wear",
            name = "Sony ULT Wear (Harman)",
            gains31Db = listOf(
                -2.0f, -2.8f, -3.6f, -4.5f, -5.4f, -6.2f, -6.8f, -7.0f, -6.4f, -5.2f,
                -3.8f, -2.5f, -1.3f, -0.4f, 0.2f, 0.6f, 0.9f, 1.2f, 1.6f, 2.1f,
                2.7f, 3.2f, 3.4f, 2.8f, 1.4f, -0.2f, -0.8f, 0.4f, 1.2f, 1.4f, 0.6f,
            ),
            brand = "Sony",
            category = CatalogCategory.OVER_EAR,
        ),
        // Apple
        CatalogHeadphoneEntry(
            catalogId = "catalog_airpods_pro_2",
            name = "Apple AirPods Pro 2 (Harman)",
            gains31Db = listOf(
                1.2f, 1.0f, 0.8f, 0.5f, 0.2f, 0.0f, -0.2f, -0.4f, -0.6f, -0.8f,
                -1.0f, -0.8f, -0.4f, 0.0f, 0.2f, 0.3f, 0.2f, 0.0f, 0.3f, 0.6f,
                0.8f, 1.0f, 1.2f, 0.8f, 0.2f, -0.5f, -0.4f, 0.6f, 1.2f, 1.0f, 0.4f,
            ),
            brand = "Apple",
            category = CatalogCategory.TWS,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_apple_airpods_max",
            name = "Apple AirPods Max (Harman)",
            gains31Db = listOf(
                0.6f, 0.4f, 0.2f, 0.0f, -0.3f, -0.6f, -0.8f, -0.9f, -0.8f, -0.6f,
                -0.4f, -0.2f, 0.0f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.9f, 1.4f,
                2.0f, 2.8f, 3.4f, 3.0f, 1.6f, -0.6f, -1.2f, 0.2f, 1.0f, 1.1f, 0.4f,
            ),
            brand = "Apple",
            category = CatalogCategory.OVER_EAR,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_apple_airpods_3",
            name = "Apple AirPods 3 (Harman)",
            gains31Db = listOf(
                5.4f, 4.8f, 4.1f, 3.2f, 2.2f, 1.2f, 0.3f, -0.4f, -0.8f, -1.0f,
                -0.9f, -0.6f, -0.3f, 0.0f, 0.2f, 0.3f, 0.3f, 0.2f, 0.4f, 0.7f,
                1.0f, 1.2f, 1.0f, 0.4f, -0.6f, -1.4f, -1.2f, 0.0f, 0.8f, 1.0f, 0.4f,
            ),
            brand = "Apple",
            category = CatalogCategory.TWS,
        ),
        // Samsung
        CatalogHeadphoneEntry(
            catalogId = "catalog_galaxy_buds2_pro",
            name = "Samsung Galaxy Buds2 Pro (Harman)",
            gains31Db = listOf(
                0.8f, 0.6f, 0.4f, 0.2f, 0.0f, -0.2f, -0.4f, -0.6f, -0.8f, -0.8f,
                -0.6f, -0.4f, 0.0f, 0.2f, 0.3f, 0.2f, 0.0f, -0.2f, 0.0f, 0.4f,
                0.8f, 0.6f, 0.2f, -0.4f, -1.4f, -2.2f, -2.4f, -0.8f, 0.5f, 0.8f, 0.3f,
            ),
            brand = "Samsung",
            category = CatalogCategory.TWS,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_samsung_buds3_pro",
            name = "Samsung Galaxy Buds3 Pro (Harman)",
            gains31Db = listOf(
                0.4f, 0.2f, 0.0f, -0.2f, -0.5f, -0.7f, -0.8f, -0.9f, -0.8f, -0.6f,
                -0.4f, -0.1f, 0.1f, 0.3f, 0.4f, 0.3f, 0.2f, 0.0f, 0.2f, 0.6f,
                0.9f, 0.8f, 0.3f, -0.5f, -1.5f, -2.0f, -1.8f, -0.4f, 0.6f, 0.8f, 0.3f,
            ),
            brand = "Samsung",
            category = CatalogCategory.TWS,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_samsung_buds_fe",
            name = "Samsung Galaxy Buds FE (Harman)",
            gains31Db = listOf(
                0.2f, 0.0f, -0.2f, -0.5f, -0.8f, -1.0f, -1.1f, -1.1f, -0.9f, -0.6f,
                -0.3f, 0.0f, 0.2f, 0.3f, 0.4f, 0.3f, 0.1f, 0.0f, 0.2f, 0.6f,
                1.0f, 1.2f, 0.9f, 0.3f, -0.6f, -1.3f, -1.5f, -0.5f, 0.4f, 0.7f, 0.3f,
            ),
            brand = "Samsung",
            category = CatalogCategory.TWS,
        ),
        // Sennheiser
        CatalogHeadphoneEntry(
            catalogId = "catalog_sennheiser_hd600",
            name = "Sennheiser HD 600 (Harman)",
            gains31Db = listOf(
                6.0f, 5.6f, 5.0f, 4.2f, 3.2f, 2.2f, 1.2f, 0.4f, -0.2f, -0.5f,
                -0.6f, -0.5f, -0.2f, 0.0f, 0.2f, 0.3f, 0.2f, 0.0f, -0.3f, -0.8f,
                -1.2f, -1.8f, -2.6f, -2.0f, -0.6f, 0.6f, 1.0f, 1.2f, 1.6f, 1.8f, 1.0f,
            ),
            brand = "Sennheiser",
            category = CatalogCategory.STUDIO,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_sennheiser_hd650",
            name = "Sennheiser HD 650 (Harman)",
            gains31Db = listOf(
                6.2f, 5.7f, 5.0f, 4.0f, 2.9f, 1.8f, 0.8f, -0.1f, -0.8f, -1.1f,
                -1.0f, -0.7f, -0.3f, 0.0f, 0.2f, 0.3f, 0.2f, 0.0f, -0.2f, -0.5f,
                -0.8f, -1.2f, -1.6f, -0.9f, 0.4f, 1.5f, 1.9f, 1.8f, 1.9f, 2.0f, 1.1f,
            ),
            brand = "Sennheiser",
            category = CatalogCategory.STUDIO,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_sennheiser_hd560s",
            name = "Sennheiser HD 560S (Harman)",
            gains31Db = listOf(
                4.8f, 4.4f, 3.8f, 3.0f, 2.1f, 1.3f, 0.6f, 0.1f, -0.2f, -0.4f,
                -0.4f, -0.3f, -0.1f, 0.1f, 0.2f, 0.3f, 0.2f, 0.0f, -0.2f, -0.5f,
                -0.7f, -0.8f, -1.0f, -1.4f, -1.8f, -1.4f, -0.4f, 0.4f, 0.8f, 0.9f, 0.4f,
            ),
            brand = "Sennheiser",
            category = CatalogCategory.STUDIO,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_sennheiser_momentum_4",
            name = "Sennheiser Momentum 4 (Harman)",
            gains31Db = listOf(
                -0.8f, -1.3f, -1.9f, -2.5f, -3.1f, -3.6f, -3.8f, -3.7f, -3.2f, -2.5f,
                -1.7f, -1.0f, -0.4f, 0.1f, 0.4f, 0.5f, 0.5f, 0.4f, 0.6f, 1.0f,
                1.5f, 2.0f, 2.2f, 1.7f, 0.6f, -0.8f, -1.4f, -0.4f, 0.6f, 0.9f, 0.4f,
            ),
            brand = "Sennheiser",
            category = CatalogCategory.OVER_EAR,
        ),
        // Beyerdynamic
        CatalogHeadphoneEntry(
            catalogId = "catalog_beyerdynamic_dt770_80",
            name = "Beyerdynamic DT 770 Pro (80 Ohm · Harman)",
            gains31Db = listOf(
                1.8f, 1.4f, 0.9f, 0.3f, -0.3f, -0.8f, -1.2f, -1.4f, -1.2f, -0.6f,
                0.4f, 1.2f, 1.5f, 1.1f, 0.6f, 0.3f, 0.1f, 0.0f, 0.3f, 0.8f,
                1.4f, 2.0f, 2.4f, 2.2f, 0.2f, -3.2f, -4.6f, -2.8f, -0.6f, 0.4f, 0.0f,
            ),
            brand = "Beyerdynamic",
            category = CatalogCategory.STUDIO,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_beyerdynamic_dt990",
            name = "Beyerdynamic DT 990 Pro (Harman)",
            gains31Db = listOf(
                5.4f, 4.8f, 4.0f, 3.0f, 1.8f, 0.6f, -0.5f, -1.4f, -1.8f, -1.6f,
                -1.1f, -0.5f, 0.0f, 0.4f, 0.6f, 0.7f, 0.5f, 0.2f, 0.4f, 0.9f,
                1.5f, 2.1f, 2.3f, 1.6f, -1.2f, -4.8f, -6.0f, -3.6f, -1.0f, 0.2f, -0.4f,
            ),
            brand = "Beyerdynamic",
            category = CatalogCategory.STUDIO,
        ),
        // Bose
        CatalogHeadphoneEntry(
            catalogId = "catalog_bose_qc45",
            name = "Bose QuietComfort 45 (Harman)",
            gains31Db = listOf(
                0.8f, 0.5f, 0.2f, -0.2f, -0.6f, -1.0f, -1.3f, -1.4f, -1.2f, -0.9f,
                -0.5f, -0.2f, 0.1f, 0.3f, 0.4f, 0.4f, 0.3f, 0.2f, 0.5f, 0.9f,
                1.4f, 1.8f, 1.6f, 0.8f, -0.8f, -2.4f, -3.2f, -2.0f, -0.4f, 0.6f, 0.2f,
            ),
            brand = "Bose",
            category = CatalogCategory.OVER_EAR,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_bose_qc_ultra",
            name = "Bose QuietComfort Ultra (Harman)",
            gains31Db = listOf(
                0.2f, -0.2f, -0.7f, -1.3f, -1.9f, -2.4f, -2.7f, -2.8f, -2.5f, -1.9f,
                -1.3f, -0.7f, -0.2f, 0.2f, 0.4f, 0.5f, 0.5f, 0.4f, 0.7f, 1.1f,
                1.6f, 2.0f, 2.1f, 1.5f, 0.4f, -0.9f, -1.4f, -0.5f, 0.5f, 0.8f, 0.3f,
            ),
            brand = "Bose",
            category = CatalogCategory.OVER_EAR,
        ),
        // JBL
        CatalogHeadphoneEntry(
            catalogId = "catalog_jbl_tune_720bt",
            name = "JBL Tune 720BT (Harman)",
            gains31Db = listOf(
                0.6f, 0.4f, 0.1f, -0.2f, -0.6f, -0.9f, -1.1f, -1.1f, -0.9f, -0.6f,
                -0.3f, 0.0f, 0.2f, 0.3f, 0.4f, 0.4f, 0.3f, 0.2f, 0.5f, 0.9f,
                1.3f, 1.6f, 1.5f, 1.0f, 0.2f, -0.8f, -1.1f, -0.3f, 0.5f, 0.7f, 0.2f,
            ),
            brand = "JBL",
            category = CatalogCategory.OVER_EAR,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_jbl_live_660nc",
            name = "JBL Live 660NC (Harman)",
            gains31Db = listOf(
                0.4f, 0.1f, -0.3f, -0.7f, -1.2f, -1.6f, -1.9f, -2.0f, -1.8f, -1.4f,
                -0.9f, -0.4f, 0.0f, 0.3f, 0.5f, 0.5f, 0.4f, 0.3f, 0.6f, 1.0f,
                1.5f, 1.9f, 1.8f, 1.1f, 0.1f, -1.0f, -1.4f, -0.4f, 0.5f, 0.8f, 0.3f,
            ),
            brand = "JBL",
            category = CatalogCategory.OVER_EAR,
        ),
        // Audio-Technica
        CatalogHeadphoneEntry(
            catalogId = "catalog_ath_m50x",
            name = "Audio-Technica ATH-M50x (Harman)",
            gains31Db = listOf(
                1.2f, 0.8f, 0.3f, -0.3f, -0.9f, -1.5f, -2.0f, -2.3f, -2.2f, -1.8f,
                -1.2f, -0.5f, 0.2f, 0.7f, 0.9f, 0.8f, 0.6f, 0.3f, 0.5f, 0.9f,
                1.2f, 1.4f, 1.0f, -0.4f, -2.2f, -2.6f, -1.8f, -0.6f, 0.4f, 0.8f, 0.3f,
            ),
            brand = "Audio-Technica",
            category = CatalogCategory.STUDIO,
        ),
        // Moondrop
        CatalogHeadphoneEntry(
            catalogId = "catalog_moondrop_chu_2",
            name = "Moondrop Chu II (Harman)",
            gains31Db = listOf(
                0.8f, 0.6f, 0.4f, 0.1f, -0.2f, -0.5f, -0.7f, -0.7f, -0.5f, -0.3f,
                -0.1f, 0.1f, 0.2f, 0.3f, 0.3f, 0.2f, 0.0f, -0.1f, 0.1f, 0.5f,
                0.9f, 1.1f, 0.8f, 0.2f, -0.8f, -1.4f, -1.2f, -0.2f, 0.6f, 0.9f, 0.4f,
            ),
            brand = "Moondrop",
            category = CatalogCategory.IEM,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_moondrop_aria_2",
            name = "Moondrop Aria 2 (Harman)",
            gains31Db = listOf(
                1.0f, 0.8f, 0.5f, 0.2f, -0.1f, -0.4f, -0.6f, -0.6f, -0.4f, -0.2f,
                0.0f, 0.1f, 0.2f, 0.3f, 0.3f, 0.2f, 0.1f, 0.0f, 0.2f, 0.5f,
                0.8f, 1.0f, 0.8f, 0.4f, -0.4f, -1.0f, -0.8f, 0.0f, 0.7f, 1.0f, 0.5f,
            ),
            brand = "Moondrop",
            category = CatalogCategory.IEM,
        ),
        // Truthear
        CatalogHeadphoneEntry(
            catalogId = "catalog_truthear_zero_red",
            name = "Truthear Zero:Red (Harman)",
            gains31Db = listOf(
                1.4f, 1.2f, 0.9f, 0.5f, 0.1f, -0.2f, -0.4f, -0.5f, -0.4f, -0.2f,
                0.0f, 0.1f, 0.2f, 0.3f, 0.3f, 0.2f, 0.1f, 0.0f, 0.3f, 0.7f,
                1.1f, 1.4f, 1.3f, 0.8f, 0.0f, -0.6f, -0.8f, 0.0f, 0.6f, 0.9f, 0.4f,
            ),
            brand = "Truthear",
            category = CatalogCategory.IEM,
        ),
        CatalogHeadphoneEntry(
            catalogId = "catalog_truthear_hexa",
            name = "Truthear Hexa (Harman)",
            gains31Db = listOf(
                2.6f, 2.3f, 1.9f, 1.4f, 0.9f, 0.4f, 0.0f, -0.2f, -0.3f, -0.2f,
                -0.1f, 0.0f, 0.1f, 0.2f, 0.2f, 0.1f, 0.0f, -0.1f, 0.1f, 0.4f,
                0.7f, 0.9f, 0.7f, 0.2f, -0.6f, -1.2f, -1.4f, -0.4f, 0.5f, 0.8f, 0.3f,
            ),
            brand = "Truthear",
            category = CatalogCategory.IEM,
        ),
    )

    fun filter(
        query: String,
        category: CatalogCategory? = null,
    ): List<CatalogHeadphoneEntry> {
        val needle = query.trim().lowercase(Locale.ROOT)
        return entries.filter { entry ->
            val categoryMatch = category == null || entry.category == category
            val queryMatch = needle.isEmpty() ||
                entry.name.lowercase(Locale.ROOT).contains(needle) ||
                entry.brand.lowercase(Locale.ROOT).contains(needle)
            categoryMatch && queryMatch
        }
    }

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
