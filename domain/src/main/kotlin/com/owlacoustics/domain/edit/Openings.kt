package com.owlacoustics.domain.edit

import com.owlacoustics.domain.geometry.EPS
import com.owlacoustics.domain.geometry.MIN_EDGE
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.Measure
import com.owlacoustics.domain.model.Opening
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.model.Provenance
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.Validation
import com.owlacoustics.domain.units.EnteredValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Placeholder sizes are provisional planning values (never measured). */
const val PLACEHOLDER_WINDOW_WIDTH = 0.9144 // 36 in
const val PLACEHOLDER_DOOR_WIDTH = 0.8128 // 32 in

object Openings {

    /** Free intervals [start, end] on the wall excluding other openings (touching allowed). */
    fun freeIntervals(m: RoomModel, wallId: String, excludeId: String? = null): List<Pair<Double, Double>> {
        val w = m.wall(wallId) ?: return emptyList()
        val others = m.openingsOn(wallId).filter { it.id != excludeId }
        val out = ArrayList<Pair<Double, Double>>()
        var cursor = 0.0
        for (o in others) {
            if (o.offset > cursor + EPS) out += cursor to o.offset
            cursor = max(cursor, o.offset + o.width)
        }
        if (w.length > cursor + EPS) out += cursor to w.length
        return out
    }

    fun add(m: RoomModel, wallId: String, kind: OpeningKind, centerOffset: Double? = null): EditResult {
        val w = m.wall(wallId) ?: return EditResult.Rejected("Select a wall first.")
        val desired = if (kind == OpeningKind.WINDOW) PLACEHOLDER_WINDOW_WIDTH else PLACEHOLDER_DOOR_WIDTH
        val c = centerOffset ?: (w.length / 2)
        val gaps = freeIntervals(m, wallId)
        if (gaps.isEmpty()) return EditResult.Rejected("There is no free space on this wall.")
        // prefer the gap containing the requested centre, else the largest gap
        val gap = gaps.firstOrNull { c >= it.first && c <= it.second } ?: gaps.maxBy { it.second - it.first }
        val gapLen = gap.second - gap.first
        val width = min(desired, gapLen)
        if (width < MIN_EDGE) return EditResult.Rejected("There is no free space on this wall.")
        val offset = (c - width / 2).coerceIn(gap.first, gap.second - width)
        val (mm, id) = m.allocId(if (kind == OpeningKind.WINDOW) "win" else "door")
        val o = Opening(id, wallId, kind, offset, width, provenance = Provenance.PLANNING)
        return Edits.finish(m, mm.copy(openings = mm.openings + o), if (kind == OpeningKind.WINDOW) "Add window" else "Add door")
    }

    fun remove(m: RoomModel, id: String): EditResult {
        if (m.openings.none { it.id == id }) return EditResult.Rejected("Opening not found.")
        return Edits.finish(m, m.copy(openings = m.openings.filter { it.id != id }), "Remove opening")
    }

    private fun replace(m: RoomModel, o: Opening, label: String, entryKey: String? = null, entered: EnteredValue? = null): EditResult {
        val w = m.wall(o.wallId) ?: return EditResult.Rejected("Opening has no wall.")
        val issues = Validation.openingIssues(o, w.length)
        if (issues.isNotEmpty()) return EditResult.Rejected(issues.first().message)
        val clash = m.openingsOn(o.wallId).firstOrNull { it.id != o.id && it.offset < o.offset + o.width - EPS && it.offset + it.width > o.offset + EPS }
        if (clash != null) return EditResult.Rejected("That would overlap another opening.")
        val entries = if (entryKey != null && entered != null) m.entries + (entryKey to entered) else m.entries
        val cand = m.copy(openings = m.openings.map { if (it.id == o.id) o else it }, entries = entries)
        return Edits.finish(m, cand, label, mapOf(o.id to o.offset))
    }

    private fun find(m: RoomModel, id: String) = m.openings.firstOrNull { it.id == id }

    private fun reviewed(o: Opening) = if (o.provenance == Provenance.PLANNING) o.copy(provenance = Provenance.USER_ENTERED) else o

    /** Edit left offset: moves the opening with its width fixed. */
    fun setLeft(m: RoomModel, id: String, v: EnteredValue): EditResult {
        val o = find(m, id) ?: return EditResult.Rejected("Opening not found.")
        return replace(m, o.copy(offset = v.meters), "Set left distance", "opening:$id:left", v)
    }

    /** Edit right offset: offset = wall length − right − width. */
    fun setRight(m: RoomModel, id: String, v: EnteredValue): EditResult {
        val o = find(m, id) ?: return EditResult.Rejected("Opening not found.")
        val w = m.wall(o.wallId) ?: return EditResult.Rejected("Opening has no wall.")
        return replace(m, o.copy(offset = w.length - v.meters - o.width), "Set right distance", "opening:$id:right", v)
    }

    /** Numeric width: window keeps its centre, door keeps its start jamb (D-06). */
    fun setWidth(m: RoomModel, id: String, v: EnteredValue): EditResult {
        val o = find(m, id) ?: return EditResult.Rejected("Opening not found.")
        val width = v.meters
        if (width <= MIN_EDGE) return EditResult.Rejected("Width must be positive.")
        val offset = if (o.kind == OpeningKind.WINDOW) o.offset + o.width / 2 - width / 2 else o.offset
        return replace(m, reviewed(o).copy(offset = offset, width = width), "Set width", "opening:$id:width", v)
    }

    fun setVertical(m: RoomModel, id: String, height: Measure?, bottom: Measure?): EditResult {
        val o = find(m, id) ?: return EditResult.Rejected("Opening not found.")
        return replace(m, o.copy(height = height, bottom = bottom), "Set opening height")
    }

    fun cycleHanding(m: RoomModel, id: String): EditResult {
        val o = find(m, id) ?: return EditResult.Rejected("Opening not found.")
        if (o.kind != OpeningKind.DOOR) return EditResult.Rejected("Only doors have a swing.")
        return replace(m, o.copy(handing = o.handing.next()), "Change door swing")
    }

    /** Commits a gesture result (already clamped preview) as one edit. */
    fun commitGeometry(m: RoomModel, id: String, offset: Double, width: Double, label: String): EditResult {
        val o = find(m, id) ?: return EditResult.Rejected("Opening not found.")
        val changedWidth = abs(width - o.width) > EPS
        val next = (if (changedWidth) reviewed(o) else o).copy(offset = offset, width = width)
        return replace(m, next, label)
    }

    /** Moves an opening to another wall position chosen explicitly (valid relocation). */
    fun relocate(m: RoomModel, id: String, wallId: String, offset: Double): EditResult {
        val o = find(m, id) ?: return EditResult.Rejected("Opening not found.")
        return replace(m, o.copy(wallId = wallId, offset = offset), "Move opening to wall")
    }

    // ---------- drag preview clamping ----------

    /** Clamp an along-wall move to the free interval containing the opening. */
    fun clampMove(m: RoomModel, id: String, desiredOffset: Double): Pair<Double, Boolean> {
        val o = find(m, id) ?: return desiredOffset to false
        val gap = containingGap(m, o) ?: return o.offset to true
        val lo = gap.first; val hi = gap.second - o.width
        val c = desiredOffset.coerceIn(lo, max(lo, hi))
        return c to (abs(c - desiredOffset) > EPS)
    }

    /** Clamp a resize keeping [fixedEdge] (absolute offset) and moving the other edge to [movingEdge]. */
    fun clampResize(m: RoomModel, id: String, fixedEdge: Double, movingEdge: Double): Pair<Pair<Double, Double>, Boolean> {
        val o = find(m, id) ?: return (fixedEdge to movingEdge) to false
        val gap = containingGap(m, o) ?: return (o.offset to o.width) to true
        val minW = MIN_EDGE * 10
        val clamped = if (movingEdge >= fixedEdge) movingEdge.coerceIn(fixedEdge + minW, gap.second)
        else movingEdge.coerceIn(gap.first, fixedEdge - minW)
        val off = min(fixedEdge, clamped); val w = abs(clamped - fixedEdge)
        return (off to w) to (abs(clamped - movingEdge) > EPS)
    }

    /** Symmetric width change about the centre, clamped. */
    fun clampSymmetric(m: RoomModel, id: String, center: Double, desiredWidth: Double): Pair<Pair<Double, Double>, Boolean> {
        val o = find(m, id) ?: return (center - desiredWidth / 2 to desiredWidth) to false
        val gap = containingGap(m, o) ?: return (o.offset to o.width) to true
        val maxHalf = min(center - gap.first, gap.second - center)
        val half = (desiredWidth / 2).coerceIn(MIN_EDGE * 5, max(MIN_EDGE * 5, maxHalf))
        return (center - half to half * 2) to (abs(half * 2 - desiredWidth) > EPS)
    }

    private fun containingGap(m: RoomModel, o: Opening): Pair<Double, Double>? =
        freeIntervals(m, o.wallId, o.id).firstOrNull { o.offset >= it.first - 1e-7 && o.offset + o.width <= it.second + 1e-7 }
}

/** Gesture intent for window/door drags, locked after touch slop for the whole pointer sequence (D-08). */
enum class OpeningIntent { MOVE, FINE_WIDTH, RESIZE_START, RESIZE_END, FINE_START, FINE_END }

enum class OpeningHandle { CENTER, START, END }

object OpeningGesture {
    /** Fine-mode gain relative to finger motion (engineering tuning, C-08). */
    const val FINE_GAIN = 0.25

    /** Decide intent from the first motion beyond slop, in wall-local tangent/normal coordinates. */
    fun intent(handle: OpeningHandle, kind: OpeningKind, delta: Vec2, wallDir: Vec2): OpeningIntent {
        val along = abs(delta dot wallDir)
        val across = abs(delta dot wallDir.perp())
        val parallel = along >= across
        return when (handle) {
            OpeningHandle.CENTER -> if (parallel || kind == OpeningKind.DOOR) OpeningIntent.MOVE else OpeningIntent.FINE_WIDTH
            OpeningHandle.START -> if (parallel || kind == OpeningKind.DOOR) OpeningIntent.RESIZE_START else OpeningIntent.FINE_START
            OpeningHandle.END -> if (parallel || kind == OpeningKind.DOOR) OpeningIntent.RESIZE_END else OpeningIntent.FINE_END
        }
    }

    data class Preview(val offset: Double, val width: Double, val atBoundary: Boolean)

    /**
     * Computes the clamped preview for [total] world-space motion since gesture start.
     * Perpendicular motion along the wall's outward normal expands; inward contracts.
     * [snap] is applied only to coarse modes.
     */
    fun preview(
        m: RoomModel, start: Opening, intent: OpeningIntent, total: Vec2, wallDir: Vec2, outward: Vec2,
        snap: ((Double) -> Double)? = null,
    ): Preview {
        val t = total dot wallDir
        val nrm = total dot outward
        val s0 = start.offset; val e0 = start.offset + start.width
        return when (intent) {
            OpeningIntent.MOVE -> {
                var off = s0 + t
                if (snap != null) off = snap(off)
                val (c, b) = Openings.clampMove(m, start.id, off)
                Preview(c, start.width, b)
            }
            OpeningIntent.FINE_WIDTH -> {
                val center = s0 + start.width / 2
                val (r, b) = Openings.clampSymmetric(m, start.id, center, start.width + 2 * FINE_GAIN * nrm)
                Preview(r.first, r.second, b)
            }
            OpeningIntent.RESIZE_START -> {
                var mv = s0 + t
                if (snap != null) mv = snap(mv)
                val (r, b) = Openings.clampResize(m, start.id, e0, mv); Preview(r.first, r.second, b)
            }
            OpeningIntent.RESIZE_END -> {
                var mv = e0 + t
                if (snap != null) mv = snap(mv)
                val (r, b) = Openings.clampResize(m, start.id, s0, mv); Preview(r.first, r.second, b)
            }
            OpeningIntent.FINE_START -> {
                val (r, b) = Openings.clampResize(m, start.id, e0, s0 - FINE_GAIN * nrm); Preview(r.first, r.second, b)
            }
            OpeningIntent.FINE_END -> {
                val (r, b) = Openings.clampResize(m, start.id, s0, e0 + FINE_GAIN * nrm); Preview(r.first, r.second, b)
            }
        }
    }
}
