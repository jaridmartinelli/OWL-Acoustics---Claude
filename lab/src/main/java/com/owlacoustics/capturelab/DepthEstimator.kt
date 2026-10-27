package com.owlacoustics.capturelab

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * Depth Anything V2 Small (Apache-2.0, int8 ONNX) running on the phone. Output is relative disparity
 * (larger = closer); it has no metric scale until fitted to the floor (DepthScale).
 */
class DepthEstimator(context: Context) : AutoCloseable {
    companion object { const val W = 252; const val H = 336 } // multiples of 14, portrait 3:4
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        context.assets.open("depth_anything_v2_small_q8.onnx").use { it.readBytes() }, OrtSession.SessionOptions())
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f); private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** Returns disparity [H * W] for an upright frame resized to W×H. */
    fun run(f: UprightFrame): FloatArray {
        val plane = W * H
        val data = FloatArray(3 * plane)
        for (y in 0 until H) for (x in 0 until W) {
            val sx = (x * f.w / W).coerceIn(0, f.w - 1); val sy = (y * f.h / H).coerceIn(0, f.h - 1)
            val c = f.rgb[sy * f.w + sx]
            val i = y * W + x
            data[i] = (((c shr 16) and 255) / 255f - mean[0]) / std[0]
            data[plane + i] = (((c shr 8) and 255) / 255f - mean[1]) / std[1]
            data[2 * plane + i] = ((c and 255) / 255f - mean[2]) / std[2]
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, H.toLong(), W.toLong())).use { t ->
            session.run(mapOf("pixel_values" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val out = (r[0].value as Array<Array<FloatArray>>)[0]
                val flat = FloatArray(plane)
                for (y in 0 until H) System.arraycopy(out[y], 0, flat, y * W, W)
                return flat
            }
        }
    }

    override fun close() { session.close() }
}
