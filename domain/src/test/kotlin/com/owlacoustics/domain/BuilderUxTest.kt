package com.owlacoustics.domain

import com.owlacoustics.domain.edit.Edits
import com.owlacoustics.domain.edit.Openings
import com.owlacoustics.domain.edit.Snap
import com.owlacoustics.domain.edit.Templates
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.units.DisplayUnits
import com.owlacoustics.domain.units.LengthFormat
import com.owlacoustics.domain.units.LengthParser
import com.owlacoustics.domain.units.LengthUnit
import com.owlacoustics.domain.units.ParseResult
import com.owlacoustics.domain.units.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The persona path: an 11' 6 1/4" × 15' 1/8" room typed into the manual builder. */
class BuilderUxTest {
    private val ft = LengthUnit.FOOT
    private fun e(s: String) = (LengthParser.parse(s, ft) as ParseResult.Ok).value

    @Test fun tapeMeasureShorthandParses() {
        val inch = Rational.of(254, 10000)
        assertEquals(Rational.of(138) * inch + Rational.of(1, 4) * inch, e("11 6 1/4").exactMeters)
        assertEquals(e("11' 6 1/4\"").exactMeters, e("11 6 1/4").exactMeters)
        assertEquals(e("15' 1/8\"").exactMeters, e("15 0 1/8").exactMeters)
        assertEquals(Rational.of(180) * inch + Rational.of(1, 8) * inch, e("15' 1/8\"").exactMeters)
        // in metric mode two bare numbers stay ambiguous and are refused
        assertTrue(LengthParser.parse("3 50", LengthUnit.METER) is ParseResult.Invalid)
        assertEquals("= 11' 6 1/4\"  (3.512 m)", LengthFormat.interpretation("11 6 1/4", DisplayUnits.IMPERIAL))
    }

    @Test fun typingBothSidesKeepsTheRoomRectangular() {
        var m = Templates.newRoom("r")
        m = ok(Edits.setWallLength(m, m.walls[0].id, e("15' 1/8\"")))
        assertTrue("corners stay square after the first entry", Edits.isRectangle(m))
        m = ok(Edits.setWallLength(m, m.walls[1].id, e("11 6 1/4")))
        assertTrue(Edits.isRectangle(m))
        val l = e("15' 1/8\"").meters; val w = e("11 6 1/4").meters
        assertNear(l, m.walls[0].length, 1e-12); assertNear(l, m.walls[2].length, 1e-12)
        assertNear(w, m.walls[1].length, 1e-12); assertNear(w, m.walls[3].length, 1e-12)
        assertNear(l * w, Polygons.area(m.outline), 1e-12)
        assertEquals("15' 0 1/8\"", LengthFormat.formatFeetInches(m.walls[2].length))
        assertEquals("11' 6 1/4\"", LengthFormat.formatFeetInches(m.walls[3].length))
        // exact typed text is kept for the walls the person typed
        assertEquals("11 6 1/4", m.entries["wall:${m.walls[1].id}:length"]!!.text)
    }

    @Test fun keepAnglesOnLShapeAndOpeningsStayPut() {
        var m = room(0.0 to 0.0, 6.0 to 0.0, 6.0 to 3.0, 3.0 to 3.0, 3.0 to 6.0, 0.0 to 6.0)
        m = ok(Openings.add(m, "w2", OpeningKind.WINDOW, 1.5))
        val before = (0 until 6).map { Edits.interiorAngle(m, it) }
        m = ok(Edits.setWallLength(m, "w0", ev("7 m")))
        val after = (0 until 6).map { Edits.interiorAngle(m, it) }
        for (k in 0 until 6) assertNear(before[k], after[k], 1e-9)
        assertNear(27.0 + 3.0, Polygons.area(m.outline), 1e-9)
    }

    @Test fun farCornerModeStillAvailable() {
        val m = ok(Edits.setWallLength(rect(4.0, 3.0), "w0", ev("5 m"), Edits.LengthMode.FAR_CORNER))
        assertTrue(!Edits.isRectangle(m))
        assertNear(4.0, m.vertices[2].x)
    }

    @Test fun keepAnglesRespectsLocks() {
        val m = ok(Edits.setLocked(rect(4.0, 3.0), "v2", true))
        rejected(Edits.setWallLength(m, "w0", ev("5 m")))
    }

    @Test fun squareCornerMakesNinetyDegrees() {
        val m = room(0.0 to 0.0, 4.0 to 0.0, 4.3 to 3.0, 0.0 to 3.0)
        val s = ok(Edits.squareCorner(m, "v2"))
        assertNear(90.0, Edits.interiorAngle(s, 2), 1e-9)
        assertNear(90.0, Edits.interiorAngle(rect(4.0, 3.0), 1), 1e-9)
        assertNear(270.0, Edits.interiorAngle(room(0.0 to 0.0, 4.0 to 0.0, 4.0 to 2.0, 2.0 to 2.0, 2.0 to 4.0, 0.0 to 4.0), 3), 1e-9)
    }

    @Test fun cornerDragSnapsToExactRightAngleNotNinetyPointOne() {
        val m = rect(4.0, 3.0)
        // drag the (4,3) corner slightly off: both walls within 4° of square → exact corner
        val r = Snap.vertex(m, "v2", Vec2(4.03, 3.04), 0.15, Snap.dragStep(DisplayUnits.METRIC))
        assertTrue(Snap.Kind.RIGHT_CORNER in r.engaged)
        assertNear(4.0, r.pos.x, 1e-12); assertNear(3.0, r.pos.y, 1e-12)
        // far from any snap: raw position passes through untouched
        val free = Snap.vertex(m, "v2", Vec2(5.1, 3.9), 0.05, 0.01)
        assertTrue(free.engaged.isEmpty())
        assertNear(5.1, free.pos.x, 1e-12); assertNear(3.9, free.pos.y, 1e-12)
    }

    @Test fun snapWorksInRoomAxesForRotatedRoom() {
        val m0 = rect(4.0, 3.0)
        val rot = m0.copy(vertices = m0.vertices.map { val p = it.pos.rotated(Math.toRadians(20.0)); it.copy(x = p.x, y = p.y) })
        val target = Vec2(4.0, 3.0).rotated(Math.toRadians(20.0))
        val r = Snap.vertex(rot, "v2", target + Vec2(0.03, -0.02), 0.15, 0.01)
        assertTrue(Snap.Kind.RIGHT_CORNER in r.engaged)
        assertNear(target.x, r.pos.x, 1e-9); assertNear(target.y, r.pos.y, 1e-9)
    }

    @Test fun singleArmSnapRoundsLengthToStep() {
        val m = rect(4.0, 3.0)
        // move corner v1 (4,0) along wall 1's line: angle from v0 is 0°, length steps to whole half-inches
        val r = Snap.vertex(m, "v1", Vec2(4.2037, 0.01), 0.05, 0.0127)
        assertTrue(Snap.Kind.ANGLE in r.engaged)
        assertNear(0.0, r.pos.y, 1e-12)
        val steps = r.pos.x / 0.0127
        assertNear(Math.rint(steps), steps, 1e-9)
    }
}
