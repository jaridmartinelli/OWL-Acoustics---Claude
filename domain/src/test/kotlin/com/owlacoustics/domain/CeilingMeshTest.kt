package com.owlacoustics.domain

import com.owlacoustics.domain.ceiling.CeilingGeometry
import com.owlacoustics.domain.ceiling.CeilingSupport
import com.owlacoustics.domain.edit.CeilingEdits
import com.owlacoustics.domain.edit.Openings
import com.owlacoustics.domain.mesh.MeshBuilder
import com.owlacoustics.domain.mesh.SurfaceKind
import com.owlacoustics.domain.model.ArchCurve
import com.owlacoustics.domain.model.Ceiling
import com.owlacoustics.domain.model.CeilingDetailKind
import com.owlacoustics.domain.model.CeilingProfile
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.model.ProfileSample
import com.owlacoustics.domain.model.TrayTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CeilingMeshTest {
    private val box = rect(5.0, 4.0)
    private fun def(p: CeilingProfile, outline: List<com.owlacoustics.domain.geometry.Vec2> = box.outline) =
        CeilingGeometry.support(outline, Ceiling(p)) as CeilingSupport.Defined

    @Test fun unsetAndFlatWithoutHeightAreMissingNotDefaulted() {
        assertTrue(CeilingGeometry.support(box.outline, Ceiling()) is CeilingSupport.Missing)
        val miss = CeilingGeometry.support(box.outline, Ceiling(CeilingProfile.Flat(null))) as CeilingSupport.Missing
        assertEquals(listOf("ceiling height"), miss.inputs)
    }

    @Test fun flatAndSlope() {
        assertNear(2.7, def(CeilingProfile.Flat(meas(2.7))).heightAt(v(1.0, 1.0)))
        // rises toward +y (bearing 90): 2.4 at y=0 → 3.2 at y=4
        val s = def(CeilingProfile.SingleSlope(90.0, meas(2.4), meas(3.2)))
        assertNear(2.4, s.heightAt(v(2.0, 0.0)), 1e-12)
        assertNear(2.8, s.heightAt(v(2.0, 2.0)), 1e-12)
        assertNear(3.2, s.heightAt(v(0.0, 4.0)), 1e-12)
        // the plane equation matches the height function
        val pl = s.planes.single()
        assertNear(s.heightAt(v(3.3, 1.7)), pl.z(v(3.3, 1.7)), 1e-12)
    }

    @Test fun asymmetricPeakIsNotAssumedSymmetric() {
        // ridge along x (bearing 0) → across axis is y; ridge 1.5 m from side A (y=0)
        val p = def(CeilingProfile.Peaked(0.0, meas(1.5), meas(4.0), meas(2.5), meas(3.0)))
        assertNear(2.5, p.heightAt(v(1.0, 0.0)))
        assertNear(4.0, p.heightAt(v(1.0, 1.5)))
        assertNear(3.0, p.heightAt(v(1.0, 4.0)))
        assertNear(2.5 + 1.5 * 0.75 / 1.5, p.heightAt(v(1.0, 0.75)))
        assertEquals(2, p.planes.size)
        val missing = CeilingGeometry.support(box.outline, Ceiling(CeilingProfile.Peaked(0.0, meas(1.5), meas(4.0), meas(2.5), null)))
        assertTrue(missing is CeilingSupport.Missing)
    }

    @Test fun flatCenterVault() {
        val p = def(CeilingProfile.FlatCenterVault(0.0, meas(1.0), meas(2.0), meas(3.5), meas(2.5), meas(2.5)))
        assertNear(3.5, p.heightAt(v(2.0, 2.0)))
        assertNear(3.0, p.heightAt(v(2.0, 0.5)))
        assertEquals(3, p.planes.size)
    }

    @Test fun archNeedsExplicitCurveAndMatchesFormula() {
        val noCurve = CeilingGeometry.support(box.outline, Ceiling(CeilingProfile.Arched(0.0, meas(2.4), meas(3.4), null)))
        assertTrue(noCurve is CeilingSupport.Missing)
        // circular segment: chord 4, rise 1 → radius (4+1)/2 = 2.5; at quarter span s=1: h = 2.4 + sqrt(6.25-1) - 1.5
        val a = def(CeilingProfile.Arched(0.0, meas(2.4), meas(3.4), ArchCurve.CIRCULAR_SEGMENT))
        assertNear(3.4, a.heightAt(v(1.0, 2.0)), 1e-12)
        assertNear(2.4 + kotlin.math.sqrt(5.25) - 1.5, a.heightAt(v(1.0, 1.0)), 1e-12)
        assertTrue(a.approximate)
        assertTrue(a.curvedUnsupportedForReflections)
        val e = def(CeilingProfile.Arched(0.0, meas(2.4), meas(3.4), ArchCurve.HALF_ELLIPSE))
        assertNear(2.4 + kotlin.math.sqrt(1 - 0.25), e.heightAt(v(1.0, 1.0)), 1e-12)
        val sampled = def(CeilingProfile.Arched(0.0, meas(2.4), null, ArchCurve.SAMPLED,
            listOf(ProfileSample(0.0, 2.4), ProfileSample(0.3, 3.0), ProfileSample(1.0, 2.6))))
        assertNear(3.0, sampled.heightAt(v(1.0, 1.2)), 1e-12)
    }

    @Test fun trayFitsIrregularPolygon() {
        val l = room(0.0 to 0.0, 6.0 to 0.0, 6.0 to 3.0, 3.0 to 3.0, 3.0 to 6.0, 0.0 to 6.0)
        val t = def(CeilingProfile.Tray(meas(2.4), meas(2.8), meas(0.5)), l.outline)
        assertNear(2.4, t.heightAt(v(0.2, 0.2)))
        assertNear(2.8, t.heightAt(v(1.0, 1.0)))
        assertNear(2.8, t.heightAt(v(1.0, 5.0)))
        val tooDeep = CeilingGeometry.support(l.outline, Ceiling(CeilingProfile.Tray(meas(2.4), meas(2.8), meas(2.0))))
        assertTrue(tooDeep is CeilingSupport.Missing)
        val sloped = def(CeilingProfile.Tray(meas(2.4), meas(2.8), meas(0.5), TrayTransition.SLOPED, meas(0.4)), l.outline)
        assertNear(2.6, sloped.heightAt(v(0.7, 2.0)), 1e-9)
    }

    @Test fun specialCeilingIsDraft() {
        assertTrue(CeilingGeometry.support(box.outline, Ceiling(CeilingProfile.Special("barrel + skylight"))) is CeilingSupport.Draft)
    }

    @Test fun meshUsesActualPolygonAndCutsKnownApertures() {
        var m = room(0.0 to 0.0, 6.0 to 0.0, 6.0 to 3.0, 3.0 to 3.0, 3.0 to 6.0, 0.0 to 6.0)
        m = ok(CeilingEdits.setProfile(m, CeilingProfile.Flat(meas(2.5))))
        m = ok(Openings.add(m, "w0", OpeningKind.WINDOW, 3.0))
        val mesh0 = MeshBuilder.build(m)
        assertTrue(mesh0.partial)
        assertTrue(mesh0.missing.any { it.contains("aperture not cut") })
        val floorArea = mesh0.triangles.filter { it.kind == SurfaceKind.FLOOR }.sumOf { triArea(it) }
        assertNear(27.0, floorArea, 1e-9) // L area, not the 36 m² bounding box
        val ceilArea = mesh0.triangles.filter { it.kind == SurfaceKind.CEILING }.sumOf { triArea(it) }
        assertNear(27.0, ceilArea, 1e-9)
        val wallArea0 = mesh0.triangles.filter { it.kind == SurfaceKind.WALL }.sumOf { triArea(it) }
        assertNear(24.0 * 2.5, wallArea0, 1e-9) // perimeter 24 m × 2.5 m
        val id = m.openings.single().id
        m = ok(Openings.setVertical(m, id, meas(1.2), meas(0.9)))
        val mesh = MeshBuilder.build(m)
        assertFalse(mesh.partial)
        val wallArea = mesh.triangles.filter { it.kind == SurfaceKind.WALL }.sumOf { triArea(it) }
        assertNear(24.0 * 2.5 - m.openings.single().width * 1.2, wallArea, 1e-9)
    }

    @Test fun peakedWallsFollowProfile() {
        var m = box
        m = ok(CeilingEdits.setProfile(m, CeilingProfile.Peaked(0.0, meas(2.0), meas(4.0), meas(3.0), meas(3.0))))
        val mesh = MeshBuilder.build(m)
        // side walls (x=0 and x=5) are pentagons: 4*3 + triangle 4*1/2 = 14 each; long walls 5*3 = 15 each
        val wallArea = mesh.triangles.filter { it.kind == SurfaceKind.WALL }.sumOf { triArea(it) }
        assertNear(2 * 14.0 + 2 * 15.0, wallArea, 1e-9)
        // ceiling: two slopes each 5 × sqrt(2²+1²)
        val ceil = mesh.triangles.filter { it.kind == SurfaceKind.CEILING }.sumOf { triArea(it) }
        assertNear(2 * 5 * kotlin.math.sqrt(5.0), ceil, 1e-9)
    }

    @Test fun unknownCeilingGivesPartialOutlineNotFakeSolid() {
        val mesh = MeshBuilder.build(box)
        assertTrue(mesh.partial)
        assertTrue(mesh.triangles.none { it.kind == SurfaceKind.WALL || it.kind == SurfaceKind.CEILING })
    }

    @Test fun detailNeedsVerticalAndConfirmRequiresDefinedProfile() {
        var m = ok(CeilingEdits.setProfile(box, CeilingProfile.Flat(meas(2.7))))
        m = ok(CeilingEdits.addDetail(m, CeilingDetailKind.BEAM, v(2.5, 2.0), 4.0, 0.2, 0.0, null))
        assertTrue(MeshBuilder.build(m).missing.any { it.contains("vertical") })
        rejected(CeilingEdits.addDetail(m, CeilingDetailKind.DROPPED, v(4.9, 2.0), 1.0, 1.0, 0.0, meas(2.4)))
        rejected(CeilingEdits.confirm(ok(CeilingEdits.setProfile(box, CeilingProfile.Flat(null))), true))
        assertTrue(ok(CeilingEdits.confirm(m, true)).ceiling.confirmed)
    }

    private fun triArea(t: com.owlacoustics.domain.mesh.Tri) = ((t.b - t.a) cross (t.c - t.a)).length / 2
}
