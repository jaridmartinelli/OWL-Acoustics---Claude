package com.owlacoustics.capturelab.trace

import android.content.Context
import com.owlacoustics.capturelab.DepthEstimator
import com.owlacoustics.capturelab.UprightFrame
import com.owlacoustics.domain.geometry.Vec3
import com.owlacoustics.domain.trace.CameraPose
import com.owlacoustics.domain.trace.EdgePrefer
import com.owlacoustics.domain.trace.EdgeSnap
import com.owlacoustics.domain.trace.Pinhole
import com.owlacoustics.domain.trace.Surface
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/** A direction in the room where the floor (or ceiling) was seen to meet a wall. */
class AutoSample(val surface: Surface, val ray: Vec3)

class AutoResult(val samples: List<AutoSample>, val floorSeen: Boolean, val ms: Long)

/**
 * Finds where the floor and the ceiling meet the walls in one picture, without help from the user.
 *
 * The on-device depth model gives relative depth. Gravity is known from the motion sensor, so the picture's lower
 * part is searched for the one depth scaling under which many pixels lie on a level plane below the camera: that
 * is the floor. Every pixel then has a height above the floor. Going up each column, the floor ends where the
 * surface turns upright; it counts as a wall only if it keeps rising to about camera height (a bed or a couch does
 * not). Where the upright surface gives way to a level one overhead, the ceiling begins. Each boundary is then
 * moved onto the nearest picture edge. Unverified on a phone.
 */
class AutoDetector(context: Context) : AutoCloseable {
    private val depth = runCatching { DepthEstimator(context) }.getOrNull()
    val available get() = depth != null
    private val rnd = Random(7)

    override fun close() { depth?.close() }

    fun detect(f: TraceFrame, pose: CameraPose): AutoResult {
        val t0 = System.currentTimeMillis()
        val d = depth ?: return AutoResult(emptyList(), false, 0)
        val w = DepthEstimator.W; val h = DepthEstimator.H
        val disp = d.run(UprightFrame(f.lumW, f.lumH, f.gray(), 0.0, t0))
        val k = f.k.scaled(w.toDouble() / f.k.w)
        val step = 4
        val cols = (step / 2 until w step step).toList()
        // per sampled pixel: camera-forward share of the ray and the ray in the room
        val cz = Array(cols.size) { DoubleArray(h) }
        val rx = Array(cols.size) { DoubleArray(h) }; val ry = Array(cols.size) { DoubleArray(h) }; val rz = Array(cols.size) { DoubleArray(h) }
        for ((ci, x) in cols.withIndex()) for (y in 0 until h) {
            val c = Pinhole.cameraRay(k, x.toDouble(), y.toDouble()); val r = pose.r * c
            cz[ci][y] = c.z; rx[ci][y] = r.x; ry[ci][y] = r.y; rz[ci][y] = r.z
        }
        // floor hypothesis: disparity = a · (1 / forward depth of a floor point one unit below the camera) + b
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (ci in cols.indices) for (y in (h * 0.55).toInt() until h step 2) {
            if (rz[ci][y] > -0.14) continue
            xs += -rz[ci][y] / cz[ci][y]; ys += disp[y * w + cols[ci]].toDouble()
        }
        if (xs.size < 60) return AutoResult(emptyList(), false, System.currentTimeMillis() - t0)
        val span = (ys.max() - ys.min()).coerceAtLeast(1e-6)
        var bestA = 0.0; var bestB = 0.0; var bestIn = 0
        repeat(80) {
            val i = rnd.nextInt(xs.size); val j = rnd.nextInt(xs.size)
            if (abs(xs[i] - xs[j]) < 1e-3) return@repeat
            val a = (ys[i] - ys[j]) / (xs[i] - xs[j]); val b = ys[i] - a * xs[i]
            if (a <= 0) return@repeat
            var n = 0
            for (q in xs.indices) if (abs(ys[q] - a * xs[q] - b) < 0.03 * span) n++
            if (n > bestIn) { bestIn = n; bestA = a; bestB = b }
        }
        if (bestIn < 0.25 * xs.size) return AutoResult(emptyList(), false, System.currentTimeMillis() - t0)
        // least squares on the inliers
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var n = 0
        for (q in xs.indices) if (abs(ys[q] - bestA * xs[q] - bestB) < 0.03 * span) { sx += xs[q]; sy += ys[q]; sxx += xs[q] * xs[q]; sxy += xs[q] * ys[q]; n++ }
        val den = n * sxx - sx * sx
        val a = if (abs(den) > 1e-9) (n * sxy - sx * sy) / den else bestA
        val b = if (abs(den) > 1e-9) (sy - a * sx) / n else bestB
        if (a <= 0) return AutoResult(emptyList(), false, System.currentTimeMillis() - t0)

        val map = f.edgeMap()
        val toLum = f.lumW.toDouble() / w
        fun refined(x: Int, y: Int, prefer: EdgePrefer): Vec3 {
            val sp = EdgeSnap.along(map, x * toLum, y * toLum, 1.0, 0.0, 7.0, prefer)
            val u = (if (sp.snapped) sp.x else x * toLum) / f.lumScale; val v = (if (sp.snapped) sp.y else y * toLum) / f.lumScale
            return Pinhole.worldRay(f.k, pose, u, v)
        }
        val out = ArrayList<AutoSample>()
        for ((ci, x) in cols.withIndex()) {
            fun t(y: Int): Double { val e = disp[y * w + x] - b; return if (e <= 1e-6) Double.NaN else (a / e) / cz[ci][y] }
            // top of the floor in this column
            var yf = -1; var miss = 0
            for (y in h - 1 downTo 0) {
                val tt = t(y)
                val height = if (tt.isNaN()) Double.NaN else 1 + tt * rz[ci][y]
                val range = if (tt.isNaN()) 0.0 else tt * hypot(rx[ci][y], ry[ci][y])
                if (!height.isNaN() && abs(height) < 0.08 + 0.04 * range && rz[ci][y] < -0.03) { yf = y; miss = 0 }
                else if (yf >= 0) { if (++miss >= 4) break }
                else if (y < h * 0.35) break
            }
            if (yf < 0) continue
            val rf = t(yf) * hypot(rx[ci][yf], ry[ci][yf])
            if (rf.isNaN() || rf < 0.2 || rf > 12) continue
            var wallRows = 0; var hMax = 0.0; var lastWall = yf; var overhead = 0; var yc = -1
            for (y in yf - 1 downTo 0) {
                val tt = t(y); if (tt.isNaN()) continue
                val height = 1 + tt * rz[ci][y]; val range = tt * hypot(rx[ci][y], ry[ci][y])
                if (height > 0.12 && abs(range - rf) < 0.2 * rf) { wallRows++; if (height > hMax) hMax = height; lastWall = y; overhead = 0 }
                else if (range < 0.8 * rf && height > 0.5 && rz[ci][y] > 0.03) { if (++overhead >= 3) { yc = lastWall; break } }
            }
            val rows = (yf - lastWall).coerceAtLeast(1)
            val steady = wallRows >= 0.5 * rows
            val tall = hMax >= 0.9 || (lastWall <= 3 && wallRows >= 6)
            if (!steady || !tall) continue
            out += AutoSample(Surface.FLOOR, refined(x, yf, EdgePrefer.LOWER))
            if (yc in 1 until h && rz[ci][yc] > 0.05) out += AutoSample(Surface.CEILING, refined(x, yc, EdgePrefer.UPPER))
        }
        return AutoResult(out, true, System.currentTimeMillis() - t0)
    }
}
