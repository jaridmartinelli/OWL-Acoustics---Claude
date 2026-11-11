package com.owlacoustics.capturelab.trace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.owlacoustics.capturelab.LabResult
import com.owlacoustics.capturelab.LabStore
import com.owlacoustics.capturelab.Method
import com.owlacoustics.capturelab.P
import com.owlacoustics.capturelab.ft
import com.owlacoustics.domain.trace.ScaleBasis
import com.owlacoustics.domain.units.DisplayUnits
import com.owlacoustics.domain.units.LengthFormat
import java.util.UUID
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Panel = Color(0xFF14181C)
private val PanelHi = Color(0xFF232A30)
private val OnPanel = Color(0xFFF2F5F6)
private val Muted = Color(0xFFA9B4BA)
private val Lime = Color(0xFFC6F432)

private enum class Mode { PENDING, BRUSH, TWO, DONE }

private const val HOLD_MS = 450L

/**
 * Live camera scan: the app finds the floor, walls and ceiling as the phone moves and meshes them in one-foot
 * squares; a finger brush adds or takes away where it got the boundary wrong. Lab build, unverified on a phone.
 */
@Composable
fun TraceScreen(store: LabStore, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = remember { TraceController(TraceDraftStore(ctx.applicationContext)) }
    val feed = remember { SensorTraceFeed(c.settings.widestLens) }
    val started = remember { System.currentTimeMillis() }
    TraceContent(c, feed, onClose = onDone, onSave = { resultOf(c, started)?.let { store.addResult(it); onDone() } })
}

/** The saved lab result: the plan outline plus, in the note, everything the scale and shape rest on. */
fun resultOf(c: TraceController, startedMs: Long): LabResult? {
    val plan = c.solution.plan ?: return null
    if (!plan.closed) return null
    val s = c.solution
    val inferred = s.segments.count { !it.snapped }
    val note = listOf(
        when (s.basis) {
            ScaleBasis.TYPED_LENGTH -> "scale from ${c.scan.typed.size} typed size(s)"
            ScaleBasis.CEILING_TYPED -> "scale from typed ceiling ${ft(s.ceilingHeight)}"
            ScaleBasis.CEILING_DEFAULT -> "scale from ASSUMED ${ft(s.ceilingHeight)} ceiling"
            ScaleBasis.PROVISIONAL -> "scale NOT set (guessed phone height)"
        },
        "ceiling ${ft(s.ceilingHeight)}",
        "outline from ${plan.source.name.lowercase()}",
        if (plan.squared) "corners squared" else "corners as found",
        "pivot ${ft(c.settings.solver.pivotRadiusM)} assumed",
        if (inferred > 0) "$inferred wall(s) filled in from the other surface" else "all walls seen directly",
    ).joinToString(" · ")
    return LabResult(UUID.randomUUID().toString(), Method.TRACE, System.currentTimeMillis(), System.currentTimeMillis() - startedMs,
        0, plan.corners.map { P(it.x, it.y) }, eyeHeightM = -s.floorZ, note = note)
}

@Composable
fun TraceContent(c: TraceController, feed: TraceFeed, onClose: () -> Unit, onSave: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    c.density = LocalDensity.current.density
    var showSettings by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var show3d by remember { mutableStateOf(false) }

    DisposableEffect(feed) {
        val det = AutoDetector(ctx.applicationContext)
        c.detector = det
        feed.onFrame = { c.onLiveFrame(it) }
        feed.onProblem = { c.problem = it }
        feed.start(ctx, owner)
        view.keepScreenOn = true
        onDispose {
            feed.stop(); feed.onFrame = null; feed.onProblem = null
            c.detector = null
            c.dispose(det)
            view.keepScreenOn = false
        }
    }
    BackHandler {
        when {
            show3d -> show3d = false
            confirmRestart -> confirmRestart = false
            showSettings -> showSettings = false
            !c.back() -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // header
            Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("OWL ACOUSTICS", color = OnPanel, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(18.dp).background(Muted))
                Text("Capture room", color = OnPanel, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconBox(Glyph.MORE, "Test switches", "trace-settings", 40.dp) { showSettings = true }
                Spacer(Modifier.width(6.dp))
                Pill("Restart", "trace-restart", enabled = !c.scan.isEmpty, glyph = Glyph.RESTART) { confirmRestart = true }
                Spacer(Modifier.width(6.dp))
                IconBox(Glyph.CLOSE, "Close", "trace-close", 40.dp, onClick = onClose)
            }
            // hint and scale
            Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                Text(c.hint(), color = OnPanel, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("trace-coach"))
                Text(c.scaleTag(), color = if (c.scaleWarning) TraceColors.warn else Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("trace-scale"))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val vw = minOf(maxWidth, maxHeight * 0.75f)
                Viewport(c, Modifier.size(vw, vw / 0.75f))
            }
            if (c.editing != null) Keypad(c)
            else {
                Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolButton("Floor", TraceColors.floor, c.tool == Tool.FLOOR, "trace-floor", Modifier.weight(1f)) { c.pickTool(Tool.FLOOR) }
                    ToolButton("Wall", TraceColors.wall, c.tool == Tool.WALL, "trace-wall", Modifier.weight(1f)) { c.pickTool(Tool.WALL) }
                    ToolButton("Ceiling", TraceColors.ceiling, c.tool == Tool.CEILING, "trace-ceiling", Modifier.weight(1f)) { c.pickTool(Tool.CEILING) }
                    ToolButton("3D", OnPanel, false, "trace-3d", Modifier.weight(1f), enabled = c.solution.plan != null) { show3d = true }
                }
                Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Pill("Undo", "trace-undo", enabled = c.history.canUndo, glyph = Glyph.UNDO, modifier = Modifier.weight(1f)) { c.undo() }
                    Pill("Redo", "trace-redo", enabled = c.history.canRedo, glyph = Glyph.REDO, modifier = Modifier.weight(1f)) { c.redo() }
                    Text("Save", color = if (c.canSave) TraceColors.ink else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.weight(1.3f).clip(RoundedCornerShape(12.dp)).background(if (c.canSave) Lime else PanelHi)
                            .clickable(enabled = c.canSave, onClick = onSave).padding(vertical = 13.dp).testTag("trace-save"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        if (show3d) Room3d(c) { show3d = false }
        if (showSettings) SettingsPanel(c) { showSettings = false }
        if (confirmRestart) ConfirmCard("Restart this room?", "Clear the capture? Undo can bring it back.", "Restart",
            onConfirm = { c.restart(); confirmRestart = false }, onCancel = { confirmRestart = false })
    }
}

@Composable
private fun Viewport(c: TraceController, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().testTag("trace-canvas").semantics { contentDescription = c.describe() }
            .onSizeChanged { c.canvasW = it.width.toFloat(); c.canvasH = it.height.toFloat() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val hit = c.viewMap()?.let { c.hit(down.position, it) } ?: Hit.Empty
                    var mode = Mode.PENDING
                    var now = down.uptimeMillis
                    while (true) {
                        // a hold is measured from the first touch: small jitter events must not restart the clock
                        val ev = if (mode == Mode.PENDING) {
                            val left = HOLD_MS - (now - down.uptimeMillis)
                            if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent() }
                        } else awaitPointerEvent()
                        if (ev != null) now = ev.changes.firstOrNull()?.uptimeMillis ?: now
                        if (ev == null) {
                            // held still: a point opens its actions, anywhere else starts brushing
                            if (hit is Hit.Anchor) { c.hold(hit); mode = Mode.DONE } else if (c.tool != Tool.WALL) { c.brushDown(down.position); mode = Mode.BRUSH }
                            else mode = Mode.DONE
                            continue
                        }
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            if (mode == Mode.BRUSH) c.brushCancel()
                            mode = Mode.TWO
                            c.twoFinger(ev.calculatePan())
                            ev.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && (mode == Mode.PENDING || mode == Mode.BRUSH)) {
                            val ch = pressed[0]
                            if (mode == Mode.PENDING && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                // Wall mode has no brush: a drag there does nothing (and is not a tap)
                                if (c.tool == Tool.WALL) mode = Mode.DONE else { c.brushDown(down.position); mode = Mode.BRUSH }
                            }
                            if (mode == Mode.BRUSH) c.brushMove(ch.position)
                            ch.consume()
                        }
                        if (ev.changes.none { it.pressed }) break
                    }
                    when (mode) {
                        Mode.PENDING -> c.tap(hit)
                        Mode.BRUSH -> c.brushUp()
                        else -> {}
                    }
                }
            }) { drawTrace(c, tm) }

        // brush mode: Add or Subtract
        if (c.tool != Tool.WALL) Row(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xD914181C)).padding(3.dp)) {
            for (sub in listOf(false, true)) {
                val on = c.subtract == sub
                Text(if (sub) "−" else "+", color = if (on) TraceColors.ink else OnPanel, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(if (!on) Color.Transparent else if (sub) Color(0xFFFF5A4D) else TraceColors.of(c.surface))
                        .clickable { c.subtract = sub }.size(44.dp).padding(top = 6.dp).testTag(if (sub) "trace-subtract" else "trace-add")
                        .semantics { contentDescription = if (sub) "Brush takes away" else "Brush adds" },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        // actions for a held point
        c.menu?.let { h ->
            val at = c.viewMap()?.let { vm -> c.solution.points(h.surface).firstOrNull { it.id == h.id }?.let { vm.project(it.pos) } }
            val x = with(density) { ((at?.x ?: 0f) + 16.dp.toPx()).coerceIn(0f, (c.canvasW - 170.dp.toPx()).coerceAtLeast(0f)) }
            val y = with(density) { ((at?.y ?: 0f) - 60.dp.toPx()).coerceIn(0f, (c.canvasH - 130.dp.toPx()).coerceAtLeast(0f)) }
            Column(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(160.dp).clip(RoundedCornerShape(12.dp)).background(Panel)
                .border(1.dp, Muted, RoundedCornerShape(12.dp)).padding(vertical = 6.dp)) {
                Text("Point actions", color = OnPanel, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                Text("Remove", color = Color(0xFFFF8A80), fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { c.removeCorner(h) }
                    .padding(horizontal = 14.dp, vertical = 11.dp).testTag("trace-point-remove"))
                Text("Close", color = OnPanel, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { c.menu = null }.padding(horizontal = 14.dp, vertical = 11.dp))
            }
        }
    }
}

/** Number pad for typing a size; replaces the bottom buttons while a size is being edited. */
@Composable
private fun Keypad(c: TraceController) {
    var text by remember(c.editing) { mutableStateOf(c.editingText()) }
    var error by remember(c.editing) { mutableStateOf<String?>(null) }
    fun key(k: String) { text += k; error = null }
    Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.editingTitle(), color = OnPanel, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill("Cancel", "trace-edit-cancel") { c.cancelEdit() }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text.ifEmpty { " " } + "▏", color = TraceColors.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).testTag("trace-edit"))
            Text(error ?: LengthFormat.interpretation(text, DisplayUnits.IMPERIAL) ?: "feet · e.g. 4.5 or 11 6 1/4", color = if (error != null) Color(0xFFC5221F) else Color(0xFF51606A),
                fontSize = 12.sp, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "⌫"))) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in row) Key(k, Modifier.weight(1f)) { if (k == "⌫") { text = text.dropLast(1); error = null } else key(k) }
                }
            }
            Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Key("space", Modifier.weight(1f), small = true) { key(" ") }
                    Key("/", Modifier.weight(1f)) { key("/") }
                }
                Key("Clear", Modifier.fillMaxWidth(), small = true) { text = ""; error = null }
                Text("Done", color = TraceColors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Lime).clickable { error = c.commitEdit(text) }
                        .heightIn(min = 94.dp).padding(top = 34.dp).testTag("trace-edit-done"))
            }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, small: Boolean = false, onClick: () -> Unit) {
    Text(label, color = OnPanel, fontSize = if (small) 13.sp else 20.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(PanelHi).clickable(onClick = onClick).heightIn(min = 44.dp).padding(top = if (small) 13.dp else 8.dp))
}

@Composable
private fun ToolButton(label: String, color: Color, on: Boolean, tag: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(if (on) color else PanelHi).clickable(enabled = enabled, onClick = onClick)
        .heightIn(min = 46.dp).padding(horizontal = 8.dp).testTag(tag).semantics { contentDescription = label + if (on) ", selected" else "" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (!on) { Box(Modifier.size(12.dp).border(2.dp, if (enabled) color else Muted, RoundedCornerShape(3.dp))); Spacer(Modifier.width(6.dp)) }
        Text(label, color = if (on) TraceColors.ink else if (enabled) OnPanel else Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Pill(label: String, tag: String, enabled: Boolean = true, glyph: Glyph? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fg = if (enabled) OnPanel else Color(0xFF5B666D)
    Row(modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, fg, RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
        .heightIn(min = 40.dp).padding(horizontal = 12.dp).testTag(tag).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (glyph != null) { GlyphIcon(glyph, fg, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, color = fg, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun IconBox(glyph: Glyph, label: String, tag: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(PanelHi).clickable(onClick = onClick).testTag(tag).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, OnPanel, Modifier.size(size * 0.45f))
    }
}

private enum class Glyph { UNDO, REDO, RESTART, CLOSE, MORE }

/** Icons drawn as shapes, so they look the same on every phone. */
@Composable
private fun GlyphIcon(g: Glyph, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.minDimension
        val st = Stroke(w * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * w, y * w)
        when (g) {
            Glyph.UNDO, Glyph.REDO -> withTransform({ if (g == Glyph.REDO) scale(-1f, 1f, center) }) {
                drawPath(Path().apply {
                    moveTo(w * 0.22f, w * 0.38f); lineTo(w * 0.60f, w * 0.38f)
                    cubicTo(w * 0.90f, w * 0.38f, w * 0.90f, w * 0.78f, w * 0.60f, w * 0.78f); lineTo(w * 0.34f, w * 0.78f)
                }, color, style = st)
                drawPath(Path().apply { moveTo(w * 0.40f, w * 0.20f); lineTo(w * 0.22f, w * 0.38f); lineTo(w * 0.40f, w * 0.56f) }, color, style = st)
            }
            Glyph.RESTART -> {
                drawArc(color, -50f, 290f, false, p(0.20f, 0.20f), Size(w * 0.60f, w * 0.60f), style = st)
                drawPath(Path().apply { moveTo(w * 0.70f, w * 0.06f); lineTo(w * 0.70f, w * 0.28f); lineTo(w * 0.92f, w * 0.28f) }, color, style = st)
            }
            Glyph.CLOSE -> { drawLine(color, p(0.22f, 0.22f), p(0.78f, 0.78f), st.width, StrokeCap.Round); drawLine(color, p(0.78f, 0.22f), p(0.22f, 0.78f), st.width, StrokeCap.Round) }
            Glyph.MORE -> for (y in listOf(0.22f, 0.5f, 0.78f)) drawCircle(color, w * 0.085f, p(0.5f, y))
        }
    }
}

/** The room as a model you can turn and zoom. Opens only when asked for; nothing here places points. */
@Composable
private fun Room3d(c: TraceController, onBack: () -> Unit) {
    val tm = rememberTextMeasurer()
    var yaw by remember { mutableFloatStateOf(0.6f) }
    var pitch by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    Column(Modifier.fillMaxSize().background(Color(0xFFF4F1EA)).clickable(enabled = false) {}.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Room model", color = TraceColors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(if (c.solution.calibrated) "Sizes from your measurement" else "Sizes are estimates until you type one", color = Color(0xFF51606A), fontSize = 13.sp)
        Canvas(Modifier.weight(1f).fillMaxWidth().testTag("trace-3d-canvas").pointerInput(Unit) {
            detectTransformGestures { _, pan, z, _ ->
                yaw += pan.x * 0.008f; pitch = (pitch + pan.y * 0.006f).coerceIn(0.1f, 1.45f); zoom = (zoom * z).coerceIn(0.5f, 4f)
            }
        }) {
            val plan = c.solution.plan ?: return@Canvas
            val pts = plan.corners
            if (pts.size < 2) return@Canvas
            val cx = pts.sumOf { it.x } / pts.size; val cy = pts.sumOf { it.y } / pts.size
            val h = c.solution.ceilingHeight
            val reach = pts.maxOf { kotlin.math.hypot(it.x - cx, it.y - cy) }.coerceAtLeast(0.5)
            val s = (minOf(size.width, size.height) * 0.42f / (reach + h * 0.3)).toFloat() * zoom
            fun proj(x: Double, y: Double, z: Double): Pair<Offset, Double> {
                val dx = x - cx; val dy = y - cy; val dz = z - h / 2
                val rx = dx * cos(yaw) - dy * sin(yaw); val ry = dx * sin(yaw) + dy * cos(yaw)
                val sy = ry * sin(pitch) - dz * cos(pitch)
                return Offset(size.width / 2 + (rx * s).toFloat(), size.height / 2 + (sy * s).toFloat()) to (ry * cos(pitch) + dz * sin(pitch))
            }
            fun poly(v: List<Offset>, fill: Color, line: Color) {
                val path = Path().apply { v.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
                drawPath(path, fill); drawPath(path, line, style = Stroke(2f * density))
            }
            if (plan.closed) poly(pts.map { proj(it.x, it.y, 0.0).first }, TraceColors.floor, TraceColors.ink)
            val n = pts.size
            val walls = (0 until if (plan.closed) n else n - 1).map { i ->
                val a = pts[i]; val b = pts[(i + 1) % n]
                val quad = listOf(proj(a.x, a.y, 0.0), proj(b.x, b.y, 0.0), proj(b.x, b.y, h), proj(a.x, a.y, h))
                Triple(i, quad.map { it.first }, quad.sumOf { it.second } / 4)
            }.sortedByDescending { it.third }
            // far walls first; the nearest ones are see-through so the inside stays visible
            for ((k, w) in walls.withIndex()) poly(w.second, TraceColors.wall.copy(alpha = if (k < walls.size / 2) 0.95f else 0.25f), TraceColors.ink.copy(alpha = 0.6f))
            if (plan.closed) poly(pts.map { proj(it.x, it.y, h).first }, TraceColors.ceiling.copy(alpha = 0.35f), TraceColors.ink.copy(alpha = 0.5f))
            val pre = if (c.solution.calibrated) "" else "~"
            for (w in walls) {
                val l = tm.measure(pre + ft(plan.lengths[w.first]), TextStyle(fontSize = 12.sp, color = TraceColors.ink, fontWeight = FontWeight.Bold, background = Color(0xE6FFFFFF)))
                val m = (w.second[0] + w.second[1]) / 2f
                drawText(l, topLeft = Offset(m.x - l.size.width / 2f, m.y - l.size.height / 2f))
            }
            val hl = tm.measure(pre + ft(h) + " high", TextStyle(fontSize = 12.sp, color = TraceColors.ink, fontWeight = FontWeight.Bold, background = Color(0xE6FFFFFF)))
            val e = walls.first().second
            val hm = (e[0] + e[3]) / 2f
            drawText(hl, topLeft = Offset(hm.x - hl.size.width / 2f, hm.y - hl.size.height / 2f))
        }
        Text("Drag to rotate • Pinch to zoom", color = Color(0xFF51606A), fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text("←  Back to drawing", color = TraceColors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.5.dp, TraceColors.ink, RoundedCornerShape(12.dp)).clickable(onClick = onBack)
                .padding(vertical = 14.dp).testTag("trace-3d-back"))
    }
}

@Composable
private fun ConfirmCard(title: String, body: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 320.dp).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).clickable(enabled = false) {}.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = TraceColors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFF51606A), fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Cancel", color = TraceColors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .border(1.dp, TraceColors.ink, RoundedCornerShape(10.dp)).clickable(onClick = onCancel).padding(horizontal = 22.dp, vertical = 12.dp).testTag("trace-confirm-cancel"))
                Text(confirm, color = TraceColors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Lime)
                    .clickable(onClick = onConfirm).padding(horizontal = 22.dp, vertical = 12.dp).testTag("trace-confirm-ok"))
            }
        }
    }
}

/** Lab switches, for comparing options on a phone. */
@Composable
private fun SettingsPanel(c: TraceController, onClose: () -> Unit) {
    val s = c.settings
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Panel).clickable(enabled = false) {}
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("trace-settings-panel"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Test switches", color = OnPanel, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill("Done", "trace-settings-done", onClick = onClose)
            }
            Toggle("Find floor, walls and ceiling automatically", "Off: only the brush marks them", s.autoDetect) { c.updateSettings(s.copy(autoDetect = it)) }
            Toggle("Fill hidden floor edges from the ceiling", "And hidden ceiling edges from the floor", s.borrowAcross) { c.updateSettings(s.copy(borrowAcross = it)) }
            Toggle("Square up corners within 10°", null, s.solver.squareCorners) { c.updateSettings(s.copy(solver = s.solver.copy(squareCorners = it))) }
            Toggle("Show ~estimates before a size is typed", null, s.showEstimates) { c.updateSettings(s.copy(showEstimates = it)) }
            Toggle("Widest lens (0.6×)", "Takes effect the next time this screen opens", s.widestLens) { c.updateSettings(s.copy(widestLens = it)) }
            val inches = s.solver.pivotRadiusM / 0.0254
            Text("Phone held %.0f in from the point you turn around".format(inches), color = OnPanel, fontSize = 13.sp)
            Slider(inches.toFloat(), { c.updateSettings(s.copy(solver = s.solver.copy(pivotRadiusM = it.roundToInt() * 0.0254))) }, valueRange = 0f..24f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = TraceColors.floor, inactiveTrackColor = PanelHi),
                modifier = Modifier.height(32.dp).testTag("trace-pivot"))
            Text("If the boundary slides off the walls as you turn, change this until it stays.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OnPanel, fontSize = 13.sp)
            if (sub != null) Text(sub, color = Muted, fontSize = 11.sp)
        }
        Switch(value, onChange, modifier = Modifier.height(30.dp), colors = SwitchDefaults.colors(
            checkedTrackColor = TraceColors.floor, checkedThumbColor = Color.White, uncheckedTrackColor = PanelHi, uncheckedThumbColor = Muted, uncheckedBorderColor = Muted))
    }
}
