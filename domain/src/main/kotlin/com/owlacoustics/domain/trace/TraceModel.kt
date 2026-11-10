package com.owlacoustics.domain.trace

import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import kotlinx.serialization.Serializable

@Serializable
enum class Surface { FLOOR, CEILING }

/** A corner bends the boundary and shapes the room. A marker sits on a straight line and only splits a measurement. */
@Serializable
enum class AnchorKind { CORNER, MARKER }

/**
 * One point on a traced boundary.
 *
 * A corner keeps its original observation: the unit world ray from the camera ([dx], [dy], [dz]; z up), the phone's
 * horizontal heading when it was captured ([hx], [hy]; used by the stand-and-turn pivot model) and the tracked camera
 * position when a tracker supplies one ([px], [py], [pz]). A marker keeps only [t], its fraction along the straight
 * line between the corners either side.
 */
@Serializable
data class TraceAnchor(
    val id: Int,
    val kind: AnchorKind = AnchorKind.CORNER,
    val dx: Double = 0.0, val dy: Double = 0.0, val dz: Double = 0.0,
    val hx: Double = 1.0, val hy: Double = 0.0,
    val px: Double? = null, val py: Double? = null, val pz: Double? = null,
    /** The line arriving at this point followed an edge the camera saw (false: drawn freehand, lower confidence). */
    val snapped: Boolean = true,
    /** Placed or moved by hand; never merged away automatically. */
    val forced: Boolean = false,
    val t: Double = 0.0,
) {
    val isCorner get() = kind == AnchorKind.CORNER
    val ray get() = Vec3(dx, dy, dz)
    val heading get() = Vec2(hx, hy)
    val tracked get() = if (px != null && py != null && pz != null) Vec3(px, py, pz) else null
}

/** A corner observation about to be added. */
data class NewCorner(val ray: Vec3, val heading: Vec2, val tracked: Vec3? = null, val snapped: Boolean = true, val forced: Boolean = false)

/** Anchors in boundary order. Markers follow the corner that starts their line; those after the last corner lie on the closing line. */
@Serializable
data class TraceBoundary(val anchors: List<TraceAnchor> = emptyList(), val closed: Boolean = false, val closingSnapped: Boolean = true) {
    val corners get() = anchors.filter { it.isCorner }
    fun find(id: Int) = anchors.firstOrNull { it.id == id }

    /** Consecutive anchor pairs, including the closing pair of a closed boundary. */
    fun pairs(): List<Pair<TraceAnchor, TraceAnchor>> {
        if (anchors.size < 2) return emptyList()
        val out = ArrayList<Pair<TraceAnchor, TraceAnchor>>()
        for (i in 0 until anchors.size - 1) out += anchors[i] to anchors[i + 1]
        if (closed) out += anchors.last() to anchors.first()
        return out
    }
}

/** A length the user typed. [text] is kept exactly as entered. */
@Serializable
data class TypedLength(val surface: Surface, val a: Int, val b: Int, val meters: Double, val text: String) {
    fun joins(x: Int, y: Int) = (a == x && b == y) || (a == y && b == x)
}

@Serializable
data class TypedValue(val meters: Double, val text: String)

@Serializable
data class TraceDoc(
    val floor: TraceBoundary = TraceBoundary(),
    val ceiling: TraceBoundary = TraceBoundary(),
    val typed: List<TypedLength> = emptyList(),
    val ceilingHeight: TypedValue? = null,
    val nextId: Int = 1,
) {
    fun boundary(s: Surface) = if (s == Surface.FLOOR) floor else ceiling
    fun with(s: Surface, b: TraceBoundary) = if (s == Surface.FLOOR) copy(floor = b) else copy(ceiling = b)
    fun typedFor(s: Surface, a: Int, b: Int) = typed.firstOrNull { it.surface == s && it.joins(a, b) }
    val isEmpty get() = floor.anchors.isEmpty() && ceiling.anchors.isEmpty() && typed.isEmpty() && ceilingHeight == null
}

/** Pure edits; each returns a new document. */
object TraceEdits {
    private fun anchorOf(id: Int, c: NewCorner) = TraceAnchor(
        id, AnchorKind.CORNER, c.ray.x, c.ray.y, c.ray.z, c.heading.x, c.heading.y, c.tracked?.x, c.tracked?.y, c.tracked?.z, c.snapped, c.forced)

    /** Adds corners to the end of an open boundary; [close] joins the end back to the first corner. */
    fun append(doc: TraceDoc, s: Surface, corners: List<NewCorner>, close: Boolean = false, closingSnapped: Boolean = true): TraceDoc {
        val b = doc.boundary(s)
        if (b.closed) return doc
        var id = doc.nextId
        val added = corners.map { anchorOf(id++, it) }
        val anchors = b.anchors + added
        val canClose = close && anchors.count { it.isCorner } >= 3
        return prune(doc.with(s, TraceBoundary(anchors, canClose, !canClose || closingSnapped)).copy(nextId = id))
    }

    fun close(doc: TraceDoc, s: Surface, closingSnapped: Boolean = true): TraceDoc {
        val b = doc.boundary(s)
        return if (b.closed || b.corners.size < 3) doc else doc.with(s, b.copy(closed = true, closingSnapped = closingSnapped))
    }

    /** Replaces a corner's observation (the user dragged it). */
    fun moveCorner(doc: TraceDoc, s: Surface, id: Int, c: NewCorner): TraceDoc {
        val b = doc.boundary(s)
        if (b.find(id)?.isCorner != true) return doc
        return doc.with(s, b.copy(anchors = b.anchors.map { if (it.id == id) anchorOf(id, c.copy(forced = true)).copy(snapped = it.snapped) else it }))
    }

    /** Slides a marker along its line. */
    fun moveMarker(doc: TraceDoc, s: Surface, id: Int, t: Double): TraceDoc {
        val b = doc.boundary(s)
        if (b.find(id)?.kind != AnchorKind.MARKER) return doc
        val moved = b.anchors.map { if (it.id == id) it.copy(t = t.coerceIn(0.02, 0.98)) else it }
        return prune(doc.with(s, b.copy(anchors = sortMarkers(moved))))
    }

    /** Adds a marker at fraction [t] of the line that starts at corner [cornerId]. Returns the document and the new id. */
    fun insertMarker(doc: TraceDoc, s: Surface, cornerId: Int, t: Double): Pair<TraceDoc, Int?> {
        val b = doc.boundary(s)
        val i = b.anchors.indexOfFirst { it.id == cornerId && it.isCorner }
        if (i < 0) return doc to null
        val hasFarCorner = b.closed || b.anchors.drop(i + 1).any { it.isCorner }
        if (!hasFarCorner) return doc to null
        val id = doc.nextId
        val m = TraceAnchor(id, AnchorKind.MARKER, t = t.coerceIn(0.02, 0.98), forced = true)
        val anchors = b.anchors.toMutableList().apply { add(i + 1, m) }
        return prune(doc.with(s, b.copy(anchors = sortMarkers(anchors))).copy(nextId = id + 1)) to id
    }

    /**
     * Deletes a corner or a marker. Deleting a corner merges its two lines into one; markers on them keep their place
     * using [positions] (anchor id → plan position from the current solution). Deleting a marker only merges two
     * measurements.
     */
    fun delete(doc: TraceDoc, s: Surface, id: Int, positions: Map<Int, Vec2> = emptyMap()): TraceDoc {
        val b = doc.boundary(s)
        val target = b.find(id) ?: return doc
        if (!target.isCorner) return prune(doc.with(s, b.copy(anchors = b.anchors.filter { it.id != id })))
        val corners = b.corners
        val k = corners.indexOfFirst { it.id == id }
        val n = corners.size
        val stillClosed = b.closed && n - 1 >= 3
        val prev = if (k > 0) corners[k - 1] else if (b.closed) corners[n - 1] else null
        val next = if (k < n - 1) corners[k + 1] else if (b.closed) corners[0] else null
        if (b.closed && !stillClosed) {
            // fewer than three corners left: back to a plain open line without markers
            return prune(doc.with(s, TraceBoundary(corners.filter { it.id != id }, false)))
        }
        val groups = markerGroups(b)
        val keep = ArrayList<TraceAnchor>()
        for (c in corners) {
            if (c.id == id) continue
            keep += c
            val own = groups[c.id].orEmpty()
            if (prev != null && next != null && c.id == prev.id) {
                // markers of prev→deleted and deleted→next move onto prev→next
                val a = positions[prev.id]; val z = positions[next.id]
                val merged = (own + groups[id].orEmpty()).mapNotNull { m ->
                    val p = positions[m.id]
                    if (a == null || z == null || p == null) null
                    else { val d = z - a; val l2 = d dot d; if (l2 < 1e-12) null else m.copy(t = (((p - a) dot d) / l2).coerceIn(0.02, 0.98)) }
                }
                keep += merged.sortedBy { it.t }
            } else if (prev == null && next != null && c.id == next.id) {
                keep += own // deleted the first corner of an open line: its markers go with it
            } else if (!(next == null && prev != null && c.id == prev.id)) {
                keep += own
            }
        }
        return prune(doc.with(s, TraceBoundary(keep, stillClosed)))
    }

    fun setTyped(doc: TraceDoc, s: Surface, a: Int, b: Int, meters: Double, text: String): TraceDoc =
        doc.copy(typed = doc.typed.filterNot { it.surface == s && it.joins(a, b) } + TypedLength(s, a, b, meters, text))

    fun clearTyped(doc: TraceDoc, s: Surface, a: Int, b: Int): TraceDoc =
        doc.copy(typed = doc.typed.filterNot { it.surface == s && it.joins(a, b) })

    fun setCeilingHeight(doc: TraceDoc, v: TypedValue?): TraceDoc = doc.copy(ceilingHeight = v)

    /** Empty document that keeps issuing fresh ids, so an undo of the restart never collides. */
    fun restart(doc: TraceDoc) = TraceDoc(nextId = doc.nextId)

    private fun markerGroups(b: TraceBoundary): Map<Int, List<TraceAnchor>> {
        val out = HashMap<Int, MutableList<TraceAnchor>>()
        var cur: Int? = null
        for (a in b.anchors) if (a.isCorner) cur = a.id else cur?.let { out.getOrPut(it) { ArrayList() } += a }
        return out
    }

    private fun sortMarkers(anchors: List<TraceAnchor>): List<TraceAnchor> {
        val out = ArrayList<TraceAnchor>(anchors.size)
        val run = ArrayList<TraceAnchor>()
        for (a in anchors) {
            if (a.isCorner) { out += run.sortedBy { it.t }; run.clear(); out += a } else run += a
        }
        out += run.sortedBy { it.t }
        return out
    }

    /** Drops typed lengths whose two points are no longer neighbours, and markers left without a line. */
    private fun prune(doc: TraceDoc): TraceDoc {
        fun clean(b: TraceBoundary): TraceBoundary {
            val firstCorner = b.anchors.indexOfFirst { it.isCorner }
            if (firstCorner < 0) return TraceBoundary()
            var anchors = b.anchors.drop(firstCorner)
            if (!b.closed) { val last = anchors.indexOfLast { it.isCorner }; anchors = anchors.take(last + 1) }
            return b.copy(anchors = anchors)
        }
        val f = clean(doc.floor); val c = clean(doc.ceiling)
        fun ok(t: TypedLength): Boolean = (if (t.surface == Surface.FLOOR) f else c).pairs().any { t.joins(it.first.id, it.second.id) }
        return doc.copy(floor = f, ceiling = c, typed = doc.typed.filter(::ok))
    }
}

/** Linear undo/redo over whole documents (they are small). */
data class TraceHistory(val past: List<TraceDoc> = emptyList(), val present: TraceDoc = TraceDoc(), val future: List<TraceDoc> = emptyList()) {
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun commit(next: TraceDoc): TraceHistory = if (next == present) this else TraceHistory((past + present).takeLast(LIMIT), next, emptyList())
    fun undo(): TraceHistory = if (!canUndo) this else TraceHistory(past.dropLast(1), past.last(), listOf(present) + future)
    fun redo(): TraceHistory = if (!canRedo) this else TraceHistory(past + present, future.first(), future.drop(1))
    companion object { const val LIMIT = 200 }
}

/** Puts fitted strokes and tapped points onto a boundary: continues from the last point, closes the loop, merges straight joins. */
object TraceAssembler {
    /** Joins that turn by less than this are treated as one straight wall (unless the point was placed by hand). */
    const val STRAIGHT_DEG = 7.0
    private const val NEAR_M = 0.25
    private const val OFFSET_M = 0.03

    /**
     * @param corners new corners in drawing order (leave out the first when the stroke continued from the last point,
     * and the last when it ended on the first point to close the loop)
     * @param closes the stroke ended on the boundary's first point
     * @param closingSnapped whether the closing line followed an edge
     */
    fun addStroke(doc: TraceDoc, s: Surface, corners: List<NewCorner>, closes: Boolean, closingSnapped: Boolean, settings: TraceSettings): TraceDoc {
        val before = doc.boundary(s)
        if (before.closed) return doc
        val join = before.corners.lastOrNull()?.id
        var out = TraceEdits.append(doc, s, corners, closes, closingSnapped)
        if (join != null && corners.isNotEmpty()) out = mergeIfStraight(out, s, join, settings)
        else if (join != null && closes) out = mergeIfStraight(out, s, join, settings)
        if (out.boundary(s).closed) out.boundary(s).corners.firstOrNull()?.let { out = mergeIfStraight(out, s, it.id, settings) }
        return out
    }

    /** Removes corner [id] when the boundary runs straight through it and it was not placed by hand. */
    fun mergeIfStraight(doc: TraceDoc, s: Surface, id: Int, settings: TraceSettings): TraceDoc {
        val b = doc.boundary(s)
        val corners = b.corners
        val k = corners.indexOfFirst { it.id == id }
        if (k < 0 || corners[k].forced) return doc
        val n = corners.size
        if (b.closed && n <= 3) return doc
        val prev = if (k > 0) corners[k - 1] else if (b.closed) corners[n - 1] else return doc
        val next = if (k < n - 1) corners[k + 1] else if (b.closed) corners[0] else return doc
        val pos = TraceSolver.solve(doc, settings).planPositions(s)
        val p = pos[id] ?: return doc; val a = pos[prev.id] ?: return doc; val c = pos[next.id] ?: return doc
        val u = (p - a).normalized(); val v = (c - p).normalized()
        if (u.length < 0.5 || v.length < 0.5) return doc
        val turn = Math.toDegrees(kotlin.math.abs(kotlin.math.atan2(u cross v, u dot v)))
        // straight by angle, or (for a point very close to a neighbour, where the angle is noise) by its offset from the line
        val line = (c - a).normalized()
        val along = (p - a) dot line
        val offset = kotlin.math.abs((p - a) cross line)
        val near = minOf(p.distanceTo(a), p.distanceTo(c)) < NEAR_M
        val straight = turn <= STRAIGHT_DEG || (near && offset <= OFFSET_M && along > 0 && along < a.distanceTo(c))
        if (!straight) return doc
        // the merged line is only as trustworthy as its weaker half
        val intoThis = corners[k].snapped
        val closingIntoNext = b.closed && k == n - 1
        val marked = if (closingIntoNext) b.copy(closingSnapped = b.closingSnapped && intoThis)
            else b.copy(anchors = b.anchors.map { if (it.id == next.id) it.copy(snapped = it.snapped && intoThis) else it })
        return TraceEdits.delete(doc.with(s, marked), s, id, pos)
    }
}
