package com.owlacoustics.domain.capture

import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import kotlinx.serialization.Serializable

/** Server-route clip limit (§3.1). */
const val MAX_CLIP_MS = 60_000L

/** Remote analysis configuration (B-07). Null URL means the route is unavailable. */
data class RemoteAnalysisConfig(val serviceUrl: String?, val consentText: String?, val retentionPolicy: String?) {
    val available get() = !serviceUrl.isNullOrBlank() && !consentText.isNullOrBlank() && !retentionPolicy.isNullOrBlank()
    val missing get() = listOfNotNull(
        "service URL and user sign-in".takeIf { serviceUrl.isNullOrBlank() },
        "approved consent text".takeIf { consentText.isNullOrBlank() },
        "retention/deletion policy".takeIf { retentionPolicy.isNullOrBlank() },
    )
}

@Serializable
data class Consent(val purpose: String, val text: String, val grantedMs: Long, val retention: String)

@Serializable
data class FrameRef(val index: Int, val timeMs: Long, val clipMediaId: String)

@Serializable
data class AnalysisRequest(
    val roomId: String,
    val generation: Long,
    val inputHash: String,
    val consent: Consent,
    /** Chronological, correlated frames from the clip(s); order is significant. */
    val frames: List<FrameRef>,
)

@Serializable
data class CandidateWall(val id: String, val start: Vec2? = null, val end: Vec2? = null, val evidenceFrames: List<Int> = emptyList(), val sufficiency: String = "uncertain")

@Serializable
data class CandidateOpening(val id: String, val wallId: String, val kind: String, val offset: Double? = null, val width: Double? = null)

@Serializable
data class ScaleEvidence(val kind: String, val description: String, val metersPerUnit: Double)

@Serializable
data class AnalysisResponse(
    val schema: Int,
    val roomId: String,
    val generation: Long,
    val walls: List<CandidateWall>,
    val openings: List<CandidateOpening> = emptyList(),
    val unresolvedRegions: List<String> = emptyList(),
    val scale: ScaleEvidence? = null,
    val provider: String,
)

/** Conservative import of a remote candidate plan: never closes, squares or scales anything that is not supported. */
data class CandidatePlan(
    val vertices: List<Vec2>?,
    val closed: Boolean,
    val metric: Boolean,
    val issues: List<String>,
    val openings: List<CandidateOpening>,
)

object CandidateImport {
    fun validate(req: AnalysisRequest, resp: AnalysisResponse): CandidatePlan {
        val issues = ArrayList<String>()
        if (resp.schema != 1) return CandidatePlan(null, false, false, listOf("Unsupported response format."), emptyList())
        if (resp.roomId != req.roomId || resp.generation != req.generation) {
            return CandidatePlan(null, false, false, listOf("Result belongs to a different room or an older edit; ignored."), emptyList())
        }
        val walls = resp.walls
        if (walls.any { w -> listOfNotNull(w.start, w.end).any { !it.isFinite() } }) {
            return CandidatePlan(null, false, false, listOf("Response contained invalid coordinates."), emptyList())
        }
        val complete = walls.filter { it.start != null && it.end != null }
        if (complete.size < walls.size) issues += "${walls.size - complete.size} wall(s) have unknown endpoints and stay unresolved."
        // chain walls end-to-start; closure only if the chain genuinely returns to its start
        val chain = ArrayList<Vec2>()
        var closed = false
        if (complete.size == walls.size && complete.size >= 3) {
            for ((i, w) in complete.withIndex()) {
                if (i > 0 && complete[i - 1].end!!.distanceTo(w.start!!) > 1e-6) { issues += "Walls ${complete[i - 1].id} and ${w.id} do not connect."; break }
                chain += w.start!!
            }
            closed = chain.size == complete.size && complete.last().end!!.distanceTo(complete.first().start!!) <= 1e-6
            if (!closed) issues += "Outline is not closed by the evidence; it will not be closed automatically."
        }
        val verts = if (closed && Polygons.firstSelfIntersection(chain) == null) chain else null
        if (closed && verts == null) issues += "Candidate outline crosses itself."
        val metric = resp.scale != null && resp.scale.metersPerUnit.isFinite() && resp.scale.metersPerUnit > 0
        if (!metric) issues += "No supported metric scale in the result; dimensions stay unknown until you calibrate."
        if (resp.unresolvedRegions.isNotEmpty()) issues += "Unresolved: " + resp.unresolvedRegions.joinToString()
        val scaled = if (metric && verts != null) verts.map { it * resp.scale!!.metersPerUnit } else verts
        return CandidatePlan(scaled, closed && verts != null, metric, issues, resp.openings)
    }
}

/**
 * Viewpoint-independence policy (F-08): near-identical frames (small perceptual-hash distance) do not count as new
 * viewpoints. The hash is a 64-bit difference hash computed from real frame luminance by the app.
 */
object ViewpointPolicy {
    const val MIN_HAMMING = 10

    fun hamming(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    /** Returns indices of frames that are distinct from every previously accepted frame. */
    fun independent(hashes: List<Long>): List<Int> {
        val kept = ArrayList<Int>()
        for ((i, h) in hashes.withIndex()) if (kept.none { hamming(hashes[it], h) < MIN_HAMMING }) kept += i
        return kept
    }

    /** 9×8 luminance grid → 64-bit dHash. */
    fun dHash(lum9x8: IntArray): Long {
        require(lum9x8.size == 72)
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (lum9x8[y * 9 + x] > lum9x8[y * 9 + x + 1]) h = h or (1L shl bit)
            bit++
        }
        return h
    }
}
