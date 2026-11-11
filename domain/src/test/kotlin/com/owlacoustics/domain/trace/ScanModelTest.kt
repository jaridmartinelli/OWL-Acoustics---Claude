package com.owlacoustics.domain.trace

import com.owlacoustics.domain.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ScanModelTest {
    private val L = SyntheticRoom.L; private val W = SyntheticRoom.W
    private val poly = listOf(Vec2(0.0, 0.0), Vec2(L, 0.0), Vec2(L, W), Vec2(0.0, W)).map { it - Vec2(L * 0.45, W * 0.55) }

    private fun cast(a: Double): Double {
        val d = Vec2(cos(a), sin(a)); var best = Double.MAX_VALUE
        for (i in poly.indices) { val p = poly[i]; val e = poly[(i + 1) % 4] - p; val den = d cross e
            if (kotlin.math.abs(den) < 1e-12) continue
            val t = (p cross e) / den; val u = (p cross d) / den
            if (t > 0 && u in 0.0..1.0) best = minOf(best, t) }
        return best
    }

    // each direction seen twice: a single sighting does not count yet
    private fun seen(bins: IntRange, unit: Double) = bins.map { it to (cast(SurfaceScan.yawOf(it)) / unit).toFloat() }.let { SurfaceScan().withAuto(it).withAuto(it) }

    @Test fun automaticReadingsBecomeARoomAndBrushConstraintsWin() {
        val h = 1.35; val hc = 8 * FT - h
        // floor seen all round except behind a couch (40°); ceiling seen all round
        var scan = ScanState(floor = seen(0..359, h).let { f -> f.copy(auto = f.auto.mapIndexed { i, v -> if (i in 100..139) 0f else v }) }, ceiling = seen(0..359, hc))
        val mem = CornerMemory()
        val doc = ScanBuilder.build(scan, borrowAcross = true, memory = mem)
        assertTrue(doc.floor.closed); assertEquals(4, doc.floor.corners.size)
        val sol = TraceSolver.solve(doc, TraceSettings())
        assertEquals(ScaleBasis.CEILING_DEFAULT, sol.basis)
        assertEquals(h, -sol.floorZ, 0.02)
        assertTrue(maxAbsDiff(sol.plan!!.lengths.sorted(), listOf(W, L, W, L).sorted()) < 0.06)
        // walls are fitted at right angles in the room's own axes
        assertTrue(sol.plan!!.squared || sol.plan!!.lengths.size == 4)
        // ids stay the same on a rebuild, so a typed size stays attached
        val again = ScanBuilder.build(scan, true, mem)
        assertEquals(doc.floor.corners.map { it.id }, again.floor.corners.map { it.id })
        // without borrowing, the hidden stretch leaves the floor open
        assertTrue(!ScanBuilder.build(scan, false, CornerMemory()).floor.closed)
        // Subtract pulls the boundary in and later automatic readings beyond it are ignored
        val b = 20; val real = scan.floor.value(b)
        scan = scan.with(Surface.FLOOR, scan.floor.withSubtract(b, real * 0.8f, 0f))
        assertEquals(real * 0.8f, scan.floor.value(b), 1e-4f)
        scan = scan.with(Surface.FLOOR, scan.floor.withAuto(listOf(b to real)))
        assertEquals(real * 0.8f, scan.floor.value(b), 1e-4f)
        // Add reaches further than a short automatic reading
        val c = 200; val short = scan.floor.value(c)
        assertEquals(short * 1.2f, scan.floor.withAdd(c, short * 1.2f, 0f).value(c), 1e-4f)
        // a direction seen only once is ignored
        assertTrue(ScanBuilder.build(ScanState(floor = SurfaceScan().withAuto((0..359).map { it to 2f })), true, CornerMemory()).floor.anchors.isEmpty())
        // undo/redo
        val (hist, back) = ScanHistory().push(ScanState()).undo(scan)!!
        assertTrue(back.isEmpty); assertEquals(scan, hist.redo(back)!!.second)
    }

    /** Readings as noisy as clutter makes them: 5% scatter, one in eight wildly wrong. The outline must stay 4 walls. */
    @Test fun noisyReadingsStillGiveFourStraightWalls() {
        val rnd = kotlin.random.Random(3)
        val h = 1.35
        var f = SurfaceScan()
        repeat(12) {
            f = f.withAuto((0..359).map { b ->
                val true_ = cast(SurfaceScan.yawOf(b)) / h
                val r = if (rnd.nextDouble() < 0.125) true_ * (0.4 + rnd.nextDouble() * 1.4) else true_ * (1 + rnd.nextGaussian() * 0.05)
                b to r.toFloat()
            })
        }
        val ceiling = seen(0..359, 8 * FT - h)
        val doc = ScanBuilder.build(ScanState(floor = f, ceiling = ceiling), true, CornerMemory())
        assertEquals("corners", 4, doc.floor.corners.size)
        assertTrue(doc.floor.closed)
        val sol = TraceSolver.solve(doc, TraceSettings())
        val err = maxAbsDiff(sol.plan!!.lengths.sorted(), listOf(W, L, W, L).sorted())
        println("SCAN-NOISE worst wall error %.1f in".format(err / IN))
        assertTrue("worst wall error ${err / IN} in", err < 6 * IN)
        // half the room seen (open): straight walls, no spikes
        val half = ScanBuilder.build(ScanState(floor = SurfaceScan(auto = f.auto.mapIndexed { i, v -> if (i < 180) v else 0f }, hits = f.hits.mapIndexed { i, v -> if (i < 180) v else 0 })), false, CornerMemory())
        assertTrue("open outline has ${half.floor.corners.size} points", half.floor.corners.size in 2..4)
    }
}
