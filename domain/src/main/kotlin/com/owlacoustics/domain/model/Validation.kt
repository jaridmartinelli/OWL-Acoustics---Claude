package com.owlacoustics.domain.model

import com.owlacoustics.domain.geometry.EPS
import com.owlacoustics.domain.geometry.MIN_EDGE
import com.owlacoustics.domain.geometry.Polygons

enum class Severity { BLOCKING, REVIEW }

data class Issue(val code: String, val severity: Severity, val message: String, val objectId: String? = null)

object Validation {
    /** Topology/opening checks applied at edit commit, load and calculation boundaries. */
    fun validate(m: RoomModel): List<Issue> {
        val out = ArrayList<Issue>()
        val v = m.vertices
        if (v.size < 3) {
            out += Issue("too_few_vertices", Severity.BLOCKING, "A room outline needs at least three corners.")
            return out
        }
        if (v.any { !it.x.isFinite() || !it.y.isFinite() }) {
            out += Issue("non_finite", Severity.BLOCKING, "A corner has an invalid coordinate.")
            return out
        }
        val ids = v.map { it.id }
        if (ids.toSet().size != ids.size) out += Issue("dup_vertex_id", Severity.BLOCKING, "Duplicate corner identity.")
        val wallIds = v.map { it.wallId }
        if (wallIds.toSet().size != wallIds.size) out += Issue("dup_wall_id", Severity.BLOCKING, "Duplicate wall identity.")
        for (w in m.walls) {
            if (w.length < MIN_EDGE) out += Issue("zero_wall", Severity.BLOCKING, "A wall has zero length.", w.id)
        }
        val pts = m.outline
        if (Polygons.firstSelfIntersection(pts) != null) {
            out += Issue("self_intersection", Severity.BLOCKING, "Walls cross or fold back on each other.")
        }
        if (Polygons.signedArea(pts) <= EPS) {
            out += Issue("orientation", Severity.BLOCKING, "The outline has no interior area.")
        }
        for (o in m.openings) {
            val w = m.wall(o.wallId)
            if (w == null) {
                out += Issue("orphan_opening", Severity.BLOCKING, "An opening is not attached to a wall.", o.id)
                continue
            }
            out += openingIssues(o, w.length)
        }
        for ((wallId, list) in m.openings.groupBy { it.wallId }) {
            val sorted = list.sortedBy { it.offset }
            for (k in 1 until sorted.size) {
                val p = sorted[k - 1]; val q = sorted[k]
                if (p.offset + p.width > q.offset + EPS) {
                    out += Issue("opening_overlap", Severity.BLOCKING, "Two openings overlap on the same wall.", q.id)
                }
            }
            if (m.wall(wallId) == null) continue
        }
        return out
    }

    fun openingIssues(o: Opening, wallLength: Double): List<Issue> {
        val out = ArrayList<Issue>()
        if (!o.offset.isFinite() || !o.width.isFinite()) out += Issue("opening_nan", Severity.BLOCKING, "Opening size is invalid.", o.id)
        if (o.width <= MIN_EDGE) out += Issue("opening_width", Severity.BLOCKING, "Opening width must be positive.", o.id)
        if (o.offset < -EPS || o.offset + o.width > wallLength + EPS) {
            out += Issue("opening_range", Severity.BLOCKING, "Opening extends beyond its wall.", o.id)
        }
        o.height?.let { if (it.meters <= 0) out += Issue("opening_height", Severity.BLOCKING, "Opening height must be positive.", o.id) }
        o.bottom?.let { if (it.meters < 0) out += Issue("opening_bottom", Severity.BLOCKING, "Bottom elevation cannot be negative.", o.id) }
        return out
    }

    fun blocking(m: RoomModel) = validate(m).filter { it.severity == Severity.BLOCKING }

    /** Review items for plan confirmation that are not blocking. */
    fun reviewItems(m: RoomModel): List<Issue> {
        val out = ArrayList<Issue>()
        if (m.scale.kind == ScaleKind.PLANNING) {
            out += Issue("planning_scale", Severity.REVIEW,
                "Dimensions are a planning drawing, not measurements. Replace them with your room's actual dimensions if known.")
        }
        if (m.scale.kind == ScaleKind.UNKNOWN) {
            out += Issue("unknown_scale", Severity.BLOCKING, "Scale is unknown. Calibrate with a known length or enter dimensions.")
        }
        val provisional = m.openings.filter { it.provenance == Provenance.PLANNING }
        if (provisional.isNotEmpty()) {
            out += Issue("provisional_openings", Severity.REVIEW,
                "${provisional.size} opening size(s) are placeholders that have not been reviewed.")
        }
        return out
    }
}
