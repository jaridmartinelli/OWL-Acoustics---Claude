package com.owlacoustics.domain.mesh

import com.owlacoustics.domain.ceiling.CeilingGeometry
import com.owlacoustics.domain.ceiling.CeilingSupport
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import com.owlacoustics.domain.model.CeilingDetailKind
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.model.RoomModel

enum class SurfaceKind { FLOOR, WALL, CEILING, DETAIL }

data class Tri(val a: Vec3, val b: Vec3, val c: Vec3, val kind: SurfaceKind, val ref: String = "")

data class Line3(val points: List<Vec3>, val kind: String, val ref: String)

data class RoomMesh(
    val triangles: List<Tri>,
    val lines: List<Line3>,
    /** True when some surfaces are not represented because inputs are missing. */
    val partial: Boolean,
    val missing: List<String>,
    val approximate: Boolean,
    val description: String,
)

/** Builds the 3D view from the same canonical polygon, ceiling profile and openings used everywhere else (C-15). */
object MeshBuilder {
    fun build(m: RoomModel): RoomMesh {
        val outline = m.outline
        val tris = ArrayList<Tri>()
        val lines = ArrayList<Line3>()
        val missing = ArrayList<String>()
        // floor
        for (t in Polygons.triangulate(outline)) {
            tris += Tri(lift(outline[t[0]], 0.0), lift(outline[t[1]], 0.0), lift(outline[t[2]], 0.0), SurfaceKind.FLOOR)
        }
        val support = CeilingGeometry.support(outline, m.ceiling)
        if (support !is CeilingSupport.Defined) {
            when (support) {
                is CeilingSupport.Missing -> missing += support.inputs.map { "Ceiling: $it" }
                is CeilingSupport.Draft -> missing += support.reason
                else -> {}
            }
            // outline only: no fake walls or ceiling
            lines += Line3(outline.map { lift(it, 0.0) } + lift(outline.first(), 0.0), "outline", "floor")
            return RoomMesh(tris, lines, partial = true, missing = missing, approximate = false,
                description = "Partial view — floor outline only")
        }
        ceilingTriangles(outline, support, tris)
        // walls with apertures
        for (w in m.walls) {
            val L = w.length
            val ts = sortedSetOf(0.0, L)
            support.frame?.let { f ->
                val s0 = f.s(w.a); val s1 = f.s(w.b)
                for (b in support.breaks) {
                    if ((b - s0) * (b - s1) < 0) ts += L * (b - s0) / (s1 - s0)
                }
            }
            support.trayOuterInset?.let { }
            val ops = m.openingsOn(w.id)
            for (o in ops) { ts += o.offset; ts += o.offset + o.width }
            val list = ts.filter { it in 0.0..L }.toList()
            for (k in 1 until list.size) {
                val t0 = list[k - 1]; val t1 = list[k]
                if (t1 - t0 < 1e-9) continue
                val p0 = w.pointAt(t0); val p1 = w.pointAt(t1)
                val z0 = support.heightAt(p0); val z1 = support.heightAt(p1)
                val mid = (t0 + t1) / 2
                val o = ops.firstOrNull { mid > it.offset && mid < it.offset + it.width }
                val bottom = o?.bottom?.meters; val height = o?.height?.meters
                if (o != null && bottom != null && height != null && bottom + height <= minOf(z0, z1) + 1e-9) {
                    if (bottom > 0) quad(tris, p0, p1, 0.0, 0.0, bottom, bottom, w.id)
                    quad(tris, p0, p1, bottom + height, bottom + height, z0, z1, w.id)
                } else {
                    quad(tris, p0, p1, 0.0, 0.0, z0, z1, w.id)
                }
            }
            for (o in ops) {
                val a = w.pointAt(o.offset); val b = w.pointAt(o.offset + o.width)
                val bottom = o.bottom?.meters; val height = o.height?.meters
                val name = if (o.kind == OpeningKind.WINDOW) "Window" else "Door"
                if (bottom == null || height == null) {
                    missing += "$name ${o.id}: ${listOfNotNull("height".takeIf { height == null }, (if (o.kind == OpeningKind.WINDOW) "sill height" else "bottom elevation").takeIf { bottom == null }).joinToString(" and ")} — aperture not cut"
                    lines += Line3(listOf(lift(a, 0.0), lift(b, 0.0)), "opening-unknown", o.id)
                } else {
                    val top = bottom + height
                    if (top > minOf(support.heightAt(a), support.heightAt(b)) + 1e-9) {
                        missing += "$name ${o.id}: taller than the wall at this position — aperture not cut"
                    }
                    lines += Line3(listOf(lift(a, bottom), lift(b, bottom), lift(b, top), lift(a, top), lift(a, bottom)), "opening", o.id)
                }
            }
        }
        // ceiling details
        for (d in m.ceiling.details) {
            val fp = CeilingGeometry.detailFootprint(d)
            val v = d.vertical?.meters
            if (v == null) {
                missing += "${d.kind.label}: vertical dimension needed"
                lines += Line3(fp.map { lift(it, support.heightAt(it)) } + lift(fp.first(), support.heightAt(fp.first())), "detail-unknown", d.id)
                continue
            }
            when (d.kind) {
                CeilingDetailKind.DROPPED -> boxUnder(tris, fp, { v }, { support.heightAt(it) }, d.id)
                CeilingDetailKind.BEAM -> boxUnder(tris, fp, { support.heightAt(it) - v }, { support.heightAt(it) }, d.id)
                CeilingDetailKind.COFFER -> lines += Line3(fp.map { lift(it, support.heightAt(it) + v) } + lift(fp.first(), support.heightAt(fp.first()) + v), "coffer", d.id)
            }
        }
        return RoomMesh(tris, lines, partial = missing.isNotEmpty(), missing = missing, approximate = support.approximate,
            description = support.description)
    }

    private fun lift(p: Vec2, z: Double) = Vec3(p.x, p.y, z)

    private fun quad(out: MutableList<Tri>, p0: Vec2, p1: Vec2, zb0: Double, zb1: Double, zt0: Double, zt1: Double, ref: String) {
        if (zt0 - zb0 < 1e-9 && zt1 - zb1 < 1e-9) return
        val a = lift(p0, zb0); val b = lift(p1, zb1); val c = lift(p1, zt1); val d = lift(p0, zt0)
        out += Tri(a, b, c, SurfaceKind.WALL, ref); out += Tri(a, c, d, SurfaceKind.WALL, ref)
    }

    private fun boxUnder(out: MutableList<Tri>, fp: List<Vec2>, bottom: (Vec2) -> Double, top: (Vec2) -> Double, ref: String) {
        for (i in fp.indices) {
            val p0 = fp[i]; val p1 = fp[(i + 1) % fp.size]
            val a = lift(p0, bottom(p0)); val b = lift(p1, bottom(p1)); val c = lift(p1, top(p1)); val d = lift(p0, top(p0))
            out += Tri(a, b, c, SurfaceKind.DETAIL, ref); out += Tri(a, c, d, SurfaceKind.DETAIL, ref)
        }
        out += Tri(lift(fp[0], bottom(fp[0])), lift(fp[1], bottom(fp[1])), lift(fp[2], bottom(fp[2])), SurfaceKind.DETAIL, ref)
        out += Tri(lift(fp[0], bottom(fp[0])), lift(fp[2], bottom(fp[2])), lift(fp[3], bottom(fp[3])), SurfaceKind.DETAIL, ref)
    }

    private fun ceilingTriangles(outline: List<Vec2>, s: CeilingSupport.Defined, out: MutableList<Tri>) {
        val inner = s.trayInset
        val outer = s.trayOuterInset
        if (inner != null && outer != null) {
            val base = s.heightAt(outline.first())
            val raised = s.heightAt(Polygons.triangulate(inner).first().let { t ->
                (inner[t[0]] + inner[t[1]] + inner[t[2]]) / 3.0
            })
            val ccw = if (Polygons.signedArea(outline) < 0) outline.reversed() else outline
            for (i in ccw.indices) {
                val j = (i + 1) % ccw.size
                ringQuad(out, ccw[i], ccw[j], outer[j], outer[i], base, base)
                if (inner !== outer) ringQuad(out, outer[i], outer[j], inner[j], inner[i], base, raised)
                else {
                    val a = lift(inner[i], base); val b = lift(inner[j], base); val c = lift(inner[j], raised); val d = lift(inner[i], raised)
                    out += Tri(a, b, c, SurfaceKind.CEILING); out += Tri(a, c, d, SurfaceKind.CEILING)
                }
            }
            for (t in Polygons.triangulate(inner)) out += Tri(lift(inner[t[0]], raised), lift(inner[t[1]], raised), lift(inner[t[2]], raised), SurfaceKind.CEILING)
            return
        }
        val f = s.frame
        for (t in Polygons.triangulate(outline)) {
            val tri = listOf(outline[t[0]], outline[t[1]], outline[t[2]])
            val pieces = if (f == null || s.breaks.size < 2) listOf(tri) else (1 until s.breaks.size).mapNotNull { k ->
                var poly = Polygons.clipHalfPlane(tri, f.v, f.sMin + s.breaks[k - 1])
                poly = Polygons.clipHalfPlane(poly, -f.v, -(f.sMin + s.breaks[k]))
                poly.takeIf { it.size >= 3 && Polygons.area(it) > 1e-12 }
            }
            for (poly in pieces) for (i in 1 until poly.size - 1) {
                out += Tri(lift(poly[0], s.heightAt(poly[0])), lift(poly[i], s.heightAt(poly[i])), lift(poly[i + 1], s.heightAt(poly[i + 1])), SurfaceKind.CEILING)
            }
        }
    }

    private fun ringQuad(out: MutableList<Tri>, a: Vec2, b: Vec2, c: Vec2, d: Vec2, zab: Double, zcd: Double) {
        val A = lift(a, zab); val B = lift(b, zab); val C = lift(c, zcd); val D = lift(d, zcd)
        out += Tri(A, B, C, SurfaceKind.CEILING); out += Tri(A, C, D, SurfaceKind.CEILING)
    }
}
