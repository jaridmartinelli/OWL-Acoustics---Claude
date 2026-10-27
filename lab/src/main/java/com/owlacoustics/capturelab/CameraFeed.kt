package com.owlacoustics.capturelab

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.atan

/** Upright (as displayed in portrait) RGB frame, downsampled, with its vertical focal length in pixels. */
class UprightFrame(val w: Int, val h: Int, val rgb: IntArray, val focalPx: Double, val timeMs: Long) {
    fun lum(x: Int, y: Int): Double { val c = rgb[y * w + x]; return 0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255) }
    val cy get() = h / 2.0
}

/**
 * Camera preview plus an RGBA analysis stream converted to upright frames. Vertical field of view comes from
 * the lens focal length and sensor size reported by Camera2 (assumes the analysis stream covers the full 4:3 sensor).
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraFeed(modifier: Modifier, step: Int = 4, onFrame: ((UprightFrame) -> Unit)?) {
    val owner = LocalLifecycleOwner.current
    val exec = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { exec.shutdown() } }
    AndroidView(factory = { c ->
        val view = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().setResolutionSelector(sel()).build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setResolutionSelector(sel())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            provider.unbindAll()
            val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble()
            val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (onFrame != null) analysis.setAnalyzer(exec) { img ->
                try {
                    val f = toUpright(img, step, focal, sensor?.width?.toDouble(), sensor?.height?.toDouble())
                    if (f != null) onFrame(f)
                } finally { img.close() }
            }
        }, ContextCompat.getMainExecutor(c))
        view
    }, modifier = modifier)
}

private fun sel() = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
    .build()

private fun toUpright(img: ImageProxy, step: Int, focalMm: Double?, sensorWmm: Double?, sensorHmm: Double?): UprightFrame? {
    val plane = img.planes[0]
    val buf = plane.buffer
    val rs = plane.rowStride; val ps = plane.pixelStride
    val w = img.width; val h = img.height
    val rot = img.imageInfo.rotationDegrees
    val uw = (if (rot % 180 == 0) w else h) / step
    val uh = (if (rot % 180 == 0) h else w) / step
    val out = IntArray(uw * uh)
    for (v in 0 until uh) for (u in 0 until uw) {
        val su = u * step; val sv = v * step
        val (x, y) = when (rot) {
            90 -> sv to (h - 1 - su)
            180 -> (w - 1 - su) to (h - 1 - sv)
            270 -> (w - 1 - sv) to su
            else -> su to sv
        }
        val i = y * rs + x * ps
        val rr = buf.get(i).toInt() and 255; val gg = buf.get(i + 1).toInt() and 255; val bb = buf.get(i + 2).toInt() and 255
        out[v * uw + u] = (rr shl 16) or (gg shl 8) or bb
    }
    // the upright image's vertical axis is the sensor's long axis when rotated by 90/270
    val sensorAlongUprightH = if (rot % 180 == 0) sensorHmm else sensorWmm
    val focalPx = if (focalMm != null && sensorAlongUprightH != null && sensorAlongUprightH > 0) uh * focalMm / sensorAlongUprightH
    else uh / 2.0 / kotlin.math.tan(Math.toRadians(33.0)) // fallback ~66° vertical FOV, flagged in the UI
    return UprightFrame(uw, uh, out, focalPx, System.currentTimeMillis())
}

fun verticalFovDeg(f: UprightFrame) = Math.toDegrees(2 * atan(f.h / 2.0 / f.focalPx))
