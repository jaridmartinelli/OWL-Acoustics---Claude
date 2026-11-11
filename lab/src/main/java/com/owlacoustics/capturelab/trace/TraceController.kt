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
import com.owlacoustics.domain.trace.CameraPose
import com.owlacoustics.domain.trace.CornerMemory
import com.owlacoustics.domain.trace.EdgeMap
import com.owlacoustics.domain.trace.Intrinsics
import com.owlacoustics.domain.trace.Mat3
import com.owlacoustics.domain.trace.Pinhole
import com.owlacoustics.domain.trace.RemovedCorner
import com.owlacoustics.domain.trace.ScaleBasis
import com.owlacoustics.domain.trace.ScanBuilder
import com.owlacoustics.domain.trace.ScanHistory
import com.owlacoustics.domain.trace.ScanState
import com.owlacoustics.domain.trace.SolvedSegment
import com.owlacoustics.domain.trace.Surface
import com.owlacoustics.domain.trace.SurfaceScan
import com.owlacoustics.domain.trace.TraceMeshBuilder
import com.owlacoustics.domain.trace.TraceSettings
import com.owlacoustics.domain.trace.TraceSolver
import com.owlacoustics.domain.trace.TypedLength
import com.owlacoustics.domain.trace.TypedValue
import com.owlacoustics.domain.units.LengthParser
import com.owlacoustics.domain.units.LengthUnit
import com.owlacoustics.domain.units.ParseResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

/** Which surface the brush and the size labels work on. Wall shows heights; walls themselves are never brushed. */
@Serializable
enum class Tool { FLOOR, WALL, CEILING }

@Serializable
data class LabTraceSettings(
    val solver: TraceSettings = TraceSettings(),
    /** Find floor, walls and ceiling without the brush. */
    val autoDetect: Boolean = true,
    /** Where furniture hides the floor edge, fill it in from the ceiling line above (and the other way round). */
    val borrowAcross: Boolean = true,
    /** Show sizes marked "~" before any real measurement is typed (owner default: hidden until one is typed). */
    val showEstimates: Boolean = false,
    val widestLens: Boolean = true,
)

@Serializable
data class TraceDraft(val scan: ScanState = ScanState(), val settings: LabTraceSettings = LabTraceSettings(), val tool: Tool = Tool.FLOOR)

/** Autosaved working copy. Undo history is not kept across launches. */
class TraceDraftStore(context: Context) {
    private val file = File(context.filesDir, "scan-draft.json")
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
class ViewMap(val k: Intrinsics, val pose: CameraPose, private val left: Float, private val top: Float, val scale: Float) {
    fun toScreen(p: Vec2) = Offset(left + p.x.toFloat() * scale, top + p.y.toFloat() * scale)
    fun toImage(o: Offset) = Vec2((o.x - left) / scale.toDouble(), (o.y - top) / scale.toDouble())
    fun project(p: Vec3): Offset? = Pinhole.project(k, pose, p, NEAR)?.let(::toScreen)
    fun segment(a: Vec3, b: Vec3): Pair<Offset, Offset>? = Pinhole.projectSegment(k, pose, a, b, NEAR)?.let { toScreen(it.first) to toScreen(it.second) }
    fun polygon(pts: List<Vec3>): List<Offset> = Pinhole.projectPolygon(k, pose, pts, NEAR).map(::toScreen)
    fun ray(o: Offset): Vec3 { val i = toImage(o); return Pinhole.worldRay(k, pose, i.x, i.y) }
    companion object { const val NEAR = 0.2 }
}

sealed interface Hit {
    data class Label(val seg: SolvedSegment) : Hit
    data object Height : Hit
    data class Anchor(val id: Int, val surface: Surface) : Hit
    data object Empty : Hit
}

sealed interface EditTarget {
    data class Segment(val surface: Surface, val a: Int, val b: Int) : EditTarget
    data object Ceiling : EditTarget
}

class LabelBox(val rect: Rect, val seg: SolvedSegment)

/** State and behaviour of the live camera scan. All methods run on the main thread unless noted. */
class TraceController(private val drafts: TraceDraftStore?) {
    private val draft = drafts?.load() ?: TraceDraft()
    var settings by mutableStateOf(draft.settings); private set
    var scan by mutableStateOf(draft.scan); private set
    var history by mutableStateOf(ScanHistory()); private set
    var tool by mutableStateOf(draft.tool); private set
    /** Brush mode: false adds to the surface, true takes away. */
    var subtract by mutableStateOf(false)
    private val memory = CornerMemory()
    val doc by derivedStateOf { ScanBuilder.build(scan, settings.borrowAcross, memory, settings.solver.squareCorners) }
    val solution by derivedStateOf { TraceSolver.solve(doc, settings.solver) }
    val mesh by derivedStateOf { TraceMeshBuilder.build(doc, solution, settings.solver, true) }

    var live by mutableStateOf<TraceFrame?>(null); private set
    /** Heading correction from the two-finger gesture (radians about the vertical). */
    var yawFix by mutableStateOf(0.0); private set
    var selected by mutableStateOf<Hit.Anchor?>(null); private set
    var menu by mutableStateOf<Hit.Anchor?>(null)
    var editing by mutableStateOf<EditTarget?>(null); private set
    var finger by mutableStateOf<Offset?>(null); private set
    var problem by mutableStateOf<String?>(null)
    var restored by mutableStateOf(!draft.scan.isEmpty); private set
    /** What the automatic detection last reported, for the hint line. */
    var autoNote by mutableStateOf<String?>(null); private set

    var canvasW = 0f; var canvasH = 0f
    var density = 1f
    val labels = ArrayList<LabelBox>()
    val heightBoxes = ArrayList<Rect>()

    var detector: AutoDetector? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var brushBefore: ScanState? = null

    private fun dp(v: Float) = v * density
    val surface get() = if (tool == Tool.CEILING) Surface.CEILING else Surface.FLOOR

    // ---- frames and automatic detection ----

    fun onLiveFrame(f: TraceFrame) {
        live?.release(); live = f
        val det = detector
        if (!f.poseOk || f.turnRate > 90) return
        if (det != null && settings.autoDetect && busy.compareAndSet(false, true)) {
            f.retain()
            val pose = poseOf(f)
            worker.execute {
                try {
                    val r = det.detect(f, pose)
                    main.post { applyAuto(r, pose) }
                } catch (_: Throwable) {
                } finally { f.release(); busy.set(false) }
            }
        }
    }

    private fun applyAuto(r: AutoResult, pose: CameraPose) {
        autoNote = if (!r.floorSeen) "Point lower so the floor is in view" else if (r.samples.isEmpty()) "No wall found here: brush it in" else null
        if (r.samples.isEmpty()) return
        var next = scan
        for (s in Surface.entries) {
            val list = r.samples.filter { it.surface == s }.mapNotNull { polar(s, it.ray, pose) }.map { SurfaceScan.bin(it.first) to it.second.toFloat() }
            if (list.isNotEmpty()) next = next.with(s, next.surface(s).withAuto(list))
        }
        if (next != scan) { scan = next; restored = false }
    }

    /** Closes the depth model on the worker thread, after any detection still running there. */
    fun dispose(det: AutoCloseable?) {
        live?.release(); live = null
        worker.execute { runCatching { det?.close() } }
        worker.shutdown()
        save()
    }

    fun poseOf(f: TraceFrame): CameraPose {
        val r = Mat3.rotZ(yawFix) * f.rot
        val h = CameraPose(r, Vec3(0.0, 0.0, 0.0)).heading()
        return CameraPose(r, Vec3(h.x * settings.solver.pivotRadiusM, h.y * settings.solver.pivotRadiusM, 0.0))
    }

    fun viewMap(f: TraceFrame? = live): ViewMap? {
        f ?: return null
        if (canvasW <= 0 || canvasH <= 0) return null
        val s = minOf(canvasW / f.k.w, canvasH / f.k.h)
        return ViewMap(f.k, poseOf(f), (canvasW - f.k.w * s) / 2, (canvasH - f.k.h * s) / 2, s)
    }

    /**
     * Direction and distance, seen from the point the user turns around, of where [ray] meets the surface's plane.
     * Distance is in plane units (camera heights for the floor).
     */
    private fun polar(s: Surface, ray: Vec3, pose: CameraPose): Pair<Double, Double>? {
        val unit = if (s == Surface.FLOOR) -solution.floorZ else solution.ceilingZ
        if (unit <= 0.05) return null
        val t = if (s == Surface.FLOOR) { if (ray.z > -0.03) return null; -1 / ray.z } else { if (ray.z < 0.03) return null; 1 / ray.z }
        val x = pose.t.x / unit + ray.x * t; val y = pose.t.y / unit + ray.y * t
        val r = hypot(x, y)
        return if (r < 0.05 || r > 40) null else kotlin.math.atan2(y, x) to r
    }

    // ---- saving and history ----

    private fun save() { drafts?.save(TraceDraft(scan, settings, tool)) }
    private fun commit(next: ScanState) { if (next == scan) return; history = history.push(scan); scan = next; restored = false; save() }

    fun updateSettings(s: LabTraceSettings) { settings = s; save() }
    fun pickTool(t: Tool) { tool = t; selected = null; menu = null; editing = null; save() }
    fun undo() { history.undo(scan)?.let { history = it.first; scan = it.second; clearUi(); save() } }
    fun redo() { history.redo(scan)?.let { history = it.first; scan = it.second; clearUi(); save() } }
    /** Clears everything; Undo brings it back. */
    fun restart() { clearUi(); commit(ScanState()) }
    private fun clearUi() { selected = null; menu = null; editing = null }

    /** Back button: closes whatever is open. False when nothing was. */
    fun back(): Boolean = when {
        editing != null -> { editing = null; true }
        menu != null -> { menu = null; true }
        selected != null -> { selected = null; true }
        else -> false
    }

    // ---- touch ----

    fun hit(p: Offset, vm: ViewMap): Hit {
        if (tool == Tool.WALL && heightBoxes.any { it.inflate(dp(8f)).contains(p) }) return Hit.Height
        labels.firstOrNull { it.rect.inflate(dp(6f)).contains(p) }?.let { return Hit.Label(it.seg) }
        var best: Hit = Hit.Empty; var bd = dp(28f)
        for (s in if (tool == Tool.WALL) Surface.entries.toList() else listOf(surface)) for (pt in solution.points(s)) {
            val sc = vm.project(pt.pos) ?: continue
            val d = (sc - p).getDistance()
            if (d < bd) { bd = d; best = Hit.Anchor(pt.id, s) }
        }
        return best
    }

    fun brushDown(p: Offset) { brushBefore = scan; finger = p; selected = null; menu = null; brushMove(p) }

    /** Paints with a finger-sized brush. Works on the live picture; the readings are stored in the room, not on screen. */
    fun brushMove(p: Offset) {
        if (tool == Tool.WALL) return
        val f = live ?: return
        val vm = viewMap(f) ?: return
        finger = p
        val s = surface
        val map: EdgeMap? = runCatching { f.edgeMap() }.getOrNull()
        var next = scan.surface(s)
        val radius = dp(22f)
        var o = -radius
        while (o <= radius) {
            val q = Offset(p.x + o, p.y)
            o += dp(5f)
            val here = polar(s, vm.ray(q), vm.pose) ?: continue
            val bin = SurfaceScan.bin(here.first)
            val img = vm.toImage(q)
            // Add looks from the finger toward the horizon for the surface's edge; Subtract looks back the other way
            val towardHorizon = if (s == Surface.FLOOR) -1 else 1
            val dir = if (subtract) -towardHorizon else towardHorizon
            val found = if (map == null) 0f else edgeAlongColumn(f, vm, map, img, dir, s, here.second,
                if (!subtract) next.max[bin] else 0f)
            next = if (subtract) next.withSubtract(bin, here.second.toFloat(), found) else next.withAdd(bin, here.second.toFloat(), found)
        }
        if (next != scan.surface(s)) scan = scan.with(s, next)
    }

    /** First strong level edge met when walking up or down the picture from the finger; returns its distance or 0. */
    private fun edgeAlongColumn(f: TraceFrame, vm: ViewMap, map: EdgeMap, img: Vec2, dir: Int, s: Surface, from: Double, limit: Float): Float {
        val ls = f.lumScale
        val x = img.x * ls
        var y = img.y * ls
        val steps = (map.h * 0.45).toInt()
        val g = DoubleArray(steps)
        var n = 0
        while (n < steps) {
            y += dir
            if (!map.inside(x, y)) break
            g[n++] = abs(map.gyAt(x, y))
        }
        if (n < 4) return 0f
        var top = 0.0
        for (i in 0 until n) if (g[i] > top) top = g[i]
        val need = maxOf(5.0, 0.5 * top)
        for (i in 1 until n - 1) {
            if (g[i] < need || g[i] < g[i - 1] || g[i] < g[i + 1]) continue
            val ray = Pinhole.worldRay(f.k, vm.pose, img.x, (img.y * ls + dir * (i + 1)) / ls)
            val at = polar(s, ray, vm.pose) ?: return 0f
            val r = at.second.toFloat()
            if (limit > 0 && r > limit) return 0f
            // Add must not come back nearer than the finger; Subtract must not go further
            if ((!subtract && r < from) || (subtract && r > from)) continue
            return r
        }
        return 0f
    }

    fun brushUp() {
        finger = null
        val before = brushBefore ?: return
        brushBefore = null
        if (before != scan) { history = history.push(before); restored = false; save() }
    }

    fun brushCancel() { brushBefore?.let { scan = it }; brushBefore = null; finger = null }

    fun tap(h: Hit) {
        when (h) {
            is Hit.Label -> { selected = null; editing = EditTarget.Segment(h.seg.surface, h.seg.a, h.seg.b) }
            Hit.Height -> editCeiling()
            is Hit.Anchor -> { selected = if (selected == h) null else h; editing = null }
            Hit.Empty -> { selected = null; menu = null; editing = null }
        }
    }

    fun hold(h: Hit) { if (h is Hit.Anchor) { selected = h; menu = h } }

    /** Removes a corner: its two walls become one, and no corner is put back at that spot. */
    fun removeCorner(h: Hit.Anchor) {
        val pos = solution.points(h.surface).firstOrNull { it.id == h.id }?.pos
        menu = null; selected = null
        pos ?: return
        val unit = if (h.surface == Surface.FLOOR) -solution.floorZ else solution.ceilingZ
        commit(scan.copy(removed = scan.removed + RemovedCorner(h.surface, pos.x / unit, pos.y / unit)))
    }

    /** Two fingers: slide the lines sideways back onto the room when the heading has drifted. */
    fun twoFinger(pan: Offset) {
        val f = live ?: return
        val vm = viewMap(f) ?: return
        yawFix += pan.x / (f.k.f * vm.scale)
    }

    // ---- typing sizes ----

    fun editCeiling() { selected = null; menu = null; editing = EditTarget.Ceiling }
    fun cancelEdit() { editing = null }

    fun editingTitle() = if (editing is EditTarget.Ceiling) "Editing ceiling height" else "Editing distance"

    fun editingText(): String = when (val e = editing) {
        is EditTarget.Segment -> scan.typed.firstOrNull { it.surface == e.surface && it.joins(e.a, e.b) }?.text ?: ""
        EditTarget.Ceiling -> scan.ceilingHeight?.text ?: ""
        null -> ""
    }

    /** Applies typed text; empty removes the typed value. Returns an error message, or null when accepted. */
    fun commitEdit(text: String): String? {
        val e = editing ?: return null
        val t = text.trim()
        if (t.isEmpty()) {
            commit(when (e) {
                is EditTarget.Segment -> scan.copy(typed = scan.typed.filterNot { it.surface == e.surface && it.joins(e.a, e.b) })
                EditTarget.Ceiling -> scan.copy(ceilingHeight = null)
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
                commit(scan.copy(typed = scan.typed.filterNot { it.surface == e.surface && it.joins(e.a, e.b) } + TypedLength(e.surface, e.a, e.b, m, t)))
            }
            EditTarget.Ceiling -> {
                if (m < 1.5 || m > 9) return "Ceiling height should be between 5 and 30 ft"
                commit(scan.copy(ceilingHeight = TypedValue(m, t)))
            }
        }
        editing = null
        return null
    }

    // ---- what to show ----

    /** Sizes show as real numbers once a real measurement sets the scale (owner rule), or as "~" estimates if switched on. */
    val sizesVisible get() = solution.calibrated || (settings.showEstimates && solution.basis != ScaleBasis.PROVISIONAL)

    fun labelFor(seg: SolvedSegment): String = when {
        seg.typed != null -> ft(seg.typed!!.meters)
        solution.calibrated -> ft(seg.measured)
        settings.showEstimates && solution.basis != ScaleBasis.PROVISIONAL -> "~" + ft(seg.measured)
        else -> "+ size"
    }

    fun heightLabel(): String = when {
        scan.ceilingHeight != null || solution.calibrated -> ft(solution.ceilingHeight)
        else -> ft(solution.ceilingHeight) + " assumed"
    }

    val canSave get() = solution.plan?.closed == true

    fun scaleTag(): String {
        val s = solution
        return when (s.basis) {
            ScaleBasis.PROVISIONAL -> "Scale not set yet"
            ScaleBasis.CEILING_DEFAULT -> "Assumes ${ft(s.ceilingHeight)} ceiling · type a size to calibrate"
            ScaleBasis.CEILING_TYPED -> "Calibrated · ceiling ${ft(s.ceilingHeight)}"
            ScaleBasis.TYPED_LENGTH -> when (s.ceilingWarning) {
                "typed-mismatch" -> "Lines suggest a ${ft(s.impliedCeiling ?: 0.0)} ceiling, you typed ${ft(s.ceilingHeight)}"
                "unusual" -> "Ceiling works out to ${ft(s.ceilingHeight)} · check the boundary"
                else -> "Calibrated · ceiling " + ft(s.ceilingHeight)
            }
        }
    }

    val scaleWarning get() = solution.ceilingWarning != null

    /** How far round the room (percent) the selected surface has a boundary. */
    fun progress(): Int = ScanBuilder.coverage(scan.surface(surface)) * 100 / SurfaceScan.BINS

    fun hint(): String {
        problem?.let { return it }
        val f = live ?: return "Starting the camera…"
        if (!f.poseOk) return "Waiting for the motion sensor…"
        if (f.turnRate > 60) return "Move slower"
        if (restored && !scan.isEmpty) return "Earlier scan restored · drag with two fingers to line it up"
        if (detector?.available != true && settings.autoDetect) return "Automatic detection unavailable · brush the " + (if (surface == Surface.FLOOR) "floor" else "ceiling")
        autoNote?.let { if (scan.surface(surface).isEmpty || it.startsWith("No wall")) return it }
        return when {
            tool == Tool.WALL -> "Tap a height to type it"
            !canSave -> "Turn slowly · brush where the boundary is wrong · ${progress()}% round"
            !solution.calibrated -> "Tap + size on the longest wall and type its length"
            else -> "Tap a corner · Hold a point for actions"
        }
    }

    /** Spoken summary of the camera view for screen readers. */
    fun describe(): String {
        fun part(name: String, s: Surface): String {
            val b = doc.boundary(s)
            return "$name boundary " + if (b.closed) "closed with ${b.corners.size} corners" else if (b.anchors.isEmpty()) "not found yet" else "open with ${b.corners.size} points"
        }
        val sizes = if (sizesVisible) solution.plan?.lengths?.joinToString(", ") { ft(it) }?.let { ". Walls: $it" } ?: "" else ""
        return "Camera view. " + part("Floor", Surface.FLOOR) + ". " + part("Ceiling", Surface.CEILING) + sizes
    }
}
