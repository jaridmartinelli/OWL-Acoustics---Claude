package com.owlacoustics.domain.placement

import com.owlacoustics.domain.acoustics.PathStatus
import com.owlacoustics.domain.acoustics.Reflections
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.bearingDeg
import com.owlacoustics.domain.model.ItemKind
import com.owlacoustics.domain.model.KeepOut
import com.owlacoustics.domain.model.Role
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.SetupItem
import com.owlacoustics.domain.model.Validation
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Placement optimisation contract (C-17).
 *
 * Variables: position of the unlocked rig anchor (listener, or the voice/instrument of a recording group) on a
 * plan grid, and its facing chosen from the room's wall-normal directions (room orientation).
 * Fixed: the declared listening distance / microphone distance, layout angles, heights and every locked item.
 * Feasible: every moved item inside the outline, at least [WALL_MARGIN] from walls, outside keep-clear areas,
 * with an unobstructed direct path.
 * Objectives (no weights, no invented targets): maximise the earliest first-order wall/ceiling reflection delay after
 * the direct sound; for symmetric playback layouts minimise left/right reflection-timing asymmetry.
 * Output: Pareto-non-dominated candidates ranked by the first objective, with reasons and before/after metrics.
 */
object PlacementOptimizer {
    const val WALL_MARGIN = 0.05
    const val COARSE_STEP = 0.25
    const val FINE_STEP = 0.05
    const val PODCAST_SEPARATION = 0.6

    @Serializable
    data class Metrics(
        /** Earliest valid wall/ceiling reflection after direct sound, ms; null if none was found. */
        val earliestMs: Double?,
        val asymmetryMs: Double?,
        val nearestWallM: Double,
        val validReflections: Int,
    )

    @Serializable
    data class Placed(val itemId: String, val x: Double, val y: Double, val facingDeg: Double?)

    @Serializable
    data class Proposal(val placements: List<Placed>, val metrics: Metrics, val reasons: List<String>, val facingWall: Int?)

    @Serializable
    data class Result(
        val proposals: List<Proposal>,
        val current: Metrics?,
        val currentIsNonDominated: Boolean,
        val inputs: List<String>,
        val limits: List<String>,
        val blocked: String? = null,
    )

    fun optimize(m: RoomModel, progress: (Double) -> Unit = {}): Result {
        if (Validation.blocking(m).isNotEmpty()) return blocked("Fix the floor plan errors first.")
        val items = m.setup.items
        if (items.isEmpty()) return blocked("Choose a purpose and setup first.")
        val speakers = items.filter { it.kind == ItemKind.SPEAKER }
        val listener = items.firstOrNull { it.kind == ItemKind.LISTENER }
        return if (speakers.isNotEmpty() && listener != null) playback(m, speakers, listener, progress)
        else recording(m, progress)
    }

    private fun blocked(reason: String) = Result(emptyList(), null, false, emptyList(), emptyList(), reason)

    private val limits = listOf(
        "Criteria are geometric: reflection timing and symmetry. They do not measure loudness, decay or frequency response.",
        "Heights are kept as you entered them; unknown heights limit the analysis to wall paths in plan.",
        "Subwoofer placement is not optimised (needs a modal pressure model beyond this build).",
    )

    private fun facings(m: RoomModel, extra: Double?): List<Pair<Double, Int?>> {
        val out = ArrayList<Pair<Double, Int?>>()
        for (w in m.walls) {
            val f = bearingDeg(w.outward)
            if (out.none { angleDiff(it.first, f) < 1.0 }) out += f to w.index
        }
        if (extra != null && out.none { angleDiff(it.first, extra) < 1.0 }) out += extra to null
        return out
    }

    private fun angleDiff(a: Double, b: Double): Double { val d = abs(((a - b) % 360 + 540) % 360 - 180); return d }

    private fun feasible(m: RoomModel, p: Vec2, keepOuts: List<KeepOut>): Boolean {
        val o = m.outline
        if (!Polygons.contains(o, p) || Polygons.distanceToBoundary(o, p) < WALL_MARGIN) return false
        return keepOuts.none { k -> abs(p.x - k.center.x) <= k.sizeX / 2 && abs(p.y - k.center.y) <= k.sizeY / 2 }
    }

    private fun grid(m: RoomModel, step: Double, center: Vec2? = null, radius: Double = 0.0): List<Vec2> {
        val o = m.outline
        val xs = if (center == null) o.minOf { it.x }..o.maxOf { it.x } else (center.x - radius)..(center.x + radius)
        val ys = if (center == null) o.minOf { it.y }..o.maxOf { it.y } else (center.y - radius)..(center.y + radius)
        val out = ArrayList<Vec2>()
        var x = xs.start + (if (center == null) step / 2 else 0.0)
        while (x <= xs.endInclusive + 1e-9) {
            var y = ys.start + (if (center == null) step / 2 else 0.0)
            while (y <= ys.endInclusive + 1e-9) { out += Vec2(x, y); y += step }
            x += step
        }
        return out
    }

    private fun metrics(m: RoomModel, pairs: Set<Pair<String, String>>, mirrored: List<Pair<String, String>>, moved: List<SetupItem>): Metrics {
        val rep = Reflections.analyze(m)
        val valid = rep.paths.filter { it.status == PathStatus.VALID && it.surfaceRef != "floor" && (it.sourceId to it.receiverId) in pairs }
        val earliest = valid.minOfOrNull { it.afterDirectMs }
        val asym = if (mirrored.isEmpty()) null else mirrored.map { (l, r) ->
            val a = valid.filter { it.sourceId == l }.map { it.afterDirectMs }.sorted().take(3)
            val b = valid.filter { it.sourceId == r }.map { it.afterDirectMs }.sorted().take(3)
            val k = min(a.size, b.size)
            if (k == 0) 0.0 else (0 until k).sumOf { abs(a[it] - b[it]) } / k
        }.average()
        val nearest = moved.mapNotNull { it.position }.minOfOrNull { Polygons.distanceToBoundary(m.outline, it) } ?: 0.0
        return Metrics(earliest, asym, nearest, valid.size)
    }

    private fun better(a: Metrics, b: Metrics): Boolean { // a dominates b
        val ea = a.earliestMs ?: Double.MAX_VALUE; val eb = b.earliestMs ?: Double.MAX_VALUE
        val sa = a.asymmetryMs ?: 0.0; val sb = b.asymmetryMs ?: 0.0
        return ea >= eb - 1e-9 && sa <= sb + 1e-9 && (ea > eb + 1e-9 || sa < sb - 1e-9)
    }

    private data class Cand(val placed: List<Placed>, val metrics: Metrics, val facingWall: Int?, val anchor: Vec2)

    private fun withPlacements(m: RoomModel, placed: List<Placed>): RoomModel {
        val map = placed.associateBy { it.itemId }
        return m.copy(setup = m.setup.copy(items = m.setup.items.map { i ->
            map[i.id]?.let { i.copy(position = Vec2(it.x, it.y), facingDeg = it.facingDeg ?: i.facingDeg) } ?: i
        }))
    }

    private fun pareto(cands: List<Cand>): List<Cand> {
        val front = cands.filter { c -> cands.none { o -> o !== c && better(o.metrics, c.metrics) } }
        val sorted = front.sortedWith(compareByDescending<Cand> { it.metrics.earliestMs ?: Double.MAX_VALUE }.thenBy { it.metrics.asymmetryMs ?: 0.0 })
        val out = ArrayList<Cand>()
        for (c in sorted) {
            if (out.none { it.anchor.distanceTo(c.anchor) < 0.3 && it.facingWall == c.facingWall }) out += c
            if (out.size >= 5) break
        }
        return out
    }

    // ---------------- playback ----------------

    private fun playback(m: RoomModel, speakers: List<SetupItem>, listener: SetupItem, progress: (Double) -> Unit): Result {
        val placedSpeakers = speakers.filter { it.position != null }
        val distance = m.setup.items.let {
            if (listener.position != null && placedSpeakers.isNotEmpty()) placedSpeakers.map { s -> s.position!!.distanceTo(listener.position) }.average() else null
        }
        val allSpeakersLocked = speakers.all { it.locked }
        val someLocked = speakers.any { it.locked } && !allSpeakersLocked
        if (someLocked) return blocked("Some speakers are locked and others are not. Lock all speakers (optimise the listening position) or unlock them all.")
        if (listener.locked && allSpeakersLocked) return blocked("Everything is locked, so there is nothing to move. Unlock the listener or the speakers.")
        val pairs = speakers.map { it.id to listener.id }.toSet()
        val mirrored = speakers.mapNotNull { l ->
            val a = l.layoutAngleDeg ?: return@mapNotNull null
            if (a <= 0) return@mapNotNull null
            speakers.firstOrNull { it.layoutAngleDeg != null && abs(it.layoutAngleDeg + a) < 1e-6 }?.let { l.id to it.id }
        }
        val current = if (speakers.all { it.position != null } && listener.position != null) metrics(m, pairs, mirrored, speakers + listener) else null
        val inputs = ArrayList<String>()
        val cands = ArrayList<Cand>()
        if (allSpeakersLocked) {
            if (placedSpeakers.size != speakers.size) return blocked("Place the locked speakers first.")
            inputs += "Speakers fixed (locked); listener position varies."
            val centroid = placedSpeakers.map { it.position!! }.reduce { a, b -> a + b } / placedSpeakers.size.toDouble()
            val pts = grid(m, COARSE_STEP)
            for ((i, p) in pts.withIndex()) {
                if (i % 50 == 0) progress(i.toDouble() / pts.size)
                if (!feasible(m, p, m.setup.keepOuts)) continue
                if (placedSpeakers.any { !Polygons.segmentInside(m.outline, it.position!!, p) || it.position.distanceTo(p) < 0.3 }) continue
                val facing = bearingDeg(centroid - p)
                val placed = listOf(Placed(listener.id, p.x, p.y, facing))
                val mm = withPlacements(m, placed)
                cands += Cand(placed, metrics(mm, pairs, mirrored, listOf(listener.copy(position = p))), null, p)
            }
        } else {
            val r = distance ?: return blocked("Place the speakers and listener once (any rough position), so the listening distance is known. The optimiser keeps that distance.")
            inputs += "Listening distance kept at %.2f m (from your setup).".format(r)
            inputs += "Layout angles: " + speakers.joinToString { "${it.label} ${it.layoutAngleDeg?.let { a -> "%.0f°".format(a) } ?: "free"}" }
            val anchors = if (listener.locked) listOf(listener.position ?: return blocked("Place the locked listener first.")) else grid(m, COARSE_STEP)
            val fac = facings(m, listener.facingDeg)
            var k = 0
            fun evalAt(p: Vec2, f: Double, wall: Int?) {
                if (!feasible(m, p, m.setup.keepOuts)) return
                val placed = ArrayList<Placed>()
                placed += Placed(listener.id, p.x, p.y, f)
                for (s in speakers) {
                    val ang = s.layoutAngleDeg ?: 0.0
                    val q = p + Vec2.fromBearingDeg(f + ang) * r
                    if (!feasible(m, q, m.setup.keepOuts) || !Polygons.segmentInside(m.outline, p, q)) return
                    placed += Placed(s.id, q.x, q.y, bearingDeg(p - q))
                }
                val mm = withPlacements(m, placed)
                val moved = mm.setup.items.filter { it.id == listener.id || speakers.any { s -> s.id == it.id } }
                cands += Cand(placed, metrics(mm, pairs, mirrored, moved), wall, p)
            }
            for (p in anchors) {
                if (k++ % 25 == 0) progress(0.7 * k / anchors.size)
                for ((f, wall) in fac) evalAt(p, f, wall)
            }
            // local refinement around the coarse front
            if (!listener.locked) {
                for (seed in pareto(cands)) {
                    val f = seed.placed.first().facingDeg!!
                    for (p in grid(m, FINE_STEP, seed.anchor, COARSE_STEP)) evalAt(p, f, seed.facingWall)
                }
            }
        }
        progress(1.0)
        return finish(m, cands, current, inputs)
    }

    // ---------------- recording ----------------

    private fun recording(m: RoomModel, progress: (Double) -> Unit): Result {
        val groups = m.setup.items.filter { it.group > 0 }.groupBy { it.group }
        if (groups.isEmpty()) return blocked("This setup has no source/microphone pairs to place.")
        val inputs = ArrayList<String>()
        var model = m
        val allPlaced = ArrayList<Placed>()
        var currentMetrics: Metrics? = null
        val currentAll = groups.values.all { g -> g.all { it.position != null } }
        if (currentAll) {
            val pairs = groups.values.flatMap { g -> g.filter { it.kind.role == Role.SOURCE }.flatMap { s -> g.filter { it.kind.role == Role.RECEIVER }.map { s.id to it.id } } }.toSet()
            currentMetrics = metrics(m, pairs, emptyList(), m.setup.items.filter { it.group > 0 })
        }
        val perGroupCands = ArrayList<Cand>()
        for ((gi, entry) in groups.entries.withIndex()) {
            val g = entry.value
            val src = g.firstOrNull { it.kind.role == Role.SOURCE } ?: return blocked("Group ${entry.key} needs a voice or instrument.")
            val mic = g.firstOrNull { it.kind.role == Role.RECEIVER } ?: return blocked("Group ${entry.key} needs a microphone.")
            if (src.locked && mic.locked) { inputs += "${src.label} and ${mic.label} are locked."; continue }
            val d = if (src.position != null && mic.position != null) src.position.distanceTo(mic.position) else
                return blocked("Place ${src.label} and ${mic.label} once (rough positions are fine) so the microphone distance is known.")
            inputs += "${mic.label} distance kept at %.2f m from ${src.label}.".format(d)
            val pairs = setOf(src.id to mic.id)
            val others = allPlaced.map { Vec2(it.x, it.y) } + m.setup.items.filter { it.group != entry.key && it.group > 0 && it.position != null && allPlaced.none { p -> p.itemId == it.id } }.map { it.position!! }
            val cands = ArrayList<Cand>()
            val fac = facings(m, src.facingDeg)
            val anchors = when {
                src.locked -> listOf(src.position!!)
                mic.locked -> null
                else -> grid(m, COARSE_STEP)
            }
            fun consider(sp: Vec2, mp: Vec2, f: Double, wall: Int?) {
                if (!feasible(m, sp, m.setup.keepOuts) || !feasible(m, mp, m.setup.keepOuts)) return
                if (!Polygons.segmentInside(m.outline, sp, mp)) return
                if (others.any { it.distanceTo(sp) < PODCAST_SEPARATION || it.distanceTo(mp) < PODCAST_SEPARATION }) return
                val placed = listOf(Placed(src.id, sp.x, sp.y, f), Placed(mic.id, mp.x, mp.y, bearingDeg(sp - mp)))
                val mm = withPlacements(model, placed)
                cands += Cand(placed, metrics(mm, pairs, emptyList(), mm.setup.items.filter { it.id == src.id || it.id == mic.id }), wall, sp)
            }
            if (anchors == null) {
                val mp = mic.position!!
                for ((f, wall) in fac) consider(mp - Vec2.fromBearingDeg(f) * d, mp, f, wall)
            } else {
                for ((i, sp) in anchors.withIndex()) {
                    if (i % 25 == 0) progress((gi + 0.8 * i / anchors.size) / groups.size)
                    for ((f, wall) in fac) consider(sp, sp + Vec2.fromBearingDeg(f) * d, f, wall)
                }
                if (!src.locked) for (seed in pareto(cands).take(3)) {
                    val f = seed.placed.first().facingDeg!!
                    for (sp in grid(m, FINE_STEP, seed.anchor, COARSE_STEP)) consider(sp, sp + Vec2.fromBearingDeg(f) * d, f, seed.facingWall)
                }
            }
            val best = pareto(cands)
            if (groups.size == 1) { progress(1.0); return finish(m, cands, currentMetrics, inputs) }
            val top = best.firstOrNull() ?: return blocked("No valid position was found for ${src.label}; check keep-clear areas and room size.")
            allPlaced += top.placed
            model = withPlacements(model, top.placed)
            perGroupCands += top
        }
        progress(1.0)
        if (allPlaced.isEmpty()) return blocked("Everything is locked, so there is nothing to move.")
        val pairs = groups.values.flatMap { g -> g.filter { it.kind.role == Role.SOURCE }.flatMap { s -> g.filter { it.kind.role == Role.RECEIVER }.map { s.id to it.id } } }.toSet()
        val combined = metrics(model, pairs, emptyList(), model.setup.items.filter { it.group > 0 })
        val c = Cand(allPlaced, combined, null, Vec2(allPlaced[0].x, allPlaced[0].y))
        inputs += "Groups placed one after another, at least %.1f m apart (engineering spacing).".format(PODCAST_SEPARATION)
        return finish(m, listOf(c), currentMetrics, inputs)
    }

    private fun finish(m: RoomModel, cands: List<Cand>, current: Metrics?, inputs: List<String>): Result {
        if (cands.isEmpty()) return Result(emptyList(), current, false, inputs, limits, "No feasible placement found. Check keep-clear areas, locks and the listening distance.")
        val front = pareto(cands)
        val nonDominated = current != null && cands.none { better(it.metrics, current) }
        val proposals = front.map { c ->
            val reasons = ArrayList<String>()
            c.facingWall?.let { reasons += "Faces wall ${it + 1}." }
            val e = c.metrics.earliestMs
            reasons += if (e == null) "No first-order wall/ceiling reflection path was found for these positions."
            else "Earliest wall/ceiling reflection arrives %.1f ms after the direct sound".format(e) +
                (current?.earliestMs?.let { " (now %.1f ms)".format(it) } ?: "") + "."
            c.metrics.asymmetryMs?.let { a ->
                reasons += "Left/right reflection timing differs by %.2f ms on average".format(a) + (current?.asymmetryMs?.let { " (now %.2f ms)".format(it) } ?: "") + "."
            }
            reasons += "Nearest moved item is %.2f m from a wall.".format(c.metrics.nearestWallM)
            Proposal(c.placed, c.metrics, reasons, c.facingWall)
        }
        return Result(proposals, current, nonDominated, inputs, limits)
    }
}
