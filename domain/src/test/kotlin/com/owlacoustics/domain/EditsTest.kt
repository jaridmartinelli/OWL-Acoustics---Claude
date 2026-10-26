package com.owlacoustics.domain

import com.owlacoustics.domain.edit.Edits
import com.owlacoustics.domain.edit.Openings
import com.owlacoustics.domain.edit.StartShape
import com.owlacoustics.domain.edit.Templates
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.model.Provenance
import com.owlacoustics.domain.model.ScaleKind
import com.owlacoustics.domain.workflow.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditsTest {
    @Test fun newRoomIsLabeledPlanningRectangle() {
        val m = Templates.newRoom("a")
        assertEquals(4, m.vertices.size)
        assertEquals(ScaleKind.PLANNING, m.scale.kind)
        assertTrue(m.vertices.all { it.provenance == Provenance.PLANNING })
        assertNear(12 * 0.3048, m.wallAt(0).length)
        assertNear(10 * 0.3048, m.wallAt(1).length)
    }

    @Test fun moveCornerUpdatesAreaAndNeighbours() {
        val m = rect(4.0, 3.0)
        val n = ok(Edits.moveVertex(m, "v2", v(5.0, 3.0)))
        // independent trapezoid area: (4 + 5)/2 * 3
        assertNear(13.5, Polygons.area(n.outline))
        assertNear(kotlin.math.sqrt(1.0 + 9.0), n.wallAt(1).length)
    }

    @Test fun invalidCornerMoveRejectedKeepsModel() {
        val m = rect(4.0, 3.0)
        val reason = rejected(Edits.moveVertex(m, "v2", v(-1.0, -1.0)))
        assertTrue(reason.isNotBlank())
    }

    @Test fun moveWallKeepsNeighbourDirections() {
        val m = rect(4.0, 3.0)
        // wall 1 is x=4 from (4,0) to (4,3); outward is +x
        val n = ok(Edits.moveWall(m, "w1", 0.5))
        assertNear(4.5, n.vertices[1].x); assertNear(0.0, n.vertices[1].y)
        assertNear(4.5, n.vertices[2].x); assertNear(3.0, n.vertices[2].y)
        assertNear(13.5, Polygons.area(n.outline))
    }

    @Test fun setWallLengthAnchorsStartCorner() {
        val m = rect(4.0, 3.0)
        val n = ok(Edits.setWallLength(m, "w0", ev("5 m"), Edits.LengthMode.FAR_CORNER))
        assertNear(0.0, n.vertices[0].x)
        assertNear(5.0, n.vertices[1].x)
        assertNear(5.0, n.wallAt(0).length)
        assertEquals("5 m", n.entries["wall:w0:length"]!!.text)
        // next wall now runs from (5,0) to (4,3)
        assertNear(kotlin.math.sqrt(1.0 + 9.0), n.wallAt(1).length)
    }

    @Test fun exactLengthEntryIsNotQuantised() {
        val m = rect(4.0, 3.0)
        val e = ev("13' 1 3/16\"", com.owlacoustics.domain.units.LengthUnit.FOOT)
        val n = ok(Edits.setWallLength(m, "w0", e))
        assertNear(e.meters, n.wallAt(0).length, 1e-12)
    }

    @Test fun lockedCornerRespected() {
        val m = ok(Edits.setLocked(rect(4.0, 3.0), "v1", true))
        rejected(Edits.setWallLength(m, "w0", ev("5 m")))
        rejected(Edits.moveVertex(m, "v1", v(4.2, 0.0)))
        rejected(Edits.moveWall(m, "w1", 0.2))
    }

    @Test fun splitAndRemoveVertex() {
        val m = rect(4.0, 3.0)
        val s = ok(Edits.splitWall(m, "w0", 1.0))
        assertEquals(5, s.vertices.size)
        assertEquals("w0", s.vertices[0].wallId)
        assertNear(1.0, s.wallAt(0).length)
        assertNear(3.0, s.wallAt(1).length)
        assertNear(12.0, Polygons.area(s.outline))
        val moved = ok(Edits.moveVertex(s, s.vertices[1].id, v(1.0, -0.5)))
        assertNear(12.0 + 0.5 * 4 / 2, Polygons.area(moved.outline))
        val back = ok(Edits.removeVertex(moved, s.vertices[1].id))
        assertEquals(4, back.vertices.size)
        assertNear(12.0, Polygons.area(back.outline))
    }

    @Test fun alcoveAddsArea() {
        val m = rect(4.0, 3.0)
        // wall 2 runs (4,3)->(0,3), outward +y
        val n = ok(Edits.addAlcove(m, "w2", 2.0, 1.0, 0.8))
        assertEquals(8, n.vertices.size)
        assertNear(12.0 + 0.8, Polygons.area(n.outline))
        assertTrue(com.owlacoustics.domain.model.Validation.blocking(n).isEmpty())
    }

    @Test fun openingsSurviveWallOperations() {
        var m = rect(4.0, 3.0)
        m = ok(Openings.add(m, "w0", OpeningKind.WINDOW, 1.0))
        val win = m.openings.single()
        // moving the far wall lengthens w0 at its end; window keeps its offset from the start
        val moved = ok(Edits.moveWall(m, "w1", 1.0))
        assertNear(win.offset, moved.openings.single().offset)
        // moving the start wall shifts w0's start: window keeps its distance from the (unchanged) end
        val moved2 = ok(Edits.moveWall(m, "w3", 1.0))
        val o2 = moved2.openings.single()
        assertNear(4.0 - (win.offset + win.width), moved2.wall("w0")!!.length - (o2.offset + o2.width))
        // split beyond the window moves nothing; split inside it is rejected
        ok(Edits.splitWall(m, "w0", 3.0))
        rejected(Edits.splitWall(m, "w0", win.offset + win.width / 2))
        // shrinking the wall under the window is rejected, never silently deleting it
        rejected(Edits.setWallLength(m, "w0", ev("1 m")))
    }

    @Test fun alcoveReassignsOpeningsBeyondIt() {
        var m = rect(6.0, 3.0)
        m = ok(Openings.add(m, "w0", OpeningKind.DOOR, 5.0))
        val door = m.openings.single()
        val n = ok(Edits.addAlcove(m, "w0", 2.0, 1.0, 0.5))
        val d2 = n.openings.single()
        val w = n.wall(d2.wallId)!!
        // same absolute position
        assertNear(m.wall("w0")!!.pointAt(door.offset).x, w.pointAt(d2.offset).x)
        assertNear(0.0, w.pointAt(d2.offset).y)
    }

    @Test fun planConfirmationIndependentOfCeiling() {
        val m = Templates.newRoom("a")
        val c = ok(Workflow.confirmPlan(m))
        assertTrue(c.planConfirmed)
        assertTrue(Workflow.stages(c).planReviewed)
        assertFalse(Workflow.stages(c).ceilingSupported)
        // a geometry edit means the drawing must be reviewed again
        val e = ok(Edits.moveWall(c, c.vertices[1].wallId, 0.1))
        assertFalse(e.planConfirmed)
    }

    @Test fun lShapeTemplateHasSixCornersAndNoRightAngleAssumptionOnAngled() {
        assertEquals(6, Templates.newRoom("x", StartShape.L_SHAPE).vertices.size)
        val a = Templates.newRoom("x", StartShape.ANGLED)
        assertFalse(Polygons.isRightAngled(a.outline, 0.01))
    }

    @Test fun bearingEdit() {
        val m = rect(4.0, 3.0)
        val n = ok(Edits.setWallBearing(m, "w0", 10.0))
        assertNear(4.0, n.wallAt(0).length)
        assertNear(10.0, com.owlacoustics.domain.geometry.bearingDeg(n.wallAt(0).vector), 1e-9)
    }
}
