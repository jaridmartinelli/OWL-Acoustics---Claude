package com.owlacoustics.domain.geometry

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Arithmetic robustness tolerance in metres (C-04). Not a physical accuracy statement. */
const val EPS = 1e-9
/** Minimum committed edge length in metres (C-04). */
const val MIN_EDGE = 1e-6

@Serializable
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun div(s: Double) = Vec2(x / s, y / s)
    operator fun unaryMinus() = Vec2(-x, -y)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    infix fun cross(o: Vec2) = x * o.y - y * o.x
    val length get() = hypot(x, y)
    fun normalized(): Vec2 { val l = length; return if (l < EPS) Vec2(0.0, 0.0) else Vec2(x / l, y / l) }
    /** Left perpendicular. */
    fun perp() = Vec2(-y, x)
    fun isFinite() = x.isFinite() && y.isFinite()
    fun distanceTo(o: Vec2) = (this - o).length
    fun rotated(rad: Double) = Vec2(x * cos(rad) - y * sin(rad), x * sin(rad) + y * cos(rad))
    companion object {
        fun fromBearingDeg(deg: Double) = Vec2(cos(Math.toRadians(deg)), sin(Math.toRadians(deg)))
    }
}

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length get() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 { val l = length; return if (l < EPS) this else this * (1.0 / l) }
    fun xy() = Vec2(x, y)
    fun distanceTo(o: Vec3) = (this - o).length
}

fun bearingDeg(v: Vec2): Double {
    var d = Math.toDegrees(atan2(v.y, v.x))
    if (d < 0) d += 360.0
    return d
}

object Polygons {
    /** Shoelace signed area; positive for counter-clockwise order. */
    fun signedArea(p: List<Vec2>): Double {
        var a = 0.0
        for (i in p.indices) {
            val q = p[i]; val r = p[(i + 1) % p.size]
            a += q.x * r.y - r.x * q.y
        }
        return a / 2.0
    }

    fun area(p: List<Vec2>) = abs(signedArea(p))

    fun perimeter(p: List<Vec2>): Double = p.indices.sumOf { p[it].distanceTo(p[(it + 1) % p.size]) }

    /** Proper or touching intersection test of segments ab and cd. */
    fun segmentsIntersect(a: Vec2, b: Vec2, c: Vec2, d: Vec2, eps: Double = EPS): Boolean {
        val d1 = orient(c, d, a); val d2 = orient(c, d, b)
        val d3 = orient(a, b, c); val d4 = orient(a, b, d)
        if (((d1 > eps && d2 < -eps) || (d1 < -eps && d2 > eps)) &&
            ((d3 > eps && d4 < -eps) || (d3 < -eps && d4 > eps))) return true
        if (abs(d1) <= eps && onSegment(c, d, a, eps)) return true
        if (abs(d2) <= eps && onSegment(c, d, b, eps)) return true
        if (abs(d3) <= eps && onSegment(a, b, c, eps)) return true
        if (abs(d4) <= eps && onSegment(a, b, d, eps)) return true
        return false
    }

    /** Strict crossing: interiors intersect at a single point not at any endpoint. */
    fun segmentsCrossProperly(a: Vec2, b: Vec2, c: Vec2, d: Vec2, eps: Double = 1e-12): Boolean {
        val d1 = orient(c, d, a); val d2 = orient(c, d, b)
        val d3 = orient(a, b, c); val d4 = orient(a, b, d)
        return ((d1 > eps && d2 < -eps) || (d1 < -eps && d2 > eps)) &&
            ((d3 > eps && d4 < -eps) || (d3 < -eps && d4 > eps))
    }

    fun orient(a: Vec2, b: Vec2, c: Vec2) = (b - a) cross (c - a)

    private fun onSegment(a: Vec2, b: Vec2, p: Vec2, eps: Double) =
        p.x >= min(a.x, b.x) - eps && p.x <= max(a.x, b.x) + eps &&
            p.y >= min(a.y, b.y) - eps && p.y <= max(a.y, b.y) + eps

    /** Returns indices (i, j) of the first pair of non-adjacent edges that intersect, or null. */
    fun firstSelfIntersection(p: List<Vec2>): Pair<Int, Int>? {
        val n = p.size
        for (i in 0 until n) {
            val a = p[i]; val b = p[(i + 1) % n]
            for (j in i + 1 until n) {
                if (j == i || (j + 1) % n == i || (i + 1) % n == j) continue
                val c = p[j]; val d = p[(j + 1) % n]
                if (segmentsIntersect(a, b, c, d)) return i to j
            }
        }
        // adjacent edges folding back onto each other
        for (i in 0 until n) {
            val a = p[(i + n - 1) % n]; val b = p[i]; val c = p[(i + 1) % n]
            val u = b - a; val v = c - b
            if (abs(u cross v) <= EPS * max(1.0, u.length * v.length) && (u dot v) < 0) return (i + n - 1) % n to i
        }
        return null
    }

    fun contains(p: List<Vec2>, q: Vec2): Boolean {
        var inside = false
        var j = p.size - 1
        for (i in p.indices) {
            val a = p[i]; val b = p[j]
            if ((a.y > q.y) != (b.y > q.y)) {
                val x = (b.x - a.x) * (q.y - a.y) / (b.y - a.y) + a.x
                if (q.x < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    fun distanceToSegment(q: Vec2, a: Vec2, b: Vec2): Double {
        val ab = b - a
        val l2 = ab dot ab
        if (l2 < EPS * EPS) return q.distanceTo(a)
        val t = ((q - a) dot ab / l2).coerceIn(0.0, 1.0)
        return q.distanceTo(a + ab * t)
    }

    fun distanceToBoundary(p: List<Vec2>, q: Vec2): Double =
        p.indices.minOf { distanceToSegment(q, p[it], p[(it + 1) % p.size]) }

    /** True when the open segment ab stays inside the polygon (does not cross a boundary edge). */
    fun segmentInside(p: List<Vec2>, a: Vec2, b: Vec2): Boolean {
        for (i in p.indices) {
            if (segmentsCrossProperly(a, b, p[i], p[(i + 1) % p.size])) return false
        }
        // a segment through a reflex vertex can leave the room without a proper crossing; sample midpoints
        val steps = 16
        for (k in 1 until steps) {
            val m = a + (b - a) * (k.toDouble() / steps)
            if (!contains(p, m) && distanceToBoundary(p, m) > 1e-7) return false
        }
        return true
    }

    fun isRightAngled(p: List<Vec2>, tolDeg: Double): Boolean {
        for (i in p.indices) {
            val a = p[(i + p.size - 1) % p.size]; val b = p[i]; val c = p[(i + 1) % p.size]
            val u = (a - b).normalized(); val v = (c - b).normalized()
            val ang = Math.toDegrees(kotlin.math.acos((u dot v).coerceIn(-1.0, 1.0)))
            if (abs(ang - 90.0) > tolDeg) return false
        }
        return true
    }

    /** Ear-clipping triangulation of a simple CCW polygon. Returns index triples. */
    fun triangulate(p: List<Vec2>): List<IntArray> {
        val n = p.size
        if (n < 3) return emptyList()
        val idx = ArrayList<Int>((0 until n).toList())
        if (signedArea(p) < 0) idx.reverse()
        val out = ArrayList<IntArray>()
        var guard = 0
        while (idx.size > 3 && guard < 10_000) {
            guard++
            var clipped = false
            for (k in idx.indices) {
                val i0 = idx[(k + idx.size - 1) % idx.size]; val i1 = idx[k]; val i2 = idx[(k + 1) % idx.size]
                val a = p[i0]; val b = p[i1]; val c = p[i2]
                if (orient(a, b, c) <= 1e-14) continue
                var any = false
                for (m in idx) {
                    if (m == i0 || m == i1 || m == i2) continue
                    if (pointInTriangle(p[m], a, b, c)) { any = true; break }
                }
                if (any) continue
                out += intArrayOf(i0, i1, i2)
                idx.removeAt(k)
                clipped = true
                break
            }
            if (!clipped) {
                // degenerate (collinear) vertex: drop it
                val k = idx.indices.firstOrNull { k ->
                    val a = p[idx[(k + idx.size - 1) % idx.size]]; val b = p[idx[k]]; val c = p[idx[(k + 1) % idx.size]]
                    abs(orient(a, b, c)) <= 1e-14
                } ?: break
                idx.removeAt(k)
            }
        }
        if (idx.size == 3) out += intArrayOf(idx[0], idx[1], idx[2])
        return out
    }

    private fun pointInTriangle(q: Vec2, a: Vec2, b: Vec2, c: Vec2): Boolean {
        val d1 = orient(a, b, q); val d2 = orient(b, c, q); val d3 = orient(c, a, q)
        return d1 >= -1e-14 && d2 >= -1e-14 && d3 >= -1e-14
    }

    /** Clips a convex polygon to the half-plane dot(p, n) >= c (Sutherland–Hodgman step). */
    fun clipHalfPlane(poly: List<Vec2>, n: Vec2, c: Double): List<Vec2> {
        if (poly.isEmpty()) return poly
        val out = ArrayList<Vec2>()
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val da = (a dot n) - c; val db = (b dot n) - c
            if (da >= 0) out += a
            if ((da >= 0) != (db >= 0)) {
                val t = da / (da - db)
                out += a + (b - a) * t
            }
        }
        return out
    }

    /** Intersection of infinite lines p + t·d and q + s·e; null if parallel. */
    fun lineIntersection(p: Vec2, d: Vec2, q: Vec2, e: Vec2): Vec2? {
        val den = d cross e
        if (abs(den) < 1e-12) return null
        val t = ((q - p) cross e) / den
        return p + d * t
    }

    /**
     * Inward offset of a simple CCW polygon by distance [d] via offset-edge intersection.
     * Returns null when the result is degenerate, self-intersecting or reversed.
     */
    fun inset(p: List<Vec2>, d: Double): List<Vec2>? {
        val n = p.size
        val ccw = if (signedArea(p) < 0) p.reversed() else p
        val lines = (0 until n).map { i ->
            val a = ccw[i]; val b = ccw[(i + 1) % n]
            val dir = (b - a).normalized()
            val inward = dir.perp()
            (a + inward * d) to dir
        }
        val out = ArrayList<Vec2>()
        for (i in 0 until n) {
            val prev = lines[(i + n - 1) % n]; val cur = lines[i]
            val x = lineIntersection(prev.first, prev.second, cur.first, cur.second) ?: (cur.first)
            out += x
        }
        if (signedArea(out) <= EPS) return null
        if (firstSelfIntersection(out) != null) return null
        for (i in 0 until n) {
            val origDir = ccw[(i + 1) % n] - ccw[i]
            val newDir = out[(i + 1) % n] - out[i]
            if ((origDir dot newDir) <= 0) return null
        }
        if (out.any { !contains(ccw, it) }) return null
        return out
    }
}
