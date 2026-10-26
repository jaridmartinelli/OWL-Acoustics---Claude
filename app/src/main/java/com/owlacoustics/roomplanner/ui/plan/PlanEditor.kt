package com.owlacoustics.roomplanner.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.owlacoustics.domain.edit.EditResult
import com.owlacoustics.domain.edit.Edits
import com.owlacoustics.domain.edit.OpeningGesture
import com.owlacoustics.domain.edit.OpeningHandle
import com.owlacoustics.domain.edit.OpeningIntent
import com.owlacoustics.domain.edit.Openings
import com.owlacoustics.domain.edit.StartShape
import com.owlacoustics.domain.edit.Templates
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.bearingDeg
import com.owlacoustics.domain.model.Opening
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.model.Provenance
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.units.DisplayUnits
import com.owlacoustics.domain.units.LengthFormat
import com.owlacoustics.domain.units.LengthParser
import com.owlacoustics.domain.units.ParseResult
import com.owlacoustics.domain.workflow.Workflow
import com.owlacoustics.roomplanner.ui.RoomViewModel
import com.owlacoustics.roomplanner.ui.common.DegreesField
import com.owlacoustics.roomplanner.ui.common.LengthField
import com.owlacoustics.roomplanner.ui.common.MeasureField
import com.owlacoustics.roomplanner.ui.common.Note
import com.owlacoustics.roomplanner.ui.common.defaultUnit
import com.owlacoustics.roomplanner.ui.common.display
import com.owlacoustics.roomplanner.ui.common.fmt
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface DragOp {
    data class VertexDrag(val id: String, val start: Vec2, var final: Vec2? = null) : DragOp
    data class WallDrag(val id: String, var dist: Double = 0.0) : DragOp
    data class OpeningDrag(val id: String, val handle: OpeningHandle, val start: Opening, var intent: OpeningIntent? = null, var result: Pair<Double, Double>? = null) : DragOp
}

private data class InlineEdit(val key: String, val at: Offset, val text: String)

private fun replaceOpening(m: RoomModel, id: String, off: Double, w: Double) =
    m.copy(openings = m.openings.map { if (it.id == id) it.copy(offset = off, width = w) else it })

/** Canvas-first 2D builder (§5): select-first walls, direct corners, inline exact values, hybrid openings, snap. */
@Composable
fun PlanEditor(
    vm: RoomViewModel,
    model: RoomModel,
    backdrop: (androidx.compose.ui.graphics.drawscope.DrawScope.(ViewTransform) -> Unit)? = null,
    requestSel: Sel? = null,
    onRequestConsumed: () -> Unit = {},
) {
    var sel by remember { mutableStateOf<Sel>(Sel.None) }
    // a correction requested from the 3D review opens the matching object here
    LaunchedEffect(requestSel) { if (requestSel != null) { sel = requestSel; onRequestConsumed() } }
    var action by remember { mutableStateOf(WallAction.NONE) }
    var transform by remember { mutableStateOf<ViewTransform?>(null) }
    var preview by remember { mutableStateOf<RoomModel?>(null) }
    var drag by remember { mutableStateOf<DragOp?>(null) }
    var guides by remember { mutableStateOf<List<Guide>>(emptyList()) }
    var boundary by remember { mutableStateOf(false) }
    var snap by rememberSaveable { mutableStateOf(true) }
    var inline by remember { mutableStateOf<InlineEdit?>(null) }
    val density = LocalDensity.current
    val snapTolPx = with(density) { 12.dp.toPx() }

    LaunchedEffect(model) { if (drag == null) preview = null }
    // drop a selection whose object no longer exists (e.g. after undo)
    LaunchedEffect(model, sel) {
        val gone = when (val s = sel) {
            is Sel.Wall -> model.wall(s.id) == null
            is Sel.Vertex -> model.vertices.none { it.id == s.id }
            is Sel.Opening -> model.openings.none { it.id == s.id }
            Sel.None -> false
        }
        if (gone) { sel = Sel.None; action = WallAction.NONE }
    }

    val shown = preview ?: model

    fun snapVertex(id: String, p: Vec2, t: ViewTransform): Vec2 {
        if (!snap) { guides = emptyList(); return p }
        val tol = t.pixelsToMeters(snapTolPx)
        var x = p.x; var y = p.y
        val g = ArrayList<Guide>()
        val others = model.vertices.filter { it.id != id }
        others.minByOrNull { abs(it.x - p.x) }?.takeIf { abs(it.x - p.x) < tol }?.let { x = it.x; g += Guide(Vec2(it.x, it.y), Vec2(it.x, p.y)) }
        others.minByOrNull { abs(it.y - p.y) }?.takeIf { abs(it.y - p.y) < tol }?.let { y = it.y; g += Guide(Vec2(it.x, it.y), Vec2(p.x, it.y)) }
        guides = g
        return Vec2(x, y)
    }

    fun snapOpeningOffset(o: Opening, off: Double, t: ViewTransform): Double {
        if (!snap) return off
        val w = model.wall(o.wallId) ?: return off
        val tol = t.pixelsToMeters(snapTolPx)
        val centered = w.length / 2 - o.width / 2
        if (abs(off - centered) < tol) { guides = listOf(Guide(w.pointAt(w.length / 2) - w.outward * 0.3, w.pointAt(w.length / 2) + w.outward * 0.3)); return centered }
        guides = emptyList()
        return off
    }

    fun commitInline(e: InlineEdit, text: String) {
        val parsed = LengthParser.parse(text, defaultUnit(model.units))
        val v = when (parsed) {
            is ParseResult.Ok -> parsed.value
            is ParseResult.Invalid -> { vm.say(parsed.reason); return }
            is ParseResult.Unknown -> { vm.say("A length is required here."); return }
        }
        val parts = e.key.split(':')
        vm.edit { m ->
            when {
                parts[0] == "wall" -> Edits.setWallLength(m, parts[1], v)
                parts[2] == "left" -> Openings.setLeft(m, parts[1], v)
                parts[2] == "right" -> Openings.setRight(m, parts[1], v)
                parts[2] == "width" -> Openings.setWidth(m, parts[1], v)
                else -> EditResult.Rejected("Unknown field")
            }
        }
    }

    var canvasSize by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier.fillMaxSize()) {
        // view controls and live area live outside the canvas so they never cover geometry or finger targets
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(end = 8.dp)) {
                Text("Area " + LengthFormat.formatArea(Polygons.area(shown.outline), shown.units), style = MaterialTheme.typography.labelLarge)
                Text(shown.scale.kind.label, style = MaterialTheme.typography.labelSmall)
            }
            Text("Snap", style = MaterialTheme.typography.labelMedium)
            Switch(checked = snap, onCheckedChange = { snap = it }, modifier = Modifier.padding(horizontal = 4.dp).testTag("snap-toggle"))
            val center = canvasSize / 2f
            TextButton(onClick = { transform = transform?.transformed(center, Offset.Zero, 1.25f, 0f) }) { Text("Zoom +") }
            TextButton(onClick = { transform = transform?.transformed(center, Offset.Zero, 0.8f, 0f) }) { Text("Zoom −") }
            TextButton(onClick = { transform = ViewTransform.fit(model.outline, canvasSize.x, canvasSize.y, transform?.rotation ?: 0f) }) { Text("Fit") }
            TextButton(onClick = { transform = transform?.transformed(center, Offset.Zero, 1f, (Math.PI / 2).toFloat()) }) { Text("Rotate view") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            val wPx = with(density) { maxWidth.toPx() }; val hPx = with(density) { maxHeight.toPx() }
            LaunchedEffect(wPx, hPx) { canvasSize = Offset(wPx, hPx) }
            val t = transform ?: ViewTransform.fit(model.outline, wPx, hPx).also { transform = it }
            val callbacks = CanvasCallbacks(
                onTap = { target, world ->
                    inline = null
                    when (target) {
                        is Target.Label -> {
                            val spot = labelSpots(shown, t, sel, with(density) { WALL_LABEL_OFFSET_DP.dp.toPx() }).firstOrNull { it.key == target.key }
                            inline = InlineEdit(target.key, target.at, spot?.text ?: "")
                        }
                        is Target.OpeningHit -> {
                            val o = model.openings.firstOrNull { it.id == target.id }
                            if (o != null && sel == Sel.Opening(o.id) && o.kind == OpeningKind.DOOR && target.handle == OpeningHandle.CENTER) {
                                vm.edit { Openings.cycleHanding(it, o.id) }
                            } else sel = Sel.Opening(target.id)
                            action = WallAction.NONE
                        }
                        is Target.Vertex -> { sel = Sel.Vertex(target.id); action = WallAction.NONE }
                        is Target.WallTap, is Target.WallMove -> {
                            val id = if (target is Target.WallTap) target.id else (target as Target.WallMove).id
                            if (sel == Sel.Wall(id) && action == WallAction.SPLIT) {
                                val w = model.wall(id)!!
                                val off = ((world - w.a) dot w.dir).coerceIn(0.0, w.length)
                                vm.edit { Edits.splitWall(it, id, off) }
                                action = WallAction.NONE
                            } else if (sel != Sel.Wall(id)) { sel = Sel.Wall(id); action = WallAction.NONE }
                        }
                        Target.Background -> { sel = Sel.None; action = WallAction.NONE }
                    }
                },
                onDragStart = { target, _ ->
                    inline = null
                    when (target) {
                        is Target.Vertex -> {
                            val v = model.vertices.first { it.id == target.id }
                            if (v.locked) { vm.say("This corner is locked."); false }
                            else { sel = Sel.Vertex(v.id); drag = DragOp.VertexDrag(v.id, v.pos); true }
                        }
                        is Target.WallMove -> { drag = DragOp.WallDrag(target.id); true }
                        is Target.OpeningHit -> {
                            val o = model.openings.first { it.id == target.id }
                            sel = Sel.Opening(o.id); drag = DragOp.OpeningDrag(o.id, target.handle, o); true
                        }
                        else -> false
                    }
                },
                onDrag = { delta, _ ->
                    when (val d = drag) {
                        is DragOp.VertexDrag -> {
                            val p = snapVertex(d.id, d.start + delta, t)
                            when (val r = Edits.moveVertex(model, d.id, p)) {
                                is EditResult.Ok -> { preview = r.model; d.final = p; boundary = false }
                                is EditResult.Rejected -> boundary = true
                            }
                        }
                        is DragOp.WallDrag -> {
                            val w = model.wall(d.id)
                            if (w != null) when (val r = Edits.moveWall(model, d.id, delta dot w.outward)) {
                                is EditResult.Ok -> { preview = r.model; d.dist = delta dot w.outward; boundary = false }
                                is EditResult.Rejected -> boundary = true
                            }
                        }
                        is DragOp.OpeningDrag -> {
                            val w = model.wall(d.start.wallId)
                            if (w != null) {
                                val intent = d.intent ?: OpeningGesture.intent(d.handle, d.start.kind, delta, w.dir).also { d.intent = it }
                                val snapFn: ((Double) -> Double)? = if (intent == OpeningIntent.MOVE) { off -> snapOpeningOffset(d.start, off, t) } else null
                                val p = OpeningGesture.preview(model, d.start, intent, delta, w.dir, w.outward, snapFn)
                                d.result = p.offset to p.width
                                preview = replaceOpening(model, d.id, p.offset, p.width)
                                boundary = p.atBoundary
                            }
                        }
                        null -> {}
                    }
                },
                onDragEnd = {
                    val d = drag
                    drag = null; guides = emptyList(); boundary = false
                    val done = { preview = null }
                    when (d) {
                        is DragOp.VertexDrag -> d.final?.let { p -> vm.edit(done) { Edits.moveVertex(it, d.id, p) } } ?: done()
                        is DragOp.WallDrag -> if (abs(d.dist) > 1e-9) vm.edit(done) { Edits.moveWall(it, d.id, d.dist) } else done()
                        is DragOp.OpeningDrag -> d.result?.let { (off, w) ->
                            val label = when (d.intent) { OpeningIntent.MOVE -> "Move opening"; else -> "Resize opening" }
                            vm.edit(done) { Openings.commitGeometry(it, d.id, off, w, label) }
                        } ?: done()
                        null -> done()
                    }
                },
                onCancel = { drag = null; preview = null; guides = emptyList(); boundary = false },
                onTransform = { centroid, pan, zoom, rot ->
                    if (drag == null) transform = t.transformed(centroid, pan, zoom, rot)
                },
            )
            PlanCanvas(shown, t, sel, action, guides, boundary, backdrop, callbacks, Modifier.fillMaxSize().testTag("plan-canvas"))
            inline?.let { e ->
                InlineField(e, wPx, hPx, onDone = { txt -> commitInline(e, txt); inline = null }, onCancel = { inline = null })
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            when (val s = sel) {
                is Sel.Wall -> WallPanel(vm, model, s.id, action, { action = it }) { newSel -> sel = newSel }
                is Sel.Vertex -> VertexPanel(vm, model, s.id)
                is Sel.Opening -> OpeningPanel(vm, model, s.id) { sel = Sel.None }
                Sel.None -> RoomPanel(vm, model) { sel = it }
            }
        }
    }
}

@Composable
private fun InlineField(e: InlineEdit, wPx: Float, hPx: Float, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember(e) { mutableStateOf(e.text) }
    val fr = remember { FocusRequester() }
    val density = LocalDensity.current
    val fieldW = with(density) { 150.dp.toPx() }; val fieldH = with(density) { 64.dp.toPx() }
    // keep the field next to its label but above the finger and inside the canvas
    val x = (e.at.x - fieldW / 2).coerceIn(0f, (wPx - fieldW).coerceAtLeast(0f))
    val y = (e.at.y - fieldH - with(density) { 24.dp.toPx() }).coerceIn(0f, (hPx - fieldH).coerceAtLeast(0f))
    Surface(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(150.dp), tonalElevation = 6.dp, shape = MaterialTheme.shapes.small) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text) }),
                modifier = Modifier.weight(1f).focusRequester(fr).testTag("inline-measure"),
            )
            TextButton(onClick = onCancel) { Text("✕") }
        }
    }
    LaunchedEffect(e) { fr.requestFocus() }
}

@Composable
private fun WallPanel(vm: RoomViewModel, m: RoomModel, id: String, action: WallAction, setAction: (WallAction) -> Unit, select: (Sel) -> Unit) {
    val w = m.wall(id) ?: return
    val u = m.units
    val step = if (u == DisplayUnits.IMPERIAL) 0.0254 else 0.01
    Text("Wall ${w.index + 1} · ${display(w.length, m.entries["wall:$id:length"], u)}", style = MaterialTheme.typography.titleSmall)
    Note("Choose what to do with this wall. Dragging a wall only moves it after you pick Move.")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(action == WallAction.MOVE, { setAction(if (action == WallAction.MOVE) WallAction.NONE else WallAction.MOVE) }, { Text("Move wall") }, modifier = Modifier.testTag("action-move"))
        FilterChip(action == WallAction.SPLIT, { setAction(if (action == WallAction.SPLIT) WallAction.NONE else WallAction.SPLIT) }, { Text("Split / add corner") }, modifier = Modifier.testTag("action-split"))
        AssistChip({ vm.edit { Edits.addAlcove(it, id, w.length / 2, minOf(0.9144, w.length / 2), 0.6096) } }, { Text("Add alcove") })
        // the new opening becomes the selection so its live left/width/right values are shown straight away
        val before = m.openings.map { it.id }.toSet()
        val selectNew = { vm.state?.value?.model?.openings?.firstOrNull { it.id !in before }?.let { select(Sel.Opening(it.id)) }; Unit }
        AssistChip({ vm.edit(selectNew) { Openings.add(it, id, OpeningKind.WINDOW) } }, { Text("Add window") }, modifier = Modifier.testTag("add-window"))
        AssistChip({ vm.edit(selectNew) { Openings.add(it, id, OpeningKind.DOOR) } }, { Text("Add door") }, modifier = Modifier.testTag("add-door"))
    }
    if (action == WallAction.MOVE) Note("Drag the highlighted wall, or use the step buttons.")
    if (action == WallAction.SPLIT) Note("Tap the wall where the new corner should go, or split it in the middle:")
    if (action == WallAction.SPLIT) OutlinedButton({ vm.edit { Edits.splitWall(it, id, w.length / 2) }; setAction(WallAction.NONE) }) { Text("Split at middle") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Move", style = MaterialTheme.typography.labelLarge)
        OutlinedButton({ vm.edit { Edits.moveWall(it, id, -step) } }) { Text("In ${fmt(step, u)}") }
        OutlinedButton({ vm.edit { Edits.moveWall(it, id, step) } }) { Text("Out ${fmt(step, u)}") }
    }
    LengthField("Length (start corner stays fixed)", w.length, m.entries["wall:$id:length"], u, allowUnknown = false) { e ->
        e?.let { v -> vm.edit { Edits.setWallLength(it, id, v) } }
    }
    DegreesField("Direction (degrees)", bearingDeg(w.vector)) { d -> d?.let { deg -> vm.edit { Edits.setWallBearing(it, id, deg) } } }
    val n = m.vertices.size
    val prev = m.wallAt((w.index + n - 1) % n); val next = m.wallAt((w.index + 1) % n)
    Note("Connected walls: Wall ${prev.index + 1} ${fmt(prev.length, u)} · Wall ${next.index + 1} ${fmt(next.length, u)}")
}

@Composable
private fun VertexPanel(vm: RoomViewModel, m: RoomModel, id: String) {
    val v = m.vertices.firstOrNull { it.id == id } ?: return
    val u = m.units
    val step = if (u == DisplayUnits.IMPERIAL) 0.0254 else 0.01
    Text("Corner ${m.vertices.indexOf(v) + 1} · ${v.provenance.label}", style = MaterialTheme.typography.titleSmall)
    Note("Drag the corner on the plan, or nudge it here.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({ vm.edit { Edits.moveVertex(it, id, v.pos + Vec2(-step, 0.0)) } }) { Text("←") }
        OutlinedButton({ vm.edit { Edits.moveVertex(it, id, v.pos + Vec2(step, 0.0)) } }) { Text("→") }
        OutlinedButton({ vm.edit { Edits.moveVertex(it, id, v.pos + Vec2(0.0, step)) } }) { Text("↑") }
        OutlinedButton({ vm.edit { Edits.moveVertex(it, id, v.pos + Vec2(0.0, -step)) } }) { Text("↓") }
    }
    Note("Each step moves ${fmt(step, u)}.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Lock this corner", modifier = Modifier.weight(1f))
        Switch(v.locked, { lk -> vm.edit { Edits.setLocked(it, id, lk) } })
    }
    OutlinedButton({ vm.edit { Edits.removeVertex(it, id) } }) { Text("Remove corner") }
}

@Composable
private fun OpeningPanel(vm: RoomViewModel, m: RoomModel, id: String, onRemoved: () -> Unit) {
    val o = m.openings.firstOrNull { it.id == id } ?: return
    val w = m.wall(o.wallId) ?: return
    val u = m.units
    val name = if (o.kind == OpeningKind.WINDOW) "Window" else "Door"
    Text("$name on wall ${w.index + 1}" + if (o.provenance == Provenance.PLANNING) " · placeholder size" else "", style = MaterialTheme.typography.titleSmall)
    if (o.kind == OpeningKind.WINDOW) Note("Drag the centre along the wall to move; drag across the wall for fine symmetric width. Drag an end to resize it; across for ultra-fine.")
    else Note("Drag along the wall to move. Tap the highlighted door to change its swing drawing. Drag an end to resize.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LengthField("Left", o.offset, m.entries["opening:$id:left"], u, Modifier.weight(1f), allowUnknown = false) { e -> e?.let { v -> vm.edit { Openings.setLeft(it, id, v) } } }
        LengthField("Width", o.width, m.entries["opening:$id:width"], u, Modifier.weight(1f), allowUnknown = false) { e -> e?.let { v -> vm.edit { Openings.setWidth(it, id, v) } } }
        LengthField("Right", w.length - o.offset - o.width, m.entries["opening:$id:right"], u, Modifier.weight(1f), allowUnknown = false) { e -> e?.let { v -> vm.edit { Openings.setRight(it, id, v) } } }
    }
    Note("Left + width + right = wall length ${fmt(w.length, u)}. " + if (o.kind == OpeningKind.WINDOW) "Typed width keeps the centre." else "Typed width keeps the left jamb.")
    if (o.kind == OpeningKind.DOOR) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Swing drawing: ${o.handing.label}", modifier = Modifier.weight(1f))
            OutlinedButton({ vm.edit { Openings.cycleHanding(it, id) } }) { Text("Change swing") }
        }
        Note("This is a drawing choice, not detected evidence.")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MeasureField("Height", o.height, u, Modifier.weight(1f)) { h -> vm.edit { Openings.setVertical(it, id, h, it.openings.first { x -> x.id == id }.bottom) } }
        MeasureField(if (o.kind == OpeningKind.WINDOW) "Sill height" else "Bottom (0 if at floor)", o.bottom, u, Modifier.weight(1f)) { b ->
            vm.edit { Openings.setVertical(it, id, it.openings.first { x -> x.id == id }.height, b) }
        }
    }
    Note("Leave blank if unknown. Heights are needed only for the 3D opening and some reflection paths.")
    OutlinedButton({ vm.edit(onRemoved) { Openings.remove(it, id) } }) { Text("Remove $name") }
}

@Composable
private fun RoomPanel(vm: RoomViewModel, m: RoomModel, select: (Sel) -> Unit) {
    val stages = Workflow.stages(m)
    Text("Tap a wall to choose an action, drag a corner to reshape, tap a measurement to type it.", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (w in m.walls) AssistChip({ select(Sel.Wall(w.id)) }, { Text("Wall ${w.index + 1}: ${display(w.length, m.entries["wall:${w.id}:length"], m.units)}") })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Units", style = MaterialTheme.typography.labelLarge)
        FilterChip(m.units == DisplayUnits.IMPERIAL, { vm.edit { Edits.finish(it, it.copy(units = DisplayUnits.IMPERIAL), "Show feet and inches") } }, { Text("ft / in") })
        FilterChip(m.units == DisplayUnits.METRIC, { vm.edit { Edits.finish(it, it.copy(units = DisplayUnits.METRIC), "Show metres") } }, { Text("metric") })
    }
    Text("Starting shapes (working drawings, not measurements)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in StartShape.values()) AssistChip({
            vm.edit { Edits.replaceOutline(it, Templates.outline(s), Provenance.PLANNING, "Start from ${s.label}") }
        }, { Text(s.label) })
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    if (m.planConfirmed && stages.planReviewed) {
        Text("Floor plan confirmed (the drawing, not a survey).", style = MaterialTheme.typography.bodyMedium)
    } else {
        stages.planBlocking.forEach { Text("• " + it.message, color = MaterialTheme.colorScheme.error) }
        stages.planReview.forEach { Text("• " + it.message, style = MaterialTheme.typography.bodySmall) }
        Button({ vm.edit { Workflow.confirmPlan(it) } }, enabled = stages.planBlocking.isEmpty(), modifier = Modifier.testTag("confirm-plan")) {
            Text("Confirm floor plan")
        }
        Note("A ceiling height is not needed to confirm the plan.")
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (m.finalized) "Room finalized. You can still edit it, and undo/redo keeps its full history." else "Finalize when you are happy with this room. It stays editable.",
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ vm.edit { Workflow.setFinalized(it, !m.finalized) } }, modifier = Modifier.testTag("finalize")) {
            Text(if (m.finalized) "Mark as draft" else "Finalize room")
        }
    }
}
