package com.owlacoustics.domain.trace

import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/** Ray-cast picture of a [SyntheticRoom]: floor, dark baseboard, alternating walls, bright ceiling. */
fun renderRoom(room: SyntheticRoom, pose: CameraPose, k: Intrinsics, baseboard: Double = 0.09): ByteArray {
    val out = ByteArray(k.w * k.h)
    val n = room.poly.size
    for (v in 0 until k.h) for (u in 0 until k.w) {
        val d = Pinhole.worldRay(k, pose, u.toDouble(), v.toDouble())
        var best = Double.MAX_VALUE; var shade = 0
        if (d.z < -1e-9) { val t = (room.zf - pose.t.z) / d.z; if (t < best) { best = t; shade = 120 } }
        if (d.z > 1e-9) { val t = (room.zc - pose.t.z) / d.z; if (t < best) { best = t; shade = 235 } }
        for (i in 0 until n) {
            val a = room.poly[i]; val e = room.poly[(i + 1) % n] - a
            val den = d.x * e.y - d.y * e.x
            if (abs(den) < 1e-12) continue
            val ox = a.x - pose.t.x; val oy = a.y - pose.t.y
            val t = (ox * e.y - oy * e.x) / den
            val s = (ox * d.y - oy * d.x) / den
            if (t <= 1e-6 || s < 0 || s > 1 || t >= best) continue
            val z = pose.t.z + t * d.z
            if (z < room.zf || z > room.zc) continue
            best = t; shade = if (z < room.zf + baseboard) 60 else if (i % 2 == 0) 200 else 186
        }
        out[v * k.w + u] = shade.toByte()
    }
    return out
}

class TraceImageTest {
    private val k = Intrinsics(640 / (2 * tan(Math.toRadians(33.0))), 240.0, 320.0, 480, 640)
    private val set = TraceSettings()

    private fun image(w: Int, h: Int, f: (Int, Int) -> Int) = ByteArray(w * h) { f(it % w, it / w).toByte() }

    @Test fun snapPicksFloorLineBelowBaseboardAndCeilingLineAboveMoulding() {
        // wall (200) / baseboard (60) / floor (120): the baseboard's top edge is the stronger one
        val img = image(200, 200) { _, y -> if (y < 100) 200 else if (y < 108) 60 else 120 }
        val map = EdgeMap.of(img, 200, 200)
        val strongest = EdgeSnap.along(map, 90.0, 103.0, 1.0, 0.0, 12.0, EdgePrefer.STRONGEST)
        val lower = EdgeSnap.along(map, 90.0, 103.0, 1.0, 0.0, 12.0, EdgePrefer.LOWER)
        val upper = EdgeSnap.along(map, 90.0, 103.0, 1.0, 0.0, 12.0, EdgePrefer.UPPER)
        assertEquals(99.5, strongest.y, 0.6)
        assertEquals("floor mode takes the lowest edge", 107.5, lower.y, 0.6)
        assertEquals(99.5, upper.y, 0.6)
        assertTrue(lower.snapped && upper.snapped)
        // drawing direction does not matter
        assertEquals(107.5, EdgeSnap.along(map, 90.0, 103.0, -1.0, 0.0, 12.0, EdgePrefer.LOWER).y, 0.6)
        // nothing to grab on a blank wall: the point stays under the finger and is marked unsnapped
        val blank = EdgeMap.of(image(200, 200) { _, _ -> 128 }, 200, 200)
        val free = EdgeSnap.along(blank, 90.0, 103.0, 1.0, 0.0, 12.0, EdgePrefer.LOWER)
        assertFalse(free.snapped); assertEquals(103.0, free.y, 0.0)
        // an edge crossing the stroke (a door frame) is not followed
        val cross = EdgeMap.of(image(200, 200) { x, _ -> if (x < 100) 200 else 60 }, 200, 200)
        assertFalse(EdgeSnap.along(cross, 98.0, 100.0, 1.0, 0.0, 12.0).snapped)
        // a tap grabs the nearest strong edge
        val tap = EdgeSnap.nearest(map, 50.0, 96.0, 8.0)
        assertTrue(tap.snapped); assertEquals(99.5, tap.y, 1.1)
    }

    @Test fun wobblyStrokeBecomesStraightLinesWithACornerWhereTheyCross() {
        val rnd = Random(4)
        // true path: (20,300) → (260,340) → (460,250), drawn with ±3 px wobble and a flick at the end
        val a = Vec2(20.0, 300.0); val c = Vec2(260.0, 340.0); val b = Vec2(460.0, 250.0)
        val pts = ArrayList<StrokePoint>()
        fun leg(p: Vec2, q: Vec2) { val n = (p.distanceTo(q) / 3).toInt(); for (i in 0..n) { val t = i.toDouble() / n
            val w = (q - p).normalized().perp() * (rnd.nextDouble() - 0.5) * 6.0; val x = p + (q - p) * t + w; pts += StrokePoint(x.x, x.y, true) } }
        leg(a, c); leg(c, b)
        pts += StrokePoint(463.0, 244.0, true); pts += StrokePoint(461.0, 238.0, true) // flick on lifting
        val fit = StrokeFitter.fit(pts, tol = 4.0, minSegment = 14.0)!!
        assertEquals(3, fit.vertices.size)
        assertTrue("corner error ${fit.vertices[1].distanceTo(c)} px", fit.vertices[1].distanceTo(c) < 2.5)
        assertTrue(fit.vertices[2].distanceTo(b) < 6.0)
        assertEquals(listOf(true, true), fit.segmentSnapped)
        // a straight stroke stays one line; continuing from an existing point keeps that point exactly
        val straight = (0..60).map { StrokePoint(100.0 + it * 4, 200.0 + (rnd.nextDouble() - 0.5) * 5, it % 3 != 0) }
        val start = Vec2(60.0, 200.0)
        val one = StrokeFitter.fit(straight, 4.0, 14.0, start)!!
        assertEquals(2, one.vertices.size)
        assertEquals(start, one.vertices[0])
        assertEquals(200.0, one.vertices[1].y, 2.0)
        // mostly freehand → marked unsnapped
        val free = StrokeFitter.fit(straight.map { it.copy(snapped = false) }, 4.0, 14.0)!!
        assertEquals(listOf(false), free.segmentSnapped)
    }

    /** One simulated user going round the room, freezing a view, tracing what it shows, turning on. */
    private fun sweep(room: SyntheticRoom, surface: Surface, docIn: TraceDoc, rnd: Random, wobblePx: Double): TraceDoc {
        val n = room.poly.size
        val z = if (surface == Surface.FLOOR) room.zf else room.zc
        val total = room.lengths.sum()
        fun pointAt(sIn: Double): Vec3 {
            var s = ((sIn % total) + total) % total
            for (i in 0 until n) {
                val len = room.lengths[i]
                if (s <= len) { val p = room.poly[i] + (room.poly[(i + 1) % n] - room.poly[i]) * (s / len); return Vec3(p.x, p.y, z) }
                s -= len
            }
            return Vec3(room.poly[0].x, room.poly[0].y, z)
        }
        var doc = docIn
        val start = room.lengths[0] * 0.3
        var done = 0.0
        var guard = 0
        while (!doc.boundary(surface).closed && guard++ < 60) {
            val aim = pointAt(start + done + 0.7)
            val yaw = atan2(aim.y, aim.x)
            val rel = aim - room.cam(yaw)
            val pose = room.pose(yaw, atan2(rel.z, rel.xy().length))
            val map = EdgeMap.of(renderRoom(room, pose, k), k.w, k.h)
            // the part of the boundary this frozen view shows, from where the last stroke ended
            val raw = ArrayList<Vec2>()
            var step = 0.0
            while (done + step <= total + 1e-9) {
                val px = Pinhole.project(k, pose, pointAt(start + done + step)) ?: break
                if (px.x < 24 || px.x > k.w - 24 || px.y < 24 || px.y > k.h - 24) break
                raw += px; step += 0.02
            }
            assertTrue("view $guard shows some boundary", raw.size > 10)
            val covered = (raw.size - 1) * 0.02
            val closes = done + covered >= total - 0.021
            val phase = rnd.nextDouble() * 6.28
            val finger = raw.mapIndexed { i, p ->
                val t = if (i + 1 < raw.size) raw[i + 1] - p else p - raw[i - 1]
                p + t.normalized().perp() * (wobblePx * sin(i * 0.15 + phase) + (rnd.nextDouble() - 0.5) * 2)
            }
            val prefer = if (surface == Surface.FLOOR) EdgePrefer.LOWER else EdgePrefer.UPPER
            var prev: Vec2? = null
            val pts = finger.mapIndexed { i, p ->
                val d = finger[minOf(i + 4, finger.size - 1)] - finger[maxOf(i - 4, 0)]
                val sp = EdgeSnap.along(map, p.x, p.y, d.x, d.y, 11.0, prefer, prev = prev)
                prev = if (sp.snapped) sp.pos else null
                StrokePoint(sp.x, sp.y, sp.snapped)
            }
            // continue from the pinned last point: where the current solution puts it in this view
            val last = doc.boundary(surface).corners.lastOrNull()
            val startPx = last?.let { l -> Pinhole.project(k, pose, TraceSolver.solve(doc, set).points(surface).first { it.id == l.id }.pos) }
            val fit = StrokeFitter.fit(pts, tol = 2.5, minSegment = 10.0, start = startPx)
            assertNotNull(fit); fit!!
            var verts = fit.vertices.mapIndexed { i, v -> NewCorner(Pinhole.worldRay(k, pose, v.x, v.y), pose.heading(), snapped = i == 0 || fit.segmentSnapped[i - 1]) }
            if (startPx != null) verts = verts.drop(1)
            if (closes) verts = verts.dropLast(1)
            doc = TraceAssembler.addStroke(doc, surface, verts, closes, fit.segmentSnapped.last(), set)
            done += covered
        }
        return doc
    }

    /** Whole chain on synthetic pictures: edge detection, snapping, line fitting, pinning between views, solving. */
    @Test fun tracingSyntheticPicturesRecoversTheRoom() {
        val rnd = Random(11)
        // phone held lower than the provisional guess, pivot as the solver assumes
        val room = SyntheticRoom.persona(camHeight = 1.32)
        var doc = sweep(room, Surface.FLOOR, TraceDoc(), rnd, wobblePx = 5.0)
        assertTrue(doc.floor.closed)
        assertEquals("corners: " + doc.floor.corners.size, 4, doc.floor.corners.size)
        doc = sweep(room, Surface.CEILING, doc, rnd, wobblePx = 5.0)
        assertTrue(doc.ceiling.closed)
        assertEquals(4, doc.ceiling.corners.size)
        val sol = TraceSolver.solve(doc, set)
        assertEquals(ScaleBasis.CEILING_DEFAULT, sol.basis)
        assertTrue(sol.segments.all { it.snapped })
        val got = sol.plan!!.lengths.sorted(); val want = room.lengths.sorted()
        val worst = maxAbsDiff(got, want)
        println("TRACE-E2E synthetic pictures (480×640 edge map): camera height %.3f m (true 1.320), worst wall error %.1f in, walls %s"
            .format(-sol.floorZ, worst / IN, sol.plan!!.lengths.joinToString { "%.3f".format(it) }))
        assertEquals(1.32, -sol.floorZ, 0.03)
        assertTrue("worst wall error ${worst / IN} in", worst < 3 * IN)
    }

    @Test fun lShapedRoomKeepsItsSixCorners() {
        val rnd = Random(5)
        val pts = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 2.6), Vec2(2.6, 2.6), Vec2(2.6, 5.0), Vec2(0.0, 5.0))
        val room = SyntheticRoom(pts, Vec2(1.4, 1.3), 8 * FT, 1.40, 0.30)
        val doc = sweep(room, Surface.FLOOR, TraceDoc(), rnd, wobblePx = 4.0)
        assertTrue(doc.floor.closed)
        assertEquals(6, doc.floor.corners.size)
        val sol = TraceSolver.solve(TraceEdits.append(doc, Surface.CEILING, listOf(room.observe(room.ceilingCorner(0)))), set)
        val worst = maxAbsDiff(sol.plan!!.lengths.sorted(), room.lengths.sorted())
        println("TRACE-E2E L-shape worst wall error %.1f in".format(worst / IN))
        assertTrue("worst ${worst / IN} in", worst < 4 * IN)
    }
}
