package com.owlacoustics.roomplanner.ui.plan

import androidx.compose.ui.geometry.Offset
import com.owlacoustics.domain.geometry.Vec2
import kotlin.math.cos
import kotlin.math.sin

/**
 * View-only transform: metres → screen pixels with y up, plus pan/zoom/rotation. Changing it never changes geometry.
 */
data class ViewTransform(val scale: Float = 80f, val offset: Offset = Offset(100f, 600f), val rotation: Float = 0f) {
    fun toScreen(p: Vec2): Offset {
        val x = p.x * scale; val y = -p.y * scale
        val c = cos(rotation); val s = sin(rotation)
        return Offset((x * c - y * s).toFloat(), (x * s + y * c).toFloat()) + offset
    }

    fun toWorld(o: Offset): Vec2 {
        val d = o - offset
        val c = cos(-rotation); val s = sin(-rotation)
        val x = d.x * c - d.y * s; val y = d.x * s + d.y * c
        return Vec2(x / scale.toDouble(), -y / scale.toDouble())
    }

    /** Screen-space delta → world delta (no translation). */
    fun deltaToWorld(d: Offset): Vec2 {
        val c = cos(-rotation); val s = sin(-rotation)
        val x = d.x * c - d.y * s; val y = d.x * s + d.y * c
        return Vec2(x / scale.toDouble(), -y / scale.toDouble())
    }

    fun pixelsToMeters(px: Float) = px / scale.toDouble()

    /** Applies a pinch/pan/rotate about [centroid]. */
    fun transformed(centroid: Offset, pan: Offset, zoom: Float, rot: Float): ViewTransform {
        val newScale = (scale * zoom).coerceIn(5f, 2000f)
        val z = newScale / scale
        // rotate/scale the offset around the centroid
        val rel = offset - centroid
        val c = cos(rot); val s = sin(rot)
        val rr = Offset(rel.x * c - rel.y * s, rel.x * s + rel.y * c) * z
        return ViewTransform(newScale, centroid + rr + pan, rotation + rot)
    }

    companion object {
        fun fit(points: List<Vec2>, width: Float, height: Float, rotation: Float = 0f, margin: Float = 72f): ViewTransform {
            if (points.isEmpty() || width <= 0 || height <= 0) return ViewTransform()
            val probe = ViewTransform(1f, Offset.Zero, rotation)
            val sp = points.map { probe.toScreen(it) }
            val minX = sp.minOf { it.x }; val maxX = sp.maxOf { it.x }
            val minY = sp.minOf { it.y }; val maxY = sp.maxOf { it.y }
            val w = (maxX - minX).coerceAtLeast(1e-3f); val h = (maxY - minY).coerceAtLeast(1e-3f)
            val sc = minOf((width - 2 * margin) / w, (height - 2 * margin) / h).coerceIn(5f, 2000f)
            val t = ViewTransform(sc, Offset.Zero, rotation)
            val sp2 = points.map { t.toScreen(it) }
            val cx = (sp2.minOf { it.x } + sp2.maxOf { it.x }) / 2; val cy = (sp2.minOf { it.y } + sp2.maxOf { it.y }) / 2
            return t.copy(offset = Offset(width / 2 - cx, height / 2 - cy))
        }
    }
}
