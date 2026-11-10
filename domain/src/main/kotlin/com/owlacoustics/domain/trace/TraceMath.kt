package com.owlacoustics.domain.trace

import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Row-major 3×3 matrix. Used as a rotation taking camera coordinates to world coordinates. */
class Mat3(private val m: DoubleArray) {
    init { require(m.size == 9) }
    operator fun get(r: Int, c: Int) = m[r * 3 + c]
    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z, m[6] * v.x + m[7] * v.y + m[8] * v.z)
    /** Inverse rotation (world → camera). */
    fun transposeTimes(v: Vec3) = Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z, m[1] * v.x + m[4] * v.y + m[7] * v.z, m[2] * v.x + m[5] * v.y + m[8] * v.z)
    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) r[i * 3 + j] = this[i, 0] * o[0, j] + this[i, 1] * o[1, j] + this[i, 2] * o[2, j]
        return Mat3(r)
    }
    fun col(c: Int) = Vec3(m[c], m[3 + c], m[6 + c])
    fun toArray() = m.copyOf()

    companion object {
        val identity = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        fun rotZ(a: Double) = Mat3(doubleArrayOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0))
        fun ofColumns(x: Vec3, y: Vec3, z: Vec3) = Mat3(doubleArrayOf(x.x, y.x, z.x, x.y, y.y, z.y, x.z, y.z, z.z))

        /**
         * Camera → world rotation for a camera whose optical axis points along azimuth [yaw] (counter-clockwise from
         * world +x), raised by [pitch] above the horizon, rolled by [roll] about the axis.
         * Camera axes: x right, y down, z forward. World: z up.
         */
        fun lookAt(yaw: Double, pitch: Double, roll: Double = 0.0): Mat3 {
            val f = Vec3(cos(pitch) * cos(yaw), cos(pitch) * sin(yaw), sin(pitch))
            val r0 = Vec3(sin(yaw), -cos(yaw), 0.0)
            val d0 = f cross r0
            val r = r0 * cos(roll) + d0 * sin(roll)
            val d = d0 * cos(roll) - r0 * sin(roll)
            return ofColumns(r, d, f)
        }
    }
}

/** Pinhole intrinsics of an upright image: focal length and principal point in pixels. Lens distortion is not modelled. */
data class Intrinsics(val f: Double, val cx: Double, val cy: Double, val w: Int, val h: Int) {
    fun scaled(s: Double) = Intrinsics(f * s, cx * s, cy * s, (w * s).toInt(), (h * s).toInt())
}

/** Camera → world rotation [r] and camera position [t] in world metres (z up). */
class CameraPose(val r: Mat3, val t: Vec3) {
    val forward get() = r.col(2)

    /**
     * Horizontal direction the phone is "facing", stable when the camera looks straight up or down: the optical axis
     * is blended with the phone's top edge, which points forward when the phone is tipped down and backward when
     * tipped up.
     */
    fun heading(): Vec2 {
        val f = r.col(2)
        val up = r.col(1) * -1.0
        val v = Vec2(f.x - up.x * f.z, f.y - up.y * f.z)
        return if (v.length < 1e-6) Vec2(1.0, 0.0) else v.normalized()
    }
}

object Pinhole {
    /** Unit ray in camera coordinates through pixel ([u], [v]). */
    fun cameraRay(k: Intrinsics, u: Double, v: Double): Vec3 {
        val x = (u - k.cx) / k.f; val y = (v - k.cy) / k.f
        val n = sqrt(x * x + y * y + 1.0)
        return Vec3(x / n, y / n, 1.0 / n)
    }

    fun worldRay(k: Intrinsics, pose: CameraPose, u: Double, v: Double): Vec3 = pose.r * cameraRay(k, u, v)

    fun toCamera(pose: CameraPose, p: Vec3): Vec3 = pose.r.transposeTimes(p - pose.t)

    private fun px(k: Intrinsics, c: Vec3) = Vec2(k.cx + k.f * c.x / c.z, k.cy + k.f * c.y / c.z)

    /** Pixel of world point [p], or null when it is behind the camera. */
    fun project(k: Intrinsics, pose: CameraPose, p: Vec3, near: Double = 0.05): Vec2? {
        val c = toCamera(pose, p)
        return if (c.z < near) null else px(k, c)
    }

    /** Pixel a world direction points at (a point infinitely far away), or null when it points behind the camera. */
    fun projectDirection(k: Intrinsics, pose: CameraPose, d: Vec3): Vec2? {
        val c = pose.r.transposeTimes(d)
        return if (c.z < 1e-3) null else px(k, c)
    }

    /** Projects a world segment, clipping the part behind the camera. Null when nothing is in front. */
    fun projectSegment(k: Intrinsics, pose: CameraPose, a: Vec3, b: Vec3, near: Double = 0.05): Pair<Vec2, Vec2>? {
        var ca = toCamera(pose, a); var cb = toCamera(pose, b)
        if (ca.z < near && cb.z < near) return null
        if (ca.z < near) ca = ca + (cb - ca) * ((near - ca.z) / (cb.z - ca.z))
        else if (cb.z < near) cb = cb + (ca - cb) * ((near - cb.z) / (ca.z - cb.z))
        return px(k, ca) to px(k, cb)
    }

    /** Projects a world polygon, clipping against the near plane (Sutherland–Hodgman). Empty when nothing is in front. */
    fun projectPolygon(k: Intrinsics, pose: CameraPose, poly: List<Vec3>, near: Double = 0.05): List<Vec2> {
        if (poly.size < 3) return emptyList()
        val cam = poly.map { toCamera(pose, it) }
        val out = ArrayList<Vec3>(cam.size + 2)
        for (i in cam.indices) {
            val p = cam[i]; val q = cam[(i + 1) % cam.size]
            val pin = p.z >= near; val qin = q.z >= near
            if (pin) out += p
            if (pin != qin) out += p + (q - p) * ((near - p.z) / (q.z - p.z))
        }
        return if (out.size < 3) emptyList() else out.map { px(k, it) }
    }
}
