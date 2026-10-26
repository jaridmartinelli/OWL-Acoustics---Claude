package com.owlacoustics.roomplanner.ui.ceiling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.owlacoustics.domain.geometry.Vec3
import com.owlacoustics.domain.mesh.RoomMesh
import com.owlacoustics.domain.mesh.SurfaceKind
import kotlin.math.cos
import kotlin.math.sin

/**
 * Software-projected 3D review of the canonical mesh (no separate model copy). Drag to orbit; tap a wall to correct it in 2D.
 */
@Composable
fun Room3DView(mesh: RoomMesh, modifier: Modifier = Modifier, onPick: (String) -> Unit = {}) {
    var yaw by remember { mutableFloatStateOf(-0.7f) }
    var pitch by remember { mutableFloatStateOf(0.6f) }
    val pts = mesh.triangles.flatMap { listOf(it.a, it.b, it.c) } + mesh.lines.flatMap { it.points }
    val cx = pts.map { it.x }.average().takeIf { it.isFinite() } ?: 0.0
    val cy = pts.map { it.y }.average().takeIf { it.isFinite() } ?: 0.0
    val cz = pts.map { it.z }.average().takeIf { it.isFinite() } ?: 0.0
    val radius = pts.maxOfOrNull { kotlin.math.sqrt((it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) + (it.z - cz) * (it.z - cz)) }?.coerceAtLeast(0.5) ?: 1.0

    fun project(p: Vec3, w: Float, h: Float): Pair<Offset, Double> {
        val x = p.x - cx; val y = p.y - cy; val z = p.z - cz
        val cyaw = cos(yaw.toDouble()); val syaw = sin(yaw.toDouble())
        val x1 = x * cyaw - y * syaw; val y1 = x * syaw + y * cyaw
        val cp = cos(pitch.toDouble()); val sp = sin(pitch.toDouble())
        val depth = y1 * cp + z * sp
        val up = -y1 * sp + z * cp
        val camDist = radius * 3.2
        val f = (minOf(w, h) * 0.9) / (radius * 2)
        val s = camDist / (camDist + depth)
        return Offset((w / 2 + x1 * f * s).toFloat(), (h / 2 - up * f * s).toFloat()) to depth
    }

    Canvas(
        modifier
            .semantics { contentDescription = "3D view: ${mesh.description}. " + if (mesh.partial) "Partial: ${mesh.missing.size} input(s) missing." else "All represented surfaces complete." }
            .pointerInput(Unit) { detectDragGestures { _, d -> yaw += d.x * 0.01f; pitch = (pitch + d.y * 0.01f).coerceIn(-1.4f, 1.4f) } }
            .pointerInput(mesh) {
                detectTapGestures { tap ->
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    var best: Pair<String, Double>? = null
                    for (t in mesh.triangles) {
                        if (t.kind != SurfaceKind.WALL || t.ref.isEmpty()) continue
                        val (a, da) = project(t.a, w, h); val (b, db) = project(t.b, w, h); val (c, dc) = project(t.c, w, h)
                        if (inside(tap, a, b, c)) { val d = (da + db + dc) / 3; if (best == null || d < best.second) best = t.ref to d }
                    }
                    best?.let { onPick(it.first) }
                }
            },
    ) {
        val w = size.width; val h = size.height
        val light = Vec3(0.4, -0.6, 0.7).normalized()
        val faces = mesh.triangles.map { t ->
            val (a, da) = project(t.a, w, h); val (b, db) = project(t.b, w, h); val (c, dc) = project(t.c, w, h)
            val n = ((t.b - t.a) cross (t.c - t.a)).normalized()
            val shade = (0.55 + 0.45 * kotlin.math.abs(n dot light)).toFloat()
            Triple(listOf(a, b, c), (da + db + dc) / 3, t.kind to shade)
        }.sortedByDescending { it.second }
        for ((p, _, ks) in faces) {
            val (kind, shade) = ks
            val base = when (kind) {
                SurfaceKind.FLOOR -> Color(0xFFB8B2A6)
                SurfaceKind.WALL -> Color(0xFFE6E3DC)
                SurfaceKind.CEILING -> Color(0xFFCFD8DC)
                SurfaceKind.DETAIL -> Color(0xFF6D6F73)
            }
            val col = Color(base.red * shade, base.green * shade, base.blue * shade, 0.92f)
            val path = Path().apply { moveTo(p[0].x, p[0].y); lineTo(p[1].x, p[1].y); lineTo(p[2].x, p[2].y); close() }
            drawPath(path, col)
            drawPath(path, Color(0x33000000), style = Stroke(0.8f))
        }
        for (l in mesh.lines) {
            val color = when (l.kind) { "opening" -> Color(0xFF1F7A72); "outline" -> Color(0xFF2B2D31); else -> Color(0xFFB3261E) }
            val dashed = l.kind.endsWith("unknown") || l.kind == "outline"
            for (i in 1 until l.points.size) {
                drawLine(color, project(l.points[i - 1], w, h).first, project(l.points[i], w, h).first, 4f,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null)
            }
        }
    }
}

private fun inside(p: Offset, a: Offset, b: Offset, c: Offset): Boolean {
    fun s(p1: Offset, p2: Offset, p3: Offset) = (p1.x - p3.x) * (p2.y - p3.y) - (p2.x - p3.x) * (p1.y - p3.y)
    val d1 = s(p, a, b); val d2 = s(p, b, c); val d3 = s(p, c, a)
    val neg = d1 < 0 || d2 < 0 || d3 < 0; val pos = d1 > 0 || d2 > 0 || d3 > 0
    return !(neg && pos)
}
