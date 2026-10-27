package com.owlacoustics.capturelab.ar

import android.app.Activity
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.owlacoustics.capturelab.Crosshair
import com.owlacoustics.capturelab.LabResult
import com.owlacoustics.capturelab.LabStore
import com.owlacoustics.capturelab.Method
import com.owlacoustics.capturelab.P
import com.owlacoustics.capturelab.ft
import com.owlacoustics.domain.capturelab.WallLine
import com.owlacoustics.domain.capturelab.WallOutline
import com.owlacoustics.domain.geometry.Vec2
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** World point → plan (top view): x stays, −z becomes plan y. */
fun plan(x: Float, z: Float) = Vec2(x.toDouble(), -z.toDouble())

data class PlaneInfo(val vertical: Boolean, val outline: List<FloatArray>, val wall: WallLine?)

data class ArSnapshot(
    val tracking: Boolean = false,
    val trackingReason: String = "",
    val view: FloatArray = FloatArray(16),
    val proj: FloatArray = FloatArray(16),
    val planes: List<PlaneInfo> = emptyList(),
    val centerFloorHit: FloatArray? = null,
    val corners: List<FloatArray> = emptyList(),
    val cameraPlan: Vec2? = null,
    val depthOn: Boolean = false,
)

/** Owns the ARCore session and its GL rendering; publishes a snapshot per frame for the Compose overlay. */
class ArController(private val context: Context, private val depthWanted: Boolean) : GLSurfaceView.Renderer {
    var session: Session? = null; private set
    val snap = MutableStateFlow(ArSnapshot())
    private val bg = BackgroundRenderer()
    private var vw = 1; private var vh = 1
    private val anchors = ArrayList<Anchor>()
    @Volatile private var addCorner = false
    @Volatile private var undoCorner = false
    val cameraTrail = java.util.concurrent.CopyOnWriteArrayList<Vec2>()
    var error: String? = null; private set

    fun create(): Boolean = try {
        val s = Session(context)
        val cfg = Config(s).apply {
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            if (depthWanted && s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
        }
        s.configure(cfg)
        session = s; true
    } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName; false }

    fun requestCorner() { addCorner = true }
    fun requestUndo() { undoCorner = true }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        bg.create()
        session?.setCameraTextureName(bg.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        vw = width; vh = height
        GLES20.glViewport(0, 0, width, height)
        @Suppress("DEPRECATION")
        val rot = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        session?.setDisplayGeometry(rot, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        val frame = try { s.update() } catch (e: Exception) { return }
        bg.draw(frame)
        val cam = frame.camera
        val view = FloatArray(16); val proj = FloatArray(16)
        cam.getViewMatrix(view, 0); cam.getProjectionMatrix(proj, 0, 0.05f, 50f)
        val tracking = cam.trackingState == TrackingState.TRACKING
        var hit: FloatArray? = null
        if (tracking) {
            val t = cam.pose.translation
            if (cameraTrail.isEmpty() || cameraTrail.last().distanceTo(plan(t[0], t[2])) > 0.05) cameraTrail += plan(t[0], t[2])
            val h = frame.hitTest(vw / 2f, vh / 2f).firstOrNull { r ->
                val p = r.trackable as? Plane
                p != null && p.type == Plane.Type.HORIZONTAL_UPWARD_FACING && p.isPoseInPolygon(r.hitPose)
            }
            if (h != null) hit = h.hitPose.translation
            if (addCorner) { addCorner = false; if (h != null) anchors += h.createAnchor() }
        }
        if (undoCorner) { undoCorner = false; anchors.removeLastOrNull()?.detach() }
        val planes = s.getAllTrackables(Plane::class.java).filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }.map { p ->
            val poly = p.polygon; poly.rewind()
            val pts = ArrayList<FloatArray>()
            while (poly.remaining() >= 2) { val x = poly.get(); val z = poly.get(); pts += p.centerPose.transformPoint(floatArrayOf(x, 0f, z)) }
            val vertical = p.type == Plane.Type.VERTICAL
            val wall = if (vertical) {
                val c = p.centerPose.translation; val n = p.centerPose.getTransformedAxis(1, 1f)
                val nv = plan(n[0], n[2])
                if (nv.length > 0.5) WallLine(c[0].toDouble(), -c[2].toDouble(), nv.x, nv.y, p.extentX.toDouble()) else null
            } else null
            PlaneInfo(vertical, pts, wall)
        }
        snap.value = ArSnapshot(tracking, if (tracking) "" else cam.trackingFailureReason.name, view, proj, planes, hit,
            anchors.filter { it.trackingState == TrackingState.TRACKING }.map { it.pose.translation },
            if (tracking) plan(cam.pose.tx(), cam.pose.tz()) else null,
            s.config.depthMode == Config.DepthMode.AUTOMATIC)
    }
}

/** Project a world point to screen pixels, or null when behind the camera. */
fun project(s: ArSnapshot, p: FloatArray, w: Float, h: Float): Offset? {
    val v = floatArrayOf(p[0], p[1], p[2], 1f)
    val vp = FloatArray(16); Matrix.multiplyMM(vp, 0, s.proj, 0, s.view, 0)
    val o = FloatArray(4); Matrix.multiplyMV(o, 0, vp, 0, v, 0)
    if (o[3] <= 0.01f) return null
    return Offset((o[0] / o[3] + 1) / 2 * w, (1 - o[1] / o[3]) / 2 * h)
}

/** Checks ARCore support/installation; shows the reason when unavailable. */
@Composable
fun ArGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var avail by remember { mutableStateOf(ArCoreApk.getInstance().checkAvailability(ctx)) }
    LaunchedEffect(Unit) { while (avail.isTransient) { delay(300); avail = ArCoreApk.getInstance().checkAvailability(ctx) } }
    when (avail) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> content()
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED, ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> Column(Modifier.padding(16.dp)) {
            Text("This phone supports ARCore but needs \"Google Play Services for AR\".")
            Button({
                runCatching { ArCoreApk.getInstance().requestInstall(ctx as Activity, true) }
                avail = ArCoreApk.getInstance().checkAvailability(ctx)
            }) { Text("Install / update") }
            TextButton(onBack) { Text("Back") }
        }
        else -> Column(Modifier.padding(16.dp)) {
            Text(if (avail.isTransient) "Checking ARCore…" else "ARCore isn't available on this phone ($avail). Methods 1 and 5 can't run here; the others still work.")
            TextButton(onBack) { Text("Back") }
        }
    }
}

@Composable
private fun ArView(controller: ArController, modifier: Modifier) {
    val owner = LocalLifecycleOwner.current
    val view = remember { mutableStateOf<GLSurfaceView?>(null) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { runCatching { controller.session?.resume() }; view.value?.onResume() }
                Lifecycle.Event.ON_PAUSE -> { view.value?.onPause(); controller.session?.pause() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        runCatching { controller.session?.resume() }
        onDispose { owner.lifecycle.removeObserver(obs); view.value?.onPause(); controller.session?.pause(); controller.session?.close() }
    }
    AndroidView(factory = { c ->
        GLSurfaceView(c).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(controller)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            view.value = this
        }
    }, modifier = modifier)
}

/** Method 1: aim the crosshair at each floor corner; ARCore supplies metric positions. */
@Composable
fun ArTraceScreen(store: LabStore, onDone: () -> Unit) = ArGate(onDone) {
    val ctx = LocalContext.current
    val c = remember { ArController(ctx, depthWanted = true) }
    val ok = remember { c.create() }
    val s by c.snap.collectAsState()
    var taps by remember { mutableIntStateOf(0) }
    val start = remember { System.currentTimeMillis() }
    Column(Modifier.fillMaxSize()) {
        if (!ok) { Text("AR session failed: ${c.error}"); TextButton(onDone) { Text("Back") }; return@Column }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ArView(c, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                for (p in s.planes) if (!p.vertical) for (q in p.outline) project(s, q, size.width, size.height)?.let { drawCircle(Color(0x88FFFFFF), 4f, it) }
                val pts = s.corners.mapNotNull { project(s, it, size.width, size.height) }
                for (i in pts.indices) { drawCircle(Color(0xFFF2C14E), 12f, pts[i]); if (i > 0) drawLine(Color(0xFFF2C14E), pts[i - 1], pts[i], 5f) }
                if (pts.isNotEmpty()) s.centerFloorHit?.let { h -> project(s, h, size.width, size.height)?.let { drawLine(Color(0x99F2C14E), pts.last(), it, 3f) } }
            }
            Crosshair(Modifier.fillMaxSize())
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Method.AR_TRACE.title, style = MaterialTheme.typography.titleSmall)
            Text(when {
                !s.tracking -> "Move the phone slowly to start tracking… ${s.trackingReason}"
                s.centerFloorHit == null -> "Point at the floor until white dots appear, then aim at a corner."
                else -> "Aim at the floor right where the walls meet, tap Add corner. Go around in order."
            }, style = MaterialTheme.typography.bodyMedium)
            val cornersPlan = s.corners.map { plan(it[0], it[2]) }
            if (cornersPlan.size >= 2) Text("Last wall: " + ft(cornersPlan[cornersPlan.size - 2].distanceTo(cornersPlan.last())))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ c.requestCorner(); taps++ }, enabled = s.centerFloorHit != null) { Text("Add corner (${s.corners.size})") }
                OutlinedButton({ c.requestUndo(); taps++ }, enabled = s.corners.isNotEmpty()) { Text("Undo") }
                Button({
                    store.addResult(LabResult(UUID.randomUUID().toString(), Method.AR_TRACE, System.currentTimeMillis(), System.currentTimeMillis() - start, taps + 1,
                        cornersPlan.map { P(it.x, it.y) }, note = if (s.depthOn) "ARCore depth on" else "ARCore depth off"))
                    onDone()
                }, enabled = s.corners.size >= 3) { Text("Finish") }
                TextButton(onDone) { Text("Cancel") }
            }
        }
    }
}

/** Method 5: walls found automatically as ARCore vertical planes, then intersected into an outline. */
@Composable
fun ArAutoScreen(store: LabStore, onDone: () -> Unit) = ArGate(onDone) {
    val ctx = LocalContext.current
    val c = remember { ArController(ctx, depthWanted = true) }
    val ok = remember { c.create() }
    val s by c.snap.collectAsState()
    val start = remember { System.currentTimeMillis() }
    Column(Modifier.fillMaxSize()) {
        if (!ok) { Text("AR session failed: ${c.error}"); TextButton(onDone) { Text("Back") }; return@Column }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ArView(c, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                for (p in s.planes) {
                    val pts = p.outline.mapNotNull { project(s, it, size.width, size.height) }
                    if (pts.size < 3) continue
                    val path = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
                    drawPath(path, if (p.vertical) Color(0x5532CD32) else Color(0x33FFFFFF))
                    drawPath(path, if (p.vertical) Color(0xFF32CD32) else Color(0x88FFFFFF), style = Stroke(3f))
                }
            }
        }
        val walls = s.planes.mapNotNull { it.wall }
        val center = c.cameraTrail.takeIf { it.isNotEmpty() }?.let { t -> t.reduce { a, b -> a + b } / t.size.toDouble() }
        val outline = center?.let { WallOutline.outline(walls, it) }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Method.AR_AUTO.title, style = MaterialTheme.typography.titleSmall)
            Text(if (!s.tracking) "Move the phone slowly to start tracking… ${s.trackingReason}"
                else "Walk slowly and sweep across every wall. Green = wall found. ${WallOutline.merge(walls).size} wall(s) so far" + (if (s.depthOn) " · depth on" else ""),
                style = MaterialTheme.typography.bodyMedium)
            Text(outline?.let { o -> "Outline ready: " + o.indices.joinToString { i -> ft(o[i].distanceTo(o[(i + 1) % o.size])) } } ?: "No closed outline yet.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    store.addResult(LabResult(UUID.randomUUID().toString(), Method.AR_AUTO, System.currentTimeMillis(), System.currentTimeMillis() - start, 1,
                        outline?.map { P(it.x, it.y) } ?: emptyList(), note = "${WallOutline.merge(walls).size} walls detected" + if (outline == null) "; no closed outline" else ""))
                    onDone()
                }) { Text("Finish") }
                TextButton(onDone) { Text("Cancel") }
            }
        }
    }
}

