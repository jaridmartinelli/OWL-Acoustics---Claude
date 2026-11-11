package com.owlacoustics.domain.trace

import com.owlacoustics.domain.capturelab.PolarOutline
import com.owlacoustics.domain.capturelab.PolarSample
import com.owlacoustics.domain.geometry.Vec2
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What is known about one surface's boundary, per degree of direction around the user. Distances are in "plane
 * units": for the floor, multiples of the camera's height above it; for the ceiling, multiples of the ceiling's
 * height above the camera. 0 means "nothing yet".
 *
 * [auto] is what the automatic detection has seen. [min], [max] and [edge] come from the brush and always win:
 * an Add stroke says the surface reaches at least this far ([min]) and may pin the edge it found ([edge]); a
 * Subtract stroke says it ends before here ([max]). They are kept, so later strokes and detections respect them.
 */
@Serializable
data class SurfaceScan(
    val auto: List<Float> = List(BINS) { 0f },
    val hits: List<Int> = List(BINS) { 0 },
    val min: List<Float> = List(BINS) { 0f },
    val max: List<Float> = List(BINS) { 0f },
    val edge: List<Float> = List(BINS) { 0f },
) {
    val isEmpty get() = auto.all { it == 0f } && min.all { it == 0f } && max.all { it == 0f } && edge.all { it == 0f }

    /** Boundary distance in this direction, or 0. */
    fun value(b: Int): Float {
        var v = if (edge[b] > 0) edge[b] else if (auto[b] > 0) auto[b] else min[b]
        if (max[b] > 0 && (v == 0f || v > max[b])) v = max[b]
        if (min[b] > 0 && v < min[b]) v = min[b]
        return v
    }

    /** Adds automatic readings; each is averaged in, and readings the brush has ruled out are ignored. */
    fun withAuto(samples: List<Pair<Int, Float>>): SurfaceScan {
        if (samples.isEmpty()) return this
        val a = auto.toMutableList(); val h = hits.toMutableList()
        for ((b, r) in samples) {
            if (r <= 0f || !r.isFinite()) continue
            if (max[b] > 0 && r > max[b] * 1.05f) continue
            if (min[b] > 0 && r < min[b] * 0.95f) continue
            val n = h[b]
            // once settled, a wildly different reading is treated as a glitch, not as news
            if (n >= 6 && abs(r - a[b]) > 0.3f * a[b]) continue
            a[b] = if (n == 0) r else if (n < 8) (a[b] * n + r) / (n + 1) else a[b] * 0.85f + r * 0.15f
            h[b] = n + 1
        }
        return copy(auto = a, hits = h)
    }

    /** Add stroke: the surface reaches at least [reach]; [found] is the edge found beyond the finger, or 0. */
    fun withAdd(b: Int, reach: Float, found: Float): SurfaceScan {
        val mn = min.toMutableList(); val mx = max.toMutableList(); val e = edge.toMutableList()
        if (reach > mn[b]) mn[b] = reach
        if (mx[b] > 0 && mx[b] < mn[b]) mx[b] = 0f
        if (found > 0 && found >= mn[b] && (mx[b] == 0f || found <= mx[b])) e[b] = found
        else if (e[b] > 0 && e[b] < mn[b]) e[b] = 0f
        return copy(min = mn, max = mx, edge = e)
    }

    /** Subtract stroke: the surface ends before [limit]; [found] is the edge found on the near side, or 0. */
    fun withSubtract(b: Int, limit: Float, found: Float): SurfaceScan {
        val mn = min.toMutableList(); val mx = max.toMutableList(); val e = edge.toMutableList(); val a = auto.toMutableList(); val h = hits.toMutableList()
        if (mx[b] == 0f || limit < mx[b]) mx[b] = limit
        if (mn[b] > mx[b]) mn[b] = 0f
        if (e[b] > mx[b]) e[b] = 0f
        if (a[b] > mx[b]) { a[b] = 0f; h[b] = 0 }
        if (found > 0 && found <= mx[b] && found >= mn[b]) e[b] = found
        return copy(auto = a, hits = h, min = mn, max = mx, edge = e)
    }

    companion object {
        const val BINS = 360
        fun bin(yaw: Double): Int { val d = Math.toDegrees(yaw) % 360; return ((if (d < 0) d + 360 else d).toInt()).coerceIn(0, BINS - 1) }
        fun yawOf(b: Int) = Math.toRadians(b + 0.5)
    }
}

@Serializable
data class ScanState(
    val floor: SurfaceScan = SurfaceScan(),
    val ceiling: SurfaceScan = SurfaceScan(),
    val typed: List<TypedLength> = emptyList(),
    val ceilingHeight: TypedValue? = null,
    /** Places (surface, plane position) where the user removed a corner; no corner is put back there. */
    val removed: List<RemovedCorner> = emptyList(),
) {
    fun surface(s: Surface) = if (s == Surface.FLOOR) floor else ceiling
    fun with(s: Surface, v: SurfaceScan) = if (s == Surface.FLOOR) copy(floor = v) else copy(ceiling = v)
    val isEmpty get() = floor.isEmpty && ceiling.isEmpty && typed.isEmpty() && ceilingHeight == null
}

@Serializable
data class RemovedCorner(val surface: Surface, val x: Double, val y: Double)

/** Remembers which id each corner had, so typed sizes stay attached while the outline keeps refining. */
class CornerMemory {
    private class Known(val id: Int, val surface: Surface, var p: Vec2)
    private val known = ArrayList<Known>()
    var nextId = 1; private set
    fun idFor(s: Surface, p: Vec2, taken: Set<Int>): Int {
        val k = known.filter { it.surface == s && it.id !in taken && it.p.distanceTo(p) < 0.3 }.minByOrNull { it.p.distanceTo(p) }
        if (k != null) { k.p = p; return k.id }
        val id = nextId++
        known += Known(id, s, p)
        return id
    }
    fun clear() { known.clear() }
}

/** Turns the per-direction readings into straight walls with corner points, as a [TraceDoc] the solver understands. */
object ScanBuilder {
    private const val GAP = 8

    private class Ring(val r: FloatArray, val inferred: BooleanArray)

    private fun ring(s: SurfaceScan): Ring {
        val n = SurfaceScan.BINS
        val r = FloatArray(n) { s.value(it) }
        val inf = BooleanArray(n)
        // short gaps are bridged in a straight line between their two ends
        var i = 0
        val have = (0 until n).any { r[it] > 0 }
        if (!have) return Ring(r, inf)
        while (i < n) {
            if (r[i] > 0) { i++; continue }
            var j = i
            while (j < n && r[j] == 0f) j++
            val a = (i - 1 + n) % n; val b = j % n
            val len = j - i
            if (len <= GAP && r[a] > 0 && r[b] > 0 && !(i == 0 && j == n)) {
                val pa = point(a, r[a]); val pb = point(b, r[b])
                for (k in i until j) {
                    val t = (k - i + 1.0) / (len + 1)
                    val p = pa + (pb - pa) * t
                    r[k] = hypot(p.x, p.y).toFloat(); inf[k] = true
                }
            }
            i = j
        }
        return Ring(r, inf)
    }

    private fun point(b: Int, r: Float): Vec2 { val a = SurfaceScan.yawOf(b); return Vec2(cos(a) * r, sin(a) * r) }

    /**
     * Where one surface has no reading but the other does, borrow it: walls are vertical, so both outlines are the
     * same shape and differ only by one scale factor (found from the directions where both were seen).
     */
    private fun borrow(target: Ring, source: Ring) {
        val ratios = (0 until SurfaceScan.BINS).filter { target.r[it] > 0 && source.r[it] > 0 && !target.inferred[it] && !source.inferred[it] }
            .map { target.r[it] / source.r[it] }.sorted()
        if (ratios.size < 5) return
        val k = ratios[ratios.size / 2]
        for (b in 0 until SurfaceScan.BINS) if (target.r[b] == 0f && source.r[b] > 0) { target.r[b] = source.r[b] * k; target.inferred[b] = true }
    }

    private class Fit(val corners: List<Vec2>, val closed: Boolean, val inferred: List<Boolean>)

    private fun fit(ring: Ring): Fit? {
        val n = SurfaceScan.BINS
        val filled = (0 until n).count { ring.r[it] > 0 }
        if (filled < 12) return null
        val med = (0 until n).filter { ring.r[it] > 0 }.map { ring.r[it] }.sorted().let { it[it.size / 2] }.toDouble()
        val tol = (0.035 * med).coerceIn(0.04, 0.25)
        fun inferredNear(p: Vec2): Boolean { val b = SurfaceScan.bin(atan2(p.y, p.x)); return ring.inferred[b] }
        if (filled == n) {
            val poly = PolarOutline.fit((0 until n).map { PolarSample(SurfaceScan.yawOf(it), ring.r[it].toDouble()) }, tol = tol, minRun = 5)
            if (poly != null && poly.size >= 3) return Fit(poly, true, poly.map(::inferredNear))
        }
        // open: the longest run of directions with readings
        var bestStart = -1; var bestLen = 0
        var i = 0
        while (i < 2 * n) {
            if (ring.r[i % n] == 0f) { i++; continue }
            var j = i
            while (j < i + n && ring.r[j % n] > 0) j++
            if (j - i > bestLen) { bestLen = j - i; bestStart = i % n }
            if (i >= n) break
            i = j
        }
        if (bestLen < 12) return null
        if (bestLen >= n) bestLen = n - 1
        val scale = 200.0
        val pts = (0 until bestLen).map { k -> val b = (bestStart + k) % n; point(b, ring.r[b]).let { StrokePoint(it.x * scale, it.y * scale, !ring.inferred[b]) } }
        val f = StrokeFitter.fit(pts, tol * scale, 0.2 * med * scale) ?: return null
        val corners = f.vertices.map { Vec2(it.x / scale, it.y / scale) }
        return Fit(corners, false, corners.indices.map { k -> k > 0 && !f.segmentSnapped[k - 1] })
    }

    /** Builds the document; [memory] keeps corner ids stable between calls. */
    fun build(scan: ScanState, borrowAcross: Boolean, memory: CornerMemory): TraceDoc {
        val fr = ring(scan.floor); val cr = ring(scan.ceiling)
        if (borrowAcross) { borrow(fr, cr); borrow(cr, fr) }
        fun boundary(s: Surface, ring: Ring): TraceBoundary {
            val f = fit(ring) ?: return TraceBoundary()
            var keep = f.corners.indices.filter { k -> scan.removed.none { it.surface == s && hypot(it.x - f.corners[k].x, it.y - f.corners[k].y) < 0.3 } }
            if (keep.size < 2 || (f.closed && keep.size < 3)) keep = f.corners.indices.toList()
            val taken = HashSet<Int>()
            val dz = if (s == Surface.FLOOR) -1.0 else 1.0
            val anchors = keep.map { k ->
                val p = f.corners[k]
                val id = memory.idFor(s, p, taken); taken += id
                val n = sqrt(p.x * p.x + p.y * p.y + 1.0)
                // seen from the turning point itself, one plane unit above the floor (or below the ceiling)
                TraceAnchor(id, AnchorKind.CORNER, p.x / n, p.y / n, dz / n, px = 0.0, py = 0.0, pz = 0.0, snapped = !f.inferred[k])
            }
            return TraceBoundary(anchors, f.closed && anchors.size >= 3)
        }
        val floor = boundary(Surface.FLOOR, fr); val ceiling = boundary(Surface.CEILING, cr)
        fun adjacent(t: TypedLength) = (if (t.surface == Surface.FLOOR) floor else ceiling).pairs().any { t.joins(it.first.id, it.second.id) }
        return TraceDoc(floor, ceiling, scan.typed.filter(::adjacent), scan.ceilingHeight)
    }

    /** How much of the way round (0–360) has a boundary reading. */
    fun coverage(s: SurfaceScan) = (0 until SurfaceScan.BINS).count { s.value(it) > 0 }
}

/** Linear undo/redo over scan states. */
data class ScanHistory(val past: List<ScanState> = emptyList(), val future: List<ScanState> = emptyList()) {
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun push(before: ScanState) = ScanHistory((past + before).takeLast(40), emptyList())
    fun undo(current: ScanState): Pair<ScanHistory, ScanState>? = if (!canUndo) null else ScanHistory(past.dropLast(1), listOf(current) + future) to past.last()
    fun redo(current: ScanState): Pair<ScanHistory, ScanState>? = if (!canRedo) null else ScanHistory(past + current, future.drop(1)) to future.first()
}
