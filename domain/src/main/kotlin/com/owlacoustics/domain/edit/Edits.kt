package com.owlacoustics.domain.edit

import com.owlacoustics.domain.geometry.EPS
import com.owlacoustics.domain.geometry.MIN_EDGE
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.Opening
import com.owlacoustics.domain.model.Provenance
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.Validation
import com.owlacoustics.domain.model.Vertex
import com.owlacoustics.domain.units.EnteredValue
import kotlin.math.abs

sealed interface EditResult {
    data class Ok(val model: RoomModel, val label: String) : EditResult
    data class Rejected(val reason: String) : EditResult
}

fun EditResult.modelOrNull() = (this as? EditResult.Ok)?.model

/** Allocates a new stable ID inside the model. */
fun RoomModel.allocId(prefix: String): Pair<RoomModel, String> = copy(nextId = nextId + 1) to "$prefix$nextId"

/**
 * Validated geometry commands. Every durable change (gesture commit or exact entry) passes through here.
 * Commands never silently delete an opening or move a locked vertex.
 */
object Edits {

    /** Normalises orientation, re-attaches openings and validates; rejects with an explanation. */
    fun finish(old: RoomModel, candidate: RoomModel, label: String, keepOpeningsFrom: Map<String, Double>? = null): EditResult {
        var m = candidate
        if (Polygons.signedArea(m.outline) < 0) return EditResult.Rejected("That change would turn the room inside out.")
        m = m.copy(openings = reattach(old, m, keepOpeningsFrom))
        m = pruneStaleEntries(m)
        // the confirmed drawing no longer matches: it must be reviewed again (vertical opening data does not count)
        if (old.planConfirmed && m.planConfirmed && planGeometryChanged(old, m)) m = m.copy(planConfirmed = false)
        val blocking = Validation.blocking(m)
        if (blocking.isNotEmpty()) return EditResult.Rejected(blocking.first().message)
        if (m == old) return EditResult.Rejected("No change.")
        return EditResult.Ok(m, label)
    }

    private fun reattach(old: RoomModel, new: RoomModel, explicit: Map<String, Double>?): List<Opening> =
        new.openings.map { o ->
            explicit?.get(o.id)?.let { return@map o.copy(offset = it) }
            val ow = old.wall(o.wallId) ?: return@map o
            val nw = new.wall(o.wallId) ?: return@map o
            val startSame = ow.a.distanceTo(nw.a) < EPS
            val endSame = ow.b.distanceTo(nw.b) < EPS
            if (!startSame && endSame) {
                val fromEnd = ow.length - o.offset
                o.copy(offset = nw.length - fromEnd)
            } else o
        }

    private fun planGeometryChanged(a: RoomModel, b: RoomModel): Boolean {
        if (a.vertices.map { Triple(it.id, it.x, it.y) } != b.vertices.map { Triple(it.id, it.x, it.y) }) return true
        fun h(m: RoomModel) = m.openings.map { listOf(it.id, it.wallId, it.kind, it.offset, it.width) }.toSet()
        return h(a) != h(b)
    }

    /** Drops exact entered values that no longer describe the geometry (they would be misleading). */
    fun pruneStaleEntries(m: RoomModel): RoomModel {
        val keep = m.entries.filter { (key, v) ->
            val parts = key.split(':')
            when {
                parts.size == 3 && parts[0] == "wall" && parts[2] == "length" ->
                    m.wall(parts[1])?.let { abs(it.length - v.meters) < 1e-9 } ?: false
                parts.size == 3 && parts[0] == "opening" -> {
                    val o = m.openings.firstOrNull { it.id == parts[1] } ?: return@filter false
                    val w = m.wall(o.wallId) ?: return@filter false
                    val actual = when (parts[2]) {
                        "left" -> o.offset
                        "right" -> w.length - o.offset - o.width
                        "width" -> o.width
                        else -> return@filter true
                    }
                    abs(actual - v.meters) < 1e-9
                }
                else -> true
            }
        }
        return if (keep.size == m.entries.size) m else m.copy(entries = keep)
    }

    private fun markEdited(v: Vertex) = v.copy(
        provenance = if (v.provenance == Provenance.PLANNING) Provenance.PLANNING else Provenance.CORRECTED,
    )

    fun moveVertex(m: RoomModel, vertexId: String, to: Vec2): EditResult {
        val i = m.vertices.indexOfFirst { it.id == vertexId }
        if (i < 0) return EditResult.Rejected("Corner not found.")
        val v = m.vertices[i]
        if (v.locked) return EditResult.Rejected("This corner is locked.")
        if (!to.isFinite()) return EditResult.Rejected("Invalid position.")
        val nv = m.vertices.toMutableList()
        nv[i] = markEdited(v).copy(x = to.x, y = to.y)
        return finish(m, m.copy(vertices = nv), "Move corner")
    }

    /** Moves a wall along its outward normal by [distance]; neighbours keep their directions. */
    fun moveWall(m: RoomModel, wallId: String, distance: Double): EditResult {
        val w = m.wall(wallId) ?: return EditResult.Rejected("Wall not found.")
        if (w.start.locked || w.end.locked) return EditResult.Rejected("A corner of this wall is locked.")
        val n = m.vertices.size
        val prev = m.wallAt((w.index + n - 1) % n)
        val next = m.wallAt((w.index + 1) % n)
        val shift = w.outward * distance
        val la = w.a + shift
        val newA = Polygons.lineIntersection(la, w.dir, prev.a, prev.dir)
        val newB = Polygons.lineIntersection(la, w.dir, next.a, next.dir)
        if (newA == null || newB == null) {
            return EditResult.Rejected("A neighbouring wall runs straight on from this one. Remove that corner or drag the corners instead.")
        }
        val nv = m.vertices.toMutableList()
        nv[w.index] = markEdited(w.start).copy(x = newA.x, y = newA.y)
        val j = (w.index + 1) % n
        nv[j] = markEdited(w.end).copy(x = newB.x, y = newB.y)
        return finish(m, m.copy(vertices = nv), "Move wall")
    }

    /** D-05: anchor the start corner and move the far corner along the wall direction. */
    fun setWallLength(m: RoomModel, wallId: String, entered: EnteredValue): EditResult {
        val len = entered.meters
        if (!len.isFinite() || len < MIN_EDGE) return EditResult.Rejected("Wall length must be positive.")
        val w = m.wall(wallId) ?: return EditResult.Rejected("Wall not found.")
        if (w.end.locked) return EditResult.Rejected("The far corner of this wall is locked.")
        val to = w.a + w.dir * len
        val nv = m.vertices.toMutableList()
        val j = (w.index + 1) % m.vertices.size
        nv[j] = w.end.copy(x = to.x, y = to.y, provenance = Provenance.USER_ENTERED)
        val cand = m.copy(vertices = nv, entries = m.entries + ("wall:$wallId:length" to entered))
        return finish(m, cand, "Set wall length")
    }

    /** Rotates the wall about its start corner to the absolute bearing (degrees CCW from +x). */
    fun setWallBearing(m: RoomModel, wallId: String, bearingDeg: Double): EditResult {
        if (!bearingDeg.isFinite()) return EditResult.Rejected("Invalid angle.")
        val w = m.wall(wallId) ?: return EditResult.Rejected("Wall not found.")
        if (w.end.locked) return EditResult.Rejected("The far corner of this wall is locked.")
        val to = w.a + Vec2.fromBearingDeg(bearingDeg) * w.length
        val nv = m.vertices.toMutableList()
        val j = (w.index + 1) % m.vertices.size
        nv[j] = w.end.copy(x = to.x, y = to.y, provenance = Provenance.USER_ENTERED)
        return finish(m, m.copy(vertices = nv), "Set wall angle")
    }

    /** Inserts a corner at [offset] along the wall (split). Openings must not straddle the split point. */
    fun splitWall(m: RoomModel, wallId: String, offset: Double): EditResult {
        val w = m.wall(wallId) ?: return EditResult.Rejected("Wall not found.")
        if (offset < MIN_EDGE || offset > w.length - MIN_EDGE) return EditResult.Rejected("Split point must be inside the wall.")
        val straddle = m.openingsOn(wallId).firstOrNull { offset > it.offset + EPS && offset < it.offset + it.width - EPS }
        if (straddle != null) return EditResult.Rejected("The split point is inside an opening. Choose a point beside it.")
        var mm = m
        val (m1, vid) = mm.allocId("v"); mm = m1
        val (m2, wid) = mm.allocId("w"); mm = m2
        val p = w.pointAt(offset)
        val nv = mm.vertices.toMutableList()
        nv.add(w.index + 1, Vertex(vid, p.x, p.y, wid, provenance = Provenance.USER_ENTERED))
        val ops = mm.openings.map { o ->
            if (o.wallId == wallId && o.offset >= offset - EPS) o.copy(wallId = wid, offset = o.offset - offset) else o
        }
        val explicit = ops.filter { it.wallId == wid || it.wallId == wallId }.associate { it.id to it.offset }
        return finish(m, mm.copy(vertices = nv, openings = ops), "Split wall", explicit)
    }

    /** Removes an intermediate corner, merging its two walls. Openings keep their positions by projection. */
    fun removeVertex(m: RoomModel, vertexId: String): EditResult {
        val n = m.vertices.size
        if (n <= 3) return EditResult.Rejected("A room needs at least three corners.")
        val i = m.vertices.indexOfFirst { it.id == vertexId }
        if (i < 0) return EditResult.Rejected("Corner not found.")
        val v = m.vertices[i]
        if (v.locked) return EditResult.Rejected("This corner is locked.")
        val prev = m.wallAt((i + n - 1) % n)
        val cur = m.wallAt(i)
        val merged = prev.id
        val na = prev.a; val nb = cur.b
        val dir = (nb - na).normalized()
        val ops = m.openings.map { o ->
            if (o.wallId != prev.id && o.wallId != cur.id) return@map o
            val w = if (o.wallId == prev.id) prev else cur
            val s = w.pointAt(o.offset)
            val c = s + w.dir * (o.width / 2)
            val proj = (c - na) dot dir
            o.copy(wallId = merged, offset = proj - o.width / 2)
        }
        val nv = m.vertices.toMutableList().also { it.removeAt(i) }
        val explicit = ops.filter { it.wallId == merged }.associate { it.id to it.offset }
        return finish(m, m.copy(vertices = nv, openings = ops), "Remove corner", explicit)
    }

    /** Adds a rectangular recess (alcove) outward from the wall at [centerOffset]. */
    fun addAlcove(m: RoomModel, wallId: String, centerOffset: Double, width: Double, depth: Double): EditResult {
        val w = m.wall(wallId) ?: return EditResult.Rejected("Wall not found.")
        if (width < MIN_EDGE || depth < MIN_EDGE) return EditResult.Rejected("Alcove width and depth must be positive.")
        val s = centerOffset - width / 2; val e = centerOffset + width / 2
        if (s < MIN_EDGE || e > w.length - MIN_EDGE) return EditResult.Rejected("The alcove must fit inside the wall with a corner on each side.")
        val clash = m.openingsOn(wallId).firstOrNull { it.offset < e - EPS && it.offset + it.width > s + EPS }
        if (clash != null) return EditResult.Rejected("An opening is in the way of the alcove. Move it first.")
        var mm = m
        val ids = (0 until 4).map { val (m1, id) = mm.allocId("v"); mm = m1; id }
        val wids = (0 until 4).map { val (m1, id) = mm.allocId("w"); mm = m1; id }
        val a = w.pointAt(s); val b = w.pointAt(e)
        val out = w.outward * depth
        val a2 = a + out; val b2 = b + out
        val nv = mm.vertices.toMutableList()
        val ins = listOf(
            Vertex(ids[0], a.x, a.y, wids[0], Provenance.USER_ENTERED),
            Vertex(ids[1], a2.x, a2.y, wids[1], Provenance.USER_ENTERED),
            Vertex(ids[2], b2.x, b2.y, wids[2], Provenance.USER_ENTERED),
            Vertex(ids[3], b.x, b.y, wids[3], Provenance.USER_ENTERED),
        )
        nv.addAll(w.index + 1, ins)
        val ops = mm.openings.map { o ->
            if (o.wallId == wallId && o.offset >= e - EPS) o.copy(wallId = wids[3], offset = o.offset - e) else o
        }
        val explicit = ops.filter { it.wallId == wids[3] || it.wallId == wallId }.associate { it.id to it.offset }
        return finish(m, mm.copy(vertices = nv, openings = ops), "Add alcove", explicit)
    }

    /** Replaces the outline (templates, import tracing). Openings are removed only if the caller passes an empty model. */
    fun replaceOutline(m: RoomModel, points: List<Vec2>, provenance: Provenance, label: String): EditResult {
        if (points.size < 3) return EditResult.Rejected("Trace at least three corners.")
        if (m.openings.isNotEmpty()) return EditResult.Rejected("Remove doors and windows before replacing the whole outline.")
        val ccw = if (Polygons.signedArea(points) < 0) points.reversed() else points
        var mm = m
        val verts = ccw.map { p ->
            val (m1, vid) = mm.allocId("v"); mm = m1
            val (m2, wid) = mm.allocId("w"); mm = m2
            Vertex(vid, p.x, p.y, wid, provenance)
        }
        return finish(m, mm.copy(vertices = verts, entries = emptyMap(), planConfirmed = false), label)
    }

    fun setLocked(m: RoomModel, vertexId: String, locked: Boolean): EditResult {
        val nv = m.vertices.map { if (it.id == vertexId) it.copy(locked = locked) else it }
        return finish(m, m.copy(vertices = nv), if (locked) "Lock corner" else "Unlock corner")
    }
}
