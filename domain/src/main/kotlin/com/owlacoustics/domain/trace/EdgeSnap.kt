package com.owlacoustics.domain.trace

import com.owlacoustics.domain.geometry.Vec2
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Smoothed image gradients (Sobel after a small blur) of an upright luminance image. */
class EdgeMap(val w: Int, val h: Int, private val gx: FloatArray, private val gy: FloatArray) {
    private fun at(a: FloatArray, x: Double, y: Double): Double {
        val xc = x.coerceIn(0.0, w - 1.001); val yc = y.coerceIn(0.0, h - 1.001)
        val x0 = floor(xc).toInt(); val y0 = floor(yc).toInt()
        val fx = xc - x0; val fy = yc - y0
        val i = y0 * w + x0
        return (a[i] * (1 - fx) + a[i + 1] * fx) * (1 - fy) + (a[i + w] * (1 - fx) + a[i + w + 1] * fx) * fy
    }
    fun gxAt(x: Double, y: Double) = at(gx, x, y)
    fun gyAt(x: Double, y: Double) = at(gy, x, y)
    fun magAt(x: Double, y: Double): Double { val a = gxAt(x, y); val b = gyAt(x, y); return sqrt(a * a + b * b) }
    fun inside(x: Double, y: Double, margin: Double = 2.0) = x >= margin && y >= margin && x <= w - 1 - margin && y <= h - 1 - margin

    companion object {
        /** [lum] holds one byte per pixel, row by row. */
        fun of(lum: ByteArray, w: Int, h: Int): EdgeMap {
            require(lum.size >= w * h && w >= 3 && h >= 3)
            val tmp = FloatArray(w * h); val blur = FloatArray(w * h)
            for (y in 0 until h) {
                val r = y * w
                for (x in 0 until w) {
                    val l = lum[r + (if (x > 0) x - 1 else x)].toInt() and 255
                    val c = lum[r + x].toInt() and 255
                    val rt = lum[r + (if (x < w - 1) x + 1 else x)].toInt() and 255
                    tmp[r + x] = (l + 2 * c + rt) * 0.25f
                }
            }
            for (y in 0 until h) {
                val u = (if (y > 0) y - 1 else y) * w; val d = (if (y < h - 1) y + 1 else y) * w; val r = y * w
                for (x in 0 until w) blur[r + x] = (tmp[u + x] + 2 * tmp[r + x] + tmp[d + x]) * 0.25f
            }
            val gx = FloatArray(w * h); val gy = FloatArray(w * h)
            for (y in 1 until h - 1) {
                val r = y * w
                for (x in 1 until w - 1) {
                    val i = r + x
                    val tl = blur[i - w - 1]; val tc = blur[i - w]; val tr = blur[i - w + 1]
                    val ml = blur[i - 1]; val mr = blur[i + 1]
                    val bl = blur[i + w - 1]; val bc = blur[i + w]; val br = blur[i + w + 1]
                    gx[i] = (tr + 2 * mr + br - tl - 2 * ml - bl) * 0.125f
                    gy[i] = (bl + 2 * bc + br - tl - 2 * tc - tr) * 0.125f
                }
            }
            return EdgeMap(w, h, gx, gy)
        }
    }
}

/** Which of several parallel edges inside the search band to take. */
enum class EdgePrefer {
    STRONGEST,
    /** Floor line: the lowest edge in the picture (below a baseboard's top edge). */
    LOWER,
    /** Ceiling line: the highest edge in the picture (above crown moulding's lower edge). */
    UPPER,
}

data class SnapPoint(val x: Double, val y: Double, val snapped: Boolean, val strength: Double = 0.0) {
    val pos get() = Vec2(x, y)
}

object EdgeSnap {
    /** Weakest gradient treated as an edge (luminance levels per pixel after smoothing). */
    const val MIN_STRENGTH = 4.0

    /**
     * Snaps a point being dragged along direction ([dirX], [dirY]) onto an edge running the same way. Searches across
     * the stroke within [radius] pixels; with no edge strong enough the point is returned unchanged and unsnapped.
     */
    fun along(map: EdgeMap, x: Double, y: Double, dirX: Double, dirY: Double, radius: Double,
              prefer: EdgePrefer = EdgePrefer.STRONGEST, minStrength: Double = MIN_STRENGTH, prev: Vec2? = null): SnapPoint {
        val dl = sqrt(dirX * dirX + dirY * dirY)
        if (dl < 1e-9 || radius < 1) return nearest(map, x, y, radius, minStrength)
        val tx = dirX / dl; val ty = dirY / dl
        val nx = -ty; val ny = tx
        val step = 0.5
        val n = (radius / step).toInt()
        val resp = DoubleArray(2 * n + 1)
        for (i in -n..n) {
            val s = i * step
            var r = 0.0
            for (tau in -2..2 step 2) {
                val px = x + nx * s + tx * tau; val py = y + ny * s + ty * tau
                r += abs(map.gxAt(px, py) * nx + map.gyAt(px, py) * ny)
            }
            resp[i + n] = if (map.inside(x + nx * s, y + ny * s)) r / 3 else 0.0
        }
        var peakMax = 0.0
        val peaks = ArrayList<Int>()
        for (i in 1 until resp.size - 1) if (resp[i] >= minStrength && resp[i] > resp[i - 1] && resp[i] >= resp[i + 1]) {
            peaks += i; if (resp[i] > peakMax) peakMax = resp[i]
        }
        if (peaks.isEmpty()) return SnapPoint(x, y, false)
        val strong = peaks.filter { resp[it] >= 0.3 * peakMax }
        fun off(i: Int) = (i - n) * step
        val vertical = abs(ny) < 0.25 // the edge runs up the picture: "lower" and "upper" mean nothing
        val preferred = when {
            prefer == EdgePrefer.LOWER && !vertical -> strong.maxBy { off(it) * ny }
            prefer == EdgePrefer.UPPER && !vertical -> strong.minBy { off(it) * ny }
            else -> strong.maxBy { resp[it] * (1 - 0.35 * (off(it) / radius) * (off(it) / radius)) }
        }
        // stay on the edge the previous point was on unless the preferred one is solidly there: no flipping between
        // two parallel edges when one of them fades in and out
        val stay = prev?.let { pv ->
            val po = (pv.x - x) * nx + (pv.y - y) * ny
            peaks.minByOrNull { abs(off(it) - po) }?.takeIf { abs(off(it) - po) <= 2.0 }
        }
        val pick = if (stay == null || stay == preferred || resp[preferred] >= 0.45 * peakMax) preferred else stay
        // parabolic refinement of the peak position
        val a = resp[pick - 1]; val b = resp[pick]; val c = resp[pick + 1]
        val den = a - 2 * b + c
        val sub = if (abs(den) < 1e-9) 0.0 else (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
        val s = (pick - n + sub) * step
        return SnapPoint(x + nx * s, y + ny * s, true, b)
    }

    /** Snaps a tapped or dragged point to the strongest edge pixel within [radius], favouring ones near the finger. */
    fun nearest(map: EdgeMap, x: Double, y: Double, radius: Double, minStrength: Double = MIN_STRENGTH): SnapPoint {
        var best = 0.0; var bx = x; var by = y; var bm = 0.0
        val r = radius.toInt()
        for (dy in -r..r) for (dx in -r..r) {
            val d2 = (dx * dx + dy * dy).toDouble()
            if (d2 > radius * radius) continue
            val px = x + dx; val py = y + dy
            if (!map.inside(px, py)) continue
            val m = map.magAt(px, py)
            if (m < minStrength) continue
            val score = m * (1 - 0.6 * d2 / (radius * radius))
            if (score > best) { best = score; bx = px; by = py; bm = m }
        }
        return if (best > 0) SnapPoint(bx, by, true, bm) else SnapPoint(x, y, false)
    }
}

data class StrokePoint(val x: Double, val y: Double, val snapped: Boolean) { val pos get() = Vec2(x, y) }

/** A stroke turned into straight lines: [vertices] are the start, each corner, and the end. */
data class StrokeFit(val vertices: List<Vec2>, val segmentSnapped: List<Boolean>)

/**
 * Turns a wobbly finger stroke into straight wall lines. Room edges are straight in a still picture, so corners are
 * placed where two fitted lines cross, which is steadier than any single touch.
 */
object StrokeFitter {
    private class Line(val p: Vec2, val d: Vec2) {
        fun project(q: Vec2) = p + d * ((q - p) dot d)
        fun dist(q: Vec2) = abs((q - p) cross d)
    }

    private fun fitLine(pts: List<Vec2>): Line? {
        if (pts.size < 2) return null
        val c = pts.reduce(Vec2::plus) / pts.size.toDouble()
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (p in pts) { val d = p - c; sxx += d.x * d.x; sxy += d.x * d.y; syy += d.y * d.y }
        if (sxx + syy < 1e-9) return null
        val theta = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
        return Line(c, Vec2(kotlin.math.cos(theta), kotlin.math.sin(theta)))
    }

    private fun intersect(a: Line, b: Line): Vec2? {
        val den = a.d cross b.d
        if (abs(den) < 1e-9) return null
        return a.p + a.d * (((b.p - a.p) cross b.d) / den)
    }

    /** Moving average over ±[radius] pixels of path length; the window shrinks toward both ends so they stay put. */
    private fun smooth(pts: List<Vec2>, radius: Double): List<Vec2> {
        val n = pts.size
        val arc = DoubleArray(n)
        for (i in 1 until n) arc[i] = arc[i - 1] + pts[i].distanceTo(pts[i - 1])
        val out = ArrayList<Vec2>(n)
        var lo = 0; var hi = 0
        for (i in 0 until n) {
            val r = minOf(radius, arc[i] - arc[0], arc[n - 1] - arc[i])
            while (arc[i] - arc[lo] > r) lo++
            if (hi < i) hi = i
            while (hi + 1 < n && arc[hi + 1] - arc[i] <= r) hi++
            var sx = 0.0; var sy = 0.0
            for (j in lo..hi) { sx += pts[j].x; sy += pts[j].y }
            out += Vec2(sx / (hi - lo + 1), sy / (hi - lo + 1))
        }
        return out
    }

    private fun douglasPeucker(pts: List<Vec2>, tol: Double): List<Int> {
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack += 0 to pts.size - 1
        while (stack.isNotEmpty()) {
            val (i0, i1) = stack.removeLast()
            if (i1 - i0 < 2) continue
            val a = pts[i0]; val b = pts[i1]; val d = b - a; val l = d.length
            var worst = -1; var wd = tol
            for (i in i0 + 1 until i1) {
                val dist = if (l < 1e-9) pts[i].distanceTo(a) else abs((pts[i] - a) cross d) / l
                if (dist > wd) { wd = dist; worst = i }
            }
            if (worst >= 0) { keep[worst] = true; stack += i0 to worst; stack += worst to i1 }
        }
        return keep.indices.filter { keep[it] }
    }

    /**
     * @param tol how far (pixels) the stroke may wander from a straight line before a corner is assumed
     * @param minSegment shortest line kept (pixels); shorter pieces are finger wobble or the flick on lifting
     * @param start an existing point the stroke continues from; kept exactly
     */
    fun fit(points: List<StrokePoint>, tol: Double, minSegment: Double, start: Vec2? = null): StrokeFit? {
        var pts = points
        if (start != null) pts = listOf(StrokePoint(start.x, start.y, true)) + pts
        if (pts.size < 2) return null
        var breaks = douglasPeucker(smooth(pts.map { it.pos }, 3 * tol), tol).toMutableList()
        // drop the flick at the end and wobble at a free start; merge short pieces in the middle
        while (breaks.size > 2) {
            val last = breaks.size - 1
            if (pts[breaks[last]].pos.distanceTo(pts[breaks[last - 1]].pos) < minSegment) {
                pts = pts.subList(0, breaks[last - 1] + 1); breaks.removeAt(last); continue
            }
            if (start == null && pts[breaks[1]].pos.distanceTo(pts[breaks[0]].pos) < minSegment) {
                val cut = breaks[1]
                pts = pts.subList(cut, pts.size); breaks = breaks.drop(1).map { it - cut }.toMutableList(); continue
            }
            val short = (1 until breaks.size - 2).firstOrNull { pts[breaks[it + 1]].pos.distanceTo(pts[breaks[it]].pos) < minSegment }
            if (short != null) { breaks.removeAt(short + 1); continue }
            if (start != null && breaks.size > 2 && pts[breaks[1]].pos.distanceTo(pts[breaks[0]].pos) < minSegment) { breaks.removeAt(1); continue }
            break
        }
        if (pts.first().pos.distanceTo(pts.last().pos) < 1e-6) return null

        fun lineFor(i0: Int, i1: Int, firstSeg: Boolean, lastSeg: Boolean): Line {
            val a = pts[i0].pos; val b = pts[i1].pos
            val chord = Line(a, (b - a).normalized())
            // points close to a corner are rounded by the finger; leave them out of the fit
            val trim = minOf(4 * tol, a.distanceTo(b) * 0.25)
            var use = (i0..i1).map { pts[it].pos }.filter { p ->
                (firstSeg || p.distanceTo(a) > trim) && (lastSeg || p.distanceTo(b) > trim)
            }
            if (use.size < 3) use = (i0..i1).map { pts[it].pos }
            var line = fitLine(use) ?: return chord
            val inliers = use.filter { line.dist(it) <= 2 * tol }
            if (inliers.size >= 3 && inliers.size < use.size) line = fitLine(inliers) ?: line
            return if ((line.d dot chord.d) < 0) Line(line.p, line.d * -1.0) else line
        }
        var lines = (0 until breaks.size - 1).map { lineFor(breaks[it], breaks[it + 1], it == 0, it == breaks.size - 2) }
        // a finger rounds a corner into a short chamfer: if the lines either side of a short piece cross right there,
        // the piece is the corner itself, not a wall (a real jog has parallel neighbours and is kept)
        var c = 1
        while (c < lines.size - 1) {
            val a = pts[breaks[c]].pos; val b = pts[breaks[c + 1]].pos
            val x = intersect(lines[c - 1], lines[c + 1])
            if (a.distanceTo(b) < 3 * minSegment && x != null && x.distanceTo((a + b) * 0.5) < 3 * minSegment) {
                val mid = (breaks[c] + breaks[c + 1]) / 2
                breaks.removeAt(c + 1); breaks[c] = mid
                lines = (0 until breaks.size - 1).map { lineFor(breaks[it], breaks[it + 1], it == 0, it == breaks.size - 2) }
            } else c++
        }
        // neighbours that turn by less than ~7° are one line
        var k = 0
        while (k < lines.size - 1) {
            if (abs(lines[k].d cross lines[k + 1].d) < 0.12 && (lines[k].d dot lines[k + 1].d) > 0) {
                breaks.removeAt(k + 1)
                lines = (0 until breaks.size - 1).map { lineFor(breaks[it], breaks[it + 1], it == 0, it == breaks.size - 2) }
            } else k++
        }
        val verts = ArrayList<Vec2>()
        verts += start ?: lines.first().project(pts.first().pos)
        for (i in 0 until lines.size - 1) {
            val corner = pts[breaks[i + 1]].pos
            val x = intersect(lines[i], lines[i + 1])
            verts += if (x != null && x.distanceTo(corner) <= 6 * tol + minSegment) x else corner
        }
        verts += lines.last().project(pts.last().pos)
        val snapped = (0 until breaks.size - 1).map { s ->
            val seg = (breaks[s]..breaks[s + 1]).map { pts[it] }
            seg.count { it.snapped } >= 0.6 * seg.size
        }
        return StrokeFit(verts, snapped)
    }
}
