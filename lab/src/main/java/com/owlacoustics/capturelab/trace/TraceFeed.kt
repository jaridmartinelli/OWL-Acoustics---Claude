package com.owlacoustics.capturelab.trace

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.owlacoustics.domain.geometry.Vec3
import com.owlacoustics.domain.trace.EdgeMap
import com.owlacoustics.domain.trace.Intrinsics
import com.owlacoustics.domain.trace.Mat3
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** Pixel storage shared between the camera thread and the screen; reused once nobody holds it. */
class FrameBuffer(val bitmap: Bitmap, val lum: ByteArray, val lumW: Int, val lumH: Int) {
    val refs = AtomicInteger(0)
}

/**
 * One camera picture with what is needed to draw it and to turn a pixel into a direction in the room.
 * The bitmap is in sensor orientation; rotate it clockwise by [rotationDeg] to see it upright. [k] and the
 * half-resolution luminance describe the upright picture. [rot] takes upright-camera axes (x right, y down,
 * z forward) to the world (z up). [tracked] is the camera position when a tracker supplies one.
 */
class TraceFrame(
    private val buf: FrameBuffer,
    val rotationDeg: Int,
    val k: Intrinsics,
    val rot: Mat3,
    val tracked: Vec3?,
    val seq: Long,
    /** How fast the phone was turning, degrees per second. */
    val turnRate: Double,
    val poseOk: Boolean,
    /** True when the lens data was missing and a typical field of view was assumed. */
    val lensGuessed: Boolean,
) {
    val bitmap get() = buf.bitmap
    val lumW get() = buf.lumW
    val lumH get() = buf.lumH
    /** Luminance pixels per full-resolution pixel. */
    val lumScale get() = buf.lumW.toDouble() / k.w
    @Volatile private var edges: EdgeMap? = null
    val edgesReady get() = edges != null
    fun edgeMap(): EdgeMap = edges ?: synchronized(this) { edges ?: EdgeMap.of(buf.lum, buf.lumW, buf.lumH).also { edges = it } }
    fun retain() { buf.refs.incrementAndGet() }
    fun release() { buf.refs.decrementAndGet() }
}

/** A source of camera pictures with poses. Frames arrive on the main thread already retained; the receiver releases them. */
interface TraceFeed {
    var onFrame: ((TraceFrame) -> Unit)?
    var onProblem: ((String) -> Unit)?
    fun start(context: Context, owner: LifecycleOwner)
    fun stop()
}

/** Phone orientation over time from the gyroscope-based rotation sensor (no compass: smooth, slow drift in heading). */
class RotationTracker(context: Context) : SensorEventListener {
    private class Sample(val t: Long, val w: Double, val x: Double, val y: Double, val z: Double)
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val ring = arrayOfNulls<Sample>(256)
    private var head = 0
    private val q = FloatArray(4)
    private var sameClock: Boolean? = null
    val available get() = sensor != null

    private var listening = false
    fun start() { if (!listening) sensor?.let { listening = sm.registerListener(this, it, 10_000) } }
    fun stop() { sm.unregisterListener(this); listening = false }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.elapsedRealtimeNanos()
        // most phones stamp sensor events on the elapsed-realtime clock; fall back to arrival time when one does not
        val same = sameClock ?: (abs(now - e.timestamp) < 1_000_000_000L).also { sameClock = it }
        SensorManager.getQuaternionFromVector(q, e.values)
        val s = Sample(if (same) e.timestamp else now, q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble())
        synchronized(ring) { ring[head] = s; head = (head + 1) % ring.size }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun neighbours(t: Long): Pair<Sample, Sample>? = synchronized(ring) {
        var newer: Sample? = null
        for (i in 1..ring.size) {
            val s = ring[(head - i + ring.size * 2) % ring.size] ?: break
            if (s.t <= t) return@synchronized s to (newer ?: s)
            newer = s
        }
        newer?.let { it to it }
    }

    /** Upright-camera → world rotation at time [t] (elapsed-realtime nanoseconds), or null before the first reading. */
    fun at(t: Long): Mat3? {
        val (a, b) = neighbours(t) ?: return null
        val f = if (b.t == a.t) 0.0 else ((t - a.t).toDouble() / (b.t - a.t)).coerceIn(0.0, 1.0)
        val sign = if (a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z < 0) -1.0 else 1.0
        var w = a.w + (sign * b.w - a.w) * f; var x = a.x + (sign * b.x - a.x) * f
        var y = a.y + (sign * b.y - a.y) * f; var z = a.z + (sign * b.z - a.z) * f
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n < 1e-9) return null
        w /= n; x /= n; y /= n; z /= n
        // device → world, then the back camera's axes: x right, y down (−device Y), z forward (−device Z)
        return Mat3(doubleArrayOf(
            1 - 2 * (y * y + z * z), -2 * (x * y - w * z), -2 * (x * z + w * y),
            2 * (x * y + w * z), -(1 - 2 * (x * x + z * z)), -2 * (y * z - w * x),
            2 * (x * z - w * y), -2 * (y * z + w * x), -(1 - 2 * (x * x + y * y))))
    }

    /** Recent turning speed in degrees per second. */
    fun turnRate(): Double = synchronized(ring) {
        val b = ring[(head - 1 + ring.size) % ring.size] ?: return 0.0
        val a = ring[(head - 6 + ring.size) % ring.size] ?: return 0.0
        val dt = (b.t - a.t) / 1e9
        if (dt <= 0) return 0.0
        val dot = abs(a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z).coerceAtMost(1.0)
        Math.toDegrees(2 * acos(dot)) / dt
    }
}

/** Focal length in stream pixels from what the camera reports about its lens and sensor. */
class LensModel(private val focalMm: Double?, private val sensorLongMm: Double?, private val pixelLong: Int?, private val activeLong: Int?) {
    /** Second value: true when lens data was missing and a typical 66° field of view was assumed. */
    fun focalPx(streamLong: Int): Pair<Double, Boolean> {
        if (focalMm == null || sensorLongMm == null || sensorLongMm <= 0) return streamLong / 2.0 / tan(Math.toRadians(33.0)) to true
        // the physical size spans the whole pixel array; the stream shows the (slightly smaller) active area
        val sensorPx = if (pixelLong != null && pixelLong > 0) focalMm * pixelLong / sensorLongMm else null
        return if (sensorPx != null && activeLong != null && activeLong > 0) sensorPx * streamLong / activeLong to false
        else streamLong * focalMm / sensorLongMm to false
    }

    companion object {
        @OptIn(ExperimentalCamera2Interop::class)
        fun of(info: Camera2CameraInfo): LensModel {
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble()
            val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pix = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val act = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            return LensModel(focal, size?.let { maxOf(it.width, it.height).toDouble() }, pix?.let { maxOf(it.width, it.height) },
                act?.let { maxOf(it.width(), it.height()) })
        }
    }
}

/** Reusable frame storage. A buffer is free once every holder has released it. */
class FramePool(private val limit: Int = 8) {
    private val all = ArrayList<FrameBuffer>()
    /** Returns a buffer already counted as held once, or null when every buffer is in use. */
    @Synchronized fun obtain(w: Int, h: Int, lumW: Int, lumH: Int): FrameBuffer? {
        all.removeAll { it.refs.get() == 0 && (it.bitmap.width != w || it.bitmap.height != h) }
        val free = all.firstOrNull { it.refs.get() == 0 }
            ?: if (all.size < limit) FrameBuffer(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), ByteArray(lumW * lumH), lumW, lumH).also { all += it } else return null
        free.refs.incrementAndGet()
        return free
    }
}

/** Stand-and-turn feed: CameraX pictures paired with the rotation sensor. The phone's position is not tracked. */
class SensorTraceFeed : TraceFeed {
    override var onFrame: ((TraceFrame) -> Unit)? = null
    override var onProblem: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val pool = FramePool()
    private var exec: ExecutorService? = null
    private var tracker: RotationTracker? = null
    private var provider: ProcessCameraProvider? = null
    private var seq = 0L
    private var row = ByteArray(0)
    private var packed: ByteBuffer? = null
    private var cameraClockSame: Boolean? = null
    @Volatile private var running = false
    private var detach: (() -> Unit)? = null

    @OptIn(ExperimentalCamera2Interop::class)
    override fun start(context: Context, owner: LifecycleOwner) {
        if (running) return
        running = true
        val ex = Executors.newSingleThreadExecutor().also { exec = it }
        val tr = RotationTracker(context).also { tracker = it; it.start() }
        if (!tr.available) onProblem?.invoke("This phone has no rotation sensor, so lines cannot stay in place.")
        // no sensor use while the app is in the background
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tr.start() else if (e == Lifecycle.Event.ON_PAUSE) tr.stop() }
        owner.lifecycle.addObserver(obs)
        detach = { owner.lifecycle.removeObserver(obs) }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!running) return@addListener
            try {
                val p = future.get().also { provider = it }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                p.unbindAll()
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                val lens = LensModel.of(Camera2CameraInfo.from(cam.cameraInfo))
                analysis.setAnalyzer(ex) { img -> try { handle(img, lens, tr) } catch (_: Throwable) {} finally { img.close() } }
            } catch (e: Exception) {
                onProblem?.invoke("Camera could not start: " + (e.message ?: e.javaClass.simpleName))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun stop() {
        running = false
        detach?.invoke(); detach = null
        runCatching { provider?.unbindAll() }
        tracker?.stop(); tracker = null
        exec?.shutdown(); exec = null
    }

    private fun handle(img: ImageProxy, lens: LensModel, tr: RotationTracker) {
        if (!running) return
        val w = img.width; val h = img.height
        val rot = ((img.imageInfo.rotationDegrees % 360) + 360) % 360
        val swap = rot % 180 != 0
        val hw = w / 2; val hh = h / 2
        val lw = if (swap) hh else hw; val lh = if (swap) hw else hh
        val fb = pool.obtain(w, h, lw, lh) ?: return
        val plane = img.planes[0]
        val buf = plane.buffer; val rs = plane.rowStride
        if (row.size < w * 4) row = ByteArray(w * 4)
        // luminance at half resolution, turned upright while copying
        val lum = fb.lum
        for (hy in 0 until hh) {
            buf.position(hy * 2 * rs)
            buf.get(row, 0, min(w * 4, buf.remaining()))
            for (hx in 0 until hw) {
                val i = hx * 8
                val l = ((row[i].toInt() and 255) * 77 + (row[i + 1].toInt() and 255) * 150 + (row[i + 2].toInt() and 255) * 29) shr 8
                val o = when (rot) {
                    90 -> hx * lw + (hh - 1 - hy)
                    180 -> (hh - 1 - hy) * lw + (hw - 1 - hx)
                    270 -> (hw - 1 - hx) * lw + hy
                    else -> hy * lw + hx
                }
                lum[o] = l.toByte()
            }
        }
        buf.rewind()
        if (rs == w * 4) fb.bitmap.copyPixelsFromBuffer(buf)
        else {
            val pk = packed?.takeIf { it.capacity() == w * h * 4 } ?: ByteBuffer.allocateDirect(w * h * 4).also { packed = it }
            pk.clear()
            for (y in 0 until h) { buf.position(y * rs); buf.get(row, 0, w * 4); pk.put(row, 0, w * 4) }
            pk.rewind(); fb.bitmap.copyPixelsFromBuffer(pk)
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val ts = img.imageInfo.timestamp
        val same = cameraClockSame ?: (now - ts in 0..1_000_000_000L).also { cameraClockSame = it }
        val pose = tr.at(if (same) ts else now - 60_000_000L)
        val uw = if (swap) h else w; val uh = if (swap) w else h
        val (f, guessed) = lens.focalPx(maxOf(w, h))
        val frame = TraceFrame(fb, rot, Intrinsics(f, uw / 2.0, uh / 2.0, uw, uh), pose ?: Mat3.identity, null, ++seq, tr.turnRate(), pose != null, guessed)
        main.post { val cb = onFrame; if (cb != null && running) cb(frame) else frame.release() }
    }
}
