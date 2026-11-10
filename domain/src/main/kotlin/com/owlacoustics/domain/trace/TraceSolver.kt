package com.owlacoustics.domain.trace

import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class TraceSettings(
    /**
     * Stand-and-turn model: the phone is assumed to sit this far in front of the vertical axis the user turns about.
     * An assumption, not a measurement; it is shown in every saved result. Ignored for anchors with a tracked position.
     */
    val pivotRadiusM: Double = 0.30,
    /** Ceiling height assumed until the user types one or types a wall length (owner default: 8 ft). */
    val defaultCeilingM: Double = 8 * 0.3048,
    /** Camera height used only while nothing else fixes the scale. Sizes are not shown as calibrated on this basis. */
    val provisionalCameraHeightM: Double = 1.40,
    val squareCorners: Boolean = true,
    val squareToleranceDeg: Double = 10.0,
    /** When the floor boundary is not closed but the ceiling boundary is, use the ceiling outline for the room. */
    val outlineFromCeiling: Boolean = true,
)

/** What the room's scale currently rests on, weakest first. */
enum class ScaleBasis { PROVISIONAL, CEILING_DEFAULT, CEILING_TYPED, TYPED_LENGTH }

enum class CeilingSource { ASSUMED, TYPED, FROM_LINES }

data class SolvedPoint(val id: Int, val kind: AnchorKind, val pos: Vec3)

data class SolvedSegment(
    val surface: Surface, val a: Int, val b: Int, val pa: Vec3, val pb: Vec3,
    /** Length from the traced lines at the current scale. */
    val measured: Double,
    val typed: TypedLength?,
    val snapped: Boolean,
) {
    /** A typed length is shown exactly as typed; otherwise the calculated one. */
    val shown get() = typed?.meters ?: measured
    val mid get() = (pa + pb) * 0.5
}

/** Top-down room outline for the plan card and the saved result: wall directions kept, typed walls locked, loop closed. */
data class PlanOutline(
    val source: Surface, val closed: Boolean, val corners: List<Vec2>, val lengths: List<Double>,
    val locked: List<Boolean>, val squared: Boolean, val area: Double?,
)

data class TraceSolution(
    val floorZ: Double, val ceilingZ: Double, val basis: ScaleBasis,
    /** True once a real measurement (a typed length, or a typed ceiling height tied to both lines) sets the scale. */
    val calibrated: Boolean,
    val ceilingSource: CeilingSource,
    /** Ceiling height that the floor and ceiling lines themselves imply at the current scale, when both exist. */
    val impliedCeiling: Double?,
    val ceilingWarning: String?,
    val floor: List<SolvedPoint>, val ceiling: List<SolvedPoint>,
    val segments: List<SolvedSegment>,
    /** Traced outline of the room as observed (not adjusted), used to pin the mesh to the picture. */
    val outline: List<Vec2>, val outlineClosed: Boolean, val outlineSource: Surface?,
    val plan: PlanOutline?,
) {
    val ceilingHeight get() = ceilingZ - floorZ
    fun points(s: Surface) = if (s == Surface.FLOOR) floor else ceiling
    fun z(s: Surface) = if (s == Surface.FLOOR) floorZ else ceilingZ
    fun planPositions(s: Surface): Map<Int, Vec2> = points(s).associate { it.id to it.pos.xy() }
}

object TraceSolver {
    const val FOOT = 0.3048
    private const val MIN_DZ = 0.02
    private const val CAP = 0.6

    /** Plan position of a point as a linear function of its plane height: P(z) = a + z·b. */
    private class Lin(val a: Vec2, val b: Vec2) { fun at(z: Double) = a + b * z }

    private fun cameraOf(an: TraceAnchor, s: TraceSettings) = an.tracked ?: Vec3(an.hx * s.pivotRadiusM, an.hy * s.pivotRadiusM, 0.0)

    private fun cornerLin(an: TraceAnchor, s: TraceSettings, floor: Boolean): Lin {
        val cam = cameraOf(an, s)
        // a floor point cannot be above the horizon (nor a ceiling point below it); clamp to keep it finite
        val dz = if (floor) min(an.dz, -MIN_DZ) else max(an.dz, MIN_DZ)
        val b = Vec2(an.dx / dz, an.dy / dz)
        return Lin(Vec2(cam.x, cam.y) - b * cam.z, b)
    }

    private fun lins(b: TraceBoundary, s: TraceSettings, floor: Boolean): LinkedHashMap<Int, Lin> {
        val corners = b.corners
        val cl = corners.associate { it.id to cornerLin(it, s, floor) }
        val out = LinkedHashMap<Int, Lin>()
        var prev: TraceAnchor? = null
        for ((i, a) in b.anchors.withIndex()) {
            if (a.isCorner) { out[a.id] = cl.getValue(a.id); prev = a; continue }
            val p = prev ?: continue
            val next = b.anchors.drop(i + 1).firstOrNull { it.isCorner } ?: (if (b.closed) corners.firstOrNull() else null) ?: continue
            val lp = cl.getValue(p.id); val ln = cl.getValue(next.id)
            out[a.id] = Lin(lp.a + (ln.a - lp.a) * a.t, lp.b + (ln.b - lp.b) * a.t)
        }
        return out
    }

    private fun minimize(lo: Double, hi: Double, n: Int = 240, f: (Double) -> Double): Double {
        var best = lo; var bv = Double.MAX_VALUE
        for (i in 0..n) { val z = lo + (hi - lo) * i / n; val v = f(z); if (v < bv) { bv = v; best = z } }
        var a = max(lo, best - (hi - lo) / n); var b = min(hi, best + (hi - lo) / n)
        val g = (sqrt(5.0) - 1) / 2
        repeat(60) { val c = b - g * (b - a); val d = a + g * (b - a); if (f(c) < f(d)) b = d else a = c }
        return (a + b) / 2
    }

    private fun typedZ(l: Map<Int, Lin>, typed: List<TypedLength>, lo: Double, hi: Double): Double? {
        val terms = typed.mapNotNull { t ->
            val a = l[t.a]; val b = l[t.b]
            if (a == null || b == null) null else Triple(b.a - a.a, b.b - a.b, t.meters)
        }
        if (terms.isEmpty()) return null
        return minimize(lo, hi) { z -> terms.sumOf { (da, db, len) -> val d = (da + db * z).length - len; d * d } }
    }

    /** Distance from [p] to a polyline, and whether [p] sits beside it (not past an open end). */
    private fun polyDist(p: Vec2, line: List<Vec2>, closed: Boolean): Pair<Double, Boolean> {
        val n = line.size
        val m = if (closed && n >= 3) n else n - 1
        var best = Double.MAX_VALUE; var ok = false
        for (i in 0 until m) {
            val a = line[i]; val b = line[(i + 1) % n]; val d = b - a
            val l2 = d dot d
            if (l2 < 1e-12) continue
            val t = ((p - a) dot d) / l2
            val q = a + d * t.coerceIn(0.0, 1.0)
            val dist = p.distanceTo(q)
            if (dist < best) { best = dist; ok = t >= -0.05 && t <= 1.05 }
        }
        return best to ok
    }

    /** Mean squared gap between the floor outline at [zf] and the ceiling outline at [zc]; second value: points that matched. */
    private fun matchCost(fc: List<Lin>, cc: List<Lin>, fClosed: Boolean, cClosed: Boolean, zf: Double, zc: Double): Pair<Double, Int> {
        val fp = fc.map { it.at(zf) }; val cp = cc.map { it.at(zc) }
        var sum = 0.0; var n = 0; var valid = 0
        fun add(p: Vec2, line: List<Vec2>, closed: Boolean) {
            val (d, ok) = polyDist(p, line, closed)
            n++
            if (ok) { valid++; sum += min(d, CAP) * min(d, CAP) } else sum += CAP * CAP
        }
        if (fp.size >= 2) for (q in cp) add(q, fp, fClosed)
        if (cp.size >= 2) for (p in fp) add(p, cp, cClosed)
        return if (n == 0) Double.MAX_VALUE to 0 else sum / n to valid
    }

    fun solve(doc: TraceDoc, s: TraceSettings): TraceSolution {
        val fl = lins(doc.floor, s, true); val cl = lins(doc.ceiling, s, false)
        val fCorners = doc.floor.corners; val cCorners = doc.ceiling.corners
        val fc = fCorners.map { fl.getValue(it.id) }; val cc = cCorners.map { cl.getValue(it.id) }
        val fCamZ = fCorners.map { cameraOf(it, s).z }; val cCamZ = cCorners.map { cameraOf(it, s).z }
        val minCam = (fCamZ + cCamZ).minOrNull() ?: 0.0
        val maxCam = (fCamZ + cCamZ).maxOrNull() ?: 0.0
        val fLo = minCam - 3.5; val fHi = minCam - 0.25
        val cLo = maxCam + 0.15; val cHi = maxCam + 6.0
        val hTyped = doc.ceilingHeight?.meters
        val h = hTyped ?: s.defaultCeilingM
        val canMatch = (fc.size >= 2 && cc.isNotEmpty()) || (cc.size >= 2 && fc.isNotEmpty())
        fun cost(zf: Double, zc: Double) = matchCost(fc, cc, doc.floor.closed, doc.ceiling.closed, zf, zc)
        fun matched(zf: Double, zc: Double) = cost(zf, zc).let { it.second > 0 && it.first < CAP * CAP * 0.9 }

        val zfTyped = typedZ(fl, doc.typed.filter { it.surface == Surface.FLOOR }, fLo, fHi)
        val zcTyped = typedZ(cl, doc.typed.filter { it.surface == Surface.CEILING }, cLo, cHi)
        val zf: Double; val zc: Double; val basis: ScaleBasis
        var implied: Double? = null
        when {
            zfTyped != null && zcTyped != null -> { zf = zfTyped; zc = zcTyped; basis = ScaleBasis.TYPED_LENGTH; implied = zc - zf }
            zfTyped != null -> {
                zf = zfTyped; basis = ScaleBasis.TYPED_LENGTH
                val m = if (canMatch) minimize(max(cLo, zf + 1.5), zf + 7.0) { cost(zf, it).first }.takeIf { matched(zf, it) } else null
                implied = m?.let { it - zf }
                zc = if (hTyped != null) zf + hTyped else m ?: (zf + h)
            }
            zcTyped != null -> {
                zc = zcTyped; basis = ScaleBasis.TYPED_LENGTH
                val m = if (canMatch) minimize(zc - 7.0, min(fHi, zc - 1.5)) { cost(it, zc).first }.takeIf { matched(it, zc) } else null
                implied = m?.let { zc - it }
                zf = if (hTyped != null) zc - hTyped else m ?: (zc - h)
            }
            else -> {
                val m = if (canMatch) minimize(max(fLo, maxCam + 0.15 - h), fHi) { cost(it, it + h).first }.takeIf { matched(it, it + h) } else null
                if (m != null) { zf = m; zc = m + h; basis = if (hTyped != null) ScaleBasis.CEILING_TYPED else ScaleBasis.CEILING_DEFAULT }
                else { zf = minCam - s.provisionalCameraHeightM; zc = zf + h; basis = ScaleBasis.PROVISIONAL }
            }
        }
        val calibrated = basis == ScaleBasis.TYPED_LENGTH || basis == ScaleBasis.CEILING_TYPED
        val ceilingSource = when {
            hTyped != null -> CeilingSource.TYPED
            basis == ScaleBasis.TYPED_LENGTH && implied != null -> CeilingSource.FROM_LINES
            else -> CeilingSource.ASSUMED
        }
        val warning = if (basis == ScaleBasis.TYPED_LENGTH && implied != null) when {
            hTyped != null && abs(implied - hTyped) > 0.10 -> "typed-mismatch"
            hTyped == null && (implied < 2.13 || implied > 3.20) -> "unusual"
            else -> null
        } else null

        fun pts(b: TraceBoundary, l: Map<Int, Lin>, z: Double) = b.anchors.mapNotNull { a -> l[a.id]?.let { SolvedPoint(a.id, a.kind, it.at(z).let { p -> Vec3(p.x, p.y, z) }) } }
        val fPts = pts(doc.floor, fl, zf); val cPts = pts(doc.ceiling, cl, zc)

        val segments = ArrayList<SolvedSegment>()
        fun segs(surface: Surface, b: TraceBoundary, p: List<SolvedPoint>) {
            val pos = p.associate { it.id to it.pos }
            val pairs = b.pairs()
            for ((i, pair) in pairs.withIndex()) {
                val (a, c) = pair
                val pa = pos[a.id] ?: continue; val pc = pos[c.id] ?: continue
                val closing = b.closed && i == pairs.size - 1
                // confidence belongs to the line, carried by the corner the line arrives at
                val arriving = if (c.isCorner) c else b.anchors.drop(b.anchors.indexOf(c)).firstOrNull { it.isCorner }
                val snapped = if (closing || arriving == null) b.closingSnapped else arriving.snapped
                segments += SolvedSegment(surface, a.id, c.id, pa, pc, pa.distanceTo(pc), doc.typedFor(surface, a.id, c.id), snapped)
            }
        }
        segs(Surface.FLOOR, doc.floor, fPts); segs(Surface.CEILING, doc.ceiling, cPts)

        val source = when {
            doc.floor.closed -> Surface.FLOOR
            doc.ceiling.closed && s.outlineFromCeiling -> Surface.CEILING
            fCorners.size >= 2 -> Surface.FLOOR
            cCorners.size >= 2 && s.outlineFromCeiling -> Surface.CEILING
            else -> null
        }
        val srcB = source?.let { doc.boundary(it) }
        val srcPts = if (source == Surface.FLOOR) fPts else cPts
        val outline = srcPts.filter { it.kind == AnchorKind.CORNER }.map { it.pos.xy() }
        val plan = if (source != null && srcB != null && outline.size >= 2) plan(source, srcB, outline, segments.filter { it.surface == source }, s) else null

        return TraceSolution(zf, zc, basis, calibrated, ceilingSource, implied, warning, fPts, cPts, segments,
            outline, srcB?.closed == true, source, plan)
    }

    private fun plan(source: Surface, b: TraceBoundary, corners: List<Vec2>, segs: List<SolvedSegment>, s: TraceSettings): PlanOutline {
        val cornerIds = b.corners.map { it.id }
        val n = corners.size
        val m = if (b.closed) n else n - 1
        // wall = corner to corner; its target is the sum of its pieces, locked only when every piece was typed
        val target = DoubleArray(m); val locked = BooleanArray(m) { true }
        var wall = -1
        for (seg in segs) {
            if (seg.a in cornerIds) wall = cornerIds.indexOf(seg.a)
            if (wall < 0 || wall >= m) continue
            target[wall] += seg.shown
            if (seg.typed == null) locked[wall] = false
        }
        val adj = adjust(corners, b.closed, target, locked, s.squareCorners, s.squareToleranceDeg)
        return PlanOutline(source, b.closed, adj.corners, adj.lengths.toList(), locked.toList(), adj.squared,
            if (b.closed && adj.corners.size >= 3) Polygons.area(adj.corners) else null)
    }

    internal class Adjusted(val corners: List<Vec2>, val lengths: DoubleArray, val squared: Boolean)

    private fun wrap(a: Double): Double { var x = a; while (x > PI) x -= 2 * PI; while (x < -PI) x += 2 * PI; return x }

    /**
     * Keeps each wall's direction (optionally squared to the room's main axes), holds typed walls at their typed
     * length and spreads the remaining misclosure over the other walls in proportion to their length.
     */
    internal fun adjust(pts: List<Vec2>, closed: Boolean, target: DoubleArray, locked: BooleanArray, square: Boolean, tolDeg: Double): Adjusted {
        val n = pts.size
        val m = if (closed) n else n - 1
        val measured = DoubleArray(m) { pts[it].distanceTo(pts[(it + 1) % n]) }
        val dirs0 = DoubleArray(m) { val d = pts[(it + 1) % n] - pts[it]; atan2(d.y, d.x) }
        var dirs = dirs0
        if (square && m >= 2) {
            val tol = Math.toRadians(tolDeg)
            // main axis = the wall direction (mod 90°) that the most wall length agrees with; angled walls do not pull it
            fun off(i: Int, axis: Double) = wrap(4 * (dirs0[i] - axis)) / 4
            val seed = (0 until m).maxBy { c -> (0 until m).sumOf { i -> if (abs(off(i, dirs0[c])) <= tol) measured[i] else 0.0 } }
            var phi = dirs0[seed]
            repeat(2) {
                var num = 0.0; var den = 0.0
                for (i in 0 until m) { val o = off(i, phi); if (abs(o) <= tol) { num += measured[i] * o; den += measured[i] } }
                if (den > 0) phi += num / den
            }
            dirs = DoubleArray(m) { i ->
                val snapped = phi + ((dirs0[i] - phi) / (PI / 2)).roundToInt() * (PI / 2)
                if (abs(wrap(dirs0[i] - snapped)) <= tol) snapped else dirs0[i]
            }
        }
        fun close(d: DoubleArray): DoubleArray? {
            if (!closed) return target.copyOf()
            val w = DoubleArray(m) { (if (locked[it]) 1e-6 else 1.0) * max(target[it], 0.05) }
            var mxx = 0.0; var mxy = 0.0; var myy = 0.0; var rx = 0.0; var ry = 0.0
            for (i in 0 until m) {
                val ux = cos(d[i]); val uy = sin(d[i])
                mxx += w[i] * ux * ux; mxy += w[i] * ux * uy; myy += w[i] * uy * uy
                rx -= target[i] * ux; ry -= target[i] * uy
            }
            val det = mxx * myy - mxy * mxy
            if (abs(det) < 1e-12) return null
            val lx = (myy * rx - mxy * ry) / det; val ly = (mxx * ry - mxy * rx) / det
            val out = DoubleArray(m) { target[it] + w[it] * (cos(d[it]) * lx + sin(d[it]) * ly) }
            return if (out.any { it < 0.03 }) null else out
        }
        var squared = dirs !== dirs0 && (0 until m).any { abs(wrap(dirs[it] - dirs0[it])) > 1e-9 }
        var lengths = close(dirs)
        if (lengths == null && squared) { dirs = dirs0; squared = false; lengths = close(dirs) }
        if (lengths == null) return Adjusted(pts, measured, false)
        val out = ArrayList<Vec2>(n)
        var p = pts[0]; out += p
        for (i in 0 until n - 1) { p += Vec2(cos(dirs[i]), sin(dirs[i])) * lengths[i]; out += p }
        val shift = pts.reduce(Vec2::plus) / n.toDouble() - out.reduce(Vec2::plus) / n.toDouble()
        return Adjusted(out.map { it + shift }, lengths, squared)
    }

    /** Where a ray from [cam] meets the horizontal plane at height [z]; null when it points away from the plane. */
    fun rayToPlane(cam: Vec3, ray: Vec3, z: Double): Vec3? {
        if (abs(ray.z) < 1e-6) return null
        val t = (z - cam.z) / ray.z
        return if (t <= 0) null else cam + ray * t
    }
}

data class Seg3(val a: Vec3, val b: Vec3)

/** Grid lines and filled areas in world metres, pinned to the traced (observed) geometry. One grid square is [spacing]. */
class TraceMesh(
    val floorLines: List<Seg3>, val wallLines: List<Seg3>, val ceilingLines: List<Seg3>,
    val floorFill: List<Vec3>, val ceilingFill: List<Vec3>, val wallFills: List<List<Vec3>>,
) {
    companion object { val EMPTY = TraceMesh(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()) }
}

object TraceMeshBuilder {
    private const val MAX_LINES = 160

    /** Segments of the grid lines parallel to [u] through [origin], every [spacing], that lie inside [poly]. */
    fun clipLines(poly: List<Vec2>, origin: Vec2, u: Vec2, spacing: Double): List<Pair<Vec2, Vec2>> {
        if (poly.size < 3) return emptyList()
        val v = u.perp()
        val offs = poly.map { (it - origin) dot v }
        var step = spacing
        val span = offs.max() - offs.min()
        if (span / step > MAX_LINES) step = spacing * kotlin.math.ceil(span / spacing / MAX_LINES)
        // lines lying on the outline itself are left to the boundary
        val k0 = kotlin.math.ceil((offs.min() + 1e-6) / step).toInt(); val k1 = kotlin.math.floor((offs.max() - 1e-6) / step).toInt()
        val out = ArrayList<Pair<Vec2, Vec2>>()
        val hits = ArrayList<Double>()
        for (k in k0..k1) {
            val c = k * step
            hits.clear()
            for (i in poly.indices) {
                val a = offs[i] - c; val b = offs[(i + 1) % poly.size] - c
                if ((a < 0) != (b < 0)) {
                    val p = poly[i] + (poly[(i + 1) % poly.size] - poly[i]) * (a / (a - b))
                    hits += (p - origin) dot u
                }
            }
            hits.sort()
            var i = 0
            while (i + 1 < hits.size) {
                if (hits[i + 1] - hits[i] > 1e-6) out += (origin + v * c + u * hits[i]) to (origin + v * c + u * hits[i + 1])
                i += 2
            }
        }
        return out
    }

    private fun gridOn(poly: List<Vec2>, origin: Vec2, u: Vec2, z: Double, spacing: Double): List<Seg3> =
        (clipLines(poly, origin, u, spacing) + clipLines(poly, origin, u.perp(), spacing)).map { (a, b) -> Seg3(Vec3(a.x, a.y, z), Vec3(b.x, b.y, z)) }

    /**
     * @param fillWhileDrawing grow the floor and ceiling outward from the camera while a boundary is still open;
     * otherwise a surface appears only once the room outline is closed.
     */
    fun build(doc: TraceDoc, sol: TraceSolution, s: TraceSettings, fillWhileDrawing: Boolean, spacing: Double = TraceSolver.FOOT): TraceMesh {
        val fCorners = sol.floor.filter { it.kind == AnchorKind.CORNER }.map { it.pos.xy() }
        val cCorners = sol.ceiling.filter { it.kind == AnchorKind.CORNER }.map { it.pos.xy() }
        val axisSrc = if (sol.outline.size >= 2) sol.outline else if (fCorners.size >= 2) fCorners else cCorners
        if (axisSrc.size < 2) return TraceMesh.EMPTY
        val origin = axisSrc[0]
        val u = (axisSrc[1] - axisSrc[0]).normalized().takeIf { it.length > 0.5 } ?: Vec2(1.0, 0.0)
        fun foot(b: TraceBoundary): Vec2 {
            val t = b.corners.mapNotNull { it.tracked }
            return if (t.isEmpty()) Vec2(0.0, 0.0) else Vec2(t.sumOf { it.x } / t.size, t.sumOf { it.y } / t.size)
        }
        val scaled = sol.basis != ScaleBasis.PROVISIONAL
        val room = sol.outline.takeIf { sol.outlineClosed && it.size >= 3 }
        val floorPoly = room ?: if (fillWhileDrawing && fCorners.size >= 2) listOf(foot(doc.floor)) + fCorners else emptyList()
        val ceilPoly = if (room != null) (if (scaled) room else emptyList())
            else if (fillWhileDrawing && cCorners.size >= 2) listOf(foot(doc.ceiling)) + cCorners else emptyList()
        val zf = sol.floorZ; val zc = sol.ceilingZ
        val floorLines = if (floorPoly.size >= 3) gridOn(floorPoly, origin, u, zf, spacing) else emptyList()
        val ceilLines = if (ceilPoly.size >= 3) gridOn(ceilPoly, origin, u, zc, spacing) else emptyList()

        val wallLines = ArrayList<Seg3>(); val wallFills = ArrayList<List<Vec3>>()
        val wallPts = room ?: if (!fillWhileDrawing) emptyList() else if (fCorners.size >= 2) fCorners else cCorners
        if (scaled && wallPts.size >= 2 && zc - zf > 0.5) {
            val m = if (room != null) wallPts.size else wallPts.size - 1
            for (i in 0 until m) {
                val a = wallPts[i]; val b = wallPts[(i + 1) % wallPts.size]
                val len = a.distanceTo(b)
                if (len < 0.02 || len > 60) continue
                val d = (b - a) / len
                wallFills += listOf(Vec3(a.x, a.y, zf), Vec3(b.x, b.y, zf), Vec3(b.x, b.y, zc), Vec3(a.x, a.y, zc))
                wallLines += Seg3(Vec3(a.x, a.y, zf), Vec3(a.x, a.y, zc))
                if (room == null && i == m - 1) wallLines += Seg3(Vec3(b.x, b.y, zf), Vec3(b.x, b.y, zc))
                var sAlong = spacing
                while (sAlong < len - 1e-6) { val p = a + d * sAlong; wallLines += Seg3(Vec3(p.x, p.y, zf), Vec3(p.x, p.y, zc)); sAlong += spacing }
                var z = zf
                while (z < zc - 1e-6) { wallLines += Seg3(Vec3(a.x, a.y, z), Vec3(b.x, b.y, z)); z += spacing }
                wallLines += Seg3(Vec3(a.x, a.y, zc), Vec3(b.x, b.y, zc))
            }
        }
        return TraceMesh(floorLines, wallLines, ceilLines,
            floorPoly.map { Vec3(it.x, it.y, zf) }, ceilPoly.map { Vec3(it.x, it.y, zc) }, wallFills)
    }
}
