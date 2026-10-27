package com.owlacoustics.domain

import com.owlacoustics.domain.capturelab.DepthScale
import com.owlacoustics.domain.capturelab.FloorEdge
import com.owlacoustics.domain.capturelab.PolarOutline
import com.owlacoustics.domain.capturelab.PolarSample
import com.owlacoustics.domain.capturelab.Scorecard
import com.owlacoustics.domain.capturelab.TiltMath
import com.owlacoustics.domain.capturelab.WallLine
import com.owlacoustics.domain.capturelab.WallOutline
import com.owlacoustics.domain.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class CaptureLabMathTest {
    private val L = 180.125 * 0.0254 // 15' 1/8"
    private val W = 138.25 * 0.0254 // 11' 6 1/4"

    /** Ray-cast distance from [o] at angle [a] to the polygon boundary (independent reference). */
    private fun cast(poly: List<Vec2>, o: Vec2, a: Double): Double {
        val d = Vec2(cos(a), sin(a))
        var best = Double.MAX_VALUE
        for (i in poly.indices) {
            val p = poly[i]; val q = poly[(i + 1) % poly.size]; val e = q - p
            val den = d cross e
            if (kotlin.math.abs(den) < 1e-12) continue
            val t = ((p - o) cross e) / den; val u = ((p - o) cross d) / den
            if (t > 0 && u in 0.0..1.0) best = minOf(best, t)
        }
        return best
    }

    private fun samples(poly: List<Vec2>, o: Vec2, noise: Double, seed: Int = 1): List<PolarSample> {
        val r = Random(seed)
        return (0 until 360 step 2).map { deg ->
            val a = Math.toRadians(deg.toDouble())
            PolarSample(a, cast(poly, o, a) * (1 + (r.nextDouble() - 0.5) * 2 * noise))
        }
    }

    @Test fun tiltMath() {
        assertEquals(1.5, TiltMath.floorDistance(1.5, Math.toRadians(45.0))!!, 1e-12)
        assertNull(TiltMath.floorDistance(1.5, 0.0))
        // door 80" at 3 m from eye height 1.5 m
        val h = 1.5; val door = 80 * 0.0254; val d = 3.0
        val alpha = atan(h / d); val beta = atan((door - h) / d)
        assertEquals(h, TiltMath.eyeHeightFromDoor(door, alpha, beta)!!, 1e-12)
    }

    @Test fun polarFitRecoversPersonaRoom() {
        val room = listOf(Vec2(0.0, 0.0), Vec2(L, 0.0), Vec2(L, W), Vec2(0.0, W))
        val poly = PolarOutline.fit(samples(room, Vec2(L * 0.45, W * 0.55), 0.0))!!
        assertEquals(4, poly.size)
        val s = Scorecard.score(poly, Scorecard.rectangle(L, W))
        assertTrue("mean error ${s.meanAbsErrorM}", s.meanAbsErrorM!! < 0.01)
        // 2% noise (a plausible camera-estimate level, not a measured figure) still gives the right shape
        val noisy = PolarOutline.fit(samples(room, Vec2(L * 0.45, W * 0.55), 0.02))!!
        assertEquals(4, noisy.size)
        val sn = Scorecard.score(noisy, Scorecard.rectangle(L, W))
        assertTrue("noisy mean error ${sn.meanAbsErrorM}", sn.meanAbsErrorM!! < 0.15)
        assertTrue(sn.meanAngleErrorDeg!! < 5)
    }

    @Test fun polarFitLShape() {
        val l = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 2.5), Vec2(2.5, 2.5), Vec2(2.5, 5.0), Vec2(0.0, 5.0))
        // from a spot that sees every wall of this L
        val poly = PolarOutline.fit(samples(l, Vec2(1.2, 1.2), 0.0))
        assertNotNull(poly)
        assertEquals(6, poly!!.size)
    }

    @Test fun wallPlanesToOutline() {
        val lines = listOf(
            WallLine(L / 2, 0.0, 0.0, 1.0, 2.0), WallLine(L, W / 2, -1.0, 0.0, 1.5),
            WallLine(L / 2, W, 0.0, -1.0, 2.0), WallLine(0.0, W / 2, 1.0, 0.0, 1.5),
            WallLine(L / 2 + 0.6, 0.05, 0.02, 1.0, 0.8), // second patch of the same wall: merged
        )
        val poly = WallOutline.outline(lines, Vec2(L / 2, W / 2))!!
        assertEquals(4, poly.size)
        val s = Scorecard.score(poly, Scorecard.rectangle(L, W))
        assertTrue(s.meanAbsErrorM!! < 0.05)
    }

    @Test fun floorEdgeAndDepthScale() {
        val rows = DoubleArray(160) { if (it < 100) 180.0 else 90.0 }
        assertEquals(99.0, FloorEdge.detect(rows, 50)!!.toDouble(), 2.0)
        assertNull(FloorEdge.detect(DoubleArray(160) { 120.0 }, 50))
        // disparity = 2/z + 0.1 exactly
        val z = (1..20).map { 1.0 + it * 0.2 }
        val f = DepthScale.fit(z.map { 1 / it }, z.map { 2 / it + 0.1 })!!
        assertEquals(2.0, f.a, 1e-9); assertEquals(0.1, f.b, 1e-9)
        assertEquals(3.0, DepthScale.depth(f, 2 / 3.0 + 0.1)!!, 1e-9)
    }

    @Test fun scorecardAlignsRotatedAndReversedWalls() {
        val poly = listOf(Vec2(0.0, 0.0), Vec2(W, 0.0), Vec2(W, L + 0.05), Vec2(0.0, L + 0.05))
        val s = Scorecard.score(poly, Scorecard.rectangle(L, W))
        assertEquals(4, s.perWallErrorM.size)
        assertEquals(0.025, s.meanAbsErrorM!!, 1e-9)
        assertEquals(0.0, s.meanAngleErrorDeg!!, 1e-9)
    }
}
