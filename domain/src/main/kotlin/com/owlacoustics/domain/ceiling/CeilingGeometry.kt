package com.owlacoustics.domain.ceiling

import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import com.owlacoustics.domain.model.ArchCurve
import com.owlacoustics.domain.model.Ceiling
import com.owlacoustics.domain.model.CeilingDetail
import com.owlacoustics.domain.model.CeilingDetailKind
import com.owlacoustics.domain.model.CeilingProfile
import com.owlacoustics.domain.model.CeilingProfile.Arched
import com.owlacoustics.domain.model.CeilingProfile.FlatCenterVault
import com.owlacoustics.domain.model.CeilingProfile.Peaked
import com.owlacoustics.domain.model.CeilingProfile.SingleSlope
import com.owlacoustics.domain.model.CeilingProfile.Tray
import com.owlacoustics.domain.model.TrayTransition
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Local frame for profile coordinates: u along the profile axis, v across; s = across distance from the polygon's minimum. */
data class ProfileFrame(val u: Vec2, val v: Vec2, val sMin: Double, val span: Double) {
    fun s(p: Vec2) = (p dot v) - sMin
    companion object {
        /** [axisDeg] is the direction along which height does NOT change (ridge/arch axis). */
        fun of(outline: List<Vec2>, axisDeg: Double): ProfileFrame {
            val u = Vec2(cos(Math.toRadians(axisDeg)), sin(Math.toRadians(axisDeg)))
            val v = u.perp()
            val ss = outline.map { it dot v }
            return ProfileFrame(u, v, ss.min(), ss.max() - ss.min())
        }
    }
}

/** A planar ceiling face usable for geometric reflection: z = a·x + b·y + c over a plan footprint. */
data class CeilingPlane(
    val label: String,
    val a: Double, val b: Double, val c: Double,
    /** Footprint: inside the room polygon AND within the across-range [sLo, sHi] of [frame] (if set) AND inside/outside [region]. */
    val frame: ProfileFrame?, val sLo: Double, val sHi: Double,
    val region: List<Vec2>? = null, val regionInside: Boolean = true,
) {
    fun z(p: Vec2) = a * p.x + b * p.y + c
    fun normal(): Vec3 = Vec3(-a, -b, 1.0).normalized()
    fun contains(outline: List<Vec2>, p: Vec2): Boolean {
        if (!Polygons.contains(outline, p)) return false
        if (frame != null) { val s = frame.s(p); if (s < sLo - 1e-9 || s > sHi + 1e-9) return false }
        if (region != null && Polygons.contains(region, p) != regionInside) return false
        return true
    }
}

sealed interface CeilingSupport {
    /** Height is defined everywhere; [planar] faces are exact planes; [approximate] true for tessellated curves. */
    data class Defined(
        val heightAt: (Vec2) -> Double,
        val planes: List<CeilingPlane>,
        /** Across-coordinate breaklines for faithful meshing. */
        val frame: ProfileFrame?,
        val breaks: List<Double>,
        val approximate: Boolean,
        val curvedUnsupportedForReflections: Boolean,
        val trayInset: List<Vec2>? = null,
        val trayOuterInset: List<Vec2>? = null,
        val description: String,
    ) : CeilingSupport
    data class Missing(val inputs: List<String>) : CeilingSupport
    data class Draft(val reason: String) : CeilingSupport
}

object CeilingGeometry {
    const val ARCH_SEGMENTS = 24

    fun support(outline: List<Vec2>, ceiling: Ceiling): CeilingSupport = when (val p = ceiling.profile) {
        CeilingProfile.Unset -> CeilingSupport.Missing(listOf("ceiling shape"))
        is CeilingProfile.Special -> CeilingSupport.Draft("Other / special / mixed ceiling: kept as an editable draft; geometry is not modeled.")
        is CeilingProfile.Flat -> {
            val h = p.height?.meters
            if (h == null) CeilingSupport.Missing(listOf("ceiling height"))
            else if (h <= 0) CeilingSupport.Missing(listOf("a positive ceiling height"))
            else CeilingSupport.Defined({ h }, listOf(CeilingPlane("Ceiling", 0.0, 0.0, h, null, 0.0, 0.0)), null, emptyList(),
                approximate = false, curvedUnsupportedForReflections = false, description = "Flat ceiling")
        }
        is SingleSlope -> {
            val miss = listOfNotNull("rise direction".takeIf { p.bearingDeg == null }, "low-side height".takeIf { p.low == null },
                "high-side height".takeIf { p.high == null })
            if (miss.isNotEmpty()) CeilingSupport.Missing(miss) else {
                // rise direction = across axis v; axis u is perpendicular to the rise
                val f = ProfileFrame.of(outline, p.bearingDeg!! - 90.0)
                val lo = p.low!!.meters; val hi = p.high!!.meters
                if (lo <= 0 || hi <= 0) CeilingSupport.Missing(listOf("positive heights")) else {
                    val k = (hi - lo) / f.span
                    // z = lo + k * (p·v − sMin)
                    val plane = CeilingPlane("Sloped ceiling", k * f.v.x, k * f.v.y, lo - k * f.sMin, null, 0.0, 0.0)
                    CeilingSupport.Defined({ q -> lo + k * f.s(q) }, listOf(plane), f, emptyList(), false, false,
                        description = "Single slope")
                }
            }
        }
        is Peaked -> {
            val miss = listOfNotNull("ridge direction".takeIf { p.ridgeBearingDeg == null }, "ridge position".takeIf { p.ridgeOffset == null },
                "ridge height".takeIf { p.ridgeHeight == null }, "side A eave height".takeIf { p.eaveA == null },
                "side B eave height".takeIf { p.eaveB == null })
            if (miss.isNotEmpty()) CeilingSupport.Missing(miss) else {
                val f = ProfileFrame.of(outline, p.ridgeBearingDeg!!)
                val r = p.ridgeOffset!!.meters; val hr = p.ridgeHeight!!.meters
                val ha = p.eaveA!!.meters; val hb = p.eaveB!!.meters
                if (r <= 0 || r >= f.span) CeilingSupport.Missing(listOf("a ridge position inside the room (0 – ${"%.2f".format(f.span)} m)"))
                else if (hr <= max(ha, hb) || min(ha, hb) <= 0) CeilingSupport.Missing(listOf("a ridge higher than both eaves"))
                else {
                    val segs = listOf(Seg(0.0, ha, r, hr), Seg(r, hr, f.span, hb))
                    piecewise(f, segs, "Peaked ceiling")
                }
            }
        }
        is FlatCenterVault -> {
            val miss = listOfNotNull("ridge direction".takeIf { p.ridgeBearingDeg == null }, "plateau position".takeIf { p.plateauOffset == null },
                "plateau width".takeIf { p.plateauWidth == null }, "plateau height".takeIf { p.plateauHeight == null },
                "side A eave height".takeIf { p.eaveA == null }, "side B eave height".takeIf { p.eaveB == null })
            if (miss.isNotEmpty()) CeilingSupport.Missing(miss) else {
                val f = ProfileFrame.of(outline, p.ridgeBearingDeg!!)
                val s0 = p.plateauOffset!!.meters; val w = p.plateauWidth!!.meters; val hp = p.plateauHeight!!.meters
                val ha = p.eaveA!!.meters; val hb = p.eaveB!!.meters
                if (s0 <= 0 || w <= 0 || s0 + w >= f.span) CeilingSupport.Missing(listOf("a plateau that fits inside the room span (${"%.2f".format(f.span)} m)"))
                else if (hp <= max(ha, hb) || min(ha, hb) <= 0) CeilingSupport.Missing(listOf("a plateau higher than both eaves"))
                else piecewise(f, listOf(Seg(0.0, ha, s0, hp), Seg(s0, hp, s0 + w, hp), Seg(s0 + w, hp, f.span, hb)), "Vaulted with flat centre")
            }
        }
        is Arched -> arched(outline, p)
        is Tray -> tray(outline, p)
    }

    private data class Seg(val s0: Double, val h0: Double, val s1: Double, val h1: Double)

    private fun piecewise(f: ProfileFrame, segs: List<Seg>, desc: String, approximate: Boolean = false, curved: Boolean = false): CeilingSupport.Defined {
        val planes = segs.mapIndexed { i, sg ->
            val k = (sg.h1 - sg.h0) / (sg.s1 - sg.s0)
            // z = h0 + k (s − s0), s = p·v − sMin
            CeilingPlane("Ceiling face ${i + 1}", k * f.v.x, k * f.v.y, sg.h0 - k * (sg.s0 + f.sMin), f, sg.s0, sg.s1)
        }
        val h: (Vec2) -> Double = { q ->
            val s = f.s(q).coerceIn(0.0, f.span)
            val sg = segs.firstOrNull { s <= it.s1 + 1e-12 } ?: segs.last()
            sg.h0 + (sg.h1 - sg.h0) * (s - sg.s0) / (sg.s1 - sg.s0)
        }
        return CeilingSupport.Defined(h, if (curved) emptyList() else planes, f, segs.map { it.s0 } + segs.last().s1, approximate, curved, description = desc)
    }

    private fun arched(outline: List<Vec2>, p: Arched): CeilingSupport {
        val miss = listOfNotNull("arch axis direction".takeIf { p.axisBearingDeg == null }, "spring height".takeIf { p.springHeight == null },
            "curve type".takeIf { p.curve == null },
            "crown height".takeIf { p.crownHeight == null && p.curve != ArchCurve.SAMPLED },
            "at least two profile points".takeIf { p.curve == ArchCurve.SAMPLED && p.samples.size < 2 })
        if (miss.isNotEmpty()) return CeilingSupport.Missing(miss)
        val f = ProfileFrame.of(outline, p.axisBearingDeg!!)
        val spring = p.springHeight!!.meters
        val profile: (Double) -> Double = when (p.curve!!) {
            ArchCurve.CIRCULAR_SEGMENT -> {
                val rise = p.crownHeight!!.meters - spring
                if (rise <= 0) return CeilingSupport.Missing(listOf("a crown higher than the spring height"))
                val half = f.span / 2
                val rho = (half * half + rise * rise) / (2 * rise)
                ({ s -> spring + sqrt(max(0.0, rho * rho - (s - half) * (s - half))) - (rho - rise) })
            }
            ArchCurve.HALF_ELLIPSE -> {
                val rise = p.crownHeight!!.meters - spring
                if (rise <= 0) return CeilingSupport.Missing(listOf("a crown higher than the spring height"))
                val half = f.span / 2
                ({ s -> spring + rise * sqrt(max(0.0, 1 - ((s - half) / half).let { it * it })) })
            }
            ArchCurve.SAMPLED -> {
                val pts = p.samples.sortedBy { it.fraction }
                if (pts.first().fraction > 0.0 || pts.last().fraction < 1.0) return CeilingSupport.Missing(listOf("profile points at both sides (0 and 1)"))
                if (pts.any { it.height <= 0 }) return CeilingSupport.Missing(listOf("positive profile heights"));
                { s ->
                    val t = (s / f.span).coerceIn(0.0, 1.0)
                    val k = pts.indexOfFirst { it.fraction >= t }.coerceAtLeast(1)
                    val a = pts[k - 1]; val b = pts[k]
                    if (b.fraction - a.fraction < 1e-12) b.height else a.height + (b.height - a.height) * (t - a.fraction) / (b.fraction - a.fraction)
                }
            }
        }
        val n = if (p.curve == ArchCurve.SAMPLED) 0 else ARCH_SEGMENTS
        val segs = if (n == 0) {
            val pts = p.samples.sortedBy { it.fraction }
            (1 until pts.size).map { Seg(pts[it - 1].fraction * f.span, pts[it - 1].height, pts[it].fraction * f.span, pts[it].height) }
                .filter { it.s1 - it.s0 > 1e-9 }
        } else (0 until n).map { i ->
            val s0 = f.span * i / n; val s1 = f.span * (i + 1) / n
            Seg(s0, profile(s0), s1, profile(s1))
        }
        val exact = CeilingSupport.Defined({ q -> profile(f.s(q).coerceIn(0.0, f.span)) }, emptyList(), f, segs.map { it.s0 } + f.span,
            approximate = true, curvedUnsupportedForReflections = true,
            description = "Arched (${p.curve.label}); 3D view is a ${segs.size}-segment approximation")
        return exact
    }

    private fun tray(outline: List<Vec2>, p: Tray): CeilingSupport {
        val miss = listOfNotNull("base height".takeIf { p.baseHeight == null }, "raised height".takeIf { p.raisedHeight == null },
            "inset from walls".takeIf { p.inset == null },
            "slope run".takeIf { p.transition == TrayTransition.SLOPED && p.slopedRun == null })
        if (miss.isNotEmpty()) return CeilingSupport.Missing(miss)
        val hb = p.baseHeight!!.meters; val hr = p.raisedHeight!!.meters; val d = p.inset!!.meters
        if (hr <= hb || hb <= 0) return CeilingSupport.Missing(listOf("a raised height above the base height"))
        val run = if (p.transition == TrayTransition.SLOPED) p.slopedRun!!.meters else 0.0
        val outer = Polygons.inset(outline, d) ?: return CeilingSupport.Missing(listOf("an inset that fits inside this room outline"))
        val inner = if (run > 0) Polygons.inset(outline, d + run) ?: return CeilingSupport.Missing(listOf("a slope run that fits inside the room")) else outer
        val h: (Vec2) -> Double = { q ->
            when {
                Polygons.contains(inner, q) -> hr
                run > 0 && Polygons.contains(outer, q) -> {
                    val dist = Polygons.distanceToBoundary(outer, q)
                    hb + (hr - hb) * (dist / run).coerceIn(0.0, 1.0)
                }
                else -> hb
            }
        }
        val planes = listOf(
            CeilingPlane("Tray base", 0.0, 0.0, hb, null, 0.0, 0.0, region = outer, regionInside = false),
            CeilingPlane("Tray raised centre", 0.0, 0.0, hr, null, 0.0, 0.0, region = inner, regionInside = true),
        )
        return CeilingSupport.Defined(h, planes, null, emptyList(), approximate = false,
            curvedUnsupportedForReflections = p.transition == TrayTransition.SLOPED,
            trayInset = inner, trayOuterInset = outer,
            description = if (run > 0) "Tray with sloped transition" else "Tray with vertical step")
    }

    /** Plan rectangle corners of a ceiling detail. */
    fun detailFootprint(d: CeilingDetail): List<Vec2> {
        val u = Vec2.fromBearingDeg(d.bearingDeg); val v = u.perp()
        val hu = u * (d.sizeAlong / 2); val hv = v * (d.sizeAcross / 2)
        return listOf(d.center - hu - hv, d.center + hu - hv, d.center + hu + hv, d.center - hu + hv)
    }

    /** Effective lowest obstruction height at [p] including dropped areas and beams; null where a detail's depth is unknown. */
    fun effectiveCeiling(support: CeilingSupport.Defined, details: List<CeilingDetail>, p: Vec2): Double? {
        var h = support.heightAt(p)
        for (d in details) {
            if (!Polygons.contains(detailFootprint(d), p)) continue
            val v = d.vertical?.meters ?: return null
            h = when (d.kind) {
                CeilingDetailKind.DROPPED -> min(h, v)
                CeilingDetailKind.BEAM -> min(h, h - v)
                CeilingDetailKind.COFFER -> h // recess raises locally; the surrounding grid is the lower bound
            }
        }
        return h
    }

    fun detailsFit(outline: List<Vec2>, details: List<CeilingDetail>): List<String> =
        details.filter { d -> detailFootprint(d).any { !Polygons.contains(outline, it) } || abs(d.sizeAlong) < 1e-6 || abs(d.sizeAcross) < 1e-6 }
            .map { it.id }
}
