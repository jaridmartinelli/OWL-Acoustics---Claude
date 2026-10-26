package com.owlacoustics.domain.acoustics

import com.owlacoustics.domain.ceiling.CeilingGeometry
import com.owlacoustics.domain.ceiling.CeilingPlane
import com.owlacoustics.domain.ceiling.CeilingSupport
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import com.owlacoustics.domain.model.CeilingProfile
import com.owlacoustics.domain.model.Role
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.SetupItem
import com.owlacoustics.domain.model.Validation
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** Documented assumption: speed of sound in dry air at about 20 °C (C-19). */
const val SPEED_OF_SOUND = 343.0

@Serializable
enum class PathStatus(val label: String) {
    VALID("valid"),
    APERTURE("hits a door/window opening — not treated as solid wall"),
    APERTURE_UNCERTAIN("may hit an opening whose height is unknown — excluded"),
    OCCLUDED("blocked by the room's own walls or ceiling"),
    DETAIL_REGION("hits a ceiling detail that is not modeled — excluded"),
    ABOVE_WALL("point is above the wall top — excluded"),
}

@Serializable
data class P3(val x: Double, val y: Double, val z: Double?)

@Serializable
data class ReflectionPath(
    val sourceId: String,
    val receiverId: String,
    val surface: String,
    val surfaceRef: String,
    val point: P3,
    val lengthM: Double,
    val delayMs: Double,
    val afterDirectMs: Double,
    val status: PathStatus,
    val planOnly: Boolean,
    val note: String = "",
)

@Serializable
data class DirectPath(val sourceId: String, val receiverId: String, val lengthM: Double, val delayMs: Double, val planOnly: Boolean, val occluded: Boolean)

@Serializable
data class SbirEstimate(
    val sourceId: String, val receiverId: String, val surface: String, val surfaceRef: String,
    val boundaryDistanceM: Double, val pathDifferenceM: Double, val firstNullHz: Double,
)

@Serializable
data class RoomModeResult(val nx: Int, val ny: Int, val nz: Int, val hz: Double, val type: String)

@Serializable
data class ReflectionReport(
    val direct: List<DirectPath>,
    val paths: List<ReflectionPath>,
    val sbir: List<SbirEstimate>,
    val notes: List<String>,
    val speedOfSound: Double = SPEED_OF_SOUND,
)

@Serializable
enum class CapabilityStatus { SUPPORTED, PARTIAL, UNAVAILABLE }

@Serializable
data class Capability(val operation: String, val status: CapabilityStatus, val detail: String)

object Reflections {
    private const val SAMPLES = 64

    private fun ms(len: Double) = len / SPEED_OF_SOUND * 1000.0

    fun analyze(m: RoomModel, sourceFilter: ((SetupItem) -> Boolean)? = null): ReflectionReport {
        val outline = m.outline
        val notes = ArrayList<String>()
        val sources = m.setup.items.filter { it.kind.role == Role.SOURCE && it.position != null && (sourceFilter?.invoke(it) ?: true) }
        val receivers = m.setup.items.filter { it.kind.role == Role.RECEIVER && it.position != null }
        val support = CeilingGeometry.support(outline, m.ceiling)
        val defined = support as? CeilingSupport.Defined
        if (defined == null) notes += "Ceiling and floor reflections need a supported ceiling and item heights; wall paths are shown independently."
        if (defined?.curvedUnsupportedForReflections == true) notes += "Curved or sloped-transition ceiling surfaces are not used for reflections (no planar model)."
        if (m.ceiling.details.isNotEmpty()) notes += "Ceiling details are excluded from reflection surfaces; paths that land on them are not used."
        val direct = ArrayList<DirectPath>()
        val paths = ArrayList<ReflectionPath>()
        for (s in sources) for (r in receivers) {
            val sp = s.position!!; val rp = r.position!!
            val sz = s.height?.meters; val rz = r.height?.meters
            val heights = sz != null && rz != null
            val dLen = if (heights) Vec3(sp.x, sp.y, sz!!).distanceTo(Vec3(rp.x, rp.y, rz!!)) else sp.distanceTo(rp)
            val occl = !Polygons.segmentInside(outline, sp, rp)
            direct += DirectPath(s.id, r.id, dLen, ms(dLen), !heights, occl)
            // walls
            for (w in m.walls) {
                val n = w.outward
                val ds = (sp - w.a) dot n; val dr = (rp - w.a) dot n
                if (ds >= 0 || dr >= 0) continue
                val img = sp - n * (2 * ds)
                val dir = rp - img
                val den = dir dot n
                if (abs(den) < 1e-12) continue
                val t = ((w.a - img) dot n) / den
                if (t <= 0 || t >= 1) continue
                val p = img + dir * t
                val along = (p - w.a) dot w.dir
                if (along < -1e-9 || along > w.length + 1e-9) continue
                val z = if (heights) sz!! + (rz!! - sz) * t else null
                val len = if (heights) Vec3(img.x, img.y, sz!!).distanceTo(Vec3(rp.x, rp.y, rz!!)) else img.distanceTo(rp)
                var status = PathStatus.VALID
                var note = ""
                for (o in m.openingsOn(w.id)) {
                    if (along > o.offset && along < o.offset + o.width) {
                        val b = o.bottom?.meters; val h = o.height?.meters
                        status = if (b == null || h == null || z == null) PathStatus.APERTURE_UNCERTAIN
                        else if (z >= b && z <= b + h) PathStatus.APERTURE else status
                    }
                }
                if (status == PathStatus.VALID && (!Polygons.segmentInside(outline, sp, p) || !Polygons.segmentInside(outline, p, rp))) status = PathStatus.OCCLUDED
                if (status == PathStatus.VALID && defined != null && z != null) {
                    if (z > defined.heightAt(p) + 1e-9) status = PathStatus.ABOVE_WALL
                    else if (!underCeiling(defined, m, Vec3(sp.x, sp.y, sz!!), Vec3(p.x, p.y, z)) ||
                        !underCeiling(defined, m, Vec3(p.x, p.y, z), Vec3(rp.x, rp.y, rz!!))) status = PathStatus.OCCLUDED
                }
                if (status == PathStatus.VALID && defined == null && z != null) note = "Wall height at this point is not confirmed (ceiling not defined)."
                if (!heights) note = "Plan-only: item heights unknown, so length is the horizontal distance."
                paths += ReflectionPath(s.id, r.id, "Wall ${w.index + 1}", w.id, P3(p.x, p.y, z), len, ms(len), ms(len) - ms(dLen), status, !heights, note)
            }
            if (!heights) continue
            val S = Vec3(sp.x, sp.y, sz!!); val R = Vec3(rp.x, rp.y, rz!!)
            // floor
            run {
                val t = sz / (sz + rz)
                val img = Vec3(sp.x, sp.y, -sz)
                val p = img + (R - img) * t
                val pp = p.xy()
                if (Polygons.contains(outline, pp)) {
                    val len = img.distanceTo(R)
                    var status = PathStatus.VALID
                    if (!Polygons.segmentInside(outline, sp, pp) || !Polygons.segmentInside(outline, pp, rp)) status = PathStatus.OCCLUDED
                    paths += ReflectionPath(s.id, r.id, "Floor", "floor", P3(p.x, p.y, 0.0), len, ms(len), ms(len) - ms(dLen), status, false)
                }
            }
            // ceiling planes
            if (defined != null) for ((i, pl) in defined.planes.withIndex()) {
                val res = reflectPlane(S, R, pl) ?: continue
                val (p, len) = res
                if (!pl.contains(outline, p.xy())) continue
                var status = PathStatus.VALID
                if (m.ceiling.details.any { Polygons.contains(CeilingGeometry.detailFootprint(it), p.xy()) }) status = PathStatus.DETAIL_REGION
                if (status == PathStatus.VALID && (!Polygons.segmentInside(outline, sp, p.xy()) || !Polygons.segmentInside(outline, p.xy(), rp))) status = PathStatus.OCCLUDED
                if (status == PathStatus.VALID && (!underCeiling(defined, m, S, p, skipNear = p) || !underCeiling(defined, m, p, R, skipNear = p))) status = PathStatus.OCCLUDED
                paths += ReflectionPath(s.id, r.id, pl.label, "ceiling-$i", P3(p.x, p.y, p.z), len, ms(len), ms(len) - ms(dLen), status, false)
            }
        }
        if (sources.isEmpty() || receivers.isEmpty()) notes += "Place at least one source and one receiver to trace paths."
        val sbir = sbir(m, paths, direct)
        return ReflectionReport(direct, paths, sbir, notes)
    }

    /** Mirror-image reflection off plane z = a x + b y + c. Returns the reflection point and path length. */
    fun reflectPlane(S: Vec3, R: Vec3, pl: CeilingPlane): Pair<Vec3, Double>? {
        val n = Vec3(-pl.a, -pl.b, 1.0)
        val nn = n dot n
        val fs = (n dot S) - pl.c; val fr = (n dot R) - pl.c
        if (fs >= 0 || fr >= 0) return null
        val img = S - n * (2 * fs / nn)
        val dir = R - img
        val den = n dot dir
        if (abs(den) < 1e-12) return null
        val t = (pl.c - (n dot img)) / den
        if (t <= 0 || t >= 1) return null
        return (img + dir * t) to img.distanceTo(R)
    }

    /** Deterministic sampled check that a segment stays below the effective ceiling (C-19). */
    private fun underCeiling(s: CeilingSupport.Defined, m: RoomModel, a: Vec3, b: Vec3, skipNear: Vec3? = null): Boolean {
        for (k in 1 until SAMPLES) {
            val q = a + (b - a) * (k.toDouble() / SAMPLES)
            if (skipNear != null && q.distanceTo(skipNear) < 0.02) continue
            val h = CeilingGeometry.effectiveCeiling(s, m.ceiling.details, q.xy()) ?: return false
            if (q.z > h + 1e-6) return false
        }
        return true
    }

    private fun sbir(m: RoomModel, paths: List<ReflectionPath>, direct: List<DirectPath>): List<SbirEstimate> {
        val out = ArrayList<SbirEstimate>()
        for (p in paths.filter { it.status == PathStatus.VALID && !it.planOnly }) {
            val src = m.setup.items.first { it.id == p.sourceId }
            val d = direct.first { it.sourceId == p.sourceId && it.receiverId == p.receiverId }
            val delta = p.lengthM - d.lengthM
            if (delta <= 1e-6) continue
            val sp = src.position!!
            val dist = when {
                p.surfaceRef == "floor" -> src.height!!.meters
                p.surfaceRef.startsWith("ceiling") -> (p.point.z ?: 0.0) - src.height!!.meters
                else -> m.wall(p.surfaceRef)?.let { abs((sp - it.a) dot it.outward) } ?: continue
            }
            out += SbirEstimate(p.sourceId, p.receiverId, p.surface, p.surfaceRef, abs(dist), delta, SPEED_OF_SOUND / (2 * delta))
        }
        return out.sortedBy { -it.firstNullHz }
    }
}

object Modes {
    const val MAX_HZ = 300.0
    const val ANGLE_TOL_DEG = 0.01

    sealed interface Eligibility {
        data class Rectangular(val lx: Double, val ly: Double, val lz: Double) : Eligibility
        data class Unavailable(val reason: String) : Eligibility
    }

    fun eligibility(m: RoomModel): Eligibility {
        if (Validation.blocking(m).isNotEmpty()) return Eligibility.Unavailable("The floor plan has unresolved errors.")
        if (m.vertices.size != 4 || !Polygons.isRightAngled(m.outline, ANGLE_TOL_DEG)) {
            return Eligibility.Unavailable("Room-mode formula applies only to rectangular rooms. This outline is not rectangular; an irregular-room modal solver is not part of this build.")
        }
        val p = m.ceiling.profile
        if (p !is CeilingProfile.Flat || p.height == null) return Eligibility.Unavailable("Room modes need a flat ceiling with a known height.")
        if (m.ceiling.details.isNotEmpty()) return Eligibility.Unavailable("Ceiling details break the rectangular-box model.")
        return Eligibility.Rectangular(m.wallAt(0).length, m.wallAt(1).length, p.height.meters)
    }

    /** f = c/2 · sqrt((nx/Lx)² + (ny/Ly)² + (nz/Lz)²), rigid-wall rectangular box. */
    fun rectangular(lx: Double, ly: Double, lz: Double, maxHz: Double = MAX_HZ, c: Double = SPEED_OF_SOUND): List<RoomModeResult> {
        val out = ArrayList<RoomModeResult>()
        val nxMax = ceil(2 * maxHz * lx / c).toInt(); val nyMax = ceil(2 * maxHz * ly / c).toInt(); val nzMax = ceil(2 * maxHz * lz / c).toInt()
        for (nx in 0..nxMax) for (ny in 0..nyMax) for (nz in 0..nzMax) {
            if (nx == 0 && ny == 0 && nz == 0) continue
            val f = c / 2 * sqrt((nx / lx).let { it * it } + (ny / ly).let { it * it } + (nz / lz).let { it * it })
            if (f > maxHz) continue
            val nonzero = listOf(nx, ny, nz).count { it > 0 }
            out += RoomModeResult(nx, ny, nz, f, when (nonzero) { 1 -> "axial"; 2 -> "tangential"; else -> "oblique" })
        }
        return out.sortedBy { it.hz }
    }
}

object Capabilities {
    fun table(m: RoomModel, hasValidatedTreatmentData: Boolean): List<Capability> {
        val out = ArrayList<Capability>()
        val blocking = Validation.blocking(m)
        out += Capability("Editable geometry", if (blocking.isEmpty()) CapabilityStatus.SUPPORTED else CapabilityStatus.PARTIAL,
            if (blocking.isEmpty()) "Closed outline with ${m.vertices.size} walls." else blocking.first().message)
        val support = CeilingGeometry.support(m.outline, m.ceiling)
        val unknownOpenings = m.openings.count { it.height == null || it.bottom == null }
        out += when (support) {
            is CeilingSupport.Defined -> Capability("3D surfaces / apertures",
                if (unknownOpenings == 0 && m.ceiling.details.all { it.vertical != null }) CapabilityStatus.SUPPORTED else CapabilityStatus.PARTIAL,
                support.description + (if (unknownOpenings > 0) "; $unknownOpenings opening(s) need height and sill/bottom" else "") +
                    (if (support.approximate) "; curved surfaces shown as an approximation" else ""))
            is CeilingSupport.Missing -> Capability("3D surfaces / apertures", CapabilityStatus.PARTIAL, "Needs: " + support.inputs.joinToString())
            is CeilingSupport.Draft -> Capability("3D surfaces / apertures", CapabilityStatus.PARTIAL, support.reason)
        }
        out += when (val e = Modes.eligibility(m)) {
            is Modes.Eligibility.Rectangular -> Capability("Room modes", CapabilityStatus.SUPPORTED,
                "Rectangular rigid-wall formula; openings and wall construction are not modeled.")
            is Modes.Eligibility.Unavailable -> Capability("Room modes", CapabilityStatus.UNAVAILABLE, e.reason)
        }
        val sources = m.setup.items.count { it.kind.role == Role.SOURCE && it.position != null }
        val receivers = m.setup.items.count { it.kind.role == Role.RECEIVER && it.position != null }
        val heights = m.setup.items.all { it.position == null || it.height != null }
        out += Capability("First-order reflections",
            when {
                sources == 0 || receivers == 0 -> CapabilityStatus.UNAVAILABLE
                support is CeilingSupport.Defined && heights && !support.curvedUnsupportedForReflections -> CapabilityStatus.SUPPORTED
                else -> CapabilityStatus.PARTIAL
            },
            when {
                sources == 0 || receivers == 0 -> "Needs at least one placed source and receiver."
                !heights -> "Wall paths in plan only; add item heights for floor/ceiling paths and true lengths."
                support !is CeilingSupport.Defined -> "Wall and floor paths; ceiling paths need a supported ceiling."
                support.curvedUnsupportedForReflections -> "Planar surfaces only; curved ceiling surfaces excluded."
                else -> "Walls, floor and planar ceiling faces; openings excluded."
            })
        out += Capability("Speaker-boundary interference (SBIR)",
            if (sources > 0 && receivers > 0 && heights) CapabilityStatus.SUPPORTED else CapabilityStatus.UNAVAILABLE,
            "First-null frequency from path difference, rigid boundary assumed; no dip depth or severity.")
        out += Capability("Quantitative treatment estimate",
            if (hasValidatedTreatmentData) CapabilityStatus.SUPPORTED else CapabilityStatus.UNAVAILABLE,
            if (hasValidatedTreatmentData) "Validated product data available." else "No validated product absorption/diffusion data is configured (B-02).")
        return out
    }
}
