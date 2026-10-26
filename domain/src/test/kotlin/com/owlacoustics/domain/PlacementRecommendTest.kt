package com.owlacoustics.domain

import com.owlacoustics.domain.acoustics.Reflections
import com.owlacoustics.domain.catalog.Catalog
import com.owlacoustics.domain.catalog.ProductCategory
import com.owlacoustics.domain.edit.CeilingEdits
import com.owlacoustics.domain.edit.EditResult
import com.owlacoustics.domain.edit.SetupEdits
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.model.Arrangement
import com.owlacoustics.domain.model.CeilingProfile
import com.owlacoustics.domain.model.ItemKind
import com.owlacoustics.domain.model.Purpose
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.TreatmentMode
import com.owlacoustics.domain.placement.PlacementOptimizer
import com.owlacoustics.domain.recommend.ActionKind
import com.owlacoustics.domain.recommend.Recommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementRecommendTest {
    private fun stereoRoom(): RoomModel {
        var m = ok(CeilingEdits.setProfile(rect(5.0, 4.0), CeilingProfile.Flat(meas(2.6))))
        m = ok(SetupEdits.setPurpose(m, Purpose.MIXING))
        assertEquals(Arrangement.STEREO, m.setup.arrangement)
        val ids = m.setup.items.associate { it.label to it.id }
        // a deliberately poor, asymmetric starting setup close to one wall
        m = ok(SetupEdits.moveItem(m, ids["L"]!!, v(0.5, 0.4)))
        m = ok(SetupEdits.moveItem(m, ids["R"]!!, v(1.9, 0.4)))
        m = ok(SetupEdits.moveItem(m, ids["Listener"]!!, v(1.2, 1.6)))
        for (id in ids.values) m = ok(SetupEdits.setHeight(m, id, meas(1.2)))
        return m
    }

    @Test fun purposeDefaultsOnlyStereoForMixingAndHifi() {
        for (p in Purpose.values()) {
            val m = ok(SetupEdits.setPurpose(rect(4.0, 3.0), p))
            if (p == Purpose.MIXING || p == Purpose.HIFI) assertEquals(Arrangement.STEREO, m.setup.arrangement)
            else assertNull("$p", m.setup.arrangement)
        }
        val pod = ok(SetupEdits.setArrangement(ok(SetupEdits.setPurpose(rect(4.0, 3.0), Purpose.PODCAST)), Arrangement.PODCAST, 3))
        assertEquals(3, pod.setup.items.count { it.kind == ItemKind.MICROPHONE })
        assertEquals(3, pod.setup.items.count { it.kind == ItemKind.TALKER })
        val ht = ok(SetupEdits.setArrangement(rect(5.0, 4.0), Arrangement.SURROUND_5_1))
        assertEquals(5, ht.setup.items.count { it.kind == ItemKind.SPEAKER })
        assertEquals(1, ht.setup.items.count { it.kind == ItemKind.SUBWOOFER })
    }

    @Test fun stereoOptimisationIsFeasibleSymmetricAndBetter() {
        val m = stereoRoom()
        val r = PlacementOptimizer.optimize(m)
        assertNull(r.blocked)
        assertTrue(r.proposals.isNotEmpty())
        val best = r.proposals.first()
        for (p in best.placements) {
            assertTrue(Polygons.contains(m.outline, v(p.x, p.y)))
            assertTrue(Polygons.distanceToBoundary(m.outline, v(p.x, p.y)) >= PlacementOptimizer.WALL_MARGIN - 1e-9)
        }
        val cur = r.current!!
        assertTrue(best.metrics.earliestMs!! >= cur.earliestMs!!)
        assertTrue(best.metrics.asymmetryMs!! <= cur.asymmetryMs!! + 1e-9)
        // listening distance preserved (equilateral-style layout at the declared distance)
        val lst = best.placements.first { p -> m.setup.items.first { it.id == p.itemId }.kind == ItemKind.LISTENER }
        val spk = best.placements.filter { p -> m.setup.items.first { it.id == p.itemId }.kind == ItemKind.SPEAKER }
        val d0 = m.setup.items.filter { it.kind == ItemKind.SPEAKER }.map { it.position!!.distanceTo(m.setup.items.first { i -> i.kind == ItemKind.LISTENER }.position!!) }.average()
        for (s in spk) assertNear(d0, v(s.x, s.y).distanceTo(v(lst.x, lst.y)), 1e-9)
        assertTrue(best.reasons.isNotEmpty())
        // applying is one undoable edit
        val applied = ok(SetupEdits.applyPlacement(m, best.placements.associate { it.itemId to (v(it.x, it.y) to it.facingDeg) }))
        assertEquals(applied.setup.items.size, m.setup.items.size)
    }

    @Test fun lockedItemsNeverMove() {
        var m = stereoRoom()
        val lst = m.setup.items.first { it.kind == ItemKind.LISTENER }
        m = ok(SetupEdits.setLocked(m, lst.id, true))
        val r = PlacementOptimizer.optimize(m)
        for (p in r.proposals) {
            val placed = p.placements.firstOrNull { it.itemId == lst.id }
            if (placed != null) { assertNear(lst.position!!.x, placed.x); assertNear(lst.position.y, placed.y) }
        }
        rejected(SetupEdits.moveItem(m, lst.id, v(2.0, 2.0)))
        val prop = r.proposals.first()
        val moved = prop.placements.map { if (it.itemId == lst.id) it.copy(x = it.x + 0.5) else it }
        rejected(SetupEdits.applyPlacement(m, moved.associate { it.itemId to (v(it.x, it.y) to it.facingDeg) }))
        // partially locked speakers are a stated limitation, not a silent guess
        var p = stereoRoom()
        p = ok(SetupEdits.setLocked(p, p.setup.items.first { it.label == "L" }.id, true))
        assertNotNull(PlacementOptimizer.optimize(p).blocked)
    }

    @Test fun keepOutRespected() {
        var m = stereoRoom()
        m = ok(SetupEdits.addKeepOut(m, v(2.5, 2.0), 5.0, 1.0, "desk"))
        val r = PlacementOptimizer.optimize(m)
        for (prop in r.proposals) for (p in prop.placements) assertTrue(kotlin.math.abs(p.y - 2.0) > 0.5)
    }

    @Test fun emptyRoomNeedsDistanceInputNotInvention() {
        val m = ok(SetupEdits.setPurpose(rect(5.0, 4.0), Purpose.HIFI))
        assertNotNull(PlacementOptimizer.optimize(m).blocked)
    }

    @Test fun recordingPairOptimised() {
        var m = ok(SetupEdits.setPurpose(ok(CeilingEdits.setProfile(rect(3.0, 2.5), CeilingProfile.Flat(meas(2.4)))), Purpose.AUDIOBOOK))
        m = ok(SetupEdits.setArrangement(m, Arrangement.TALKER_MIC))
        val talker = m.setup.items.first { it.kind == ItemKind.TALKER }
        val mic = m.setup.items.first { it.kind == ItemKind.MICROPHONE }
        m = ok(SetupEdits.moveItem(m, talker.id, v(0.3, 0.3))); m = ok(SetupEdits.moveItem(m, mic.id, v(0.6, 0.3)))
        m = ok(SetupEdits.setHeight(m, talker.id, meas(1.5))); m = ok(SetupEdits.setHeight(m, mic.id, meas(1.5)))
        val r = PlacementOptimizer.optimize(m)
        assertNull(r.blocked)
        val best = r.proposals.first()
        assertTrue(best.metrics.earliestMs!! > r.current!!.earliestMs!!)
        val t = best.placements.first { it.itemId == talker.id }; val k = best.placements.first { it.itemId == mic.id }
        assertNear(0.3, v(t.x, t.y).distanceTo(v(k.x, k.y)), 1e-9)
    }

    @Test fun buyNothingIsAValidResult() {
        val m = stereoRoom()
        val rep = Reflections.analyze(m)
        val rec = Recommender.recommend(m, rep, PlacementOptimizer.optimize(m))
        assertEquals(TreatmentMode.BUY_NOTHING, rec.mode)
        assertEquals(0, rec.purchaseCount)
        assertTrue(rec.actions.none { it.kind == ActionKind.TREATMENT_LOCATION })
        assertTrue(rec.actions.any { it.kind == ActionKind.MOVE_SETUP })
        assertTrue(rec.costNote.contains("not configured"))
    }

    @Test fun budgetAndExclusionsReoptimiseFromScratch() {
        var m = stereoRoom()
        m = m.copy(treatment = m.treatment.copy(mode = TreatmentMode.ITEM_LIMIT, maxItems = 2))
        val rep = Reflections.analyze(m)
        val a = Recommender.recommend(m, rep, null)
        val selected = a.actions.filter { it.kind == ActionKind.TREATMENT_LOCATION }
        assertTrue(selected.size <= 2)
        assertTrue(selected.all { it.expectedBenefit.contains("not quantified") })
        // exclude the first chosen location → a different fresh selection, still within the limit
        val first = selected.first().locationKey
        val m2 = m.copy(treatment = m.treatment.copy(excludedLocations = listOf(first)))
        val b = Recommender.recommend(m2, rep, null)
        assertTrue(b.actions.none { it.locationKey == first })
        assertTrue(b.actions.count { it.kind == ActionKind.TREATMENT_LOCATION } <= 2)
        // excluding every product → nothing to buy, and that is still a valid outcome
        val m3 = m.copy(treatment = m.treatment.copy(excludedProductIds = Catalog.products.map { it.id }))
        assertEquals(0, Recommender.recommend(m3, rep, null).purchaseCount)
    }

    @Test fun catalogIntegrity() {
        val d = Catalog.byId("owl-diffuser-ab")!!
        assertEquals(ProductCategory.DIFFUSER, d.category)
        assertNear(16.435 * 0.0254, d.geometry.faceWidthM, 1e-12)
        assertNear(5.047 * 0.0254, d.geometry.depthM, 1e-12)
        assertTrue(Catalog.products.all { it.performance == null && it.price == null })
        assertTrue(!Catalog.hasValidatedPerformance)
    }
}
