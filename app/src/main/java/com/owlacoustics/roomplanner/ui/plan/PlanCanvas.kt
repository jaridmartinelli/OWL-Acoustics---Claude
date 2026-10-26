package com.owlacoustics.roomplanner.ui.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.owlacoustics.domain.edit.OpeningHandle
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.DoorHanding
import com.owlacoustics.domain.model.ItemKind
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.Role
import com.owlacoustics.roomplanner.ui.common.display
import kotlin.math.abs

sealed interface Sel {
    data object None : Sel
    data class Wall(val id: String) : Sel
    data class Vertex(val id: String) : Sel
    data class Opening(val id: String) : Sel
}

enum class WallAction { NONE, MOVE, SPLIT }

/** What a pointer went down on. Intent is decided from the target plus the selected action — never guessed. */
sealed interface Target {
    data class Vertex(val id: String) : Target
    data class WallMove(val id: String) : Target
    data class WallTap(val id: String) : Target
    data class OpeningHit(val id: String, val handle: OpeningHandle) : Target
    data class Label(val key: String, val at: Offset) : Target
    data object Background : Target
}

data class Guide(val a: Vec2, val b: Vec2)

/** Wall length labels sit outside the wall, clear of the opening touch band. */
const val WALL_LABEL_OFFSET_DP = 32f

data class LabelSpot(val key: String, val text: String, val at: Offset, val editable: Boolean)

class CanvasCallbacks(
    val onTap: (Target, Vec2) -> Unit,
    val onDragStart: (Target, Vec2) -> Boolean,
    val onDrag: (Vec2, Offset) -> Unit,
    val onDragEnd: () -> Unit,
    val onCancel: () -> Unit,
    val onTransform: (Offset, Offset, Float, Float) -> Unit,
)

fun labelSpots(m: RoomModel, t: ViewTransform, sel: Sel, labelOffsetPx: Float): List<LabelSpot> {
    val out = ArrayList<LabelSpot>()
    for (w in m.walls) {
        val mid = w.pointAt(w.length / 2) + w.outward * t.pixelsToMeters(labelOffsetPx)
        out += LabelSpot("wall:${w.id}:length", display(w.length, m.entries["wall:${w.id}:length"], m.units), t.toScreen(mid), true)
    }
    if (sel is Sel.Opening) {
        val o = m.openings.firstOrNull { it.id == sel.id }
        val w = o?.let { m.wall(it.wallId) }
        if (o != null && w != null) {
            val inward = -w.outward * t.pixelsToMeters(labelOffsetPx * 0.9f)
            val right = w.length - o.offset - o.width
            out += LabelSpot("opening:${o.id}:left", display(o.offset, m.entries["opening:${o.id}:left"], m.units), t.toScreen(w.pointAt(o.offset / 2) + inward), true)
            out += LabelSpot("opening:${o.id}:width", display(o.width, m.entries["opening:${o.id}:width"], m.units), t.toScreen(w.pointAt(o.offset + o.width / 2) + inward * 1.9), true)
            out += LabelSpot("opening:${o.id}:right", display(right, m.entries["opening:${o.id}:right"], m.units), t.toScreen(w.pointAt(o.offset + o.width + right / 2) + inward), true)
        }
    }
    return out
}

@Composable
fun PlanCanvas(
    model: RoomModel,
    transform: ViewTransform,
    sel: Sel,
    wallAction: WallAction,
    guides: List<Guide>,
    boundaryHit: Boolean,
    backdrop: (DrawScope.(ViewTransform) -> Unit)?,
    callbacks: CanvasCallbacks,
    modifier: Modifier = Modifier,
    showSetup: Boolean = true,
) {
    val density = LocalDensity.current
    val handleR = with(density) { 22f * this.density }
    val wallTol = with(density) { 18f * this.density }
    val labelOff = with(density) { WALL_LABEL_OFFSET_DP * this.density }
    val slop = LocalViewConfiguration.current.touchSlop
    val tm = rememberTextMeasurer()
    val m by rememberUpdatedState(model)
    val tr by rememberUpdatedState(transform)
    val s by rememberUpdatedState(sel)
    val act by rememberUpdatedState(wallAction)
    val cb by rememberUpdatedState(callbacks)

    fun hit(p: Offset): Target {
        val world = tr.toWorld(p)
        val labels = labelSpots(m, tr, s, labelOff)
        // selected opening handles first
        val so = (s as? Sel.Opening)?.let { sel -> m.openings.firstOrNull { it.id == sel.id } }
        if (so != null) {
            val w = m.wall(so.wallId)!!
            val a = tr.toScreen(w.pointAt(so.offset)); val b = tr.toScreen(w.pointAt(so.offset + so.width))
            if ((a - p).getDistance() < handleR) return Target.OpeningHit(so.id, OpeningHandle.START)
            if ((b - p).getDistance() < handleR) return Target.OpeningHit(so.id, OpeningHandle.END)
        }
        // openings (generous region around the aperture, including the selected door's highlighted swing area)
        for (o in m.openings) {
            val w = m.wall(o.wallId) ?: continue
            val along = (world - w.a) dot w.dir
            val signed = (world - w.a) dot w.outward
            val pad = tr.pixelsToMeters(handleR * 0.6f)
            val band = tr.pixelsToMeters(handleR * 0.75f)
            val inside = o.kind == OpeningKind.DOOR && s == Sel.Opening(o.id) && signed < 0 && -signed < o.width
            if (along > o.offset - pad && along < o.offset + o.width + pad && (abs(signed) < band || inside)) return Target.OpeningHit(o.id, OpeningHandle.CENTER)
        }
        for (l in labels) if ((l.at - p).getDistance() < handleR * 1.2f) return Target.Label(l.key, l.at)
        for (v in m.vertices) if ((tr.toScreen(v.pos) - p).getDistance() < handleR) return Target.Vertex(v.id)
        var best: Pair<String, Float>? = null
        for (w in m.walls) {
            val d = Polygons.distanceToSegment(world, w.a, w.b) * tr.scale
            if (d < wallTol && (best == null || d < best.second)) best = w.id to d.toFloat()
        }
        best?.let { (id, _) ->
            return if (s == Sel.Wall(id) && act == WallAction.MOVE) Target.WallMove(id) else Target.WallTap(id)
        }
        return Target.Background
    }

    Canvas(
        modifier.semantics {
            contentDescription = "Room plan with ${m.vertices.size} walls and ${m.openings.size} openings. Use the panel below for exact values and step controls."
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val target = hit(down.position)
                val startWorld = tr.toWorld(down.position)
                var dragging = false
                var objectDrag = false
                var multi = false
                var total = Offset.Zero
                var finished = false
                try {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Main)
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        finished = true
                        if (objectDrag) cb.onDragEnd()
                        else if (!dragging && !multi) cb.onTap(target, startWorld)
                        break
                    }
                    if (pressed.size > 1) {
                        // a pinch/rotate begins only outside an active object edit: cancel its uncommitted preview first
                        if (objectDrag) { cb.onCancel(); objectDrag = false }
                        multi = true
                        cb.onTransform(ev.calculateCentroid(), ev.calculatePan(), ev.calculateZoom(), ev.calculateRotation() * (Math.PI / 180).toFloat())
                        ev.changes.forEach { it.consume() }
                        continue
                    }
                    val ch = pressed.first()
                    val d = ch.positionChange()
                    total += d
                    if (!dragging && total.getDistance() > slop) {
                        dragging = true
                        if (!multi && target !is Target.Background && target !is Target.WallTap && target !is Target.Label) {
                            objectDrag = cb.onDragStart(target, startWorld)
                        }
                    }
                    if (dragging) {
                        if (objectDrag) cb.onDrag(tr.deltaToWorld(total), total)
                        else if (!multi) cb.onTransform(ch.position, d, 1f, 0f)
                        ch.consume()
                    }
                }
                } finally {
                    // pointer cancellation rolls back the uncommitted preview
                    if (!finished && objectDrag) cb.onCancel()
                }
            }
        },
    ) {
        backdrop?.invoke(this, tr)
        drawGrid(tr, m)
        drawRoom(m, tr, s, boundaryHit, handleR, tm, labelOff)
        for (g in guides) drawLine(Color(0xFF1F7A72), tr.toScreen(g.a), tr.toScreen(g.b), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        if (showSetup) drawSetup(m, tr, tm)
    }
}

private fun DrawScope.drawGrid(t: ViewTransform, m: RoomModel) {
    val step = if (m.units == com.owlacoustics.domain.units.DisplayUnits.IMPERIAL) 0.3048 else 0.5
    if (step * t.scale < 8) return
    val tl = t.toWorld(Offset.Zero); val br = t.toWorld(Offset(size.width, size.height))
    val tr2 = t.toWorld(Offset(size.width, 0f)); val bl = t.toWorld(Offset(0f, size.height))
    val xs = listOf(tl.x, br.x, tr2.x, bl.x); val ys = listOf(tl.y, br.y, tr2.y, bl.y)
    val c = Color(0x22888888)
    var x = kotlin.math.floor(xs.min() / step) * step
    var n = 0
    while (x <= xs.max() && n++ < 400) { drawLine(c, t.toScreen(Vec2(x, ys.min())), t.toScreen(Vec2(x, ys.max())), 1f); x += step }
    var y = kotlin.math.floor(ys.min() / step) * step
    n = 0
    while (y <= ys.max() && n++ < 400) { drawLine(c, t.toScreen(Vec2(xs.min(), y)), t.toScreen(Vec2(xs.max(), y)), 1f); y += step }
}

private val SelectAmber = Color(0xFFE0A100)
private val WallColor = Color(0xFF2B2D31)
private val WindowColor = Color(0xFF1F7A72)
private val DoorColor = Color(0xFF8A5A00)

private fun DrawScope.drawRoom(m: RoomModel, t: ViewTransform, sel: Sel, boundaryHit: Boolean, handleR: Float, tm: TextMeasurer, labelOff: Float) {
    val path = Path().apply {
        m.vertices.forEachIndexed { i, v -> val p = t.toScreen(v.pos); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }
    drawPath(path, Color(0x14F2C14E))
    for (w in m.walls) {
        val selected = sel == Sel.Wall(w.id)
        drawLine(if (selected) SelectAmber else WallColor, t.toScreen(w.a), t.toScreen(w.b), if (selected) 9f else 6f)
        if (selected) {
            // non-colour cue: tick marks at the selected wall's ends
            val n = w.outward * t.pixelsToMeters(14f)
            drawLine(SelectAmber, t.toScreen(w.a - n), t.toScreen(w.a + n), 4f)
            drawLine(SelectAmber, t.toScreen(w.b - n), t.toScreen(w.b + n), 4f)
        }
    }
    if (boundaryHit) drawPath(path, Color(0xFFB3261E), style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
    for (o in m.openings) {
        val w = m.wall(o.wallId) ?: continue
        val a = t.toScreen(w.pointAt(o.offset)); val b = t.toScreen(w.pointAt(o.offset + o.width))
        val selected = sel == Sel.Opening(o.id)
        drawLine(Color.White, a, b, 10f)
        if (o.kind == OpeningKind.WINDOW) {
            val n = w.outward * t.pixelsToMeters(4f)
            drawLine(WindowColor, t.toScreen(w.pointAt(o.offset) + n), t.toScreen(w.pointAt(o.offset + o.width) + n), 3f)
            drawLine(WindowColor, t.toScreen(w.pointAt(o.offset) - n), t.toScreen(w.pointAt(o.offset + o.width) - n), 3f)
        } else {
            drawDoor(o.handing, w.pointAt(o.offset), w.pointAt(o.offset + o.width), w.dir, w.outward, o.width, t, selected)
        }
        if (selected) {
            drawCircle(SelectAmber, handleR * 0.45f, a); drawCircle(SelectAmber, handleR * 0.45f, b)
            drawLine(SelectAmber, a, b, 4f)
        }
    }
    for (v in m.vertices) {
        val p = t.toScreen(v.pos)
        val selected = sel == Sel.Vertex(v.id)
        drawCircle(if (selected) SelectAmber else Color.White, handleR * 0.42f, p)
        drawCircle(WallColor, handleR * 0.42f, p, style = Stroke(if (v.locked) 6f else 3f))
    }
    val style = TextStyle(fontSize = 13.sp, color = WallColor, fontWeight = FontWeight.Medium, background = Color(0xDDFFFFFF))
    for (l in labelSpots(m, t, sel, labelOff)) {
        val layout = tm.measure(l.text, style)
        drawText(layout, topLeft = l.at - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }
}

private fun DrawScope.drawDoor(h: DoorHanding, a: Vec2, b: Vec2, dir: Vec2, outward: Vec2, width: Double, t: ViewTransform, selected: Boolean) {
    val c = (a + b) / 2.0
    val sc = t.toScreen(c)
    if (h != DoorHanding.UNKNOWN) {
        val hinge = if (h == DoorHanding.HINGE_START_IN || h == DoorHanding.HINGE_START_OUT) a else b
        val free = if (hinge == a) b else a
        val side = if (h == DoorHanding.HINGE_START_IN || h == DoorHanding.HINGE_END_IN) -outward else outward
        val leafEnd = hinge + side * width
        drawLine(DoorColor, t.toScreen(hinge), t.toScreen(leafEnd), 4f)
        val arc = Path()
        val steps = 16
        for (i in 0..steps) {
            val ang = (Math.PI / 2) * i / steps
            val from = free - hinge
            val toLeaf = leafEnd - hinge
            val p = hinge + from * kotlin.math.cos(ang) + toLeaf * kotlin.math.sin(ang)
            val sp = t.toScreen(p)
            if (i == 0) arc.moveTo(sp.x, sp.y) else arc.lineTo(sp.x, sp.y)
        }
        drawPath(arc, DoorColor, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
    }
    // centre circle with a rotate glyph; the whole highlighted door region is the tap target
    if (selected) {
        val region = Path().apply {
            val p1 = t.toScreen(a); val p2 = t.toScreen(b); val p3 = t.toScreen(b - outward * width); val p4 = t.toScreen(a - outward * width)
            moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close()
        }
        drawPath(region, Color(0x22E0A100))
    }
    drawCircle(Color.White, 16f, sc)
    drawCircle(DoorColor, 16f, sc, style = Stroke(3f))
    val arrow = Path().apply {
        for (i in 0..12) {
            val ang = Math.PI * 0.2 + Math.PI * 1.4 * i / 12
            val p = sc + Offset((kotlin.math.cos(ang) * 9).toFloat(), (kotlin.math.sin(ang) * 9).toFloat())
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
    }
    drawPath(arrow, DoorColor, style = Stroke(2.5f))
}

private fun DrawScope.drawSetup(m: RoomModel, t: ViewTransform, tm: TextMeasurer) {
    for (k in m.setup.keepOuts) {
        val a = t.toScreen(Vec2(k.center.x - k.sizeX / 2, k.center.y - k.sizeY / 2)); val b = t.toScreen(Vec2(k.center.x + k.sizeX / 2, k.center.y - k.sizeY / 2))
        val c = t.toScreen(Vec2(k.center.x + k.sizeX / 2, k.center.y + k.sizeY / 2)); val d = t.toScreen(Vec2(k.center.x - k.sizeX / 2, k.center.y + k.sizeY / 2))
        val p = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close() }
        drawPath(p, Color(0x33B3261E)); drawPath(p, Color(0xFFB3261E), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
    }
    val style = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
    for (i in m.setup.items) {
        val pos = i.position ?: continue
        val p = t.toScreen(pos)
        val color = when { i.kind.role == Role.SOURCE -> Color(0xFF1F7A72); i.kind == ItemKind.LISTENER -> Color(0xFF5B3FA0); else -> Color(0xFF8A5A00) }
        if (i.kind.role == Role.SOURCE) drawRect(color, p - Offset(13f, 13f), androidx.compose.ui.geometry.Size(26f, 26f))
        else drawCircle(color, 14f, p)
        if (i.locked) drawCircle(Color.Black, 18f, p, style = Stroke(3f))
        i.facingDeg?.let { f ->
            val tip = t.toScreen(pos + Vec2.fromBearingDeg(f) * t.pixelsToMeters(26f))
            drawLine(color, p, tip, 3f)
        }
        val layout = tm.measure(i.label.take(4), style)
        drawText(layout, topLeft = p - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }
}
