package com.owlacoustics.domain

import com.owlacoustics.domain.edit.StartShape
import com.owlacoustics.domain.edit.Templates
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    @Test fun areaMatchesIndependentDecomposition() {
        // L-shape: 14x6 + 8x6 rectangles (feet) computed independently
        val ft = 0.3048
        val l = Templates.outline(StartShape.L_SHAPE)
        assertNear((14 * 6 + 8 * 6) * ft * ft, Polygons.area(l), 1e-12)
        // alcove: 12x10 + 4x3
        assertNear((12 * 10 + 4 * 3) * ft * ft, Polygons.area(Templates.outline(StartShape.ALCOVE)), 1e-12)
        // angled corner: 12x10 minus triangle 3x3/2
        assertNear((120 - 4.5) * ft * ft, Polygons.area(Templates.outline(StartShape.ANGLED)), 1e-12)
    }

    @Test fun rotatedRectangleAreaIsNotBoundingBox() {
        val r = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 3.0), v(0.0, 3.0)).map { it.rotated(Math.toRadians(30.0)) }
        assertNear(12.0, Polygons.area(r), 1e-9)
        val bbox = (r.maxOf { it.x } - r.minOf { it.x }) * (r.maxOf { it.y } - r.minOf { it.y })
        assertTrue(bbox > 12.5)
    }

    @Test fun selfIntersectionDetected() {
        val bow = listOf(v(0.0, 0.0), v(2.0, 2.0), v(2.0, 0.0), v(0.0, 2.0))
        assertNotNull(Polygons.firstSelfIntersection(bow))
        assertNull(Polygons.firstSelfIntersection(Templates.outline(StartShape.L_SHAPE)))
        val fold = listOf(v(0.0, 0.0), v(2.0, 0.0), v(1.0, 0.0), v(1.0, 1.0))
        assertNotNull(Polygons.firstSelfIntersection(fold))
    }

    @Test fun templatesAreValid() {
        for (s in StartShape.values()) {
            val m = Templates.newRoom("r", s)
            assertTrue("$s ${Validation.blocking(m)}", Validation.blocking(m).isEmpty())
        }
    }

    @Test fun triangulationCoversArea() {
        for (s in StartShape.values()) {
            val p = Templates.outline(s)
            val tris = Polygons.triangulate(p)
            assertEquals(p.size - 2, tris.size)
            val sum = tris.sumOf { Polygons.area(listOf(p[it[0]], p[it[1]], p[it[2]])) }
            assertNear(Polygons.area(p), sum, 1e-9)
        }
    }

    @Test fun insetRectangleAndLShape() {
        val r = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 3.0), v(0.0, 3.0))
        val i = Polygons.inset(r, 0.5)!!
        assertNear(3.0 * 2.0, Polygons.area(i))
        val l = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 2.0), v(2.0, 2.0), v(2.0, 4.0), v(0.0, 4.0))
        val li = Polygons.inset(l, 0.5)!!
        // independent: L arms 3x1 + 1x2 (inner L with 0.5 inset) = 3*1 + 1*2... compute: inner outline (0.5,0.5)-(3.5,0.5)-(3.5,1.5)-(1.5,1.5)-(1.5,3.5)-(0.5,3.5)
        assertNear(3.0 * 1.0 + 1.0 * 2.0, Polygons.area(li))
        assertNull(Polygons.inset(r, 1.6)) // too deep
    }

    @Test fun pointInPolygonAndSegmentInside() {
        val l = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 2.0), v(2.0, 2.0), v(2.0, 4.0), v(0.0, 4.0))
        assertTrue(Polygons.contains(l, v(1.0, 3.0)))
        assertTrue(!Polygons.contains(l, v(3.0, 3.0)))
        assertTrue(Polygons.segmentInside(l, v(1.0, 1.0), v(3.0, 1.0)))
        assertTrue(!Polygons.segmentInside(l, v(3.5, 1.5), v(1.5, 3.5)))
    }

    @Test fun vecBasics() {
        assertNear(5.0, Vec2(3.0, 4.0).length)
    }
}
