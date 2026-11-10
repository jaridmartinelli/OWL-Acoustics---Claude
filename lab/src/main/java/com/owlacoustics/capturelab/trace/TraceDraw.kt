package com.owlacoustics.capturelab.trace

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import com.owlacoustics.domain.trace.AnchorKind
import com.owlacoustics.domain.trace.Pinhole
import com.owlacoustics.domain.trace.Seg3
import com.owlacoustics.domain.trace.SolvedSegment
import com.owlacoustics.domain.trace.Surface

object TraceColors {
    val floor = Color(0xFF22CCFF)
    val ceiling = Color(0xFFFFE01A)
    val wall = Color(0xFF9CF02A)
    val ink = Color(0xFF0B1216)
    val warn = Color(0xFFFFB020)
    fun of(s: Surface) = if (s == Surface.FLOOR) floor else ceiling
}

private fun DrawScope.dp(v: Float) = v * density

/** Liang–Barsky: the part of a→b inside [r], or null. */
private fun clipToRect(a: Offset, b: Offset, r: Rect): Pair<Offset, Offset>? {
    var t0 = 0f; var t1 = 1f
    val dx = b.x - a.x; val dy = b.y - a.y
    fun edge(p: Float, q: Float): Boolean {
        if (p == 0f) return q >= 0
        val t = q / p
        if (p < 0) { if (t > t1) return false; if (t > t0) t0 = t } else { if (t < t0) return false; if (t < t1) t1 = t }
        return true
    }
    if (!edge(-dx, a.x - r.left) || !edge(dx, r.right - a.x) || !edge(-dy, a.y - r.top) || !edge(dy, r.bottom - a.y)) return null
    return Offset(a.x + dx * t0, a.y + dy * t0) to Offset(a.x + dx * t1, a.y + dy * t1)
}

private fun DrawScope.frameImage(f: TraceFrame, originX: Float, originY: Float, s: Float) {
    val w = f.k.w.toFloat(); val h = f.k.h.toFloat()
    withTransform({
        translate(originX, originY)
        scale(s, s, Offset.Zero)
        when (f.rotationDeg) {
            90 -> { translate(w, 0f); rotate(90f, Offset.Zero) }
            180 -> { translate(w, h); rotate(180f, Offset.Zero) }
            270 -> { translate(0f, h); rotate(270f, Offset.Zero) }
        }
    }) { drawImage(f.bitmap.asImageBitmap()) }
}

private fun DrawScope.grid(vm: ViewMap, lines: List<Seg3>, color: Color, bounds: Rect) {
    if (lines.isEmpty()) return
    val pts = ArrayList<Offset>(lines.size * 2)
    for (l in lines) {
        val (a, b) = vm.segment(l.a, l.b) ?: continue
        val (ca, cb) = clipToRect(a, b, bounds) ?: continue
        pts += ca; pts += cb
    }
    drawPoints(pts, PointMode.Lines, Color.Black.copy(alpha = 0.22f), dp(2.6f))
    drawPoints(pts, PointMode.Lines, color.copy(alpha = 0.85f), dp(1.2f))
}

private fun DrawScope.fill(vm: ViewMap, poly: List<Vec3>, color: Color, alpha: Float): Offset? {
    val pts = vm.polygon(poly)
    if (pts.size < 3) return null
    val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
    drawPath(path, color.copy(alpha = alpha))
    return Offset(pts.sumOf { it.x.toDouble() }.toFloat() / pts.size, pts.sumOf { it.y.toDouble() }.toFloat() / pts.size)
}

private fun DrawScope.pill(tm: TextMeasurer, text: String, center: Offset, bg: Color, fg: Color, sizeSp: Float, bold: Boolean = true, border: Color? = null, lock: Boolean = false): Rect {
    val layout = tm.measure(text, TextStyle(fontSize = sizeSp.sp, color = fg, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium))
    val lockW = if (lock) dp(13f) else 0f
    val w = layout.size.width + dp(16f) + lockW; val h = layout.size.height + dp(8f)
    val tl = Offset(center.x - w / 2, center.y - h / 2)
    drawRoundRect(bg, tl, Size(w, h), CornerRadius(h / 2, h / 2))
    if (border != null) drawRoundRect(border, tl, Size(w, h), CornerRadius(h / 2, h / 2), style = Stroke(dp(1.5f)))
    if (lock) {
        // small padlock: this size was typed, not calculated
        val x = tl.x + dp(8f); val cy = center.y
        drawRoundRect(fg, Offset(x, cy - dp(1f)), Size(dp(9f), dp(7f)), CornerRadius(dp(1.5f), dp(1.5f)))
        drawArc(fg, 180f, 180f, false, Offset(x + dp(1.7f), cy - dp(5.5f)), Size(dp(5.6f), dp(8f)), style = Stroke(dp(1.6f)))
    }
    drawText(layout, topLeft = Offset(tl.x + dp(8f) + lockW, tl.y + dp(4f)))
    return Rect(tl, Size(w, h))
}

private fun DrawScope.boundaryLine(a: Offset, b: Offset, color: Color, alpha: Float, dashed: Boolean) {
    drawLine(color.copy(alpha = 0.28f * alpha), a, b, dp(11f), StrokeCap.Round)
    drawLine(TraceColors.ink.copy(alpha = 0.75f * alpha), a, b, dp(6f), StrokeCap.Round)
    drawLine(color.copy(alpha = alpha), a, b, dp(3.2f), StrokeCap.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(dp(9f), dp(8f))) else null)
}

private fun DrawScope.anchor(p: Offset, kind: AnchorKind, color: Color, alpha: Float, selected: Boolean) {
    val r = dp(if (selected) 9f else 6.5f)
    val tl = Offset(p.x - r, p.y - r); val sz = Size(2 * r, 2 * r)
    drawRect(TraceColors.ink.copy(alpha = alpha), Offset(tl.x - dp(2f), tl.y - dp(2f)), Size(sz.width + dp(4f), sz.height + dp(4f)))
    if (kind == AnchorKind.CORNER) drawRect(color.copy(alpha = alpha), tl, sz)
    else drawRect(color.copy(alpha = alpha), Offset(tl.x + dp(1.2f), tl.y + dp(1.2f)), Size(sz.width - dp(2.4f), sz.height - dp(2.4f)), style = Stroke(dp(2.4f)))
    if (selected) drawRect(Color.White, Offset(tl.x - dp(2f), tl.y - dp(2f)), Size(sz.width + dp(4f), sz.height + dp(4f)), style = Stroke(dp(2f)))
}

/** Draws the camera picture and everything pinned to the room. Also records where size labels are, for touches. */
fun DrawScope.drawTrace(c: TraceController, tm: TextMeasurer) {
    drawRect(Color.Black)
    c.labels.clear(); c.deleteBox = null
    val f = c.frame ?: return
    val vm = c.viewMap(f) ?: return
    val sol = c.solution; val mesh = c.mesh
    val bounds = Rect(-dp(40f), -dp(40f), size.width + dp(40f), size.height + dp(40f))
    val inner = Rect(dp(34f), dp(18f), size.width - dp(34f), size.height - dp(18f))
    clipRect {
        val o = vm.toScreen(Vec2(0.0, 0.0))
        frameImage(f, o.x, o.y, vm.pxPerImage)

        // surfaces: light tint + one-foot grid
        val floorC = fill(vm, mesh.floorFill, TraceColors.floor, 0.10f)
        val ceilC = fill(vm, mesh.ceilingFill, TraceColors.ceiling, 0.10f)
        var wallTag: Offset? = null; var wallSpan = 0f
        for (q in mesh.wallFills) {
            val centre = fill(vm, q, TraceColors.wall, 0.08f) ?: continue
            val pts = vm.polygon(q)
            val span = pts.maxOf { it.x } - pts.minOf { it.x }
            if (inner.contains(centre) && span > wallSpan) { wallSpan = span; wallTag = centre }
        }
        grid(vm, mesh.floorLines, TraceColors.floor, bounds)
        grid(vm, mesh.wallLines, TraceColors.wall, bounds)
        grid(vm, mesh.ceilingLines, TraceColors.ceiling, bounds)
        if (floorC != null && inner.contains(floorC)) pill(tm, "FLOOR", floorC, Color(0xB3101418), Color.White, 11f)
        if (ceilC != null && inner.contains(ceilC)) pill(tm, "CEILING", ceilC, Color(0xB3101418), Color.White, 11f)
        wallTag?.takeIf { wallSpan > dp(120f) }?.let { pill(tm, "WALL", it, Color(0xB3101418), Color.White, 11f) }

        // boundaries: the active one bright, the locked one dimmed
        for (surface in listOf(if (c.surface == Surface.FLOOR) Surface.CEILING else Surface.FLOOR, c.surface)) {
            val active = surface == c.surface
            val alpha = if (active) 1f else 0.45f
            val color = TraceColors.of(surface)
            for (seg in sol.segments) if (seg.surface == surface) {
                val (a, b) = vm.segment(seg.pa, seg.pb) ?: continue
                val (ca, cb) = clipToRect(a, b, bounds) ?: continue
                boundaryLine(ca, cb, color, alpha, !seg.snapped)
            }
            val b = c.doc.boundary(surface)
            for (pt in sol.points(surface)) {
                val s = vm.project(pt.pos) ?: continue
                if (!bounds.contains(s)) continue
                anchor(s, pt.kind, color, alpha, active && c.selected == pt.id)
            }
            // an open loop shows where to finish
            if (active && !b.closed && b.corners.size >= 3) sol.points(surface).firstOrNull()?.let { first ->
                vm.project(first.pos)?.let { drawCircle(Color.White.copy(alpha = 0.9f), dp(17f), it, style = Stroke(dp(2f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dp(5f), dp(4f))))) }
            }
        }

        // the stroke being drawn
        val st = c.stroke; val fit = c.strokeFit
        if (st != null && fit != null) {
            val color = TraceColors.of(c.surface)
            val pts = fit.vertices.map { v ->
                if (st.ref === f) vm.toScreen(v)
                else Pinhole.projectDirection(f.k, vm.pose, Pinhole.worldRay(st.ref.k, st.refPose, v.x, v.y))?.let(vm::toScreen)
            }
            if (!st.continues) c.doc.boundary(c.surface).corners.lastOrNull()?.let { last ->
                val from = sol.points(c.surface).firstOrNull { it.id == last.id }?.let { vm.project(it.pos) }
                val to = pts.firstOrNull()
                if (from != null && to != null) boundaryLine(from, to, color, 0.8f, true)
            }
            for (i in 0 until pts.size - 1) {
                val a = pts[i] ?: continue; val b = pts[i + 1] ?: continue
                boundaryLine(a, b, color, 1f, !fit.segmentSnapped[i])
            }
            for (i in 1 until pts.size - 1) pts[i]?.let { anchor(it, AnchorKind.CORNER, color, 1f, false) }
        }

        // sizes on the active boundary
        val editing = c.editing as? EditTarget.Segment
        for (seg in sol.segments) if (seg.surface == c.surface) {
            val (a, b) = vm.segment(seg.pa, seg.pb) ?: continue
            val (ca, cb) = clipToRect(a, b, inner) ?: continue
            if ((cb - ca).getDistance() < dp(46f)) continue
            val mid = (ca + cb) / 2f
            val isEdited = editing != null && editing.surface == seg.surface && editing.a == seg.a && editing.b == seg.b
            if (isEdited) { boundaryLine(ca, cb, Color.White, 1f, false); c.labels += LabelBox(Rect(mid, 1f), seg); continue }
            c.labels += LabelBox(label(tm, c, seg, mid), seg)
        }

        // Delete bubble beside the selected point
        c.selected?.let { id ->
            val s = sol.points(c.surface).firstOrNull { it.id == id }?.let { vm.project(it.pos) } ?: return@let
            val at = Offset(s.x.coerceIn(dp(50f), size.width - dp(50f)), if (s.y > dp(70f)) s.y - dp(40f) else s.y + dp(40f))
            c.deleteBox = pill(tm, "Delete", at, Color(0xFFD93025), Color.White, 14f)
        }
    }
    loupe(c, f, vm)
}

private fun DrawScope.label(tm: TextMeasurer, c: TraceController, seg: SolvedSegment, at: Offset): Rect {
    val text = c.labelFor(seg)
    return when {
        seg.typed != null -> pill(tm, text, at, Color.White, TraceColors.ink, 14f, lock = true)
        c.solution.calibrated -> pill(tm, text, at, Color(0xE6101418), Color.White, 14f)
        text.startsWith("~") -> pill(tm, text, at, Color(0xCC101418), Color(0xFFD5DADD), 13f, bold = false)
        else -> pill(tm, text, at, Color(0xCC101418), Color.White, 12f, bold = false, border = TraceColors.of(seg.surface))
    }
}

/** Magnified view above the finger of the spot it covers, centred on where the point really lands. */
private fun DrawScope.loupe(c: TraceController, f: TraceFrame, vm: ViewMap) {
    val finger = c.finger ?: return
    val (q, snapped) = c.loupeAt ?: return
    val r = dp(46f)
    var centre = Offset(finger.x, finger.y - dp(104f))
    if (centre.y < r + dp(6f)) centre = Offset(if (finger.x > size.width / 2) finger.x - dp(118f) else finger.x + dp(118f), finger.y)
    centre = Offset(centre.x.coerceIn(r + dp(4f), size.width - r - dp(4f)), centre.y.coerceIn(r + dp(4f), size.height - r - dp(4f)))
    val mag = vm.pxPerImage * 2.4f
    clipPath(Path().apply { addOval(Rect(centre, r)) }) {
        drawRect(Color.Black)
        frameImage(f, centre.x - q.x.toFloat() * mag, centre.y - q.y.toFloat() * mag, mag)
    }
    val color = TraceColors.of(c.surface)
    drawCircle(TraceColors.ink, r + dp(2f), centre, style = Stroke(dp(5f)))
    drawCircle(color, r, centre, style = Stroke(dp(3f), pathEffect = if (snapped) null else PathEffect.dashPathEffect(floatArrayOf(dp(7f), dp(6f)))))
    drawLine(Color.White, centre - Offset(dp(12f), 0f), centre - Offset(dp(4f), 0f), dp(1.5f))
    drawLine(Color.White, centre + Offset(dp(4f), 0f), centre + Offset(dp(12f), 0f), dp(1.5f))
    drawLine(Color.White, centre - Offset(0f, dp(12f)), centre - Offset(0f, dp(4f)), dp(1.5f))
    drawLine(Color.White, centre + Offset(0f, dp(4f)), centre + Offset(0f, dp(12f)), dp(1.5f))
    val h = dp(3.5f)
    if (snapped) drawRect(color, Offset(centre.x - h, centre.y - h), Size(2 * h, 2 * h))
    else drawRect(color, Offset(centre.x - h, centre.y - h), Size(2 * h, 2 * h), style = Stroke(dp(1.5f)))
}
