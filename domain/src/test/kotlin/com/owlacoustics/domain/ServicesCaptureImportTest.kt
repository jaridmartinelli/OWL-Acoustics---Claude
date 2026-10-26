package com.owlacoustics.domain

import com.owlacoustics.domain.capture.AnalysisRequest
import com.owlacoustics.domain.capture.AnalysisResponse
import com.owlacoustics.domain.capture.CandidateImport
import com.owlacoustics.domain.capture.CandidateWall
import com.owlacoustics.domain.capture.Consent
import com.owlacoustics.domain.capture.RemoteAnalysisConfig
import com.owlacoustics.domain.capture.ScaleEvidence
import com.owlacoustics.domain.capture.ViewpointPolicy
import com.owlacoustics.domain.catalog.Money
import com.owlacoustics.domain.edit.Templates
import com.owlacoustics.domain.importing.Calibration
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.model.ScaleKind
import com.owlacoustics.domain.services.OfferAvailability
import com.owlacoustics.domain.services.OrderOutcome
import com.owlacoustics.domain.services.OrderState
import com.owlacoustics.domain.services.Orders
import com.owlacoustics.domain.services.ServiceCatalog
import com.owlacoustics.domain.services.ServicePackage
import com.owlacoustics.domain.services.ServiceRequest
import com.owlacoustics.domain.services.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicesCaptureImportTest {
    private val pkg = ServicePackage("special-ceiling-review", 1, "SANDBOX ONLY", "sandbox fixture", listOf("review"),
        listOf("special-ceiling-draft"), published = true, price = Money(4900, "USD"), providerProductRef = "sandbox-sku",
        terms = "sandbox", fulfillment = "sandbox")

    private fun upd(o: OrderOutcome) = (o as OrderOutcome.Updated).order

    @Test fun unconfiguredOffersStayUnavailable() {
        assertTrue(ServiceCatalog.availability(null, true) is OfferAvailability.Unavailable)
        assertTrue(ServiceCatalog.availability(pkg.copy(published = false), true) is OfferAvailability.Unavailable)
        assertTrue(ServiceCatalog.availability(pkg.copy(price = null), true) is OfferAvailability.Unavailable)
        assertTrue(ServiceCatalog.availability(pkg, false) is OfferAvailability.Unavailable)
        assertTrue(ServiceCatalog.availability(pkg.copy(capabilityIds = listOf("on-site-visit")), true) is OfferAvailability.Unavailable)
        assertTrue(ServiceCatalog.availability(pkg, true) is OfferAvailability.Ready)
    }

    @Test fun purchaseLifecycleIsIdempotentAndTrustsProviderOnly() {
        val o = upd(Orders.begin(null, "key-1", pkg, sandbox = true, now = 1))
        assertEquals(o, upd(Orders.begin(o, "key-1", pkg, true, 2))) // repeated tap
        assertEquals(OrderState.CHECKOUT_PENDING, upd(Orders.applyVerification(o, Verification.Unknown, pkg, 3)).state)
        val tampered = upd(Orders.applyVerification(o, Verification.Paid("p1", Money(100, "USD"), pkg.id, 1), pkg, 4))
        assertEquals(OrderState.FAILED, tampered.state)
        val paid = upd(Orders.applyVerification(o, Verification.Paid("p1", Money(4900, "USD"), pkg.id, 1), pkg, 5))
        assertEquals(OrderState.VERIFIED_PAID, paid.state)
        assertEquals(paid, upd(Orders.applyVerification(paid, Verification.Paid("p1", Money(4900, "USD"), pkg.id, 1), pkg, 6)))
        // timeout after payment does not erase entitlement
        assertEquals(OrderState.VERIFIED_PAID, upd(Orders.applyVerification(paid, Verification.Unknown, pkg, 7)).state)
        // later catalog edit does not rewrite purchased terms
        val edited = pkg.copy(version = 2, inclusions = listOf("different"), price = Money(9900, "USD"))
        assertEquals(listOf("review"), paid.terms!!.inclusions)
        assertEquals(Money(4900, "USD"), paid.terms!!.price)
        assertTrue(edited.inclusions != paid.terms!!.inclusions)
        val req = ServiceRequest("roomA", 7, "hash7", "{}", "notes", listOf("room model revision 7"), emptyList(), 8)
        val sub = upd(Orders.submitRequest(paid, req, 8))
        assertEquals(OrderState.FULFILLMENT_REQUESTED, sub.state)
        assertEquals(sub, upd(Orders.submitRequest(sub, req.copy(historySeq = 9), 9))) // no duplicate/rewritten request
        assertEquals(7, sub.request!!.historySeq)
        val revoked = upd(Orders.applyVerification(sub, Verification.Revoked, pkg, 10))
        assertEquals(OrderState.REVOKED, revoked.state)
        assertEquals(paid.terms, revoked.terms)
        // no request without verified payment
        assertTrue(Orders.submitRequest(o, req, 11) is OrderOutcome.Rejected)
        assertEquals(OrderState.CANCELED, upd(Orders.applyVerification(o, Verification.Canceled, pkg, 12)).state)
    }

    private val req = AnalysisRequest("roomA", 5, "h", Consent("geometry", "text", 0, "deleted after processing"), emptyList())

    @Test fun remoteConfigMissingIsExplicit() {
        val c = RemoteAnalysisConfig(null, null, null)
        assertFalse(c.available)
        assertEquals(3, c.missing.size)
    }

    @Test fun candidateImportIsConservative() {
        val open = AnalysisResponse(1, "roomA", 5, listOf(
            CandidateWall("a", v(0.0, 0.0), v(4.0, 0.0)), CandidateWall("b", v(4.0, 0.0), v(4.0, 3.0)), CandidateWall("c", v(4.0, 3.0), v(0.0, 3.0)),
        ), provider = "mock")
        val p = CandidateImport.validate(req, open)
        assertFalse(p.closed); assertNull(p.vertices)
        val closedNoScale = open.copy(walls = open.walls + CandidateWall("d", v(0.0, 3.0), v(0.0, 0.0)))
        val p2 = CandidateImport.validate(req, closedNoScale)
        assertTrue(p2.closed); assertFalse(p2.metric)
        val scaled = CandidateImport.validate(req, closedNoScale.copy(scale = ScaleEvidence("door", "door 0.81 m", 0.5)))
        assertNear(6.0 * 0.5 * 0.5 * 2 * 1.0, Polygons.area(scaled.vertices!!) / 1.0 * 1.0 * 1.0 / 1.0, 1e-9)
        // wrong room / older generation is ignored
        assertNull(CandidateImport.validate(req, closedNoScale.copy(roomId = "roomB")).vertices)
        assertNull(CandidateImport.validate(req, closedNoScale.copy(generation = 4)).vertices)
        // unknown endpoints stay unresolved
        val unk = CandidateImport.validate(req, closedNoScale.copy(walls = closedNoScale.walls.dropLast(1) + CandidateWall("d", v(0.0, 3.0), null)))
        assertNull(unk.vertices)
        assertTrue(unk.issues.any { it.contains("unknown endpoints") })
    }

    @Test fun repeatedFramesAreNotIndependentViews() {
        val a = 0x0F0F0F0F0F0F0F0FL
        val nearA = a xor 0b111L
        val b = a.inv()
        assertEquals(listOf(0, 3), ViewpointPolicy.independent(listOf(a, a, nearA, b, b)))
        val grad = IntArray(72) { (it % 9) * 10 }
        assertEquals(0L, ViewpointPolicy.dHash(grad))
    }

    @Test fun calibrationTransformConflictAndRecalibration() {
        var m = Templates.newRoom("roomA")
        m = ok(Calibration.startImport(m, "media1", 0))
        assertEquals(ScaleKind.UNKNOWN, m.scale.kind)
        // 400 px = 4 m → 0.01 m/px
        m = ok(Calibration.addReference(m, v(100.0, 100.0), v(500.0, 100.0), ev("4 m")))
        assertNear(0.01, m.importCalibration!!.metersPerPixel!!, 1e-12)
        assertEquals(ScaleKind.IMPORT_CALIBRATED, m.scale.kind)
        val traced = ok(Calibration.traceOutline(m, listOf(v(100.0, 500.0), v(500.0, 500.0), v(500.0, 200.0), v(100.0, 200.0)), 600.0))
        assertNear(4.0 * 3.0, Polygons.area(traced.outline), 1e-9)
        assertNear(1.0, traced.outline.minOf { it.x }, 1e-9); assertNear(1.0, traced.outline.minOf { it.y }, 1e-9)
        // conflicting second reference: surfaced, not applied
        val withSecond = ok(Calibration.addReference(traced, v(0.0, 0.0), v(0.0, 300.0), ev("3.3 m")))
        assertNear(0.01, withSecond.importCalibration!!.metersPerPixel!!, 1e-12)
        assertEquals(1, Calibration.conflicts(withSecond.importCalibration!!).size)
        assertNear(12.0, Polygons.area(withSecond.outline), 1e-9)
        // explicit recalibration rescales and is a normal (undoable) edit
        val second = withSecond.importCalibration!!.references.last().id
        val recal = ok(Calibration.recalibrate(withSecond, second))
        assertNear(12.0 * 1.1 * 1.1, Polygons.area(recal.outline), 1e-9)
        // arbitrary pixel size alone never gives a scale
        rejected(Calibration.traceOutline(ok(Calibration.startImport(Templates.newRoom("x"), "m", 0)), listOf(v(0.0, 0.0), v(1.0, 0.0), v(1.0, 1.0)), 10.0))
    }
}
