package com.owlacoustics.domain

import com.owlacoustics.domain.edit.EditResult
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.Measure
import com.owlacoustics.domain.model.Provenance
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.Vertex
import com.owlacoustics.domain.units.LengthParser
import com.owlacoustics.domain.units.LengthUnit
import com.owlacoustics.domain.units.ParseResult
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

fun ok(r: EditResult): RoomModel {
    if (r is EditResult.Rejected) fail("expected Ok but rejected: ${r.reason}")
    return (r as EditResult.Ok).model
}

fun rejected(r: EditResult): String {
    assertTrue("expected rejection, got $r", r is EditResult.Rejected)
    return (r as EditResult.Rejected).reason
}

fun ev(text: String, unit: LengthUnit = LengthUnit.METER) = (LengthParser.parse(text, unit) as ParseResult.Ok).value

fun meas(m: Double) = Measure(m, Provenance.USER_ENTERED)

fun room(vararg pts: Pair<Double, Double>, id: String = "r1"): RoomModel {
    val verts = pts.mapIndexed { i, (x, y) -> Vertex("v$i", x, y, "w$i", Provenance.USER_ENTERED) }
    return RoomModel(id = id, vertices = verts, nextId = 100)
}

fun rect(w: Double, h: Double, id: String = "r1") = room(0.0 to 0.0, w to 0.0, w to h, 0.0 to h, id = id)

fun near(a: Double, b: Double, tol: Double = 1e-9) = kotlin.math.abs(a - b) <= tol

fun assertNear(expected: Double, actual: Double, tol: Double = 1e-9, msg: String = "") {
    if (!near(expected, actual, tol)) fail("$msg expected $expected but was $actual (tol $tol)")
}

fun v(x: Double, y: Double) = Vec2(x, y)
