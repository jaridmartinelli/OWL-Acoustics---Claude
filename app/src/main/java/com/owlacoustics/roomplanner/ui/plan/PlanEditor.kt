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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.owlacoustics.domain.edit.EditResult
import com.owlacoustics.domain.edit.Edits
import com.owlacoustics.domain.edit.OpeningGesture
import com.owlacoustics.domain.edit.OpeningHandle
import com.owlacoustics.domain.edit.OpeningIntent
import com.owlacoustics.domain.edit.Openings
import com.owlacoustics.domain.edit.Snap
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
    var keepSquare by rememberSaveable { mutableStateOf(true) }
    var stepIdx by rememberSaveable { mutableStateOf(1) }
    var hud by remember { mutableStateOf<String?>(null) }
    var lastEngaged by remember { mutableStateOf<Set<Snap.Kind>>(emptySet()) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val snapTolPx = with(density) { 14.dp.toPx() }
    val lengthMode = if (keepSquare) Edits.LengthMode.KEEP_ANGLES else Edits.LengthMode.FAR_CORNER

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

    /** Haptic tick only when a new snap engages, never continuously. */
    fun tick(engaged: Set<Snap.Kind>) {
        if ((engaged - lastEngaged).isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastEngaged = engaged
    }

    fun snapVertex(id: String, p: Vec2, t: ViewTransform): Vec2 {
        if (!snap) { guides = emptyList(); tick(emptySet()); return p }
        val r = Snap.vertex(model, id, p, t.pixelsToMeters(snapTolPx), Snap.dragStep(model.units))
        guides = r.guides.map { Guide(it.a, it.b, it.kind == Snap.Kind.ANGLE) }
        tick(r.engaged)
        return r.pos
    }

    fun snapOpeningOffset(o: Opening, off: Double, t: ViewTransform): Double {
        if (!snap) return off
        val w = model.wall(o.wallId) ?: return off
        val tol = t.pixelsToMeters(snapTolPx)
        val centered = w.length / 2 - o.width / 2
        if (abs(off - centered) < tol) {
            guides = listOf(Guide(w.pointAt(w.length / 2) - w.outward * 0.3, w.pointAt(w.length / 2) + w.outward * 0.3, true))
            tick(setOf(Snap.Kind.ALIGN_X)); return centered
        }
        guides = emptyList(); tick(emptySet())
        return Snap.distance(off, Snap.dragStep(model.units))
    }

    fun commitInline(e: InlineEdit, text: String, then: (() -> Unit)? = null): Boolean {
        val parsed = LengthParser.parse(text, defaultUnit(model.units))
        val v = when (parsed) {
            is ParseResult.Ok -> parsed.value
            is ParseResult.Invalid -> { vm.say(parsed.reason); return false }
            is ParseResult.Unknown -> { vm.say("A length is required here."); return false }
        }
        val parts = e.key.split(':')
        if (text.trim() == e.text.trim()) { then?.invoke(); return true }
        vm.edit(then) { m ->
            when {
                parts[0] == "wall" -> Edits.setWallLength(m, parts[1], v, lengthMode)
                parts[2] == "left" -> Openings.setLeft(m, parts[1], v)
                parts[2] == "right" -> Openings.setRight(m, parts[1], v)
                parts[2] == "width" -> Openings.setWidth(m, parts[1], v)
                else -> EditResult.Rejected("Unknown field")
            }
        }
        return true
    }

    var canvasSize by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier.fillMaxSize()) {
        // view controls and live area live outside the canvas so they never cover geometry or finger targets
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(end = 8.dp)) {
                Text("Area " + LengthFormat.formatArea(Polygons.area(shown.outline), shown.units) + " · Perimeter " + fmt(Polygons.perimeter(shown.outline), shown.units),
                    style = MaterialTheme.typography.labelLarge)
                Text(shown.scale.kind.label, style = MaterialTheme.typography.labelSmall)
            }
            FilterChip(snap, { snap = !snap }, { Text(if (snap) "Snap on" else "Snap off") }, modifier = Modifier.testTag("snap-toggle"))
            val center = canvasSize / 2f
            TextButton(onClick = { transform = transform?.transformed(center, Offset.Zero, 1.25f, 0f) }, modifier = Modifier.semantics { contentDescription = "Zoom in" }) { Text("+") }
            TextButton(onClick = { transform = transform?.transformed(center, Offset.Zero, 0.8f, 0f) }, modifier = Modifier.semantics { contentDescription = "Zoom out" }) { Text("−") }
            TextButton(onClick = { transform = ViewTransform.fit(model.outline, canvasSize.x, canvasSize.y, transform?.rotation ?: 0f) }) { Text("Fit") }
            TextButton(onClick = { transform = transform?.transformed(center, Offset.Zero, 1f, (Math.PI / 2).toFloat()) }) { Text("Rotate view") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            val wPx = with(density) { maxWidth.toPx() }; val hPx = with(density) { maxHeight.toPx() }
            LaunchedEffect(wPx, hPx) { canvasSize = Offset(wPx, hPx) }
            val t = transform ?: ViewTransform.fit(model.outline, wPx, hPx).also { transform = it }
            // after a typed change the room can outgrow the view: refit so every corner stays reachable
            LaunchedEffect(model.outline) {
                val cur = transform ?: return@LaunchedEffect
                val margin = with(density) { 24.dp.toPx() }
                val off = model.outline.any { p -> cur.toScreen(p).let { it.x < margin || it.y < margin || it.x > wPx - margin || it.y > hPx - margin } }
                if (drag == null && off) transform = ViewTransform.fit(model.outline, wPx, hPx, cur.rotation)
            }
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
                                is EditResult.Ok -> { preview = r.model; d.final = p; boundary = false; hud = cornerHud(r.model, d.id, lastEngaged) }
                                is EditResult.Rejected -> { boundary = true; hud = r.reason }
                            }
                        }
                        is DragOp.WallDrag -> {
                            val w = model.wall(d.id)
                            if (w != null) {
                                val raw = delta dot w.outward
                                val dist = if (snap) Snap.distance(raw, Snap.dragStep(model.units)) else raw
                                when (val r = Edits.moveWall(model, d.id, dist)) {
                                    is EditResult.Ok -> { preview = r.model; d.dist = dist; boundary = false; hud = wallHud(r.model, d.id, dist) }
                                    is EditResult.Rejected -> { boundary = true; hud = r.reason }
                                }
                            }
                        }
                        is DragOp.OpeningDrag -> {
                            val w = model.wall(d.start.wallId)
                            if (w != null) {
                                val intent = d.intent ?: OpeningGesture.intent(d.handle, d.start.kind, delta, w.dir).also { d.intent = it }
                                val snapFn: ((Double) -> Double)? = if (intent == OpeningIntent.MOVE) { off -> snapOpeningOffset(d.start, off, t) } else null
                                val p = OpeningGesture.preview(model, d.start, intent, delta, w.dir, w.outward, snapFn)
                                d.result = p.offset to p.width
                                val pm = replaceOpening(model, d.id, p.offset, p.width)
                                preview = pm
                                boundary = p.atBoundary
                                hud = "Left ${fmt(p.offset, model.units)} · Width ${fmt(p.width, model.units)} · Right ${fmt(w.length - p.offset - p.width, model.units)}" +
                                    if (p.atBoundary) " · at the limit" else ""
                            }
                        }
                        null -> {}
                    }
                },
                onDragEnd = {
                    val d = drag
                    drag = null; guides = emptyList(); boundary = false; hud = null; lastEngaged = emptySet()
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
                onCancel = { drag = null; preview = null; guides = emptyList(); boundary = false; hud = null; lastEngaged = emptySet() },
                onTransform = { centroid, pan, zoom, rot ->
                    if (drag == null) transform = t.transformed(centroid, pan, zoom, rot)
                },
            )
            PlanCanvas(shown, t, sel, action, guides, boundary, backdrop, callbacks, Modifier.fillMaxSize().testTag("plan-canvas"))
            // live readout at the top of the canvas, never under the finger
            hud?.let { h ->
                Surface(Modifier.align(Alignment.TopCenter).padding(8.dp).testTag("drag-hud"), tonalElevation = 6.dp, shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inverseSurface) {
                    Text(h, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.titleSmall)
                }
            }
            inline?.let { e ->
                val isWall = e.key.startsWith("wall:")
                InlineField(e, wPx, hPx, model.units, hasNext = isWall,
                    onDone = { txt -> if (commitInline(e, txt)) inline = null },
                    onNext = { txt ->
                        // walk around the room: commit this wall, then open the next one
                        val wallId = e.key.split(':')[1]
                        commitInline(e, txt) {
                            val cur = vm.state?.value?.model ?: return@commitInline
                            val w = cur.wall(wallId) ?: return@commitInline
                            val nx = cur.wallAt((w.index + 1) % cur.vertices.size)
                            val spot = labelSpots(cur, t, Sel.None, with(density) { WALL_LABEL_OFFSET_DP.dp.toPx() }).first { it.key == "wall:${nx.id}:length" }
                            sel = Sel.Wall(nx.id)
                            inline = InlineEdit(spot.key, spot.at, spot.text)
                        }
                    },
                    onCancel = { inline = null })
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            when (val s = sel) {
                is Sel.Wall -> WallPanel(vm, model, s.id, action, { action = it }, { newSel -> sel = newSel }, keepSquare, { keepSquare = it }, stepIdx) { stepIdx = it }
                is Sel.Vertex -> VertexPanel(vm, model, s.id, stepIdx) { stepIdx = it }
                is Sel.Opening -> OpeningPanel(vm, model, s.id) { sel = Sel.None }
                Sel.None -> RoomPanel(vm, model, lengthMode) { sel = it }
            }
        }
    }
}

@Composable
private fun InlineField(e: InlineEdit, wPx: Float, hPx: Float, units: DisplayUnits, hasNext: Boolean, onDone: (String) -> Unit, onNext: (String) -> Unit, onCancel: () -> Unit) {
    // select-all on open so typing replaces the old value, like a calculator field
    var value by remember(e) { mutableStateOf(TextFieldValue(e.text, TextRange(0, e.text.length))) }
    val fr = remember { FocusRequester() }
    val density = LocalDensity.current
    val fieldW = with(density) { 220.dp.toPx() }; val fieldH = with(density) { 96.dp.toPx() }
    // keep the field next to its label but above the finger and inside the canvas
    val x = (e.at.x - fieldW / 2).coerceIn(0f, (wPx - fieldW).coerceAtLeast(0f))
    val y = (e.at.y - fieldH - with(density) { 24.dp.toPx() }).coerceIn(0f, (hPx - fieldH).coerceAtLeast(0f))
    Surface(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(220.dp), tonalElevation = 6.dp, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true,
                    placeholder = { Text(if (units == DisplayUnits.IMPERIAL) "11 6 1/4" else "3.51 m") },
                    keyboardOptions = KeyboardOptions(imeAction = if (hasNext) ImeAction.Next else ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone(value.text) }, onNext = { onNext(value.text) }),
                    modifier = Modifier.weight(1f).focusRequester(fr).testTag("inline-measure"),
                )
                TextButton(onClick = { onDone(value.text) }) { Text("OK") }
            }
            val hint = LengthFormat.interpretation(value.text, units)
            Text(hint ?: if (units == DisplayUnits.IMPERIAL) "Type feet and inches: 11 6 1/4 or 11' 6 1/4\"" else "Type metres or cm: 3.51 m, 351 cm",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp).testTag("inline-hint"))
            Row {
                if (hasNext) TextButton(onClick = { onNext(value.text) }) { Text("Next wall →") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
    LaunchedEffect(e) { fr.requestFocus() }
}

private val STEPS_IMPERIAL = listOf(0.0254 / 16 to "1/16\"", 0.0254 / 4 to "1/4\"", 0.0254 to "1\"", 0.3048 to "1'")
private val STEPS_METRIC = listOf(0.001 to "1 mm", 0.005 to "5 mm", 0.01 to "1 cm", 0.1 to "10 cm")

@Composable
private fun stepPicker(u: DisplayUnits, idx: Int, set: (Int) -> Unit): Double {
    val steps = if (u == DisplayUnits.IMPERIAL) STEPS_IMPERIAL else STEPS_METRIC
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Step", style = MaterialTheme.typography.labelLarge)
        steps.forEachIndexed { i, (_, label) -> FilterChip(i == idx, { set(i) }, { Text(label) }) }
    }
    return steps[idx.coerceIn(steps.indices)].first
}

private fun cornerHud(m: RoomModel, vertexId: String, engaged: Set<Snap.Kind>): String {
    val n = m.vertices.size
    val i = m.vertices.indexOfFirst { it.id == vertexId }
    if (i < 0) return ""
    val win = m.wallAt((i + n - 1) % n); val wout = m.wallAt(i)
    val ang = Edits.interiorAngle(m, i)
    val snapText = when {
        Snap.Kind.RIGHT_CORNER in engaged -> " · square ✓"
        Snap.Kind.ANGLE in engaged -> " · on angle"
        Snap.Kind.ALIGN_X in engaged || Snap.Kind.ALIGN_Y in engaged -> " · aligned"
        else -> ""
    }
    return "Wall ${win.index + 1} ${fmt(win.length, m.units)} · Wall ${wout.index + 1} ${fmt(wout.length, m.units)} · %.1f°".format(ang) + snapText
}

private fun wallHud(m: RoomModel, wallId: String, dist: Double): String {
    val w = m.wall(wallId) ?: return ""
    val n = m.vertices.size
    val prev = m.wallAt((w.index + n - 1) % n); val next = m.wallAt((w.index + 1) % n)
    val sign = if (dist >= 0) "out " else "in "
    return "Moved $sign${fmt(abs(dist), m.units)} · Wall ${prev.index + 1} ${fmt(prev.length, m.units)} · Wall ${next.index + 1} ${fmt(next.length, m.units)}"
}

@Composable
private fun WallPanel(
    vm: RoomViewModel, m: RoomModel, id: String, action: WallAction, setAction: (WallAction) -> Unit, select: (Sel) -> Unit,
    keepSquare: Boolean, setKeepSquare: (Boolean) -> Unit, stepIdx: Int, setStep: (Int) -> Unit,
) {
    val w = m.wall(id) ?: return
    val u = m.units
    val mode = if (keepSquare) Edits.LengthMode.KEEP_ANGLES else Edits.LengthMode.FAR_CORNER
    Text("Wall ${w.index + 1} · ${display(w.length, m.entries["wall:$id:length"], u)}", style = MaterialTheme.typography.titleSmall)
    LengthField("Length", w.length, m.entries["wall:$id:length"], u, allowUnknown = false, modifier = Modifier.testTag("wall-length-field")) { e ->
        e?.let { v -> vm.edit { Edits.setWallLength(it, id, v, mode) } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Keep corners square", style = MaterialTheme.typography.bodyMedium)
            Note(if (keepSquare) "The next wall slides with this one, so every angle stays the same." else "Only the far corner moves; the next wall may tilt.")
        }
        Switch(keepSquare, setKeepSquare, modifier = Modifier.testTag("keep-square"))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(action == WallAction.MOVE, { setAction(if (action == WallAction.MOVE) WallAction.NONE else WallAction.MOVE) }, { Text("Drag wall") }, modifier = Modifier.testTag("action-move"))
        FilterChip(action == WallAction.SPLIT, { setAction(if (action == WallAction.SPLIT) WallAction.NONE else WallAction.SPLIT) }, { Text("Add corner") }, modifier = Modifier.testTag("action-split"))
        AssistChip({ vm.edit { Edits.addAlcove(it, id, w.length / 2, minOf(0.9144, w.length / 2), 0.6096) } }, { Text("Add alcove") })
        // the new opening becomes the selection so its live left/width/right values are shown straight away
        val before = m.openings.map { it.id }.toSet()
        val selectNew = { vm.state?.value?.model?.openings?.firstOrNull { it.id !in before }?.let { select(Sel.Opening(it.id)) }; Unit }
        AssistChip({ vm.edit(selectNew) { Openings.add(it, id, OpeningKind.WINDOW) } }, { Text("Add window") }, modifier = Modifier.testTag("add-window"))
        AssistChip({ vm.edit(selectNew) { Openings.add(it, id, OpeningKind.DOOR) } }, { Text("Add door") }, modifier = Modifier.testTag("add-door"))
    }
    if (action == WallAction.MOVE) Note("Drag the highlighted wall in or out. Its neighbours stretch to follow.")
    if (action == WallAction.SPLIT) {
        Note("Tap the wall where the new corner should go, or:")
        OutlinedButton({ vm.edit { Edits.splitWall(it, id, w.length / 2) }; setAction(WallAction.NONE) }) { Text("Add corner at middle") }
    }
    val step = stepPicker(u, stepIdx, setStep)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ vm.edit { Edits.moveWall(it, id, -step) } }) { Text("Move in") }
        OutlinedButton({ vm.edit { Edits.moveWall(it, id, step) } }) { Text("Move out") }
    }
    DegreesField("Direction (degrees)", bearingDeg(w.vector)) { d -> d?.let { deg -> vm.edit { Edits.setWallBearing(it, id, deg) } } }
    val n = m.vertices.size
    val prev = m.wallAt((w.index + n - 1) % n); val next = m.wallAt((w.index + 1) % n)
    Note("Connected walls: Wall ${prev.index + 1} ${fmt(prev.length, u)} · Wall ${next.index + 1} ${fmt(next.length, u)}")
}

@Composable
private fun VertexPanel(vm: RoomViewModel, m: RoomModel, id: String, stepIdx: Int, setStep: (Int) -> Unit) {
    val i = m.vertices.indexOfFirst { it.id == id }
    if (i < 0) return
    val v = m.vertices[i]
    val u = m.units
    val ang = Edits.interiorAngle(m, i)
    Text("Corner ${i + 1} · %.1f° · ${v.provenance.label}".format(ang), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Button({ vm.edit { Edits.squareCorner(it, id) } }, enabled = abs(ang - 90.0) > 0.01 && abs(ang - 270.0) > 0.01, modifier = Modifier.testTag("square-corner")) { Text("Make 90°") }
        Note("Drag the corner on the plan, or nudge it:")
    }
    val step = stepPicker(u, stepIdx, setStep)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({ vm.edit { Edits.moveVertex(it, id, v.pos + Vec2(-step, 0.0)) } }, modifier = Modifier.semantics { contentDescription = "Nudge left" }) { Text("←") }
        OutlinedButton({ vm.edit { Edits.moveVertex(it, id, v.pos + Vec2(step, 0.0)) } }, modifier = Modifier.semantics { contentDescription = "Nudge right" }) { Text("→") }
        OutlinedButton({ vm.edit { Edits.moveVertex(it, id, v.pos + Vec2(0.0, step)) } }, modifier = Modifier.semantics { contentDescription = "Nudge up" }) { Text("↑") }
        OutlinedButton({ vm.edit { Edits.moveVertex(it, id, v.pos + Vec2(0.0, -step)) } }, modifier = Modifier.semantics { contentDescription = "Nudge down" }) { Text("↓") }
    }
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
private fun RoomPanel(vm: RoomViewModel, m: RoomModel, mode: Edits.LengthMode, select: (Sel) -> Unit) {
    val stages = Workflow.stages(m)
    val u = m.units
    if (Edits.isRectangle(m)) {
        // the fastest path for most rooms: type the two tape measurements, corners stay square
        Text("Room size", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val w0 = m.walls[0]; val w1 = m.walls[1]
            LengthField("Length", w0.length, m.entries["wall:${w0.id}:length"], u, Modifier.weight(1f).testTag("room-length"), allowUnknown = false) { e ->
                e?.let { v -> vm.edit { Edits.setWallLength(it, it.walls[0].id, v, Edits.LengthMode.KEEP_ANGLES) } } }
            Text("×")
            LengthField("Width", w1.length, m.entries["wall:${w1.id}:length"], u, Modifier.weight(1f).testTag("room-width"), allowUnknown = false) { e ->
                e?.let { v -> vm.edit { Edits.setWallLength(it, it.walls[1].id, v, Edits.LengthMode.KEEP_ANGLES) } } }
        }
        Note("Opposite walls follow automatically. Not a rectangle? Tap a corner or wall on the plan to reshape it.")
    } else {
        Text("Walls", style = MaterialTheme.typography.titleMedium)
        Note("Walk around the room and type each wall. On the plan, tap a length and use Next wall →.")
        for (w in m.walls) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ select(Sel.Wall(w.id)) }, Modifier.width(84.dp)) { Text("Wall ${w.index + 1}") }
                LengthField("Length", w.length, m.entries["wall:${w.id}:length"], u, Modifier.weight(1f), allowUnknown = false) { e ->
                    e?.let { v -> vm.edit { Edits.setWallLength(it, w.id, v, mode) } } }
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (w in m.walls) AssistChip({ select(Sel.Wall(w.id)) }, { Text("Wall ${w.index + 1}: ${display(w.length, m.entries["wall:${w.id}:length"], u)}") })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Units", style = MaterialTheme.typography.labelLarge)
        FilterChip(u == DisplayUnits.IMPERIAL, { vm.edit { Edits.finish(it, it.copy(units = DisplayUnits.IMPERIAL), "Show feet and inches") } }, { Text("ft / in") })
        FilterChip(u == DisplayUnits.METRIC, { vm.edit { Edits.finish(it, it.copy(units = DisplayUnits.METRIC), "Show metres") } }, { Text("metric") })
    }
    var showShapes by rememberSaveable { mutableStateOf(false) }
    TextButton({ showShapes = !showShapes }) { Text(if (showShapes) "Hide starting shapes" else "Start over from a shape…") }
    if (showShapes) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
