package com.owlacoustics.capturelab.trace

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.owlacoustics.capturelab.ft
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import com.owlacoustics.domain.trace.AnchorKind
import com.owlacoustics.domain.trace.CameraPose
import com.owlacoustics.domain.trace.EdgePrefer
import com.owlacoustics.domain.trace.EdgeSnap
import com.owlacoustics.domain.trace.Intrinsics
import com.owlacoustics.domain.trace.Mat3
import com.owlacoustics.domain.trace.NewCorner
import com.owlacoustics.domain.trace.Pinhole
import com.owlacoustics.domain.trace.ScaleBasis
import com.owlacoustics.domain.trace.SolvedSegment
import com.owlacoustics.domain.trace.StrokeFit
import com.owlacoustics.domain.trace.StrokeFitter
import com.owlacoustics.domain.trace.StrokePoint
import com.owlacoustics.domain.trace.Surface
import com.owlacoustics.domain.trace.TraceAssembler
import com.owlacoustics.domain.trace.TraceDoc
import com.owlacoustics.domain.trace.TraceEdits
import com.owlacoustics.domain.trace.TraceHistory
import com.owlacoustics.domain.trace.TraceMeshBuilder
import com.owlacoustics.domain.trace.TraceSettings
import com.owlacoustics.domain.trace.TraceSolver
import com.owlacoustics.domain.trace.TypedValue
import com.owlacoustics.domain.units.LengthParser
import com.owlacoustics.domain.units.LengthUnit
import com.owlacoustics.domain.units.ParseResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** When the picture stops moving so a line can be traced on a still view. */
@Serializable
enum class FreezeMode(val label: String) {
    HOLD("Until I tap Resume"), TOUCH("Only while touching"), OFF("Never (live)")
}

@Serializable
enum class TrackingMode(val label: String) { TURN("Stand and turn"), WALK("Walk (ARCore)") }

/** Lab switches: the choices the owner wants to compare on a phone. */
@Serializable
data class LabTraceSettings(
    val solver: TraceSettings = TraceSettings(),
    val freeze: FreezeMode = FreezeMode.HOLD,
    val fillWhileDrawing: Boolean = true,
    /** Show sizes marked "~" before any real measurement is typed (owner default: hidden until one is typed). */
    val showEstimates: Boolean = false,
    /** Take the lowest edge at the floor and the highest at the ceiling (baseboards, mouldings). */
    val preferOuterEdge: Boolean = true,
    val haptics: Boolean = true,
    val tracking: TrackingMode = TrackingMode.TURN,
)

@Serializable
data class TraceDraft(val doc: TraceDoc = TraceDoc(), val settings: LabTraceSettings = LabTraceSettings(), val surface: Surface = Surface.FLOOR)

/** Autosaved working copy, so leaving the screen or the app loses nothing. Undo history is not kept across launches. */
class TraceDraftStore(context: Context) {
    private val file = File(context.filesDir, "trace-draft.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): TraceDraft = runCatching { json.decodeFromString(TraceDraft.serializer(), file.readText()) }.getOrDefault(TraceDraft())
    fun save(d: TraceDraft) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(TraceDraft.serializer(), d))
            tmp.renameTo(file)
        }
    }
}

/** Maps between the room, the upright picture and the screen for one frame. */
class ViewMap(
    val k: Intrinsics, val pose: CameraPose,
    private val left: Float, private val top: Float, val scale: Float,
    val zoom: Float, private val pan: Offset, val width: Float, val height: Float,
) {
    private val cx = left + k.w * scale / 2
    private val cy = top + k.h * scale / 2
    fun toScreen(p: Vec2) = Offset(((left + p.x.toFloat() * scale) - cx) * zoom + cx + pan.x, ((top + p.y.toFloat() * scale) - cy) * zoom + cy + pan.y)
    fun toImage(o: Offset) = Vec2((((o.x - pan.x - cx) / zoom + cx) - left) / scale.toDouble(), (((o.y - pan.y - cy) / zoom + cy) - top) / scale.toDouble())
    fun project(p: Vec3): Offset? = Pinhole.project(k, pose, p, NEAR)?.let(::toScreen)
    fun segment(a: Vec3, b: Vec3): Pair<Offset, Offset>? = Pinhole.projectSegment(k, pose, a, b, NEAR)?.let { toScreen(it.first) to toScreen(it.second) }
    fun polygon(pts: List<Vec3>): List<Offset> = Pinhole.projectPolygon(k, pose, pts, NEAR).map(::toScreen)
    fun ray(o: Offset): Vec3 { val i = toImage(o); return Pinhole.worldRay(k, pose, i.x, i.y) }
    /** Screen pixels per image pixel, including zoom. */
    val pxPerImage get() = scale * zoom
    companion object { const val NEAR = 0.2 }
}

sealed interface Hit {
    data class Label(val seg: SolvedSegment) : Hit
    data class Anchor(val id: Int, val kind: AnchorKind) : Hit
    /** A point on the line that starts at corner [cornerId], [t] of the way along. */
    data class Line(val cornerId: Int, val t: Double) : Hit
    /** The Delete bubble shown next to the selected point. */
    data class Delete(val id: Int) : Hit
    data object Empty : Hit
}

sealed interface EditTarget {
    data class Segment(val surface: Surface, val a: Int, val b: Int) : EditTarget
    data object Ceiling : EditTarget
}

enum class Buzz { GRAB, CORNER, CLOSE, REJECT }

/** A stroke being drawn. Points are pixels of [ref], the still picture the stroke is fitted in. */
class StrokeState(val ref: TraceFrame, val refPose: CameraPose, val start: Vec2?, val continues: Boolean) {
    val pts = ArrayList<StrokePoint>()
    var lastRaw: Vec2? = null
    var dir: Vec2 = Vec2(0.0, 0.0)
    var lastSnapLum: Vec2? = null
    var everSnapped = false
    var corners = 0
    var pathPx = 0.0
}

class LabelBox(val rect: Rect, val seg: SolvedSegment)

/**
 * State and behaviour of the camera trace screen. All methods run on the main thread.
 * Positions come in as screen pixels of the camera viewport; [density] converts the touch sizes given in dp.
 */
class TraceController(private val drafts: TraceDraftStore?) {
    /** Set by the screen; plays a haptic. */
    var buzzer: (Buzz) -> Unit = {}
    private fun buzz(b: Buzz) = buzzer(b)
    private val draft = drafts?.load() ?: TraceDraft()
    var settings by mutableStateOf(draft.settings); private set
    var history by mutableStateOf(TraceHistory(present = draft.doc)); private set
    var surface by mutableStateOf(draft.surface); private set
    /** Unsaved document while a point is being dragged. */
    private var preview by mutableStateOf<TraceDoc?>(null)
    val doc get() = preview ?: history.present
    val solution by derivedStateOf { TraceSolver.solve(doc, settings.solver) }
    val mesh by derivedStateOf { TraceMeshBuilder.build(doc, solution, settings.solver, settings.fillWhileDrawing) }

    var live by mutableStateOf<TraceFrame?>(null); private set
    var frozen by mutableStateOf<TraceFrame?>(null); private set
    val frame get() = frozen ?: live
    var zoom by mutableStateOf(1f); private set
    var pan by mutableStateOf(Offset.Zero); private set
    /** Heading correction from the two-finger re-align gesture (radians about the vertical). */
    var yawFix by mutableStateOf(0.0); private set
    var stroke by mutableStateOf<StrokeState?>(null); private set
    var strokeFit by mutableStateOf<StrokeFit?>(null); private set
    var selected by mutableStateOf<Int?>(null); private set
    var editing by mutableStateOf<EditTarget?>(null); private set
    var finger by mutableStateOf<Offset?>(null); private set
    /** Where the point under the finger actually landed (picture pixels of the shown frame) and whether it snapped. */
    var loupeAt by mutableStateOf<Pair<Vec2, Boolean>?>(null); private set
    var problem by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null); private set
    var restored by mutableStateOf(!draft.doc.isEmpty); private set

    var canvasW = 0f; var canvasH = 0f
    var density = 1f
    /** Filled in while drawing; used to hit-test size labels and the Delete bubble. */
    val labels = ArrayList<LabelBox>()
    var deleteBox: Rect? = null
    private val edgeWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var downFrame: TraceFrame? = null
    private var dragId: Int? = null
    private var lastTapAt = 0L
    private var lastTapId: Int? = null

    private fun dp(v: Float) = v * density

    // ---- frames ----

    fun onLiveFrame(f: TraceFrame) {
        live?.release(); live = f
        // live tracing (no freeze) needs each new picture's edges; they are prepared off the main thread
        if (stroke != null && frozen == null) { f.retain(); edgeWorker.execute { try { f.edgeMap() } catch (_: Throwable) {} finally { f.release() } } }
    }

    fun dispose() {
        live?.release(); live = null
        frozen?.release(); frozen = null
        downFrame?.release(); downFrame = null
        edgeWorker.shutdown()
    }

    fun poseOf(f: TraceFrame): CameraPose {
        val fix = Mat3.rotZ(yawFix)
        val r = fix * f.rot
        val t = f.tracked?.let { fix * it } ?: CameraPose(r, Vec3(0.0, 0.0, 0.0)).heading().let { Vec3(it.x * settings.solver.pivotRadiusM, it.y * settings.solver.pivotRadiusM, 0.0) }
        return CameraPose(r, t)
    }

    fun viewMap(f: TraceFrame? = frame): ViewMap? {
        f ?: return null
        if (canvasW <= 0 || canvasH <= 0) return null
        val s = minOf(canvasW / f.k.w, canvasH / f.k.h)
        val frozenView = f === frozen
        return ViewMap(f.k, poseOf(f), (canvasW - f.k.w * s) / 2, (canvasH - f.k.h * s) / 2, s, if (frozenView) zoom else 1f, if (frozenView) pan else Offset.Zero, canvasW, canvasH)
    }

    fun freeze(from: TraceFrame? = live) {
        if (frozen != null || from == null) return
        from.retain(); frozen = from
        zoom = 1f; pan = Offset.Zero
        from.retain(); edgeWorker.execute { try { from.edgeMap() } catch (_: Throwable) {} finally { from.release() } }
    }

    fun resume() {
        cancelStroke()
        frozen?.release(); frozen = null
        zoom = 1f; pan = Offset.Zero
    }

    // ---- document ----

    private fun commit(next: TraceDoc) {
        history = history.commit(next)
        restored = false
        save()
    }

    private fun save() { drafts?.save(TraceDraft(history.present, settings, surface)) }

    fun updateSettings(s: LabTraceSettings) { settings = s; save() }
    fun pickSurface(s: Surface) { if (s != surface) { cancelStroke(); selected = null; editing = null; surface = s; save() } }
    fun undo() { cancelStroke(); selected = null; editing = null; history = history.undo(); save() }
    fun redo() { cancelStroke(); selected = null; editing = null; history = history.redo(); save() }
    /** Clears everything; Undo brings it back. */
    fun restart() { cancelStroke(); selected = null; editing = null; commit(TraceEdits.restart(history.present)) }
    fun clearMessage() { message = null }

    /** Back button: closes the size editor, then the selection, then the frozen view. False when nothing was open. */
    fun back(): Boolean = when {
        editing != null -> { editing = null; true }
        selected != null -> { selected = null; true }
        frozen != null -> { resume(); true }
        else -> false
    }

    private val boundary get() = history.present.boundary(surface)
    private fun solvedPos(id: Int): Vec3? = solution.points(surface).firstOrNull { it.id == id }?.pos

    // ---- hit testing ----

    fun hit(p: Offset, vm: ViewMap): Hit {
        val sel = selected
        if (sel != null && deleteBox?.inflate(dp(6f))?.contains(p) == true) return Hit.Delete(sel)
        labels.firstOrNull { it.rect.inflate(dp(6f)).contains(p) }?.let { return Hit.Label(it.seg) }
        var best: Hit = Hit.Empty; var bd = dp(26f)
        for (pt in solution.points(surface)) {
            val s = vm.project(pt.pos) ?: continue
            val d = (s - p).getDistance()
            if (d < bd) { bd = d; best = Hit.Anchor(pt.id, pt.kind) }
        }
        if (best != Hit.Empty) return best
        // a tap on a wall line: find where along the corner-to-corner line it is
        val corners = boundary.corners
        val n = corners.size
        val walls = if (boundary.closed) n else n - 1
        var lineBest: Hit = Hit.Empty; var ld = dp(18f)
        for (i in 0 until walls) {
            val a = solvedPos(corners[i].id) ?: continue; val b = solvedPos(corners[(i + 1) % n].id) ?: continue
            val (sa, sb) = vm.segment(a, b) ?: continue
            val ab = sb - sa; val l2 = ab.x * ab.x + ab.y * ab.y
            if (l2 < 1f) continue
            val u = (((p.x - sa.x) * ab.x + (p.y - sa.y) * ab.y) / l2).coerceIn(0f, 1f)
            val d = (Offset(sa.x + ab.x * u, sa.y + ab.y * u) - p).getDistance()
            if (d >= ld) continue
            val t = lineFraction(vm, p, a, b) ?: continue
            if (t > 0.03 && t < 0.97) { ld = d; lineBest = Hit.Line(corners[i].id, t) }
        }
        return lineBest
    }

    /** Fraction along the room line a→b of the point the finger points at on that surface's plane. */
    private fun lineFraction(vm: ViewMap, p: Offset, a: Vec3, b: Vec3): Double? {
        val q = TraceSolver.rayToPlane(vm.pose.t, vm.ray(p), a.z) ?: return null
        val d = b - a; val l2 = d dot d
        return if (l2 < 1e-9) null else ((q - a) dot d) / l2
    }

    // ---- touch ----

    /** First finger down: remember the picture it landed on. Nothing freezes until the finger actually draws. */
    fun touchDown(p: Offset) {
        downFrame?.release()
        downFrame = (frozen ?: live)?.also { it.retain() }
        finger = p
    }

    fun touchUp() {
        downFrame?.release(); downFrame = null
        finger = null; loupeAt = null
        if (settings.freeze == FreezeMode.TOUCH && stroke == null && dragId == null) resume()
    }

    private fun freezeForEdit() {
        if (settings.freeze != FreezeMode.OFF && frozen == null) freeze(downFrame ?: live)
    }

    private fun bandLum(vm: ViewMap, f: TraceFrame) = (dp(31f) / vm.pxPerImage * f.lumScale).toDouble().coerceIn(3.0, 40.0)

    private fun prefer() = if (!settings.preferOuterEdge) EdgePrefer.STRONGEST else if (surface == Surface.FLOOR) EdgePrefer.LOWER else EdgePrefer.UPPER

    fun beginStroke(p: Offset): Boolean {
        if (boundary.closed) { message = (if (surface == Surface.FLOOR) "Floor" else "Ceiling") + " boundary is closed. Drag a point to adjust it, or Undo."; buzz(Buzz.REJECT); return false }
        selected = null; editing = null
        freezeForEdit()
        val ref = frame ?: return false
        val refPose = poseOf(ref)
        val vm = viewMap(ref) ?: return false
        // continue from the last point when the finger starts near it
        val last = boundary.corners.lastOrNull()
        val lastPx = last?.let { solvedPos(it.id) }?.let { Pinhole.project(ref.k, refPose, it, ViewMap.NEAR) }
        val continues = lastPx != null && (vm.toScreen(lastPx) - p).getDistance() < dp(40f)
        ref.retain()
        stroke = StrokeState(ref, refPose, if (continues) lastPx else null, continues)
        moveStroke(p)
        return true
    }

    fun moveStroke(p: Offset) {
        val st = stroke ?: return
        finger = p
        val shown = frame ?: return
        val vm = viewMap(shown) ?: return
        var img = vm.toImage(p)
        // direction of travel in the shown picture, from a few samples back
        val prevRaw = st.lastRaw
        if (prevRaw != null) {
            val d = img - prevRaw
            if (d.length < 1.5) return
            st.pathPx += d.length * vm.pxPerImage
            st.dir = (st.dir * 0.7 + d.normalized() * 0.3)
        }
        st.lastRaw = img
        var snapped = false
        if (shown === st.ref || shown.edgesReady) {
            val s = shown.lumScale
            val sp = if (st.dir.length > 0.2) EdgeSnap.along(shown.edgeMap(), img.x * s, img.y * s, st.dir.x, st.dir.y, bandLum(vm, shown), prefer(), prev = st.lastSnapLum)
                else EdgeSnap.nearest(shown.edgeMap(), img.x * s, img.y * s, bandLum(vm, shown))
            if (sp.snapped) { img = Vec2(sp.x / s, sp.y / s); snapped = true; st.lastSnapLum = sp.pos } else st.lastSnapLum = null
        }
        loupeAt = img to snapped
        // live mode: carry the point into the stroke's reference picture by its direction in the room
        val inRef = if (shown === st.ref) img else Pinhole.projectDirection(st.ref.k, st.refPose, Pinhole.worldRay(shown.k, vm.pose, img.x, img.y)) ?: return
        st.pts += StrokePoint(inRef.x, inRef.y, snapped)
        if (snapped && !st.everSnapped) { st.everSnapped = true; buzz(Buzz.GRAB) }
        val fit = StrokeFitter.fit(st.pts, tol(vm), minSegment(vm), st.start)
        strokeFit = fit
        val corners = (fit?.vertices?.size ?: 2) - 2
        if (corners > st.corners) buzz(Buzz.CORNER)
        st.corners = max(st.corners, corners)
    }

    private fun tol(vm: ViewMap) = (dp(2.2f) / vm.pxPerImage).toDouble().coerceAtLeast(1.5)
    private fun minSegment(vm: ViewMap) = (dp(9f) / vm.pxPerImage).toDouble().coerceAtLeast(6.0)

    fun cancelStroke() {
        stroke?.ref?.release()
        stroke = null; strokeFit = null; loupeAt = null
        preview = null; dragId = null
    }

    /** Lifts the finger: turns the stroke into corners. Returns false when it was too short to be a stroke. */
    fun endStroke(): Boolean {
        val st = stroke ?: return false
        val fit = strokeFit
        val vm = viewMap(st.ref)
        stroke = null; strokeFit = null; loupeAt = null
        if (fit == null || vm == null || st.pathPx < dp(14f) || fit.vertices.size < 2) { st.ref.release(); return false }
        val b = boundary
        val heading = st.refPose.heading()
        val tracked = st.ref.tracked?.let { st.refPose.t }
        var verts = fit.vertices.mapIndexed { i, v ->
            NewCorner(Pinhole.worldRay(st.ref.k, st.refPose, v.x, v.y), heading, tracked,
                snapped = if (i == 0) b.anchors.isEmpty() else fit.segmentSnapped[i - 1])
        }
        if (st.continues) verts = verts.drop(1)
        // ending on the first point closes the loop
        val first = b.corners.firstOrNull()
        val firstPx = first?.let { solvedPos(it.id) }?.let { Pinhole.project(st.ref.k, st.refPose, it, ViewMap.NEAR) }
        val closes = firstPx != null && b.corners.size + verts.size - 1 >= 3 &&
            (vm.toScreen(firstPx) - vm.toScreen(fit.vertices.last())).getDistance() < dp(34f)
        if (closes) verts = verts.dropLast(1)
        st.ref.release()
        commit(TraceAssembler.addStroke(history.present, surface, verts, closes, fit.segmentSnapped.last(), settings.solver))
        if (closes) buzz(Buzz.CLOSE)
        return true
    }

    /**
     * Dragging from the end point of an open boundary continues the line, as a lasso does. To move that point
     * instead, tap it first so it is selected.
     */
    fun dragContinuesLine(h: Hit): Boolean {
        if (h !is Hit.Anchor) return true
        val b = boundary
        return !b.closed && b.corners.lastOrNull()?.id == h.id && selected != h.id
    }

    fun beginDrag(h: Hit.Anchor) {
        freezeForEdit()
        dragId = h.id; selected = h.id; editing = null
        preview = history.present
    }

    fun drag(p: Offset) {
        val id = dragId ?: return
        val f = frame ?: return
        val vm = viewMap(f) ?: return
        finger = p
        val base = history.present
        val anchor = base.boundary(surface).find(id) ?: return
        if (anchor.isCorner) {
            var img = vm.toImage(p)
            var snapped = false
            if (f.edgesReady || f === frozen) {
                val s = f.lumScale
                val sp = EdgeSnap.nearest(f.edgeMap(), img.x * s, img.y * s, bandLum(vm, f) * 0.6)
                if (sp.snapped) { img = Vec2(sp.x / s, sp.y / s); snapped = true }
            }
            loupeAt = img to snapped
            preview = TraceEdits.moveCorner(base, surface, id, NewCorner(Pinhole.worldRay(f.k, vm.pose, img.x, img.y), vm.pose.heading(), f.tracked?.let { vm.pose.t }))
        } else {
            // a marker slides along its line
            val b = base.boundary(surface)
            val i = b.anchors.indexOfFirst { it.id == id }
            val prev = b.anchors.take(i).lastOrNull { it.isCorner } ?: return
            val next = b.anchors.drop(i + 1).firstOrNull { it.isCorner } ?: b.corners.firstOrNull() ?: return
            val sol = TraceSolver.solve(base, settings.solver)
            val a = sol.points(surface).firstOrNull { it.id == prev.id }?.pos ?: return
            val c = sol.points(surface).firstOrNull { it.id == next.id }?.pos ?: return
            val t = lineFraction(vm, p, a, c) ?: return
            preview = TraceEdits.moveMarker(base, surface, id, t)
            loupeAt = vm.toImage(p) to true
        }
    }

    fun endDrag() {
        val next = preview
        preview = null; dragId = null; loupeAt = null
        if (next != null && next != history.present) { commit(next); buzz(Buzz.CORNER) }
    }

    /** A touch that did not move. [atMs] is the event time, for double-tap detection. */
    fun tap(h: Hit, p: Offset, atMs: Long) {
        val f = downFrame ?: frame ?: return
        val vm = viewMap(f) ?: return
        when (h) {
            is Hit.Delete -> delete(h.id)
            is Hit.Label -> { selected = null; editing = EditTarget.Segment(h.seg.surface, h.seg.a, h.seg.b) }
            is Hit.Anchor -> {
                val b = boundary
                if (!b.closed && b.corners.size >= 3 && b.corners.first().id == h.id && selected == null) {
                    // like a lasso: touching the first point finishes the loop
                    commit(TraceAssembler.addStroke(history.present, surface, emptyList(), true, false, settings.solver)); buzz(Buzz.CLOSE)
                }
                else if (lastTapId == h.id && atMs - lastTapAt < 350) { delete(h.id); lastTapId = null }
                else { selected = if (selected == h.id) null else h.id; editing = null; lastTapId = h.id; lastTapAt = atMs }
            }
            is Hit.Line -> {
                val (next, id) = TraceEdits.insertMarker(history.present, surface, h.cornerId, h.t)
                if (id != null) { commit(next); selected = id; buzz(Buzz.CORNER) }
            }
            Hit.Empty -> {
                if (selected != null || editing != null) { selected = null; editing = null; return }
                if (boundary.closed) return
                // tap = place a point by hand, joined to the last one by a straight line
                var img = vm.toImage(p)
                if (f.edgesReady) {
                    val s = f.lumScale
                    val sp = EdgeSnap.nearest(f.edgeMap(), img.x * s, img.y * s, bandLum(vm, f) * 0.6)
                    if (sp.snapped) img = Vec2(sp.x / s, sp.y / s)
                }
                val first = boundary.corners.firstOrNull()
                val firstS = first?.let { solvedPos(it.id) }?.let { vm.project(it) }
                if (firstS != null && boundary.corners.size >= 3 && (firstS - p).getDistance() < dp(34f)) {
                    commit(TraceAssembler.addStroke(history.present, surface, emptyList(), true, false, settings.solver)); buzz(Buzz.CLOSE)
                } else {
                    val c = NewCorner(Pinhole.worldRay(f.k, vm.pose, img.x, img.y), vm.pose.heading(), f.tracked?.let { vm.pose.t },
                        snapped = boundary.anchors.isEmpty(), forced = true)
                    commit(TraceEdits.append(history.present, surface, listOf(c))); buzz(Buzz.CORNER)
                }
            }
        }
    }

    fun delete(id: Int) {
        commit(TraceEdits.delete(history.present, surface, id, solution.planPositions(surface)))
        selected = null; buzz(Buzz.CORNER)
    }

    /** Two fingers: pinch and drag a frozen picture; on the live view, slide the lines sideways back onto the room. */
    fun twoFinger(zoomChange: Float, panChange: Offset, centroid: Offset) {
        val f = frame ?: return
        if (frozen != null) {
            val vm = viewMap(f) ?: return
            val before = vm.toImage(centroid)
            zoom = (zoom * zoomChange).coerceIn(1f, 6f)
            pan += panChange
            // keep the picture point under the fingers where it was
            viewMap(f)?.let { pan += centroid - it.toScreen(before) }
            val s = minOf(canvasW / f.k.w, canvasH / f.k.h)
            val mx = f.k.w * s * (zoom - 1) / 2; val my = f.k.h * s * (zoom - 1) / 2
            pan = Offset(pan.x.coerceIn(-mx, mx), pan.y.coerceIn(-my, my))
        } else {
            val vm = viewMap(f) ?: return
            yawFix += panChange.x / (f.k.f * vm.scale)
        }
    }

    // ---- typing sizes ----

    fun editCeiling() { selected = null; editing = EditTarget.Ceiling }
    fun cancelEdit() { editing = null }

    fun editingText(): String = when (val e = editing) {
        is EditTarget.Segment -> history.present.typedFor(e.surface, e.a, e.b)?.text
            ?: solution.segments.firstOrNull { it.surface == e.surface && it.a == e.a && it.b == e.b }?.takeIf { sizesVisible }?.let { ft(it.measured) } ?: ""
        EditTarget.Ceiling -> history.present.ceilingHeight?.text ?: ft(solution.ceilingHeight)
        null -> ""
    }

    /** Applies typed text. Empty text removes the typed value. Returns an error message, or null when accepted. */
    fun commitEdit(text: String): String? {
        val e = editing ?: return null
        val t = text.trim()
        if (t.isEmpty()) {
            commit(when (e) {
                is EditTarget.Segment -> TraceEdits.clearTyped(history.present, e.surface, e.a, e.b)
                EditTarget.Ceiling -> TraceEdits.setCeilingHeight(history.present, null)
            })
            editing = null; return null
        }
        val m = when (val r = LengthParser.parse(t, LengthUnit.FOOT)) {
            is ParseResult.Ok -> r.value.meters
            is ParseResult.Invalid -> return r.reason
            else -> return "Type a length, for example 11 6 1/4"
        }
        when (e) {
            is EditTarget.Segment -> {
                if (m < 0.05 || m > 60) return "That length is out of range"
                commit(TraceEdits.setTyped(history.present, e.surface, e.a, e.b, m, t))
            }
            EditTarget.Ceiling -> {
                if (m < 1.5 || m > 9) return "Ceiling height should be between 5 and 30 ft"
                commit(TraceEdits.setCeilingHeight(history.present, TypedValue(m, t)))
            }
        }
        editing = null
        buzz(Buzz.CORNER)
        return null
    }

    // ---- what to show ----

    /** Sizes are shown as real numbers once a real measurement sets the scale (owner rule), or as "~" estimates if switched on. */
    val sizesVisible get() = solution.calibrated || (settings.showEstimates && solution.basis != ScaleBasis.PROVISIONAL)

    fun labelFor(seg: SolvedSegment): String = when {
        seg.typed != null -> ft(seg.typed!!.meters)
        solution.calibrated -> ft(seg.measured)
        settings.showEstimates && solution.basis != ScaleBasis.PROVISIONAL -> "~" + ft(seg.measured)
        else -> "+ size"
    }

    val canSave get() = solution.plan?.closed == true

    /** Spoken summary of the camera view for screen readers. */
    fun describe(): String {
        fun part(name: String, s: Surface): String {
            val b = doc.boundary(s)
            return "$name boundary " + if (b.closed) "closed with ${b.corners.size} corners" else if (b.anchors.isEmpty()) "not started" else "open with ${b.corners.size} points"
        }
        val sizes = if (sizesVisible) solution.plan?.lengths?.joinToString(", ") { ft(it) }?.let { ". Walls: $it" } ?: "" else ""
        return "Camera view" + (if (frozen != null) ", frozen" else "") + ". " + part("Floor", Surface.FLOOR) + ". " + part("Ceiling", Surface.CEILING) + sizes
    }

    fun scaleTag(): String {
        val s = solution
        return when (s.basis) {
            ScaleBasis.PROVISIONAL -> "Scale not set · ceiling ${ft(s.ceilingHeight)} assumed"
            ScaleBasis.CEILING_DEFAULT -> "Assumes ${ft(s.ceilingHeight)} ceiling · type a wall to calibrate"
            ScaleBasis.CEILING_TYPED -> "Calibrated · ceiling ${ft(s.ceilingHeight)} typed"
            ScaleBasis.TYPED_LENGTH -> when (s.ceilingWarning) {
                "typed-mismatch" -> "Lines suggest a ${ft(s.impliedCeiling ?: 0.0)} ceiling, you typed ${ft(s.ceilingHeight)}"
                "unusual" -> "Ceiling works out to ${ft(s.ceilingHeight)} · check lines"
                else -> "Calibrated · ceiling " + ft(s.ceilingHeight) + (if (s.impliedCeiling != null && history.present.ceilingHeight == null) " from lines" else "")
            }
        }
    }

    val scaleWarning get() = solution.ceilingWarning != null

    fun coach(): String {
        problem?.let { return it }
        val f = frame
        if (f == null) return "Starting the camera…"
        if (!f.poseOk) return "Waiting for the motion sensor…"
        if (frozen == null && f.turnRate > 60) return "Turn slower"
        message?.let { return it }
        val b = boundary
        val name = if (surface == Surface.FLOOR) "floor" else "ceiling"
        val edge = if (surface == Surface.FLOOR) "where the floor meets a wall" else "where the ceiling meets a wall"
        if (restored && !history.present.isEmpty && frozen == null) return "Earlier lines restored. Drag with two fingers to slide them back onto the room."
        return when {
            stroke != null -> if (strokeFit?.segmentSnapped?.lastOrNull() == false) "No clear edge here: following your finger" else "Following the edge"
            b.anchors.isEmpty() && frozen == null -> "Aim at $edge, then trace along it with your finger"
            b.anchors.isEmpty() -> "Trace along $edge"
!b.closed && frozen != null -> "Trace on from the last point, or tap Resume and turn"
            !b.closed -> "Turn to the next part and keep tracing the $name" + (if (b.corners.size >= 3) " · end on the first point to close" else "")
            surface == Surface.FLOOR && history.present.ceiling.anchors.isEmpty() -> "Floor closed. Switch to Ceiling and trace a ceiling line to set the scale"
            !solution.calibrated -> "Tap the longest wall's size and type what your tape says"
            frozen != null -> "Drag a point to adjust · pinch to zoom · Resume to turn"
            else -> "Check the sizes, then Save"
        }
    }
}
