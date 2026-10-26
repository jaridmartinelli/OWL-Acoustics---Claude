package com.owlacoustics.domain.recommend

import com.owlacoustics.domain.acoustics.PathStatus
import com.owlacoustics.domain.acoustics.ReflectionPath
import com.owlacoustics.domain.acoustics.ReflectionReport
import com.owlacoustics.domain.catalog.Catalog
import com.owlacoustics.domain.catalog.Product
import com.owlacoustics.domain.catalog.ProductCategory
import com.owlacoustics.domain.ceiling.CeilingGeometry
import com.owlacoustics.domain.ceiling.CeilingSupport
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.TreatmentMode
import com.owlacoustics.domain.placement.PlacementOptimizer
import kotlinx.serialization.Serializable

@Serializable
enum class ActionKind { KEEP_AS_IS, MOVE_SETUP, TREATMENT_LOCATION, INFO }

@Serializable
data class Action(
    val kind: ActionKind,
    val title: String,
    val reason: String,
    val where: String,
    val expectedBenefit: String,
    val uncertainty: String,
    val productOptions: List<String> = emptyList(),
    val locationKey: String = "",
)

@Serializable
data class RecommendationSet(
    val mode: TreatmentMode,
    val actions: List<Action>,
    val summary: String,
    val purchaseCount: Int,
    val costNote: String,
    val limits: List<String>,
)

/**
 * Observation → prediction → free optimisation → justified recommendation (C-20). Pure function of current inputs;
 * every constraint change re-runs it from scratch (never subtracts from an earlier plan).
 */
object Recommender {
    private const val BENEFIT_UNKNOWN = "Benefit not quantified — no validated performance data for this product (B-02)."

    fun recommend(m: RoomModel, report: ReflectionReport, placement: PlacementOptimizer.Result?, catalog: List<Product> = Catalog.products): RecommendationSet {
        val prefs = m.treatment
        val actions = ArrayList<Action>()
        val limits = ArrayList<String>()
        limits += "Recommendations use geometric reflection timing only; they do not predict loudness, decay time or frequency response."
        if (prefs.existing.isNotEmpty()) limits += "Existing treatment is recorded as present; it receives no acoustic credit from appearance."
        // 1. free placement first
        val best = placement?.proposals?.firstOrNull()
        if (placement != null && best != null && !placement.currentIsNonDominated) {
            actions += Action(ActionKind.MOVE_SETUP, "Move your setup (free)", best.reasons.joinToString(" "),
                "See the placement proposal", "Later first reflections relative to the direct sound under the stated geometric criteria.",
                "Geometric criteria only; heights and furniture not considered unless entered.")
        } else if (placement?.currentIsNonDominated == true) {
            actions += Action(ActionKind.KEEP_AS_IS, "Your current placement is already among the best found", "No alternative improves reflection timing without worsening symmetry.",
                "Current setup", "None needed", "Within the geometric criteria only.")
        }
        // 2. remaining early reflections as geometric locations
        val valid = report.paths.filter { it.status == PathStatus.VALID && it.surfaceRef != "floor" }
            .sortedBy { it.afterDirectMs }
        val locations = cluster(valid)
        val allowed = catalog.filter { it.id !in prefs.excludedProductIds }
        val candidates = locations.filter { it.key !in prefs.excludedLocations }
        val chosen = when (prefs.mode) {
            TreatmentMode.BUY_NOTHING -> emptyList()
            TreatmentMode.ITEM_LIMIT -> candidates.take(prefs.maxItems.coerceAtLeast(0))
            TreatmentMode.FULL -> candidates
        }
        var purchases = 0
        for (loc in candidates) {
            val p = loc.paths.first()
            val fits = fittingProducts(m, p, allowed)
            val selected = loc in chosen
            if (selected && fits.isNotEmpty()) purchases++
            actions += Action(
                kind = if (selected) ActionKind.TREATMENT_LOCATION else ActionKind.INFO,
                title = (if (selected) "Treatment location: " else "Reflection point: ") + p.surface,
                reason = "Sound from ${loc.paths.joinToString { it.sourceId }} reflects here and reaches the receiver %.1f ms after the direct sound.".format(p.afterDirectMs),
                where = "%s at x %.2f m, y %.2f m%s".format(p.surface, p.point.x, p.point.y, p.point.z?.let { ", height %.2f m".format(it) } ?: ""),
                expectedBenefit = if (selected) BENEFIT_UNKNOWN else "Information only in this mode.",
                uncertainty = if (p.planOnly) "Heights unknown; point is a plan position." else "Point depends on entered positions and heights.",
                productOptions = if (selected) fits.map { it.name } else emptyList(),
                locationKey = loc.key,
            )
        }
        if (prefs.mode == TreatmentMode.FULL && allowed.any { it.category == ProductCategory.BASS_TRAP }) {
            actions += Action(ActionKind.INFO, "Corner low-frequency treatment (optional)",
                "Corners are where wall boundaries meet; corner units are commonly placed there.",
                "Room corners", BENEFIT_UNKNOWN, "Low-frequency effect depends on room modes and validated product data, neither of which supports a quantity here.")
        }
        val summary = when {
            prefs.mode == TreatmentMode.BUY_NOTHING -> "Buy nothing: " + (if (actions.any { it.kind == ActionKind.MOVE_SETUP }) "start with the free placement change." else "no purchase is needed for this plan.") +
                " ${candidates.size} reflection point(s) are listed for information."
            purchases == 0 -> "No product fits the selected locations with the current exclusions. Buying nothing is still a valid result."
            else -> "$purchases location(s) selected for optional treatment, earliest reflections first."
        }
        val costNote = if (allowed.all { it.price == null }) "Prices are not configured (B-03); no cost or budget total is shown." else "Prices from configured catalog."
        if (prefs.mode == TreatmentMode.ITEM_LIMIT) limits += "Budget mode limits the number of items; a money budget needs configured prices."
        return RecommendationSet(prefs.mode, actions, summary, purchases, costNote, limits)
    }

    data class Location(val key: String, val paths: List<ReflectionPath>)

    /** Groups paths whose reflection points lie within 0.3 m on the same surface (one panel can cover them). */
    fun cluster(paths: List<ReflectionPath>): List<Location> {
        val out = ArrayList<MutableList<ReflectionPath>>()
        for (p in paths) {
            val g = out.firstOrNull { g -> g.first().surfaceRef == p.surfaceRef && dist(g.first(), p) < 0.3 }
            if (g != null) g += p else out += mutableListOf(p)
        }
        return out.map { g -> Location("${g.first().surfaceRef}@%.1f,%.1f".format(g.first().point.x, g.first().point.y), g) }
    }

    private fun dist(a: ReflectionPath, b: ReflectionPath): Double {
        val dz = (a.point.z ?: 0.0) - (b.point.z ?: 0.0)
        return kotlin.math.sqrt((a.point.x - b.point.x).let { it * it } + (a.point.y - b.point.y).let { it * it } + dz * dz)
    }

    /** Physical fit only: the product face centred on the point stays on the surface and clear of openings. */
    fun fittingProducts(m: RoomModel, p: ReflectionPath, products: List<Product>): List<Product> {
        val wall = m.wall(p.surfaceRef)
        return products.filter { prod ->
            when {
                wall != null -> {
                    if (prod.category == ProductCategory.CLOUD || prod.category == ProductCategory.BASS_TRAP) return@filter false
                    val along = (Vec2(p.point.x, p.point.y) - wall.a) dot wall.dir
                    // panels may be mounted either way round; use the narrower face dimension horizontally
                    val half = minOf(prod.geometry.faceWidthM, prod.geometry.faceHeightM) / 2
                    val tall = maxOf(prod.geometry.faceWidthM, prod.geometry.faceHeightM)
                    if (along - half < 0 || along + half > wall.length) return@filter false
                    if (m.openingsOn(wall.id).any { along + half > it.offset && along - half < it.offset + it.width }) return@filter false
                    val z = p.point.z ?: return@filter true
                    val support = CeilingGeometry.support(m.outline, m.ceiling) as? CeilingSupport.Defined
                    val top = support?.heightAt(Vec2(p.point.x, p.point.y))
                    z - tall / 2 >= 0 && (top == null || z + tall / 2 <= top)
                }
                p.surfaceRef.startsWith("ceiling") -> prod.category == ProductCategory.CLOUD || prod.category == ProductCategory.ABSORBER
                else -> false
            }
        }
    }
}
