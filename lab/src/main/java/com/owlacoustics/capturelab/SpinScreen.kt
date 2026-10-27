package com.owlacoustics.capturelab

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.owlacoustics.domain.capturelab.DepthScale
import com.owlacoustics.domain.capturelab.FloorEdge
import com.owlacoustics.domain.capturelab.PolarOutline
import com.owlacoustics.domain.capturelab.PolarSample
import com.owlacoustics.domain.capturelab.TiltMath
import kotlinx.coroutines.delay
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Live diagnostics for the overlay and readouts. */
data class SpinLive(
    val frameW: Int = 0, val frameH: Int = 0, val horizonRow: Double? = null, val edgeRow: Int? = null,
    val edgeDist: Double? = null, val depthDist: Double? = null, val depthFitR2: Double? = null, val depthMs: Long? = null,
    val fovDeg: Double? = null,
)

/** Runs methods 2 (floor-line) and 6 (AI depth) on the same turn-on-the-spot capture. */
class SpinProcessor(private val eyeH: Double, private val depth: DepthEstimator?, private val aimNow: () -> Aim) {
    val edge: MutableList<PolarSample> = Collections.synchronizedList(ArrayList())
    val ai: MutableList<PolarSample> = Collections.synchronizedList(ArrayList())
    @Volatile var recording = false
    @Volatile var live = SpinLive()
    private val busy = AtomicBoolean(false)
    private val depthExec = Executors.newSingleThreadExecutor()

    fun close() { depthExec.shutdown() }

    fun onFrame(f: UprightFrame) {
        val aim = aimNow()
        if (!aim.valid) return
        val cy = f.cy
        val horizon = cy - f.focalPx * tan(aim.depression)
        // method 2: strongest brightness step below the horizon in a central strip
        val c0 = (f.w * 0.42).toInt(); val c1 = (f.w * 0.58).toInt()
        val rowMeans = DoubleArray(f.h) { r -> var s = 0.0; for (c in c0 until c1) s += f.lum(c, r); s / (c1 - c0) }
        val edgeRow = FloorEdge.detect(rowMeans, (horizon + 3).toInt().coerceAtLeast(3))
        var edgeDist: Double? = null
        if (edgeRow != null) {
            val dep = aim.depression + atan((edgeRow - cy) / f.focalPx)
            edgeDist = TiltMath.floorDistance(eyeH, dep)?.takeIf { it in 0.3..12.0 }
            if (recording && edgeDist != null) edge += PolarSample(aim.yaw, edgeDist)
        }
        live = live.copy(frameW = f.w, frameH = f.h, horizonRow = horizon, edgeRow = edgeRow, edgeDist = edgeDist, fovDeg = verticalFovDeg(f))
        // method 6: depth model when idle (it is slower than the camera)
        if (depth != null && busy.compareAndSet(false, true)) {
            depthExec.execute {
                try {
                    val t0 = System.currentTimeMillis()
                    val disp = depth.run(f)
                    val res = depthDistance(disp, f, aim)
                    live = live.copy(depthDist = res?.first, depthFitR2 = res?.second, depthMs = System.currentTimeMillis() - t0)
                    if (recording && res != null) ai += PolarSample(aim.yaw, res.first)
                } catch (_: Throwable) {
                } finally { busy.set(false) }
            }
        }
    }

    /** Scales relative disparity with floor pixels of known distance, then reads the wall straight ahead at eye level. */
    private fun depthDistance(disp: FloatArray, f: UprightFrame, aim: Aim): Pair<Double, Double>? {
        val W = DepthEstimator.W; val H = DepthEstimator.H
        val fm = f.focalPx * H / f.h
        val cy = H / 2.0
        val c0 = (W * 0.40).toInt(); val c1 = (W * 0.60).toInt()
        fun rowMean(y: Int): Double { var s = 0.0; for (x in c0 until c1) s += disp[y * W + x]; return s / (c1 - c0) }
        val invZ = ArrayList<Double>(); val d = ArrayList<Double>()
        for (y in (H * 0.82).toInt() until H) {
            val phi = atan((y - cy) / fm)
            val dep = aim.depression + phi
            if (dep < Math.toRadians(8.0)) continue
            val ray = eyeH / sin(dep)
            invZ += 1.0 / (ray * cos(phi)); d += rowMean(y)
        }
        val fit = DepthScale.fit(invZ, d) ?: return null
        if (fit.r2 < 0.5) return null
        val rh = (cy - fm * tan(aim.depression)).toInt()
        if (rh < H * 0.05 || rh > H * 0.78) return null
        var s = 0.0; var n = 0
        for (y in rh - 2..rh + 2) { s += rowMean(y.coerceIn(0, H - 1)); n++ }
        val z = DepthScale.depth(fit, s / n) ?: return null
        val dist = z / cos(aim.depression)
        return if (dist in 0.3..12.0) dist to fit.r2 else null
    }
}

@Composable
fun SpinScreen(store: LabStore, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val o = rememberOrientation()
    val aim by o.aim.collectAsState()
    val st by store.state.collectAsState()
    val h = st.eyeHeightM
    val depth = remember { runCatching { DepthEstimator(ctx) }.getOrNull() }
    val proc = remember(h) { h?.let { SpinProcessor(it, depth) { o.aim.value } } }
    DisposableEffect(proc) { onDispose { proc?.close(); depth?.close() } }
    var live by remember { mutableStateOf(SpinLive()) }
    var recording by remember { mutableStateOf(false) }
    var startMs by remember { mutableStateOf(0L) }
    var counts by remember { mutableStateOf(Triple(0, 0, 0)) }
    LaunchedEffect(proc) { while (true) { proc?.let { live = it.live; counts = Triple(it.edge.size, it.ai.size, maxOf(PolarOutline.coverageDeg(synchronizedCopy(it.edge)), PolarOutline.coverageDeg(synchronizedCopy(it.ai)))) }; delay(150) } }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CameraFeed(Modifier.fillMaxSize(), step = 4, onFrame = proc?.let { p -> { f: UprightFrame -> p.onFrame(f) } })
            Canvas(Modifier.fillMaxSize()) {
                if (live.frameW == 0) return@Canvas
                // PreviewView FIT_CENTER: the upright frame is letterboxed into the view
                val s = minOf(size.width / live.frameW, size.height / live.frameH)
                val offY = (size.height - live.frameH * s) / 2; val offX = (size.width - live.frameW * s) / 2
                live.horizonRow?.let { r -> val y = offY + (r * s).toFloat(); drawLine(Color(0xFF4FC3F7), Offset(offX, y), Offset(size.width - offX, y), 3f) }
                live.edgeRow?.let { r -> val y = offY + r * s; drawLine(Color(0xFFF2C14E), Offset(offX + live.frameW * s * 0.3f, y), Offset(offX + live.frameW * s * 0.7f, y), 6f) }
            }
            Crosshair(Modifier.fillMaxSize())
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${Method.SPIN_EDGE.title} + ${Method.SPIN_DEPTH.title}", style = MaterialTheme.typography.titleSmall)
            if (h == null || proc == null) { Text("Set your phone height first (home screen)."); TextButton(onDone) { Text("Back") }; return@Column }
            AimReadout(aim)
            Text("Blue = level line · amber = floor–wall line found. Tilt down until both show.", style = MaterialTheme.typography.bodySmall)
            Text("Floor-line: " + (live.edgeDist?.let { ft(it) } ?: "—") + " · AI depth: " + (live.depthDist?.let { ft(it) } ?: "—") +
                (live.depthMs?.let { " (${it} ms/frame)" } ?: "") + (if (depth == null) " · depth model failed to load" else ""))
            Text(if (recording) "Turn slowly a full circle on the spot. ${counts.third}° covered · ${counts.first} floor-line + ${counts.second} depth readings"
                else "Stand near the middle of the room, phone at the height you set (${ft(h)}).", style = MaterialTheme.typography.bodyMedium)
            live.fovDeg?.let { Text("Camera vertical view %.0f°".format(it), style = MaterialTheme.typography.labelSmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!recording) Button({ proc.edge.clear(); proc.ai.clear(); proc.recording = true; recording = true; startMs = System.currentTimeMillis() }) { Text("Start turning") }
                else Button({
                    proc.recording = false; recording = false
                    val dur = System.currentTimeMillis() - startMs
                    save(store, Method.SPIN_EDGE, synchronizedCopy(proc.edge), dur, h)
                    save(store, Method.SPIN_DEPTH, synchronizedCopy(proc.ai), dur, h)
                    onDone()
                }) { Text("Finish") }
                TextButton({ proc.recording = false; onDone() }) { Text("Cancel") }
            }
        }
    }
}

private fun synchronizedCopy(l: MutableList<PolarSample>): List<PolarSample> = synchronized(l) { l.toList() }

private fun save(store: LabStore, m: Method, samples: List<PolarSample>, dur: Long, h: Double) {
    val poly = PolarOutline.fit(samples)
    val cov = PolarOutline.coverageDeg(samples)
    store.addResult(LabResult(UUID.randomUUID().toString(), m, System.currentTimeMillis(), dur, 2, poly?.map { P(it.x, it.y) } ?: emptyList(), h,
        note = if (poly == null) "No outline from ${samples.size} readings (${cov}° covered)" else "${samples.size} readings, ${cov}° covered"))
}

