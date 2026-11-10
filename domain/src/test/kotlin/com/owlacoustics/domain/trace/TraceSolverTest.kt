package com.owlacoustics.domain.trace

import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class TraceSolverTest {
    private val set = TraceSettings()
    private fun planLengths(sol: TraceSolution) = sol.plan!!.lengths
    private fun floorSeg(sol: TraceSolution, i: Int) = sol.segments.filter { it.surface == Surface.FLOOR }[i]

    @Test fun cameraMath() {
        val k = Intrinsics(500.0, 240.0, 320.0, 480, 640)
        val pose = CameraPose(Mat3.lookAt(0.7, -0.4, 0.05), Vec3(0.2, -0.1, 0.0))
        // pixel → ray → point → pixel
        val ray = Pinhole.worldRay(k, pose, 100.0, 500.0)
        val px = Pinhole.project(k, pose, pose.t + ray * 3.0)!!
        assertEquals(100.0, px.x, 1e-9); assertEquals(500.0, px.y, 1e-9)
        assertNull(Pinhole.project(k, pose, pose.t - pose.forward))
        // a segment that starts behind the camera is clipped, not dropped
        assertNotNull(Pinhole.projectSegment(k, pose, pose.t - pose.forward * 2.0, pose.t + pose.forward * 2.0))
        // heading follows the yaw even when looking almost straight down or up
        for (pitch in listOf(-1.45, -0.6, 0.0, 0.6, 1.45)) {
            val h = CameraPose(Mat3.lookAt(0.7, pitch), Vec3(0.0, 0.0, 0.0)).heading()
            assertEquals(kotlin.math.cos(0.7), h.x, 1e-9); assertEquals(kotlin.math.sin(0.7), h.y, 1e-9)
        }
    }

    @Test fun eightFootCeilingGivesScaleWithoutPhoneHeight() {
        val room = SyntheticRoom.persona()
        val sol = TraceSolver.solve(room.traced(), set)
        assertEquals(ScaleBasis.CEILING_DEFAULT, sol.basis)
        assertFalse("an assumed ceiling is not a calibration", sol.calibrated)
        assertEquals(-1.40, sol.floorZ, 1e-4)
        assertEquals(8 * FT, sol.ceilingHeight, 1e-9)
        assertTrue(maxAbsDiff(planLengths(sol), room.lengths) < 1e-3)
        assertEquals(SyntheticRoom.L * SyntheticRoom.W, sol.plan!!.area!!, 1e-3)
    }

    @Test fun floorAloneIsProvisionalAndOneCeilingTouchSetsScale() {
        val room = SyntheticRoom.persona(camHeight = 1.25)
        val floorOnly = room.traced(ceilingToo = false)
        assertEquals(ScaleBasis.PROVISIONAL, TraceSolver.solve(floorOnly, set).basis)
        // one touch on the ceiling line of the first wall (not at a corner)
        val mid = (room.ceilingCorner(0) + room.ceilingCorner(1)) * 0.5
        val one = TraceEdits.append(floorOnly, Surface.CEILING, listOf(room.observe(mid)))
        val sol = TraceSolver.solve(one, set)
        assertEquals(ScaleBasis.CEILING_DEFAULT, sol.basis)
        assertEquals(-1.25, sol.floorZ, 1e-3)
        assertTrue(maxAbsDiff(planLengths(sol), room.lengths) < 2e-3)
    }

    @Test fun wrongCeilingAssumptionScalesEverythingAndOneTypedWallFixesIt() {
        val room = SyntheticRoom.persona(ceiling = 9 * FT)
        val doc = room.traced()
        val assumed = TraceSolver.solve(doc, set)
        // 8 ft assumed on a 9 ft room: every wall reads about 8/9 of its real length (10–11% short)
        val ratio = planLengths(assumed)[0] / room.lengths[0]
        assertTrue("ratio was $ratio", ratio > 0.885 && ratio < 0.905)
        val seg = floorSeg(assumed, 0)
        val typed = TraceEdits.setTyped(doc, Surface.FLOOR, seg.a, seg.b, room.lengths[0], "15 0 1/8")
        val sol = TraceSolver.solve(typed, set)
        assertEquals(ScaleBasis.TYPED_LENGTH, sol.basis)
        assertTrue(sol.calibrated)
        assertTrue(maxAbsDiff(planLengths(sol), room.lengths) < 2e-3)
        assertEquals(CeilingSource.FROM_LINES, sol.ceilingSource)
        assertEquals(9 * FT, sol.impliedCeiling!!, 3e-3)
        assertEquals(9 * FT, sol.ceilingHeight, 3e-3)
        assertNull("9 ft is a plausible ceiling", sol.ceilingWarning)
        assertEquals("typed text is kept exactly", "15 0 1/8", floorSeg(sol, 0).typed!!.text)
        assertEquals(room.lengths[0], floorSeg(sol, 0).shown, 0.0)
    }

    @Test fun implausibleOrContradictedCeilingIsFlagged() {
        val room = SyntheticRoom.persona(ceiling = 8 * FT)
        val doc = room.traced()
        val seg = floorSeg(TraceSolver.solve(doc, set), 0)
        // typing a wall 40% too long implies a ceiling over 11 ft
        val wrong = TraceEdits.setTyped(doc, Surface.FLOOR, seg.a, seg.b, room.lengths[0] * 1.4, "21")
        assertEquals("unusual", TraceSolver.solve(wrong, set).ceilingWarning)
        // typed ceiling wins over the lines, and the disagreement is reported
        val right = TraceEdits.setTyped(doc, Surface.FLOOR, seg.a, seg.b, room.lengths[0], "15 0 1/8")
        val both = TraceSolver.solve(TraceEdits.setCeilingHeight(right, TypedValue(9 * FT, "9")), set)
        assertEquals(9 * FT, both.ceilingHeight, 1e-9)
        assertEquals(CeilingSource.TYPED, both.ceilingSource)
        assertEquals("typed-mismatch", both.ceilingWarning)
        // a typed ceiling height with both lines traced counts as a calibration
        val ceilOnly = TraceSolver.solve(TraceEdits.setCeilingHeight(doc, TypedValue(8 * FT, "8")), set)
        assertEquals(ScaleBasis.CEILING_TYPED, ceilOnly.basis)
        assertTrue(ceilOnly.calibrated)
    }

    @Test fun pivotDistanceMatters() {
        val room = SyntheticRoom.persona(pivot = 0.35)
        val doc = room.traced()
        fun err(p: Double) = maxAbsDiff(planLengths(TraceSolver.solve(doc, set.copy(pivotRadiusM = p))), room.lengths)
        assertTrue("right pivot recovers the room", err(0.35) < 2e-3)
        // ignoring the arm's length shrinks each wall-to-wall distance by roughly twice the pivot distance
        val ignored = err(0.0)
        assertTrue("ignored pivot error was $ignored m", ignored > 0.45 && ignored < 0.80)
        // a 5 cm (2 in) wrong guess costs about 4 in on the worst wall
        val near = err(0.30)
        assertTrue("5 cm pivot error gives $near m", near > 0.05 && near < 0.13)
    }

    @Test fun typedWallsLockAndSquaredRectangleBecomesTapeExact() {
        val rnd = Random(7)
        val room = SyntheticRoom.persona()
        val doc = room.traced(noiseDeg = 0.3, rnd = rnd)
        val s0 = TraceSolver.solve(doc, set)
        var d = TraceEdits.setTyped(doc, Surface.FLOOR, floorSeg(s0, 0).a, floorSeg(s0, 0).b, SyntheticRoom.L, "15 0 1/8")
        d = TraceEdits.setTyped(d, Surface.FLOOR, floorSeg(s0, 1).a, floorSeg(s0, 1).b, SyntheticRoom.W, "11 6 1/4")
        val plan = TraceSolver.solve(d, set).plan!!
        assertTrue(plan.squared)
        assertEquals(listOf(true, true, false, false), plan.locked)
        // typed walls are exact; with square corners the opposite walls must match them
        assertEquals(SyntheticRoom.L, plan.lengths[0], 1e-4); assertEquals(SyntheticRoom.W, plan.lengths[1], 1e-4)
        assertEquals(SyntheticRoom.L, plan.lengths[2], 1e-3); assertEquals(SyntheticRoom.W, plan.lengths[3], 1e-3)
        // the outline closes
        val c = plan.corners
        val end = c.last() + (c[0] - c.last())
        assertEquals(0.0, end.distanceTo(c[0]), 1e-9)
        assertEquals(SyntheticRoom.L * SyntheticRoom.W, plan.area!!, 0.01)
    }

    @Test fun squaringCanBeTurnedOffAndLeavesAngledWallsAlone() {
        // a room with one wall at 30°: squaring must not straighten it
        val pts = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(5.5, 0.866 * 3), Vec2(5.5, 4.0), Vec2(0.0, 4.0))
        val room = SyntheticRoom(pts, Vec2(2.5, 2.0), 8 * FT, 1.4, 0.3)
        val on = TraceSolver.solve(room.traced(), set).plan!!
        assertTrue(maxAbsDiff(on.lengths, room.lengths) < 3e-3)
        val off = TraceSolver.solve(room.traced(noiseDeg = 0.4, rnd = Random(3)), set.copy(squareCorners = false)).plan!!
        assertFalse(off.squared)
    }

    @Test fun markersSplitMeasurementsWithoutChangingTheShape() {
        val room = SyntheticRoom.persona()
        var doc = room.traced()
        val first = doc.floor.corners[0].id
        // door edge 4.5 ft along the first wall
        val (withMarker, id) = TraceEdits.insertMarker(doc, Surface.FLOOR, first, 4.5 * FT / SyntheticRoom.L)
        doc = withMarker
        assertNotNull(id)
        val sol = TraceSolver.solve(doc, set)
        val segs = sol.segments.filter { it.surface == Surface.FLOOR }
        assertEquals(5, segs.size)
        assertEquals(4.5 * FT, segs[0].measured, 1e-3)
        assertEquals(SyntheticRoom.L - 4.5 * FT, segs[1].measured, 1e-3)
        assertEquals("shape unchanged: still four walls", 4, sol.plan!!.lengths.size)
        // typing the piece calibrates the whole room
        val room9 = SyntheticRoom.persona(ceiling = 9 * FT)
        var d9 = room9.traced()
        d9 = TraceEdits.insertMarker(d9, Surface.FLOOR, d9.floor.corners[0].id, 4.5 * FT / SyntheticRoom.L).first
        val s9 = TraceSolver.solve(d9, set).segments.first { it.surface == Surface.FLOOR }
        val typed = TraceSolver.solve(TraceEdits.setTyped(d9, Surface.FLOOR, s9.a, s9.b, 4.5 * FT, "4.5"), set)
        assertTrue(maxAbsDiff(typed.plan!!.lengths, room9.lengths) < 3e-3)
        // deleting the marker merges the two measurements and drops the typed piece
        val merged = TraceEdits.delete(typed.let { TraceEdits.setTyped(d9, Surface.FLOOR, s9.a, s9.b, 4.5 * FT, "4.5") }, Surface.FLOOR, s9.b)
        assertTrue(merged.typed.isEmpty())
        assertEquals(4, TraceSolver.solve(merged, set).segments.count { it.surface == Surface.FLOOR })
    }

    @Test fun deletingACornerMergesItsWallsAndKeepsMarkersInPlace() {
        // L-shaped room; remove the inner corner's neighbour and check the marker stays where it was in the room
        val pts = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 2.5), Vec2(2.5, 2.5), Vec2(2.5, 5.0), Vec2(0.0, 5.0))
        val room = SyntheticRoom(pts, Vec2(1.2, 1.2), 8 * FT, 1.4, 0.3)
        var doc = room.traced()
        val ids = doc.floor.corners.map { it.id }
        doc = TraceEdits.insertMarker(doc, Surface.FLOOR, ids[0], 0.5).first // middle of the first wall
        val before = TraceSolver.solve(doc, set)
        val markerPos = before.floor.first { it.kind == AnchorKind.MARKER }.pos
        // collinear trick: delete corner 1 → wall 0 and wall 1 merge into a diagonal; marker is projected onto it
        val after = TraceEdits.delete(doc, Surface.FLOOR, ids[1], before.planPositions(Surface.FLOOR))
        assertEquals(5, after.floor.corners.size)
        assertTrue(after.floor.closed)
        val m = TraceSolver.solve(after, set).floor.first { it.kind == AnchorKind.MARKER }
        assertTrue("marker survives on the merged wall", m.pos.distanceTo(markerPos) < 2.0)
        // three corners → deleting one opens the boundary
        var tri = TraceEdits.append(TraceDoc(), Surface.FLOOR, (0..2).map { room.observe(room.floorCorner(it)) }, close = true)
        assertTrue(tri.floor.closed)
        tri = TraceEdits.delete(tri, Surface.FLOOR, tri.floor.corners[0].id)
        assertFalse(tri.floor.closed)
        assertEquals(2, tri.floor.corners.size)
    }

    @Test fun historyUndoesEverythingIncludingRestart() {
        val room = SyntheticRoom.persona()
        val doc = room.traced()
        var h = TraceHistory().commit(doc)
        h = h.commit(TraceEdits.restart(h.present))
        assertTrue(h.present.isEmpty)
        assertTrue("fresh ids after a restart", h.present.nextId > 8)
        h = h.undo()
        assertEquals(doc, h.present)
        h = h.redo()
        assertTrue(h.present.isEmpty)
        assertEquals(h, h.commit(h.present)) // no-op commits are not recorded
        assertTrue(h.undo().undo().present.isEmpty && !h.undo().undo().canUndo)
    }

    @Test fun strokesContinueCloseAndMergeStraightJoins() {
        val room = SyntheticRoom.persona()
        fun at(x: Double, y: Double) = room.observe(Vec3(x - SyntheticRoom.L * 0.45, y - SyntheticRoom.W * 0.55, room.zf))
        val L = SyntheticRoom.L; val W = SyntheticRoom.W
        var d = TraceDoc()
        // start mid-way along the first wall, as a user would, and go round in overlapping pieces
        d = TraceAssembler.addStroke(d, Surface.FLOOR, listOf(at(L * 0.3, 0.0), at(L * 0.7, 0.0)), false, true, set)
        d = TraceAssembler.addStroke(d, Surface.FLOOR, listOf(at(L, 0.0), at(L, W * 0.5)), false, true, set)
        assertEquals("the mid-wall join was merged away", 3, d.floor.corners.size)
        d = TraceAssembler.addStroke(d, Surface.FLOOR, listOf(at(L, W), at(L * 0.4, W)), false, true, set)
        d = TraceAssembler.addStroke(d, Surface.FLOOR, listOf(at(0.0, W), at(0.0, 0.0)), true, true, set)
        assertTrue(d.floor.closed)
        assertEquals("four real corners remain", 4, d.floor.corners.size)
        val sol = TraceSolver.solve(TraceEdits.append(d, Surface.CEILING, listOf(room.observe(room.ceilingCorner(2)))), set)
        assertTrue(maxAbsDiff(sol.plan!!.lengths.sorted(), room.lengths.sorted()) < 3e-3)
        // a hand-placed point on a straight wall is never merged
        var f = TraceAssembler.addStroke(TraceDoc(), Surface.FLOOR, listOf(at(0.0, 0.0), at(L * 0.5, 0.0).copy(forced = true)), false, true, set)
        f = TraceAssembler.addStroke(f, Surface.FLOOR, listOf(at(L, 0.0)), false, true, set)
        assertEquals(3, f.floor.corners.size)
        // an unsnapped half makes the merged wall unsnapped
        var u = TraceAssembler.addStroke(TraceDoc(), Surface.FLOOR, listOf(at(0.0, 0.0), at(L * 0.5, 0.0).copy(snapped = false)), false, true, set)
        u = TraceAssembler.addStroke(u, Surface.FLOOR, listOf(at(L, 0.0)), false, true, set)
        assertEquals(2, u.floor.corners.size)
        assertFalse(TraceSolver.solve(u, set).segments.single().snapped)
    }

    @Test fun meshIsAOneFootGridPinnedToTheRoom() {
        val room = SyntheticRoom.persona()
        val doc = room.traced()
        val sol = TraceSolver.solve(doc, set)
        val mesh = TraceMeshBuilder.build(doc, sol, set, fillWhileDrawing = true)
        // lines parallel to the 15 ft wall: one per foot across 11' 6 1/4" → 11 inside; the other way 15
        val along = mesh.floorLines.filter { abs(it.a.y - it.b.y) < 1e-6 }
        val across = mesh.floorLines.filter { abs(it.a.x - it.b.x) < 1e-6 }
        assertEquals(11, along.size); assertEquals(15, across.size)
        assertTrue(along.all { abs(it.a.distanceTo(it.b) - SyntheticRoom.L) < 1e-3 })
        assertEquals(FT, abs(along[1].a.y - along[0].a.y), 1e-9)
        assertTrue(mesh.floorLines.all { abs(it.a.z - sol.floorZ) < 1e-12 })
        assertEquals(4, mesh.wallFills.size)
        assertEquals(mesh.floorLines.size, mesh.ceilingLines.size)
        // walls: verticals each foot plus horizontals each foot up to 8 ft
        assertTrue(mesh.wallLines.any { abs(it.a.z - it.b.z) > 2.0 })
        // open floor: the mesh grows as a fan from the camera while drawing, and is absent otherwise
        val open = TraceEdits.append(TraceDoc(), Surface.FLOOR, (0..2).map { room.observe(room.floorCorner(it)) })
        val so = TraceSolver.solve(open, set)
        assertTrue(TraceMeshBuilder.build(open, so, set, true).floorLines.isNotEmpty())
        assertTrue(TraceMeshBuilder.build(open, so, set, false).floorLines.isEmpty())
        assertTrue("no walls before the scale is known", TraceMeshBuilder.build(open, so, set, true).wallLines.isEmpty())
    }

    /** Simulated tracing error (not a device measurement): how far walls land from the truth. */
    @Test fun simulatedTracingErrorStaysInsideSixInchesAfterOneTypedWall() {
        val rnd = Random(2026)
        val trials = 400
        val errsTyped = ArrayList<Double>(); val errsDefault = ArrayList<Double>(); val errsUnsquared = ArrayList<Double>()
        repeat(trials) {
            val room = SyntheticRoom.persona(camHeight = 1.25 + rnd.nextDouble() * 0.35,
                at = Vec2(SyntheticRoom.L * (0.35 + 0.3 * rnd.nextDouble()), SyntheticRoom.W * (0.35 + 0.3 * rnd.nextDouble())))
            val doc = room.traced(noiseDeg = 0.3, rnd = rnd)
            val s0 = TraceSolver.solve(doc, set)
            errsDefault += maxAbsDiff(planLengths(s0), room.lengths)
            val seg = floorSeg(s0, 0)
            val typed = TraceEdits.setTyped(doc, Surface.FLOOR, seg.a, seg.b, room.lengths[0], "15 0 1/8")
            errsTyped += maxAbsDiff(planLengths(TraceSolver.solve(typed, set)), room.lengths)
            errsUnsquared += maxAbsDiff(planLengths(TraceSolver.solve(typed, set.copy(squareCorners = false))), room.lengths)
        }
        fun p95(x: List<Double>) = x.sorted()[(x.size * 0.95).toInt()]
        println("TRACE-SIM 0.3° noise, worst wall error: typed p95 %.1f in (max %.1f) · 8 ft default p95 %.1f in · typed, not squared p95 %.1f in"
            .format(p95(errsTyped) / IN, errsTyped.max() / IN, p95(errsDefault) / IN, p95(errsUnsquared) / IN))
        assertTrue("p95 after one typed wall was ${p95(errsTyped) / IN} in", p95(errsTyped) < 6 * IN)
    }
}
