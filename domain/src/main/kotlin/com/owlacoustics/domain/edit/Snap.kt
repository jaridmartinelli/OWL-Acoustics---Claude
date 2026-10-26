package com.owlacoustics.domain.edit

import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.bearingDeg
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.units.DisplayUnits
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Drag snapping for the plan editor (E-007). Snapping only shapes a drag preview; typed values never pass through it.
 * Tolerances are given by the caller in screen space converted to metres, so the feel is the same at every zoom.
 *
 * Priority for a dragged corner P between neighbours A (previous) and B (next):
 *  1. both attached walls on a snap angle → exact corner at the intersection of the two rays;
 *  2. one wall on a snap angle (multiples of 45° measured from wall 1's direction) → P projected onto that ray,
 *     its length then rounded to the drag step;
 *  3. alignment with another corner's x or y (in the room's own axes).
 * Each engaged snap is reported so the UI can draw a guide and give a haptic tick.
 */
object Snap {
    /** Angle tolerance for a wall direction to lock onto a snap angle. */
    const val ANGLE_TOL_DEG = 4.0

    /** Drag length step: 1/2 inch (imperial) or 1 cm (metric). Display/feel only, never applied to typed values. */
    fun dragStep(u: DisplayUnits) = if (u == DisplayUnits.IMPERIAL) 0.0127 else 0.01

    enum class Kind { ANGLE, RIGHT_CORNER, ALIGN_X, ALIGN_Y, LENGTH_STEP }

    data class Guide(val a: Vec2, val b: Vec2, val kind: Kind)

    data class Result(val pos: Vec2, val guides: List<Guide>, val engaged: Set<Kind>)

    /** Room axis: wall 1's direction, so "square" means square to the room, not to the screen. */
    fun axisDeg(m: RoomModel): Double = if (m.vertices.size >= 2) bearingDeg(m.wallAt(0).vector) else 0.0

    private fun snapAngle(dirDeg: Double, axis: Double): Double? {
        val rel = ((dirDeg - axis) % 360 + 360) % 360
        val nearest = (rel / 45.0).roundToLong() * 45.0
        return if (abs(rel - nearest) <= ANGLE_TOL_DEG) axis + nearest else null
    }

    private fun roundTo(x: Double, step: Double) = (x / step).roundToLong() * step

    fun vertex(m: RoomModel, vertexId: String, raw: Vec2, tolMeters: Double, step: Double): Result {
        val n = m.vertices.size
        val i = m.vertices.indexOfFirst { it.id == vertexId }
        if (i < 0 || n < 3) return Result(raw, emptyList(), emptySet())
        val a = m.vertices[(i + n - 1) % n].pos
        val b = m.vertices[(i + 1) % n].pos
        val axis = axisDeg(m)
        val minArm = tolMeters * 3 // angles are meaningless for very short arms
        val angA = if (raw.distanceTo(a) > minArm) snapAngle(bearingDeg(raw - a), axis) else null
        val angB = if (raw.distanceTo(b) > minArm) snapAngle(bearingDeg(raw - b), axis) else null
        val guides = ArrayList<Guide>()
        val engaged = HashSet<Kind>()
        val dA = angA?.let { Vec2.fromBearingDeg(it) }
        val dB = angB?.let { Vec2.fromBearingDeg(it) }
        if (dA != null && dB != null && abs(dA cross dB) > 1e-6) {
            val x = Polygons.lineIntersection(a, dA, b, dB)
            if (x != null && x.distanceTo(raw) <= tolMeters * 2) {
                guides += Guide(a, x, Kind.ANGLE); guides += Guide(b, x, Kind.ANGLE)
                engaged += Kind.ANGLE
                if (abs(dA dot dB) < 1e-6) engaged += Kind.RIGHT_CORNER
                return Result(x, guides, engaged)
            }
        }
        // one arm on a snap angle: project onto that ray, then step its length
        val single = listOfNotNull(dA?.let { Triple(a, it, raw.distanceTo(a)) }, dB?.let { Triple(b, it, raw.distanceTo(b)) })
            .map { (o, d, _) -> Triple(o, d, o + d * ((raw - o) dot d)) }
            .filter { it.third.distanceTo(raw) <= tolMeters }
            .minByOrNull { it.third.distanceTo(raw) }
        if (single != null) {
            val (o, d, p) = single
            val len = roundTo((p - o) dot d, step)
            val q = o + d * len
            guides += Guide(o, q, Kind.ANGLE)
            engaged += Kind.ANGLE; engaged += Kind.LENGTH_STEP
            return Result(q, guides, engaged)
        }
        // alignment with other corners, in room axes
        val u = Vec2.fromBearingDeg(axis); val v = u.perp()
        fun loc(p: Vec2) = Vec2(p dot u, p dot v)
        fun world(l: Vec2) = u * l.x + v * l.y
        val lr = loc(raw)
        var lx = lr.x; var ly = lr.y
        val others = m.vertices.filterIndexed { k, _ -> k != i }.map { it.pos }
        others.minByOrNull { abs(loc(it).x - lr.x) }?.let { o ->
            if (abs(loc(o).x - lr.x) <= tolMeters) { lx = loc(o).x; engaged += Kind.ALIGN_X; guides += Guide(o, world(Vec2(lx, ly)), Kind.ALIGN_X) }
        }
        others.minByOrNull { abs(loc(it).y - lr.y) }?.let { o ->
            if (abs(loc(o).y - lr.y) <= tolMeters) { ly = loc(o).y; engaged += Kind.ALIGN_Y; guides += Guide(o, world(Vec2(lx, ly)), Kind.ALIGN_Y) }
        }
        return Result(world(Vec2(lx, ly)), guides, engaged)
    }

    /** Wall move distance rounded to the drag step when snapping. */
    fun distance(d: Double, step: Double) = roundTo(d, step)
}
