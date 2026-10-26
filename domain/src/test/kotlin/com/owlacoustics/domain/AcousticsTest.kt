package com.owlacoustics.domain

import com.owlacoustics.domain.acoustics.Capabilities
import com.owlacoustics.domain.acoustics.CapabilityStatus
import com.owlacoustics.domain.acoustics.Modes
import com.owlacoustics.domain.acoustics.PathStatus
import com.owlacoustics.domain.acoustics.Reflections
import com.owlacoustics.domain.acoustics.SPEED_OF_SOUND
import com.owlacoustics.domain.edit.CeilingEdits
import com.owlacoustics.domain.edit.Openings
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.CeilingProfile
import com.owlacoustics.domain.model.ItemKind
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.Setup
import com.owlacoustics.domain.model.SetupItem
import com.owlacoustics.domain.model.Vertex
import com.owlacoustics.domain.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class AcousticsTest {
    private fun withItems(m: RoomModel, s: Vec2, sz: Double?, r: Vec2, rz: Double?) = m.copy(setup = Setup(items = listOf(
        SetupItem("s", ItemKind.SPEAKER, "S", s, sz?.let { meas(it) }),
        SetupItem("r", ItemKind.LISTENER, "R", r, rz?.let { meas(it) }),
    )))

    @Test fun rectangularModesMatchAnalytic() {
        val modes = Modes.rectangular(5.0, 4.0, 2.5)
        assertNear(SPEED_OF_SOUND / 2 / 5.0, modes.first { it.nx == 1 && it.ny == 0 && it.nz == 0 }.hz, 1e-9)
        assertNear(SPEED_OF_SOUND / 2 / 4.0, modes.first { it.nx == 0 && it.ny == 1 && it.nz == 0 }.hz, 1e-9)
        assertNear(SPEED_OF_SOUND / 2 * sqrt(1 / 25.0 + 1 / 16.0), modes.first { it.nx == 1 && it.ny == 1 && it.nz == 0 }.hz, 1e-9)
        assertEquals("oblique", modes.first { it.nx == 1 && it.ny == 1 && it.nz == 1 }.type)
        assertTrue(modes.all { it.hz <= Modes.MAX_HZ })
        // count of axial x modes under 300 Hz: n ≤ 2·300·5/343 = 8.7 → 8
        assertEquals(8, modes.count { it.ny == 0 && it.nz == 0 })
    }

    @Test fun feetInputsConvertConsistently() {
        val ft = 0.3048
        val m = Modes.rectangular(20 * ft, 15 * ft, 8 * ft)
        assertNear(SPEED_OF_SOUND / (2 * 20 * ft), m.first().hz, 1e-9)
    }

    @Test fun modalEligibility() {
        var r = ok(CeilingEdits.setProfile(rect(5.0, 4.0), CeilingProfile.Flat(meas(2.5))))
        assertTrue(Modes.eligibility(r) is Modes.Eligibility.Rectangular)
        // rotated rectangle remains rectangular
        val rot = r.copy(vertices = r.vertices.map { val p = it.pos.rotated(0.4); it.copy(x = p.x, y = p.y) })
        val e = Modes.eligibility(rot) as Modes.Eligibility.Rectangular
        assertNear(5.0, e.lx, 1e-9); assertNear(4.0, e.ly, 1e-9)
        val l = ok(CeilingEdits.setProfile(room(0.0 to 0.0, 6.0 to 0.0, 6.0 to 3.0, 3.0 to 3.0, 3.0 to 6.0, 0.0 to 6.0), CeilingProfile.Flat(meas(2.5))))
        assertTrue(Modes.eligibility(l) is Modes.Eligibility.Unavailable)
        val sloped = ok(CeilingEdits.setProfile(rect(5.0, 4.0), CeilingProfile.SingleSlope(0.0, meas(2.4), meas(3.0))))
        assertTrue(Modes.eligibility(sloped) is Modes.Eligibility.Unavailable)
        r = r.copy(ceiling = r.ceiling.copy(profile = CeilingProfile.Flat(null)))
        assertTrue(Modes.eligibility(r) is Modes.Eligibility.Unavailable)
    }

    @Test fun firstOrderWallReflectionHandComputed() {
        val m = withItems(ok(CeilingEdits.setProfile(rect(5.0, 4.0), CeilingProfile.Flat(meas(2.5)))), v(1.0, 1.0), 1.2, v(3.0, 1.0), 1.2)
        val rep = Reflections.analyze(m)
        val d = rep.direct.single()
        assertNear(2.0, d.lengthM, 1e-12)
        val south = rep.paths.single { it.surfaceRef == "w0" }
        assertEquals(PathStatus.VALID, south.status)
        assertNear(2.0, south.point.x, 1e-12); assertNear(0.0, south.point.y, 1e-12); assertNear(1.2, south.point.z!!, 1e-12)
        assertNear(sqrt(8.0), south.lengthM, 1e-12)
        assertNear(sqrt(8.0) / SPEED_OF_SOUND * 1000, south.delayMs, 1e-9)
        assertNear((sqrt(8.0) - 2.0) / SPEED_OF_SOUND * 1000, south.afterDirectMs, 1e-9)
        // floor: image at z=-1.2 → length sqrt(4 + 2.4²)
        val floor = rep.paths.single { it.surfaceRef == "floor" }
        assertNear(sqrt(4 + 2.4 * 2.4), floor.lengthM, 1e-12)
        // ceiling at 2.5: image z = 3.8 → length sqrt(4 + 2.6²)
        val ceil = rep.paths.single { it.surfaceRef.startsWith("ceiling") }
        assertNear(sqrt(4 + 2.6 * 2.6), ceil.lengthM, 1e-12)
        assertNear(2.5, ceil.point.z!!, 1e-12)
        // SBIR first null from the floor path difference
        val sb = rep.sbir.single { it.surfaceRef == "floor" }
        assertNear(SPEED_OF_SOUND / (2 * (sqrt(4 + 2.4 * 2.4) - 2.0)), sb.firstNullHz, 1e-9)
        assertNear(1.2, sb.boundaryDistanceM, 1e-12)
    }

    @Test fun doorwayIsNotTreatedAsWall() {
        var m = ok(CeilingEdits.setProfile(rect(5.0, 4.0), CeilingProfile.Flat(meas(2.5))))
        m = ok(Openings.add(m, "w0", OpeningKind.DOOR, 2.0))
        m = withItems(m, v(1.0, 1.0), 1.2, v(3.0, 1.0), 1.2)
        // vertical unknown → uncertain, excluded
        assertEquals(PathStatus.APERTURE_UNCERTAIN, Reflections.analyze(m).paths.single { it.surfaceRef == "w0" }.status)
        val id = m.openings.single().id
        m = ok(Openings.setVertical(m, id, meas(2.0), meas(0.0)))
        assertEquals(PathStatus.APERTURE, Reflections.analyze(m).paths.single { it.surfaceRef == "w0" }.status)
        // a window whose sill is above the reflection point does not block it
        var w = ok(CeilingEdits.setProfile(rect(5.0, 4.0), CeilingProfile.Flat(meas(2.5))))
        w = ok(Openings.add(w, "w0", OpeningKind.WINDOW, 2.0))
        w = ok(Openings.setVertical(w, w.openings.single().id, meas(0.8), meas(1.5)))
        w = withItems(w, v(1.0, 1.0), 1.2, v(3.0, 1.0), 1.2)
        assertEquals(PathStatus.VALID, Reflections.analyze(w).paths.single { it.surfaceRef == "w0" }.status)
    }

    @Test fun irregularRoomWallPathsWorkWithoutCeilingAndRespectOcclusion() {
        val l = room(0.0 to 0.0, 6.0 to 0.0, 6.0 to 3.0, 3.0 to 3.0, 3.0 to 6.0, 0.0 to 6.0)
        val m = withItems(l, v(1.0, 1.0), 1.2, v(5.0, 1.0), 1.2)
        val rep = Reflections.analyze(m)
        assertTrue(rep.paths.any { it.surfaceRef == "w0" && it.status == PathStatus.VALID })
        assertTrue(rep.paths.none { it.surfaceRef.startsWith("ceiling") })
        // wall w2 (y=3 from x=6→3): source at (1,1) mirrored across y=3 → (1,5); path to (5,1) hits y=3 at x=3 → corner edge; check w4 (x=3, y 3→6)
        // receiver behind the L corner: source (1,5), receiver (5,1) → direct path occluded
        val m2 = withItems(l, v(1.0, 5.0), 1.2, v(5.0, 2.0), 1.2)
        val rep2 = Reflections.analyze(m2)
        assertTrue(rep2.direct.single().occluded)
    }

    @Test fun planOnlyWhenHeightsUnknown() {
        val m = withItems(rect(5.0, 4.0), v(1.0, 1.0), null, v(3.0, 1.0), null)
        val rep = Reflections.analyze(m)
        assertTrue(rep.paths.all { it.planOnly && !it.surfaceRef.startsWith("ceiling") && it.surfaceRef != "floor" })
        assertTrue(rep.sbir.isEmpty())
    }

    @Test fun slopedCeilingBlocksWallPointAboveEave() {
        // slope rises toward +y from 1.0 m at y=0 to 3.0 m at y=4: a reflection at height 1.2 on wall y=0 is above that wall's top
        var m = ok(CeilingEdits.setProfile(rect(5.0, 4.0), CeilingProfile.SingleSlope(90.0, meas(1.0), meas(3.0))))
        m = withItems(m, v(1.0, 2.0), 1.2, v(3.0, 2.0), 1.2)
        val south = Reflections.analyze(m).paths.single { it.surfaceRef == "w0" }
        assertEquals(PathStatus.ABOVE_WALL, south.status)
    }

    @Test fun capabilityTableSeparatesMethods() {
        val l = room(0.0 to 0.0, 6.0 to 0.0, 6.0 to 3.0, 3.0 to 3.0, 3.0 to 6.0, 0.0 to 6.0)
        val m = withItems(l, v(1.0, 1.0), 1.2, v(5.0, 1.0), 1.2)
        val t = Capabilities.table(m, false).associateBy { it.operation }
        assertEquals(CapabilityStatus.UNAVAILABLE, t["Room modes"]!!.status)
        assertEquals(CapabilityStatus.PARTIAL, t["First-order reflections"]!!.status)
        assertEquals(CapabilityStatus.UNAVAILABLE, t["Quantitative treatment estimate"]!!.status)
        assertEquals(CapabilityStatus.SUPPORTED, t["Editable geometry"]!!.status)
    }
}
