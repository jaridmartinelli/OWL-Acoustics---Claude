package com.owlacoustics.domain.capturelab

import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Geometry for the Capture Lab test app: turns camera angles, detected planes and depth into room outlines,
 * and scores them against tape-measured truth. Experimental; accuracy must be measured on real rooms.
 */
object TiltMath {
    /** Horizontal distance to a floor point seen [depression] radians below the horizon from [eyeHeight] metres. */
    fun floorDistance(eyeHeight: Double, depression: Double): Double? {
        if (eyeHeight <= 0 || depression <= Math.toRadians(2.0) || depression >= Math.toRadians(89.0)) return null
        return eyeHeight / tan(depression)
    }

    /** Floor point (plan metres, user at origin) for an aim at [depression] below horizon and heading [yaw]. */
    fun floorPoint(eyeHeight: Double, depression: Double, yaw: Double): Vec2? =
        floorDistance(eyeHeight, depression)?.let { Vec2(cos(yaw) * it, sin(yaw) * it) }

    /** Eye height from aiming at a door's bottom (depression α) and top (elevation β) of known height. */
    fun eyeHeightFromDoor(doorHeight: Double, depressionBottom: Double, elevationTop: Double): Double? {
        if (doorHeight <= 0 || depressionBottom <= Math.toRadians(1.0)) return null
        val h = doorHeight / (1 + tan(elevationTop) / tan(depressionBottom))
        return if (h.isFinite() && h > 0.2 && h < doorHeight) h else null
    }

    /** Elevation of an image row (positive up) given the optical-axis depression, row offset from centre and focal length in px. */
    fun rowElevation(axisDepression: Double, rowFromCenter: Double, focalPx: Double) = -(axisDepression + atan(rowFromCenter / focalPx))
}

@Serializable
data class PolarSample(val yaw: Double, val distance: Double)

/** Builds a room outline from distance samples taken while turning on the spot (methods 2 and 6). */
object PolarOutline {
    fun points(samples: List<PolarSample>): List<Vec2> =
        samples.sortedBy { norm(it.yaw) }.map { Vec2(cos(it.yaw) * it.distance, sin(it.yaw) * it.distance) }

    fun norm(a: Double): Double { var x = a % (2 * Math.PI); if (x < 0) x += 2 * Math.PI; return x }

    /** Coverage of the turn in degrees (5° bins). */
    fun coverageDeg(samples: List<PolarSample>): Int = samples.map { (Math.toDegrees(norm(it.yaw)) / 5).toInt() }.toSet().size * 5

    private data class Line(val p: Vec2, val d: Vec2)

    private fun fitLine(pts: List<Vec2>): Line {
        val c = pts.reduce { a, b -> a + b } / pts.size.toDouble()
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (q in pts) { val d = q - c; sxx += d.x * d.x; sxy += d.x * d.y; syy += d.y * d.y }
        val ang = 0.5 * atan2(2 * sxy, sxx - syy)
        return Line(c, Vec2(cos(ang), sin(ang)))
    }

    private fun dist(q: Vec2, a: Vec2, b: Vec2) = Polygons.distanceToSegment(q, a, b)

    /**
     * Split-and-merge on the closed ring of points: split where points deviate more than [tol] metres from the chord,
     * fit a least-squares line to each run, merge nearly collinear neighbours, then intersect consecutive lines.
     */
    fun fit(samples: List<PolarSample>, tol: Double = 0.12, minRun: Int = 4, mergeDeg: Double = 10.0): List<Vec2>? {
        val pts = points(samples)
        if (pts.size < 12) return null
        // smooth the ring with a 3-point median on distance to drop single outliers
        val n = pts.size
        val sm = (0 until n).map { i ->
            val a = pts[(i + n - 1) % n].length; val b = pts[i].length; val c = pts[(i + 1) % n].length
            val med = listOf(a, b, c).sorted()[1]
            pts[i].normalized() * med
        }
        // start from the two points farthest apart, split recursively
        var i0 = 0; var i1 = 0; var best = -1.0
        for (i in sm.indices) for (j in i + 1 until sm.size) { val d = sm[i].distanceTo(sm[j]); if (d > best) { best = d; i0 = i; i1 = j } }
        val breaks = sortedSetOf(i0, i1)
        fun split(a: Int, b: Int) {
            // indices along the ring from a to b (exclusive of wrap handling via modulo)
            val len = ((b - a) % n + n) % n
            if (len < 2) return
            var far = -1; var fd = 0.0
            for (k in 1 until len) { val idx = (a + k) % n; val d = dist(sm[idx], sm[a], sm[b]); if (d > fd) { fd = d; far = idx } }
            if (fd > tol && far >= 0) { breaks += far; split(a, far); split(far, b) }
        }
        split(i0, i1); split(i1, i0)
        val bl = breaks.toList()
        var runs = bl.indices.map { k ->
            val a = bl[k]; val b = bl[(k + 1) % bl.size]
            val len = ((b - a) % n + n) % n
            (0..len).map { sm[(a + it) % n] }
        }.filter { it.size >= minRun }
        if (runs.size < 3) return null
        var lines = runs.map { fitLine(it) }
        // merge collinear neighbours
        var changed = true
        while (changed && lines.size > 3) {
            changed = false
            for (k in lines.indices) {
                val a = lines[k]; val b = lines[(k + 1) % lines.size]
                val ang = Math.toDegrees(kotlin.math.acos(abs(a.d dot b.d).coerceAtMost(1.0)))
                val offset = abs((b.p - a.p) cross a.d)
                if (ang < mergeDeg && offset < tol * 1.5) {
                    val merged = runs[k] + runs[(k + 1) % runs.size]
                    val nr = runs.toMutableList(); val nl = lines.toMutableList()
                    val j = (k + 1) % lines.size
                    nr[k] = merged; nl[k] = fitLine(merged)
                    nr.removeAt(j); nl.removeAt(j)
                    runs = nr; lines = nl; changed = true
                    break
                }
            }
        }
        if (lines.size < 3) return null
        val corners = lines.indices.mapNotNull { k ->
            val a = lines[k]; val b = lines[(k + 1) % lines.size]
            Polygons.lineIntersection(a.p, a.d, b.p, b.d)
        }
        if (corners.size != lines.size) return null
        val out = if (Polygons.signedArea(corners) < 0) corners.reversed() else corners
        if (Polygons.firstSelfIntersection(out) != null) return null
        return out
    }
}

/** A detected vertical plane reduced to its line on the floor (method 5). */
@Serializable
data class WallLine(val px: Double, val py: Double, val nx: Double, val ny: Double, val extent: Double) {
    val point get() = Vec2(px, py)
    val normal get() = Vec2(nx, ny).normalized()
    val dir get() = normal.perp()
}

object WallOutline {
    /** Merges planes that are the same wall (normals within [deg], offsets within [tol] m), weighting by extent. */
    fun merge(lines: List<WallLine>, deg: Double = 12.0, tol: Double = 0.2): List<WallLine> {
        val out = ArrayList<WallLine>()
        for (l in lines.sortedByDescending { it.extent }) {
            val m = out.indexOfFirst { o ->
                (o.normal dot l.normal) > cos(Math.toRadians(deg)) && abs((l.point - o.point) dot o.normal) < tol
            }
            if (m < 0) out += l else {
                val o = out[m]; val w = o.extent + l.extent
                val p = (o.point * o.extent + l.point * l.extent) / w
                val nn = (o.normal * o.extent + l.normal * l.extent).normalized()
                out[m] = WallLine(p.x, p.y, nn.x, nn.y, w)
            }
        }
        return out
    }

    /** Orders walls around [center] and intersects neighbours. Returns null when they do not form a closed outline. */
    fun outline(lines: List<WallLine>, center: Vec2): List<Vec2>? {
        val walls = merge(lines)
        if (walls.size < 3) return null
        val ordered = walls.sortedBy { atan2(it.py - center.y, it.px - center.x) }
        val pts = ordered.indices.mapNotNull { k ->
            val a = ordered[k]; val b = ordered[(k + 1) % ordered.size]
            if (abs(a.dir cross b.dir) < 0.17) null else Polygons.lineIntersection(a.point, a.dir, b.point, b.dir)
        }
        if (pts.size != ordered.size) return null
        val ccw = if (Polygons.signedArea(pts) < 0) pts.reversed() else pts
        if (Polygons.firstSelfIntersection(ccw) != null) return null
        return ccw
    }
}

/** Floor–wall boundary in a central image strip (method 2): strongest brightness change below the horizon. */
object FloorEdge {
    /** [rowMeans] mean luminance per row (top to bottom). Returns the row index or null if no clear edge. */
    fun detect(rowMeans: DoubleArray, fromRow: Int, minStep: Double = 6.0): Int? {
        var best = -1; var bv = 0.0
        for (r in max(fromRow, 3) until rowMeans.size - 3) {
            val g = abs(rowMeans[r + 2] + rowMeans[r + 3] - rowMeans[r - 2] - rowMeans[r - 3]) / 2
            if (g > bv) { bv = g; best = r }
        }
        return if (best >= 0 && bv >= minStep) best else null
    }
}

/**
 * Metric scale for relative AI depth (method 6). The model outputs relative disparity; floor pixels with known
 * distance (from eye height and tilt) fit disparity ≈ a / z + b, which then converts the wall's disparity to metres.
 */
object DepthScale {
    data class Fit(val a: Double, val b: Double, val r2: Double)

    fun fit(invZ: List<Double>, disparity: List<Double>): Fit? {
        val n = invZ.size
        if (n < 8 || n != disparity.size) return null
        val mx = invZ.average(); val my = disparity.average()
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (i in 0 until n) { val dx = invZ[i] - mx; val dy = disparity[i] - my; sxx += dx * dx; sxy += dx * dy; syy += dy * dy }
        if (sxx < 1e-12 || syy < 1e-12) return null
        val a = sxy / sxx; val b = my - a * mx
        val r2 = sxy * sxy / (sxx * syy)
        return if (a > 0) Fit(a, b, r2) else null
    }

    fun depth(f: Fit, disparity: Double): Double? {
        val d = disparity - f.b
        if (d <= 1e-9) return null
        val z = f.a / d
        return if (z.isFinite() && z > 0.2 && z < 15) z else null
    }
}

@Serializable
data class Truth(val lengthsM: List<Double>, val rectangle: Boolean)

@Serializable
data class Score(
    val wallCountFound: Int,
    val wallCountTrue: Int,
    val perWallErrorM: List<Double>,
    val meanAbsErrorM: Double?,
    val maxAbsErrorM: Double?,
    val meanAngleErrorDeg: Double?,
    val areaErrorPct: Double?,
    val perimeterErrorPct: Double,
)

object Scorecard {
    fun wallLengths(p: List<Vec2>) = p.indices.map { p[it].distanceTo(p[(it + 1) % p.size]) }

    fun interiorAngles(p: List<Vec2>): List<Double> {
        val n = p.size
        return (0 until n).map { i ->
            val a = p[(i + n - 1) % n]; val v = p[i]; val b = p[(i + 1) % n]
            val u = a - v; val w = b - v
            var ang = -Math.toDegrees(atan2(u cross w, u dot w))
            if (ang < 0) ang += 360.0
            ang
        }
    }

    /** Compares a found outline with tape-measured truth: walls aligned by the best rotation/direction. */
    fun score(poly: List<Vec2>, truth: Truth): Score {
        val found = wallLengths(poly)
        val t = truth.lengthsM
        var bestErr: List<Double>? = null
        if (found.size == t.size && t.isNotEmpty()) {
            for (dir in listOf(1, -1)) for (k in t.indices) {
                val errs = found.indices.map { i -> found[i] - t[((dir * i + k) % t.size + t.size) % t.size] }
                if (bestErr == null || errs.sumOf { abs(it) } < bestErr.sumOf { abs(it) }) bestErr = errs
            }
        }
        val angles = interiorAngles(poly)
        val angleErr = if (truth.rectangle) angles.map { abs(min(abs(it - 90), abs(it - 270))) }.average() else null
        val trueArea = if (truth.rectangle && t.size == 4) t[0] * t[1] else null
        val area = Polygons.area(poly)
        val per = found.sum(); val tper = t.sum()
        return Score(
            found.size, t.size, bestErr ?: emptyList(),
            bestErr?.map { abs(it) }?.average(), bestErr?.maxOf { abs(it) },
            angleErr,
            trueArea?.let { (area - it) / it * 100 },
            if (tper > 0) (per - tper) / tper * 100 else 0.0,
        )
    }

    /** Rectangle truth from two sides. */
    fun rectangle(length: Double, width: Double) = Truth(listOf(length, width, length, width), true)

    fun rms(xs: List<Double>) = sqrt(xs.sumOf { it * it } / max(1, xs.size))
}
