package com.owlacoustics.domain.catalog

import kotlinx.serialization.Serializable

@Serializable
enum class ProductCategory(val label: String, val behaviour: String) {
    ABSORBER("Absorber panel", "absorptive foam panel"),
    BASS_TRAP("Corner trap", "absorptive foam corner unit"),
    CLOUD("Ceiling cloud", "hanging absorptive foam cloud"),
    DIFFUSER("Reflective diffuser", "reflective scattering surface — not an absorber"),
}

/** Catalog geometry with its source and confirmation state (C-27/C-28). */
@Serializable
data class ProductGeometry(
    val faceWidthM: Double,
    val faceHeightM: Double,
    val depthM: Double,
    val source: String,
    val confirmation: String,
)

/** Frequency-dependent performance; only present for validated product-specific data (B-02). */
@Serializable
data class PerformanceData(
    val dataset: String,
    val bandsHz: List<Double>,
    val values: List<Double>,
    val quantity: String,
    val mounting: String,
    val status: String,
    val source: String,
)

@Serializable
data class Product(
    val id: String,
    val version: Int,
    val name: String,
    val category: ProductCategory,
    val geometry: ProductGeometry,
    val material: String,
    val mounting: String,
    val performance: PerformanceData? = null,
    /** Retail price is separate configuration (B-03); null means not configured. */
    val price: Money? = null,
)

@Serializable
data class Money(val amountMinor: Long, val currency: String)

private const val IN = 0.0254
private const val FT = 0.3048

/**
 * Initial treatment catalog geometry from the brief and the supplied diffuser drawing. OWL-facing IDs only;
 * no supplier identities. No validated performance data or retail prices are configured.
 */
object Catalog {
    val products: List<Product> = listOf(
        Product("owl-wedge-2in", 1, "2-inch wedge absorber, 2 × 4 ft", ProductCategory.ABSORBER,
            ProductGeometry(2 * FT, 4 * FT, 2 * IN, "Brief section 10 nominal size", "brief-declared nominal"),
            "OWL ester polyurethane foam, nominal 1.8–2.2 lb/ft³", "Surface mount (no air gap stated)"),
        Product("owl-trap-corner", 1, "Foam corner trap, 2 × 1 × 1 ft class", ProductCategory.BASS_TRAP,
            ProductGeometry(1 * FT, 2 * FT, 1 * FT, "Brief section 10 nominal class", "brief-declared nominal"),
            "OWL ester polyurethane foam, nominal 1.8–2.2 lb/ft³", "Wall/wall corner"),
        Product("owl-flat-3in", 1, "3-inch flat absorber, 2 × 4 ft", ProductCategory.ABSORBER,
            ProductGeometry(2 * FT, 4 * FT, 3 * IN, "Brief section 10 nominal size", "brief-declared nominal"),
            "OWL ester polyurethane foam, nominal 1.8–2.2 lb/ft³", "Surface mount; any air gap must be stated explicitly"),
        Product("owl-cloud-wave", 1, "Hanging wavy foam ceiling cloud, 2 × 4 ft", ProductCategory.CLOUD,
            ProductGeometry(2 * FT, 4 * FT, 2 * IN, "Brief section 10 nominal size (thickness not specified; 2 in shown for drawing only)", "brief-declared nominal; thickness unconfirmed"),
            "OWL ester polyurethane foam, nominal 1.8–2.2 lb/ft³", "Suspended below ceiling; hang height not specified"),
        Product("owl-diffuser-ab", 1, "OWL A/B reflective diffuser set (1 A + 1 B)", ProductCategory.DIFFUSER,
            ProductGeometry(16.435 * IN, 23.976 * IN, 5.047 * IN, "OWL_AB_Diffuser_Drawing.pdf labels (per panel face; max shown depth)", "drawing-declared design geometry; not independently measured"),
            "EPS, nominal 3.0 lb/ft³ (drawing label)", "Wall mount; per-step profile data not specified"),
    )

    fun byId(id: String) = products.firstOrNull { it.id == id }

    val hasValidatedPerformance: Boolean get() = products.any { it.performance != null }
}
