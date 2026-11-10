package com.owlacoustics.capturelab.trace

import android.app.Activity
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
import com.owlacoustics.domain.trace.Surface
import com.owlacoustics.domain.units.DisplayUnits
import com.owlacoustics.domain.units.LengthFormat
import java.util.UUID
import kotlin.math.roundToInt

private val Panel = Color(0xFF12181C)
private val PanelHi = Color(0xFF222B31)
private val OnPanel = Color(0xFFF2F5F6)
private val Muted = Color(0xFFA9B4BA)

private enum class Mode { PENDING, STROKE, DRAG, TWO }

/**
 * Camera trace: freeze a view, trace the floor and ceiling lines with a finger. Lines cling to edges, stay pinned to
 * the room, and the room is meshed in one-foot squares. Lab build: the switches under ⋮ are there to be compared.
 */
@Composable
fun TraceScreen(store: LabStore, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = remember { TraceController(TraceDraftStore(ctx.applicationContext)) }
    val feed = remember { SensorTraceFeed() }
    val started = remember { System.currentTimeMillis() }
    TraceContent(c, feed, onClose = onDone, onSave = {
        resultOf(c, started)?.let { store.addResult(it); onDone() }
    })
}

/** The saved lab result: the plan outline plus, in the note, everything the scale and shape rest on. */
fun resultOf(c: TraceController, startedMs: Long): LabResult? {
    val plan = c.solution.plan ?: return null
    if (!plan.closed) return null
    val s = c.solution; val set = c.settings
    val unsnapped = s.segments.count { !it.snapped }
    val note = listOf(
        when (s.basis) {
            ScaleBasis.TYPED_LENGTH -> "scale from ${c.doc.typed.size} typed size(s)"
            ScaleBasis.CEILING_TYPED -> "scale from typed ceiling ${ft(s.ceilingHeight)}"
            ScaleBasis.CEILING_DEFAULT -> "scale from ASSUMED ${ft(s.ceilingHeight)} ceiling"
            ScaleBasis.PROVISIONAL -> "scale NOT set (guessed phone height)"
        },
        "ceiling ${ft(s.ceilingHeight)}",
        "outline from ${plan.source.name.lowercase()} line",
        if (plan.squared) "corners squared" else "corners as traced",
        if (set.tracking == TrackingMode.TURN) "pivot ${ft(set.solver.pivotRadiusM)} assumed" else "ARCore position",
        if (unsnapped > 0) "$unsnapped freehand line(s)" else "all lines on edges",
        "freeze: ${set.freeze.label}",
    ).joinToString(" · ")
    return LabResult(UUID.randomUUID().toString(), Method.TRACE, System.currentTimeMillis(), System.currentTimeMillis() - startedMs,
        c.doc.floor.anchors.size + c.doc.ceiling.anchors.size, plan.corners.map { P(it.x, it.y) }, eyeHeightM = -s.floorZ, note = note)
}

@Composable
fun TraceContent(c: TraceController, feed: TraceFeed, onClose: () -> Unit, onSave: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    c.density = density.density
    var showSettings by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }

    DisposableEffect(feed) {
        c.buzzer = { b ->
            if (c.settings.haptics) view.performHapticFeedback(when (b) {
                Buzz.GRAB -> HapticFeedbackConstants.CLOCK_TICK
                Buzz.CORNER -> HapticFeedbackConstants.CONTEXT_CLICK
                Buzz.CLOSE -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
                Buzz.REJECT -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            })
        }
        feed.onFrame = { c.onLiveFrame(it) }
        feed.onProblem = { c.problem = it }
        feed.start(ctx, owner)
        view.keepScreenOn = true
        // the size being typed is placed above the keyboard by this screen itself, so the window must not move
        val window = (ctx as? Activity)?.window
        val oldMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose {
            feed.stop(); feed.onFrame = null; feed.onProblem = null
            c.buzzer = {}
            c.dispose()
            view.keepScreenOn = false
            if (window != null && oldMode != null) window.setSoftInputMode(oldMode)
        }
    }
    LaunchedEffect(c.message) { if (c.message != null) { kotlinx.coroutines.delay(3500); c.clearMessage() } }
    // Back peels one layer at a time before leaving the screen
    BackHandler {
        when {
            confirmRestart -> confirmRestart = false
            showSettings -> showSettings = false
            !c.back() -> onClose()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val screenH = with(density) { maxHeight.toPx() }
        Column(Modifier.fillMaxSize()) {
            TopBar(c, onClose = onClose, onSettings = { showSettings = true })
            // the picture is as large as fits; on a short phone it gives up width before the controls give up height
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val vw = minOf(maxWidth, maxHeight * 0.75f)
                val spare = maxHeight - vw / 0.75f
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // with room below the picture the Freeze button sits there; otherwise in the picture's corner
                    Viewport(c, screenH, Modifier.size(vw, vw / 0.75f), freezeInside = spare < 60.dp)
                    if (spare >= 60.dp) InfoRow(c, Modifier.height(spare))
                }
            }
            BottomBar(c, onRestart = { confirmRestart = true }, onSave = onSave)
        }
        if (showSettings) SettingsPanel(c) { showSettings = false }
        if (confirmRestart) ConfirmCard(
            title = "Start over?", body = "This clears the floor and ceiling lines and typed sizes. Undo brings them back.",
            confirm = "Start over", onConfirm = { c.restart(); confirmRestart = false }, onCancel = { confirmRestart = false })
    }
}

@Composable
private fun TopBar(c: TraceController, onClose: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundButton(Glyph.MORE, "Test switches", "trace-settings", onClick = onSettings)
            Text(c.coach(), color = OnPanel, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(PanelHi).padding(horizontal = 12.dp, vertical = 7.dp).testTag("trace-coach"))
            RoundButton(Glyph.CLOSE, "Close", "trace-close", onClick = onClose)
        }
        val warn = c.scaleWarning
        Row(Modifier.clip(RoundedCornerShape(14.dp)).background(if (warn) TraceColors.warn else PanelHi).clickable { c.editCeiling() }
            .heightIn(min = 30.dp).padding(horizontal = 10.dp, vertical = 5.dp).testTag("trace-scale").semantics(mergeDescendants = true) { contentDescription = "Tap to type the ceiling height" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.scaleTag(), color = if (warn) TraceColors.ink else OnPanel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            GlyphIcon(Glyph.EDIT, if (warn) TraceColors.ink else OnPanel, Modifier.size(14.dp))
        }
    }
}

@Composable
private fun Viewport(c: TraceController, screenH: Float, modifier: Modifier, freezeInside: Boolean) {
    val tm = rememberTextMeasurer()
    var topInRoot by remember { mutableStateOf(0f) }
    Box(modifier.onGloballyPositioned { topInRoot = it.positionInRoot().y }) {
        Canvas(Modifier.fillMaxSize().testTag("trace-canvas").semantics { contentDescription = c.describe() }
            .onSizeChanged { c.canvasW = it.width.toFloat(); c.canvasH = it.height.toFloat() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    c.touchDown(down.position)
                    val vm = c.viewMap()
                    val hit = if (vm != null) c.hit(down.position, vm) else Hit.Empty
                    var mode = Mode.PENDING
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            if (mode == Mode.STROKE || mode == Mode.DRAG) c.cancelStroke()
                            mode = Mode.TWO
                            c.twoFinger(ev.calculateZoom(), ev.calculatePan(), ev.calculateCentroid())
                            ev.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && mode != Mode.TWO) {
                            val ch = pressed[0]
                            if (mode == Mode.PENDING && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                mode = if (hit is Hit.Anchor && !c.dragContinuesLine(hit)) { c.beginDrag(hit); Mode.DRAG }
                                    else if (c.beginStroke(down.position)) Mode.STROKE else Mode.TWO
                            }
                            if (mode == Mode.STROKE) c.moveStroke(ch.position) else if (mode == Mode.DRAG) c.drag(ch.position)
                            ch.consume()
                        }
                        if (ev.changes.none { it.pressed }) break
                    }
                    when (mode) {
                        Mode.PENDING -> c.tap(hit, down.position, down.uptimeMillis)
                        Mode.STROKE -> if (!c.endStroke()) c.tap(hit, down.position, down.uptimeMillis)
                        Mode.DRAG -> c.endDrag()
                        Mode.TWO -> {}
                    }
                    c.touchUp()
                }
            }) { drawTrace(c, tm) }

        val frozen = c.frozen != null
        if (freezeInside) FreezeButton(c, compact = true, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
        if (frozen && c.zoom > 1.01f) Text("%.1f×".format(c.zoom), color = OnPanel, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xD912181C)).padding(horizontal = 8.dp, vertical = 3.dp))

        c.editing?.let { target -> SizeEditor(c, target, topInRoot, screenH) }
    }
}

@Composable
private fun FreezeButton(c: TraceController, compact: Boolean, modifier: Modifier = Modifier) {
    val frozen = c.frozen != null
    if (c.settings.freeze == FreezeMode.OFF && !frozen) return
    val fg = if (frozen) TraceColors.ink else OnPanel
    Row(modifier.clip(RoundedCornerShape(26.dp)).background(if (frozen) Color.White else if (compact) Color(0xD912181C) else PanelHi)
        .clickable { if (frozen) c.resume() else c.freeze() }.heightIn(min = 48.dp).padding(horizontal = if (compact) 16.dp else 18.dp, vertical = 12.dp)
        .testTag("trace-freeze").semantics(mergeDescendants = true) { contentDescription = if (frozen) "Resume camera" else "Freeze picture" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        GlyphIcon(if (frozen) Glyph.PLAY else Glyph.PAUSE, fg, Modifier.size(16.dp))
        if (!compact) Text(if (frozen) "Resume camera" else "Freeze", color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The size being typed sits on its line when that is above the keyboard, otherwise it is lifted clear of it. */
@Composable
private fun SizeEditor(c: TraceController, target: EditTarget, viewportTop: Float, screenH: Float) {
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    var value by remember(target) { mutableStateOf(c.editingText().let { TextFieldValue(it, TextRange(0, it.length)) }) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    val seg = target as? EditTarget.Segment
    val box = seg?.let { s -> c.labels.firstOrNull { it.seg.surface == s.surface && it.seg.a == s.a && it.seg.b == s.b } }
    val w = with(density) { 190.dp.toPx() }; val h = with(density) { 84.dp.toPx() }
    val safeBottom = screenH * 0.46f - viewportTop // the keyboard may take the lower half of the screen
    val cx = (box?.rect?.center?.x ?: (c.canvasW / 2)).coerceIn(w / 2 + 8, (c.canvasW - w / 2 - 8).coerceAtLeast(w / 2 + 8))
    val cy = (box?.rect?.center?.y ?: (h / 2 + 8)).coerceIn(h / 2 + 8, (safeBottom - h / 2).coerceAtLeast(h / 2 + 8))
    val color = if (seg != null) TraceColors.of(seg.surface) else TraceColors.ceiling
    Column(Modifier.offset { IntOffset((cx - w / 2).roundToInt(), (cy - h / 2).roundToInt()) }.width(190.dp)
        .clip(RoundedCornerShape(12.dp)).background(Color.White).border(3.dp, color, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (seg != null) "Length" else "Ceiling height", color = Color(0xFF51606A), fontSize = 11.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { c.cancelEdit() }.testTag("trace-edit-cancel").semantics { contentDescription = "Cancel" }, contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.CLOSE, Color(0xFF51606A), Modifier.size(14.dp))
            }
        }
        BasicTextField(value, { value = it; error = null }, singleLine = true,
            textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TraceColors.ink),
            cursorBrush = SolidColor(TraceColors.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { error = c.commitEdit(value.text) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("trace-edit"))
        val hint = error ?: LengthFormat.interpretation(value.text, DisplayUnits.IMPERIAL) ?: "e.g. 11 6 1/4 · empty clears"
        Text(hint, color = if (error != null) Color(0xFFC5221F) else Color(0xFF51606A), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    LaunchedEffect(target) { runCatching { focus.requestFocus() } }
}

@Composable
private fun InfoRow(c: TraceController, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val plan = c.solution.plan
    Row(modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.weight(1f)) {
            if (maxHeight < 56.dp) return@BoxWithConstraints
            val side = minOf(maxHeight, 132.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(Modifier.size(side).clip(RoundedCornerShape(10.dp)).background(PanelHi).testTag("trace-plan")) {
                    val pts = plan?.corners ?: return@Canvas
                    if (pts.size < 2) return@Canvas
                    val pad = 14 * density
                    val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }; val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
                    val s = minOf((size.width - 2 * pad) / (maxX - minX).coerceAtLeast(0.2), (size.height - 2 * pad) / (maxY - minY).coerceAtLeast(0.2)).toFloat()
                    val ox = (size.width - (maxX - minX).toFloat() * s) / 2; val oy = (size.height - (maxY - minY).toFloat() * s) / 2
                    fun map(x: Double, y: Double) = Offset(ox + ((x - minX) * s).toFloat(), size.height - oy - ((y - minY) * s).toFloat())
                    val path = Path().apply { pts.forEachIndexed { i, p -> val o = map(p.x, p.y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; if (plan.closed) close() }
                    if (plan.closed) drawPath(path, TraceColors.floor.copy(alpha = 0.16f))
                    drawPath(path, TraceColors.floor, style = Stroke(2.5f * density))
                    // where the phone is and which way it faces
                    c.frame?.let { f ->
                        val pose = c.poseOf(f); val hd = pose.heading()
                        val at = map(pose.t.x, pose.t.y)
                        drawCircle(Color.White, 3.2f * density, at)
                        drawLine(Color.White, at, Offset(at.x + (hd.x * 12 * density).toFloat(), at.y - (hd.y * 12 * density).toFloat()), 2f * density)
                    }
                    if (c.sizesVisible && side >= 96.dp) for (i in plan.lengths.indices) {
                        val a = pts[i]; val b = pts[(i + 1) % pts.size]
                        val l = tm.measure((if (c.solution.calibrated) "" else "~") + ft(plan.lengths[i]), TextStyle(fontSize = 9.sp, color = Color.White, background = Color(0xCC12181C)))
                        val m = map((a.x + b.x) / 2, (a.y + b.y) / 2)
                        drawText(l, topLeft = Offset((m.x - l.size.width / 2f).coerceIn(0f, size.width - l.size.width), (m.y - l.size.height / 2f).coerceIn(0f, size.height - l.size.height)))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val f = c.doc.floor; val ce = c.doc.ceiling
                    if (side >= 104.dp) {
                        Text("Floor: " + status(f.corners.size, f.closed), color = TraceColors.floor, fontSize = 12.sp, maxLines = 1)
                        Text("Ceiling: " + status(ce.corners.size, ce.closed), color = TraceColors.ceiling, fontSize = 12.sp, maxLines = 1)
                        val area = plan?.area
                        if (c.frame?.lensGuessed == true) Text("Lens data missing: sizes less reliable", color = TraceColors.warn, fontSize = 11.sp, maxLines = 1)
                        else if (area != null && c.sizesVisible) Text((if (c.solution.calibrated) "" else "~") + "%.0f sq ft".format(area / 0.09290304), color = OnPanel, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    FreezeButton(c, compact = false, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun status(corners: Int, closed: Boolean) = when {
    closed -> "closed · $corners corners"
    corners == 0 -> "not started"
    else -> "open · $corners point" + (if (corners == 1) "" else "s")
}

@Composable
private fun BottomBar(c: TraceController, onRestart: () -> Unit, onSave: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(Panel)) {
        // narrow phones get slightly smaller buttons so the row never wraps or scrolls
        val tight = maxWidth < 380.dp
        val round = if (tight) 46.dp else 52.dp
        val pad = if (tight) 10.dp else 14.dp
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(Glyph.UNDO, "Undo", "trace-undo", enabled = c.history.canUndo, size = round) { c.undo() }
            RoundButton(Glyph.REDO, "Redo", "trace-redo", enabled = c.history.canRedo, size = round) { c.redo() }
            Row(Modifier.clip(RoundedCornerShape(26.dp)).background(PanelHi).padding(3.dp)) {
                for (s in listOf(Surface.FLOOR, Surface.CEILING)) {
                    val on = c.surface == s
                    val name = if (s == Surface.FLOOR) "Floor" else "Ceiling"
                    Text(name, color = if (on) TraceColors.ink else OnPanel, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                        modifier = Modifier.clip(RoundedCornerShape(23.dp)).background(if (on) TraceColors.of(s) else Color.Transparent)
                            .clickable { c.pickSurface(s) }.heightIn(min = 46.dp).padding(horizontal = pad, vertical = 13.dp)
                            .testTag("trace-" + name.lowercase()).semantics { contentDescription = "$name boundary" + if (on) ", selected" else "" })
                }
            }
            RoundButton(Glyph.RESTART, "Start over", "trace-restart", enabled = !c.doc.isEmpty, size = round, onClick = onRestart)
            val can = c.canSave
            Text("Save", color = if (can) TraceColors.ink else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                modifier = Modifier.clip(RoundedCornerShape(26.dp)).background(if (can) Color.White else PanelHi).clickable(enabled = can, onClick = onSave)
                    .heightIn(min = round).padding(horizontal = pad, vertical = 14.dp).testTag("trace-save"))
        }
    }
}

@Composable
private fun RoundButton(glyph: Glyph, label: String, tag: String, enabled: Boolean = true, size: androidx.compose.ui.unit.Dp = 52.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(PanelHi).clickable(enabled = enabled, onClick = onClick).testTag(tag).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, if (enabled) OnPanel else Color(0xFF5B666D), Modifier.size(24.dp))
    }
}

private enum class Glyph { UNDO, REDO, RESTART, CLOSE, MORE, PLAY, PAUSE, EDIT }

/** Icons drawn as shapes, so they look the same on every phone (no missing-symbol boxes). */
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
            Glyph.PLAY -> drawPath(Path().apply { moveTo(w * 0.24f, w * 0.14f); lineTo(w * 0.86f, w * 0.5f); lineTo(w * 0.24f, w * 0.86f); close() }, color)
            Glyph.PAUSE -> { drawRect(color, p(0.20f, 0.14f), Size(w * 0.22f, w * 0.72f)); drawRect(color, p(0.58f, 0.14f), Size(w * 0.22f, w * 0.72f)) }
            Glyph.EDIT -> {
                drawLine(color, p(0.30f, 0.70f), p(0.76f, 0.24f), w * 0.20f, StrokeCap.Butt)
                drawPath(Path().apply { moveTo(w * 0.12f, w * 0.88f); lineTo(w * 0.20f, w * 0.62f); lineTo(w * 0.38f, w * 0.80f); close() }, color)
            }
        }
    }
}

@Composable
private fun ConfirmCard(title: String, body: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 320.dp).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Panel).clickable(enabled = false) {}.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = OnPanel, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Muted, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", color = OnPanel, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onCancel).padding(horizontal = 16.dp, vertical = 12.dp).testTag("trace-confirm-cancel"))
                Spacer(Modifier.width(6.dp))
                Text(confirm, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFD93025)).clickable(onClick = onConfirm).padding(horizontal = 16.dp, vertical = 12.dp).testTag("trace-confirm-ok"))
            }
        }
    }
}

/** Lab switches. Everything here exists so the owner can compare options on a phone. */
@Composable
private fun SettingsPanel(c: TraceController, onClose: () -> Unit) {
    val s = c.settings
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Panel).clickable(enabled = false) {}
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("trace-settings-panel"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Test switches", color = OnPanel, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Done", color = TraceColors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(onClick = onClose).padding(horizontal = 14.dp, vertical = 8.dp).testTag("trace-settings-done"))
            }
            Choice("Tracking", TrackingMode.entries, s.tracking, { it.label }, enabled = { it == TrackingMode.TURN }) { c.updateSettings(s.copy(tracking = it)) }
            Text("Walk (ARCore) is not built yet.", color = Muted, fontSize = 11.sp)
            Choice("Picture freezes", FreezeMode.entries, s.freeze, { it.label }) { c.updateSettings(s.copy(freeze = it)) }
            Toggle("Mesh grows while drawing", "Off: surfaces appear when the boundary closes", s.fillWhileDrawing) { c.updateSettings(s.copy(fillWhileDrawing = it)) }
            Toggle("Use the ceiling outline when the floor is hidden", null, s.solver.outlineFromCeiling) { c.updateSettings(s.copy(solver = s.solver.copy(outlineFromCeiling = it))) }
            Toggle("Square up corners within 10°", null, s.solver.squareCorners) { c.updateSettings(s.copy(solver = s.solver.copy(squareCorners = it))) }
            Toggle("Lowest edge at floor, highest at ceiling", "For baseboards and mouldings", s.preferOuterEdge) { c.updateSettings(s.copy(preferOuterEdge = it)) }
            Toggle("Show ~estimates before a size is typed", null, s.showEstimates) { c.updateSettings(s.copy(showEstimates = it)) }
            Toggle("Haptics", null, s.haptics) { c.updateSettings(s.copy(haptics = it)) }
            val inches = s.solver.pivotRadiusM / 0.0254
            Text("Phone is %.0f in in front of where you turn".format(inches), color = OnPanel, fontSize = 13.sp)
            Slider(inches.toFloat(), { c.updateSettings(s.copy(solver = s.solver.copy(pivotRadiusM = it.roundToInt() * 0.0254))) }, valueRange = 0f..24f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = TraceColors.floor, inactiveTrackColor = PanelHi),
                modifier = Modifier.height(32.dp).testTag("trace-pivot"))
            Text("Turn while watching a traced line. If it slides off the edge, change this until it stays.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OnPanel, fontSize = 13.sp)
            if (sub != null) Text(sub, color = Muted, fontSize = 11.sp)
        }
        Switch(value, onChange, modifier = Modifier.height(30.dp), colors = SwitchDefaults.colors(
            checkedTrackColor = TraceColors.floor, checkedThumbColor = Color.White, uncheckedTrackColor = PanelHi, uncheckedThumbColor = Muted, uncheckedBorderColor = Muted))
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, value: T, label: (T) -> String, enabled: (T) -> Boolean = { true }, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = OnPanel, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (o in options) {
                val on = o == value; val ok = enabled(o)
                Text(label(o), color = if (on) TraceColors.ink else if (ok) OnPanel else Color(0xFF5B666D), fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Color.White else PanelHi).clickable(enabled = ok) { onPick(o) }
                        .padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
    }
}
