package com.owlacoustics.domain

import com.owlacoustics.domain.edit.OpeningGesture
import com.owlacoustics.domain.edit.OpeningHandle
import com.owlacoustics.domain.edit.OpeningIntent
import com.owlacoustics.domain.edit.Openings
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.DoorHanding
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.model.Provenance
import com.owlacoustics.domain.model.RoomModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningsTest {
    private fun withWindow(): RoomModel = ok(Openings.add(rect(4.0, 3.0), "w0", OpeningKind.WINDOW, 2.0))

    private fun sums(m: RoomModel) {
        for (o in m.openings) {
            val w = m.wall(o.wallId)!!
            val right = w.length - o.offset - o.width
            assertNear(w.length, o.offset + o.width + right)
            assertTrue(o.offset >= -1e-9 && right >= -1e-9)
        }
    }

    @Test fun placeholderIsProvisionalAndVerticalUnknown() {
        val o = withWindow().openings.single()
        assertEquals(Provenance.PLANNING, o.provenance)
        assertNull(o.height); assertNull(o.bottom)
        assertNear(2.0, o.offset + o.width / 2)
    }

    @Test fun leftRightWidthAnchors() {
        var m = withWindow()
        val id = m.openings.single().id
        m = ok(Openings.setLeft(m, id, ev("0.5 m")))
        assertNear(0.5, m.openings.single().offset); sums(m)
        m = ok(Openings.setRight(m, id, ev("1 m")))
        val o = m.openings.single()
        assertNear(4.0 - 1.0 - o.width, o.offset); sums(m)
        // window width keeps the centre
        val c = o.offset + o.width / 2
        m = ok(Openings.setWidth(m, id, ev("1.2 m")))
        assertNear(c, m.openings.single().offset + 0.6)
        assertEquals(Provenance.USER_ENTERED, m.openings.single().provenance)
    }

    @Test fun doorWidthKeepsStartJamb() {
        var m = ok(Openings.add(rect(4.0, 3.0), "w0", OpeningKind.DOOR, 2.0))
        val d = m.openings.single()
        m = ok(Openings.setWidth(m, d.id, ev("0.9 m")))
        assertNear(d.offset, m.openings.single().offset)
        assertNear(0.9, m.openings.single().width)
    }

    @Test fun invalidExactEntriesRejectedWithPriorValue() {
        val m = withWindow()
        val id = m.openings.single().id
        rejected(Openings.setLeft(m, id, ev("3.9 m")))
        rejected(Openings.setWidth(m, id, ev("5 m")))
        rejected(Openings.setRight(m, id, ev("4 m")))
        rejected(Openings.setWidth(m, id, ev("0 m")))
    }

    @Test fun overlapRejectedTouchingAllowed() {
        var m = ok(Openings.add(rect(4.0, 3.0), "w0", OpeningKind.WINDOW, 1.0))
        m = ok(Openings.add(m, "w0", OpeningKind.WINDOW, 3.0))
        val (a, b) = m.openingsOn("w0")
        rejected(Openings.setLeft(m, b.id, ev("${a.offset + a.width - 0.1} m")))
        val touching = ok(Openings.setLeft(m, b.id, ev("${a.offset + a.width} m")))
        sums(touching)
    }

    @Test fun dragClampsAtBoundary() {
        val m = withWindow()
        val o = m.openings.single()
        val (off, hit) = Openings.clampMove(m, o.id, 10.0)
        assertNear(4.0 - o.width, off); assertTrue(hit)
        val (r, hit2) = Openings.clampResize(m, o.id, o.offset, -5.0)
        assertNear(0.0, r.first); assertTrue(hit2)
    }

    @Test fun intentResolvedInWallLocalAxesOnAngledWall() {
        val dir = Vec2.fromBearingDeg(37.0)
        val along = dir * 0.1
        val across = dir.perp() * 0.1
        assertEquals(OpeningIntent.MOVE, OpeningGesture.intent(OpeningHandle.CENTER, OpeningKind.WINDOW, along, dir))
        assertEquals(OpeningIntent.FINE_WIDTH, OpeningGesture.intent(OpeningHandle.CENTER, OpeningKind.WINDOW, across, dir))
        assertEquals(OpeningIntent.RESIZE_END, OpeningGesture.intent(OpeningHandle.END, OpeningKind.WINDOW, along, dir))
        assertEquals(OpeningIntent.FINE_END, OpeningGesture.intent(OpeningHandle.END, OpeningKind.WINDOW, across, dir))
        assertEquals(OpeningIntent.MOVE, OpeningGesture.intent(OpeningHandle.CENTER, OpeningKind.DOOR, across, dir))
    }

    @Test fun fineWidthIsSymmetricWithLowerGain() {
        val m = withWindow()
        val o = m.openings.single()
        val w = m.wall("w0")!!
        val p = OpeningGesture.preview(m, o, OpeningIntent.FINE_WIDTH, w.outward * 0.2, w.dir, w.outward)
        assertNear(o.width + 2 * OpeningGesture.FINE_GAIN * 0.2, p.width)
        assertNear(o.offset + o.width / 2, p.offset + p.width / 2)
        val e = OpeningGesture.preview(m, o, OpeningIntent.FINE_END, w.outward * 0.2, w.dir, w.outward)
        assertNear(o.offset, e.offset)
        assertNear(o.width + OpeningGesture.FINE_GAIN * 0.2, e.width)
        val r = OpeningGesture.preview(m, o, OpeningIntent.RESIZE_START, w.dir * 0.3, w.dir, w.outward)
        assertNear(o.offset + o.width, r.offset + r.width) // opposite jamb fixed
    }

    @Test fun doorHandingCyclesWithoutChangingAperture() {
        var m = ok(Openings.add(rect(4.0, 3.0), "w0", OpeningKind.DOOR, 2.0))
        val d = m.openings.single()
        assertEquals(DoorHanding.UNKNOWN, d.handing)
        val seen = mutableListOf<DoorHanding>()
        repeat(5) { m = ok(Openings.cycleHanding(m, d.id)); seen += m.openings.single().handing }
        assertEquals(listOf(DoorHanding.HINGE_START_IN, DoorHanding.HINGE_START_OUT, DoorHanding.HINGE_END_OUT, DoorHanding.HINGE_END_IN, DoorHanding.HINGE_START_IN), seen)
        assertNear(d.offset, m.openings.single().offset); assertNear(d.width, m.openings.single().width)
    }

    @Test fun verticalUnknownVersusZero() {
        var m = ok(Openings.add(rect(4.0, 3.0), "w0", OpeningKind.DOOR, 2.0))
        val id = m.openings.single().id
        m = ok(Openings.setVertical(m, id, meas(2.0), meas(0.0)))
        assertNear(0.0, m.openings.single().bottom!!.meters)
        m = ok(Openings.setVertical(m, id, meas(2.0), null))
        assertNull(m.openings.single().bottom)
        rejected(Openings.setVertical(m, id, meas(-1.0), null))
    }

    @Test fun noArbitraryMinimumGap() {
        // an opening may touch a corner exactly
        var m = withWindow()
        val id = m.openings.single().id
        m = ok(Openings.setLeft(m, id, ev("0 m")))
        assertNear(0.0, m.openings.single().offset)
    }
}
