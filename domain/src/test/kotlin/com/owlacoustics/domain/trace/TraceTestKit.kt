package com.owlacoustics.domain.trace

import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

const val FT = 0.3048
const val IN = 0.0254

/**
 * A room and a person turning on the spot inside it. Coordinates follow the solver: the turning axis is the world
 * origin, z = 0 is camera height, the phone sits [pivot] metres in front of the axis.
 */
class SyntheticRoom(corners: List<Vec2>, at: Vec2, val ceiling: Double, val camHeight: Double, val pivot: Double) {
    /** Room corners counter-clockwise, relative to the turning axis. */
    val poly: List<Vec2> = corners.map { it - at }
    val zf = -camHeight
    val zc = ceiling - camHeight
    val lengths get() = poly.indices.map { poly[it].distanceTo(poly[(it + 1) % poly.size]) }

    fun cam(yaw: Double) = Vec3(pivot * cos(yaw), pivot * sin(yaw), 0.0)
    fun pose(yaw: Double, pitch: Double, roll: Double = 0.0) = CameraPose(Mat3.lookAt(yaw, pitch, roll), cam(yaw))

    /** What the phone records for point [p] when the user turns to face it. */
    fun observe(p: Vec3, noiseDeg: Double = 0.0, rnd: Random? = null, faceOffsetDeg: Double = 0.0): NewCorner {
        val yaw = atan2(p.y, p.x) + Math.toRadians(faceOffsetDeg)
        var ray = (p - cam(yaw)).normalized()
        if (rnd != null && noiseDeg > 0) {
            val s = Math.toRadians(noiseDeg)
            val az = atan2(ray.y, ray.x) + rnd.nextGaussian() * s / cos(kotlin.math.asin(ray.z)).coerceAtLeast(0.2)
            val el = kotlin.math.asin(ray.z) + rnd.nextGaussian() * s
            ray = Vec3(cos(el) * cos(az), cos(el) * sin(az), sin(el))
        }
        return NewCorner(ray, Vec2(cos(yaw), sin(yaw)))
    }

    fun floorCorner(i: Int) = Vec3(poly[i].x, poly[i].y, zf)
    fun ceilingCorner(i: Int) = Vec3(poly[i].x, poly[i].y, zc)

    /** A document with the floor (and optionally the ceiling) traced corner by corner. */
    fun traced(ceilingToo: Boolean = true, noiseDeg: Double = 0.0, rnd: Random? = null): TraceDoc {
        var d = TraceEdits.append(TraceDoc(), Surface.FLOOR, poly.indices.map { observe(floorCorner(it), noiseDeg, rnd) }, close = true)
        if (ceilingToo) d = TraceEdits.append(d, Surface.CEILING, poly.indices.map { observe(ceilingCorner(it), noiseDeg, rnd) }, close = true)
        return d
    }

    companion object {
        /** The persona room: 15' 0 1/8" × 11' 6 1/4". */
        val L = 180.125 * IN
        val W = 138.25 * IN
        fun persona(ceiling: Double = 8 * FT, camHeight: Double = 1.40, pivot: Double = 0.30, at: Vec2 = Vec2(L * 0.45, W * 0.55)) =
            SyntheticRoom(listOf(Vec2(0.0, 0.0), Vec2(L, 0.0), Vec2(L, W), Vec2(0.0, W)), at, ceiling, camHeight, pivot)
    }
}

fun Random.nextGaussian(): Double {
    // Box–Muller
    val u = nextDouble().coerceAtLeast(1e-12); val v = nextDouble()
    return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * cos(2 * Math.PI * v)
}

fun maxAbsDiff(a: List<Double>, b: List<Double>) = a.zip(b).maxOf { abs(it.first - it.second) }
