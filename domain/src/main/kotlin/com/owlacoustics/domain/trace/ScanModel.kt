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
            // the first few readings are averaged; after that each one only nudges the estimate (a running median),
            // so a single bad reading from clutter cannot pull the boundary far
            a[b] = when {
                n == 0 -> r
                n < 3 -> (a[b] * n + r) / (n + 1)
                else -> { val step = 0.05f * a[b]; a[b] + (r - a[b]).coerceIn(-step, step) }
            }
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

    private class Ring(val r: FloatArray, val inferred: BooleanArray, val weight: FloatArray = FloatArray(r.size) { 1f })

    private fun brushed(s: SurfaceScan, b: Int) = s.min[b] > 0 || s.max[b] > 0 || s.edge[b] > 0

    private fun ring(s: SurfaceScan): Ring {
        val n = SurfaceScan.BINS
        // a direction counts once it has been seen at least twice (or the brush has said something about it)
        val raw = FloatArray(n) { if (brushed(s, it) || s.hits[it] >= 2) s.value(it) else 0f }
        // median of the neighbouring directions removes lone spikes; brushed directions are kept as they are
        val r = FloatArray(n) { b ->
            if (raw[b] == 0f || brushed(s, b)) raw[b]
            else (-3..3).map { raw[(b + it + n) % n] }.filter { it > 0f }.sorted().let { it[it.size / 2] }
        }
        val wt = FloatArray(n) { b -> if (brushed(s, b)) 10f else minOf(s.hits[b], 10).toFloat() }
        val inf = BooleanArray(n)
        // short gaps are bridged in a straight line between their two ends
        var i = 0
        val have = (0 until n).any { r[it] > 0 }
        if (!have) return Ring(r, inf, wt)
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
        return Ring(r, inf, wt)
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

    private fun fit(ring: Ring, square: Boolean): Fit? {
        val n = SurfaceScan.BINS
        val filled = (0 until n).count { ring.r[it] > 0 }
        if (filled < 12) return null
        val med = (0 until n).filter { ring.r[it] > 0 }.map { ring.r[it] }.sorted().let { it[it.size / 2] }.toDouble()
        val tol = (0.035 * med).coerceIn(0.04, 0.25)
        fun inferredNear(p: Vec2): Boolean { val b = SurfaceScan.bin(atan2(p.y, p.x)); return ring.inferred[b] }
        if (square) orthoFit(ring, med)?.let { return it }
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

    // ---- walls that meet at right angles ----

    private class OSeg(val vertical: Boolean, val value: Double, val i0: Int, val i1: Int)

    /** Wall direction (mod 90°) that most of the outline agrees with. */
    private fun mainAxis(p: List<Vec2>, closed: Boolean): Double {
        var c = 0.0; var s = 0.0
        val n = p.size
        for (i in 0 until n) {
            val a = if (closed) p[(i - 2 + n) % n] else p.getOrNull(i - 2) ?: continue
            val b = if (closed) p[(i + 2) % n] else p.getOrNull(i + 2) ?: continue
            val t = b - a; val l = t.length
            if (l < 1e-9) continue
            val ang = atan2(t.y, t.x)
            c += l * cos(4 * ang); s += l * sin(4 * ang)
        }
        return atan2(s, c) / 4
    }

    /**
     * Splits an ordered run of points into walls that alternate between "same y" and "same x" in the room's own axes.
     * Each extra wall costs [penalty] (in units of the reading scatter squared), so noise does not create corners but
     * a real jog in the wall does.
     */
    private fun orthoSegments(q: List<Vec2>, w: DoubleArray, sigma: Double, penalty: Double, minRun: Int): Pair<List<OSeg>, Double>? {
        val n = q.size
        if (n < minRun) return null
        val sw = DoubleArray(n + 1); val sx = DoubleArray(n + 1); val sxx = DoubleArray(n + 1); val sy = DoubleArray(n + 1); val syy = DoubleArray(n + 1)
        for (k in 0 until n) {
            sw[k + 1] = sw[k] + w[k]; sx[k + 1] = sx[k] + w[k] * q[k].x; sxx[k + 1] = sxx[k] + w[k] * q[k].x * q[k].x
            sy[k + 1] = sy[k] + w[k] * q[k].y; syy[k + 1] = syy[k] + w[k] * q[k].y * q[k].y
        }
        fun cost(i: Int, j: Int, v: Boolean): Double {
            val ww = sw[j + 1] - sw[i]; if (ww <= 0) return 0.0
            val s1 = if (v) sx[j + 1] - sx[i] else sy[j + 1] - sy[i]
            val s2 = if (v) sxx[j + 1] - sxx[i] else syy[j + 1] - syy[i]
            return (s2 - s1 * s1 / ww).coerceAtLeast(0.0) / (sigma * sigma)
        }
        fun mean(i: Int, j: Int, v: Boolean): Double { val ww = sw[j + 1] - sw[i]; return (if (v) sx[j + 1] - sx[i] else sy[j + 1] - sy[i]) / ww }
        val inf = Double.MAX_VALUE
        val best = Array(2) { DoubleArray(n) { inf } }
        val back = Array(2) { IntArray(n) { -1 } }
        for (j in minRun - 1 until n) for (t in 0..1) {
            var b = cost(0, j, t == 1) + penalty; var arg = -1
            for (i in minRun - 1..j - minRun) {
                val prev = best[1 - t][i]
                if (prev == inf) continue
                val c = prev + cost(i + 1, j, t == 1) + penalty
                if (c < b) { b = c; arg = i }
            }
            best[t][j] = b; back[t][j] = arg
        }
        var t = if (best[0][n - 1] <= best[1][n - 1]) 0 else 1
        val total = best[t][n - 1]
        var j = n - 1
        val out = ArrayList<OSeg>()
        while (j >= 0) { val i = back[t][j]; out += OSeg(t == 1, mean(i + 1, j, t == 1), i + 1, j); j = i; t = 1 - t }
        return out.reversed() to total
    }

    private fun corner(a: OSeg, b: OSeg) = if (a.vertical) Vec2(a.value, b.value) else Vec2(b.value, a.value)

    private fun orthoFit(ring: Ring, med: Double): Fit? {
        val n = SurfaceScan.BINS
        val sigma = (0.03 * med).coerceIn(0.02, 0.2)
        val penalty = 20.0; val minRun = 4
        val closed = (0 until n).all { ring.r[it] > 0 }
        // ordered directions: the whole ring, or the longest run of directions with readings
        val order: List<Int> = if (closed) (0 until n).toList() else {
            // walk once round from an empty direction, so a run crossing 0° is seen whole
            val z = (0 until n).first { ring.r[it] == 0f }
            var bestStart = -1; var bestLen = 0; var k = 0
            while (k < n) {
                if (ring.r[(z + k) % n] == 0f) { k++; continue }
                var len = 0
                while (k + len < n && ring.r[(z + k + len) % n] > 0) len++
                if (len > bestLen) { bestLen = len; bestStart = (z + k) % n }
                k += len
            }
            if (bestLen < 12) return null
            (0 until bestLen).map { (bestStart + it) % n }
        }
        val p = order.map { point(it, ring.r[it]) }
        val theta = mainAxis(p, closed)
        val rot = { v: Vec2 -> Vec2(v.x * cos(-theta) - v.y * sin(-theta), v.x * sin(-theta) + v.y * cos(-theta)) }
        val unrot = { v: Vec2 -> Vec2(v.x * cos(theta) - v.y * sin(theta), v.x * sin(theta) + v.y * cos(theta)) }
        val wAll = order.map { (ring.weight[it].toDouble() * if (ring.inferred[it]) 0.3 else 1.0).coerceAtLeast(0.05) }
        val wMean = wAll.average()
        fun attempt(shift: Int): Pair<Fit, Double>? {
            val idx = order.indices.map { (it + shift) % order.size }
            val q = idx.map { rot(p[it]) }
            val w = DoubleArray(idx.size) { wAll[idx[it]] / wMean }
            var (segs, cost) = orthoSegments(q, w, sigma, penalty, minRun) ?: return null
            if (closed) {
                // the ring was cut somewhere; if that cut a wall in two, join the halves
                if (segs.size % 2 == 1 && segs.size >= 3) {
                    val a = segs.first(); val b = segs.last()
                    val pts = (a.i0..a.i1) + (b.i0..b.i1)
                    val ws = pts.sumOf { w[it] }
                    val v = pts.sumOf { w[it] * (if (a.vertical) q[it].x else q[it].y) } / ws
                    segs = listOf(OSeg(a.vertical, v, b.i0, a.i1 + idx.size)) + segs.subList(1, segs.size - 1)
                    cost -= penalty
                }
                if (segs.size < 4 || segs.size % 2 == 1) return null
                val m = segs.size
                val corners = (0 until m).map { unrot(corner(segs[it], segs[(it + 1) % m])) }
                val inferred = corners.map { c -> ring.inferred[SurfaceScan.bin(atan2(c.y, c.x))] }
                return Fit(corners, true, inferred) to cost
            }
            val first = segs.first(); val last = segs.last()
            val start = if (first.vertical) Vec2(first.value, q.first().y) else Vec2(q.first().x, first.value)
            val end = if (last.vertical) Vec2(last.value, q.last().y) else Vec2(q.last().x, last.value)
            val verts = listOf(start) + (0 until segs.size - 1).map { corner(segs[it], segs[it + 1]) } + listOf(end)
            val inferred = verts.indices.map { k -> if (k == 0) false else segs[k - 1].let { s -> (s.i0..s.i1).count { ring.inferred[order[idx[it]]] } * 2 > s.i1 - s.i0 + 1 } }
            return Fit(verts.map(unrot), false, inferred) to cost
        }
        val tries = if (closed) listOf(0, n / 8, n / 4) else listOf(0)
        val fit = tries.mapNotNull { attempt(it) }.minByOrNull { it.second }?.first ?: return null
        // a fit that crosses itself or flies far outside what was seen is not trusted
        if (fit.closed && com.owlacoustics.domain.geometry.Polygons.firstSelfIntersection(fit.corners) != null) return null
        val far = 2.0 * (0 until n).maxOf { ring.r[it] }
        if (fit.corners.any { it.length > far }) return null
        return fit
    }

    /** Builds the document; [memory] keeps corner ids stable between calls. */
    fun build(scan: ScanState, borrowAcross: Boolean, memory: CornerMemory, square: Boolean = true): TraceDoc {
        val fr = ring(scan.floor); val cr = ring(scan.ceiling)
        if (borrowAcross) { borrow(fr, cr); borrow(cr, fr) }
        fun boundary(s: Surface, ring: Ring): TraceBoundary {
            val f = fit(ring, square) ?: return TraceBoundary()
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
