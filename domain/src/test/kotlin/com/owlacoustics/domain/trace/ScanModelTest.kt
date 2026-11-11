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

    private fun seen(bins: IntRange, unit: Double) = SurfaceScan().withAuto(bins.map { it to (cast(SurfaceScan.yawOf(it)) / unit).toFloat() })

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
        // undo/redo
        val (hist, back) = ScanHistory().push(ScanState()).undo(scan)!!
        assertTrue(back.isEmpty); assertEquals(scan, hist.redo(back)!!.second)
    }
}
