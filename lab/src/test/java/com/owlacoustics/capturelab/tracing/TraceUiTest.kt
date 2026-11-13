package com.owlacoustics.capturelab.tracing

import com.owlacoustics.capturelab.trace.FramePool
import com.owlacoustics.capturelab.trace.TraceFeed
import com.owlacoustics.capturelab.trace.TraceFrame
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.LifecycleOwner
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.geometry.Vec3
import com.owlacoustics.domain.trace.AnchorKind
import com.owlacoustics.domain.trace.CameraPose
import com.owlacoustics.domain.trace.Intrinsics
import com.owlacoustics.domain.trace.Mat3
import com.owlacoustics.domain.trace.Pinhole
import com.owlacoustics.domain.trace.ScaleBasis
import com.owlacoustics.domain.trace.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

private const val FT = 0.3048
private const val INCH = 0.0254

/** A painted room seen by someone turning on the spot: wood floor, white baseboard, pale walls, white ceiling. */
class DemoRoom(corners: List<Vec2>, at: Vec2, val ceiling: Double, camHeight: Double, val pivot: Double) {
    val poly = corners.map { it - at }
    val zf = -camHeight
    val zc = ceiling - camHeight
    val n get() = poly.size
    val lengths get() = poly.indices.map { poly[it].distanceTo(poly[(it + 1) % n]) }
    val perimeter get() = lengths.sum()

    fun cam(yaw: Double) = Vec3(pivot * cos(yaw), pivot * sin(yaw), 0.0)

    /** Point [s] metres along the boundary (counter-clockwise from the first corner) at height [z]. */
    fun along(sIn: Double, z: Double): Vec3 {
        var s = ((sIn % perimeter) + perimeter) % perimeter
        for (i in 0 until n) {
            val len = lengths[i]
            if (s <= len) { val p = poly[i] + (poly[(i + 1) % n] - poly[i]) * (s / len); return Vec3(p.x, p.y, z) }
            s -= len
        }
        return Vec3(poly[0].x, poly[0].y, z)
    }

    private fun rgb(r: Double, g: Double, b: Double) = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

    fun render(k: Intrinsics, rot: Mat3, cam: Vec3): IntArray {
        val out = IntArray(k.w * k.h)
        val pose = CameraPose(rot, cam)
        for (v in 0 until k.h) for (u in 0 until k.w) {
            val d = Pinhole.worldRay(k, pose, u.toDouble(), v.toDouble())
            var best = Double.MAX_VALUE; var color = 0
            if (d.z < -1e-9) {
                val t = (zf - cam.z) / d.z
                best = t
                val x = cam.x + t * d.x; val y = cam.y + t * d.y
                val plank = floor((y + 50) / 0.14)
                val tone = 0.94 + 0.10 * (((plank * 37 + floor((x + 50) / 1.1) * 11) % 7 + 7) % 7) / 7.0
                val seam = if ((y + 50) / 0.14 - plank < 0.035) 0.86 else 1.0
                color = rgb(168 * tone * seam, 125 * tone * seam, 86 * tone * seam)
            }
            if (d.z > 1e-9) { val t = (zc - cam.z) / d.z; if (t < best) { best = t; color = rgb(236.0, 236.0, 232.0) } }
            for (i in 0 until n) {
                val a = poly[i]; val e = poly[(i + 1) % n] - a
                val den = d.x * e.y - d.y * e.x
                if (abs(den) < 1e-12) continue
                val ox = a.x - cam.x; val oy = a.y - cam.y
                val t = (ox * e.y - oy * e.x) / den
                val s = (ox * d.y - oy * d.x) / den
                if (t <= 1e-6 || s < 0 || s > 1 || t >= best) continue
                val z = cam.z + t * d.z
                if (z < zf || z > zc) continue
                best = t
                color = if (z < zf + 0.09) rgb(244.0, 242.0, 236.0) else {
                    val shade = (if (i % 2 == 0) 1.0 else 0.94) * (0.93 + 0.07 * (z - zf) / (zc - zf))
                    rgb(214 * shade, 207 * shade, 196 * shade)
                }
            }
            out[v * k.w + u] = color
        }
        return out
    }
}

/** Stands in for the camera and the motion sensor. */
class FakeFeed(private val room: DemoRoom, val k: Intrinsics) : TraceFeed {
    override var onFrame: ((TraceFrame) -> Unit)? = null
    override var onProblem: ((String) -> Unit)? = null
    private val pool = FramePool(12)
    private var seq = 0L
    var pose: CameraPose? = null; private set
    override fun start(context: Context, owner: LifecycleOwner) {}
    override fun stop() {}

    fun show(yaw: Double, pitch: Double) {
        val rot = Mat3.lookAt(yaw, pitch)
        val cam = room.cam(yaw)
        pose = CameraPose(rot, cam)
        val half = k.scaled(0.5)
        val fb = pool.obtain(k.w, k.h, half.w, half.h)!!
        fb.bitmap.setPixels(room.render(k, rot, cam), 0, k.w, 0, 0, k.w, k.h)
        val small = room.render(half, rot, cam)
        for (i in small.indices) { val c = small[i]; fb.lum[i] = ((((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8).toByte() }
        onFrame?.invoke(TraceFrame(fb, 0, k, rot, null, ++seq, 0.0, true, false))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TraceUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val k = Intrinsics(960 / (2 * tan(Math.toRadians(33.0))), 360.0, 480.0, 720, 960)
    private val L = 180.125 * INCH
    private val W = 138.25 * INCH
    // the persona room, 8' 2" ceiling, phone at 4' 5", turning with the phone 0.30 m out
    private val room = DemoRoom(listOf(Vec2(0.0, 0.0), Vec2(L, 0.0), Vec2(L, W), Vec2(0.0, W)), Vec2(L * 0.45, W * 0.55), 98 * INCH, 1.35, 0.30)
    private val feed = FakeFeed(room, k)
    private val c = TraceController(null)
    private var saved = 0
    private var closed = 0

    private fun start() {
        rule.setContent { TraceContent(c, feed, onClose = { closed++ }, onSave = { saved++ }) }
        rule.waitForIdle()
    }

    /** Draws the screen as the phone would; this also runs the canvas drawing that records where labels are. */
    private fun draw(): Bitmap {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        return bmp
    }

    private fun shot(name: String) {
        val dir = File("build/trace-stills").apply { mkdirs() }
        val bmp = draw()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val canvas get() = rule.onNodeWithTag("trace-canvas")
    private fun scale() = canvas.fetchSemanticsNode().size.width.toFloat() / k.w
    private fun onCanvas(p: Vec2) = Offset((p.x * scale()).toFloat(), (p.y * scale()).toFloat())

    private fun look(yaw: Double, pitch: Double) { rule.runOnUiThread { feed.show(yaw, pitch) }; draw() }

    private fun lookAt(p: Vec3) {
        val yaw = atan2(p.y, p.x)
        val rel = p - room.cam(yaw)
        look(yaw, atan2(rel.z, rel.xy().length))
    }

    private fun resume() { if (c.frozen != null) { rule.onNodeWithTag("trace-freeze").performClick(); rule.waitForIdle() } }

    /** Drags a finger along picture points, wobbling a little like a real hand. */
    private fun stroke(px: List<Vec2>, wobble: Double = 4.0) {
        val pts = px.mapIndexed { i, p ->
            val t = if (i + 1 < px.size) px[i + 1] - p else p - px[i - 1]
            onCanvas(p + t.normalized().perp() * (wobble * sin(i * 0.18)))
        }
        canvas.performTouchInput { down(pts.first()); for (p in pts.drop(1)) moveTo(p); up() }
        rule.waitForIdle()
    }

    /** Goes round the room tracing one surface, the way a user would: look, trace what is visible, resume, turn. */
    private fun traceLoop(z: Double, surface: Surface, onView: (Int) -> Unit = {}) {
        val start = room.lengths[0] * 0.3
        var done = 0.0
        var view = 0
        while (!c.doc.boundary(surface).closed && view++ < 40) {
            resume()
            lookAt(room.along(start + done + 0.7, z))
            val pose = feed.pose!!
            val raw = ArrayList<Vec2>()
            var step = 0.0
            while (done + step <= room.perimeter + 1e-9) {
                val px = Pinhole.project(k, pose, room.along(start + done + step, z)) ?: break
                if (px.x < 40 || px.x > k.w - 40 || px.y < 40 || px.y > k.h - 40) break
                raw += px; step += 0.03
            }
            if (room.perimeter - done < 0.3) {
                // only a sliver left: touch the first point to finish the loop, as with a lasso
                val first = onCanvas(Pinhole.project(k, pose, room.along(start, z))!!)
                canvas.performTouchInput { click(first) }
                rule.waitForIdle()
            } else {
                assertTrue("view $view shows the boundary", raw.size > 8)
                stroke(raw)
                done += (raw.size - 1) * 0.03
            }
            draw()
            onView(view)
        }
        assertTrue("$surface loop closed", c.doc.boundary(surface).closed)
    }

    private fun worstWallError(): Double {
        val got = c.solution.plan!!.lengths.sorted(); val want = room.lengths.sorted()
        return got.zip(want).maxOf { abs(it.first - it.second) }
    }

    @Test fun traceTypeEditUndoAndSave() {
        start()
        rule.onNodeWithTag("trace-save").assertIsNotEnabled()
        rule.onNodeWithTag("trace-undo").assertIsNotEnabled()
        lookAt(room.along(room.lengths[0] * 0.3 + 0.7, room.zf))
        shot("01-aim-live")
        assertTrue(c.coach().startsWith("Aim at where the floor meets a wall"))

        // --- mid-stroke: the picture is frozen, the line clings to the floor edge, the loupe shows what the finger hides ---
        run {
            val pose = feed.pose!!
            val path = (0..40).mapNotNull { Pinhole.project(k, pose, room.along(room.lengths[0] * 0.3 + it * 0.03, room.zf)) }.map { onCanvas(it + Vec2(0.0, 7.0)) }
            canvas.performTouchInput { down(path.first()); for (p in path.drop(1)) moveTo(p) }
            rule.waitForIdle()
            assertNotNull("frozen as soon as the finger draws", c.frozen)
            assertNotNull(c.strokeFit)
            val (at, snapped) = c.loupeAt!!
            assertTrue("clinging to an edge", snapped)
            // the finger is 7 px below the floor line; the snapped point is on it
            val truth = Pinhole.project(k, pose, room.along(room.lengths[0] * 0.3 + 40 * 0.03, room.zf))!!
            assertTrue("snapped point is ${at.distanceTo(truth)} px from the floor line", at.distanceTo(truth) < 3.0)
            shot("02-tracing-with-loupe")
            canvas.performTouchInput { up() }
            rule.waitForIdle()
            assertEquals(2, c.doc.floor.corners.size)
            rule.onNodeWithTag("trace-undo").performClick()
            assertTrue(c.doc.isEmpty)
            resume()
        }

        // --- floor ---
        traceLoop(room.zf, Surface.FLOOR) { v -> if (v == 2) shot("03-floor-growing-frozen") }
        assertEquals("four real corners, mid-wall joins merged", 4, c.doc.floor.corners.size)
        assertEquals(ScaleBasis.PROVISIONAL, c.solution.basis)
        assertTrue(c.solution.segments.all { it.snapped })
        assertTrue(c.canSave)
        assertEquals("+ size", c.labelFor(c.solution.segments.first()))
        resume()
        assertTrue(c.coach().startsWith("Floor closed. Switch to Ceiling"))

        // --- one ceiling line sets the scale from the 8 ft default ---
        rule.onNodeWithTag("trace-ceiling").performClick()
        assertEquals(Surface.CEILING, c.surface)
        lookAt(room.along(room.lengths[0] * 0.5, room.zc))
        val pose = feed.pose!!
        val line = (0..60).mapNotNull { Pinhole.project(k, pose, room.along(room.lengths[0] * 0.5 - 0.75 + it * 0.025, room.zc)) }
            .filter { it.x > 40 && it.x < k.w - 40 }
        stroke(line)
        assertEquals(2, c.doc.ceiling.corners.size)
        assertEquals(ScaleBasis.CEILING_DEFAULT, c.solution.basis)
        assertFalse("an assumed ceiling does not show sizes", c.sizesVisible)
        assertTrue(c.scaleTag().startsWith("Assumes 8' 0\" ceiling"))
        // 8 ft assumed, real ceiling 8' 2": walls read about 2% short
        val assumedErr = worstWallError()
        assertTrue("assumed-ceiling error ${assumedErr / INCH} in", assumedErr > 1.5 * INCH && assumedErr < 6 * INCH)
        shot("04-ceiling-line-frozen")
        resume()

        // --- a view of the whole corner: mesh on floor, walls and ceiling ---
        rule.onNodeWithTag("trace-floor").performClick()
        look(atan2(room.poly[1].y, room.poly[1].x), Math.toRadians(-4.0))
        shot("05-mesh-assumed-ceiling")

        // --- type the long wall: everything calibrates ---
        rule.onNodeWithTag("trace-freeze").performClick(); rule.waitForIdle()
        draw()
        val label = c.labels.minByOrNull { abs(it.seg.measured - L) }
        assertNotNull("a size label is on screen", label); label!!
        canvas.performTouchInput { click(label.rect.center) }
        rule.waitForIdle()
        assertTrue(c.editing is EditTarget.Segment)
        rule.onNodeWithTag("trace-edit").assertIsDisplayed()
        rule.onNodeWithTag("trace-edit").performTextReplacement("15 0 1/8")
        rule.waitForIdle()
        shot("06-typing-on-the-line")
        rule.onNodeWithTag("trace-edit").performImeAction()
        rule.waitForIdle()
        assertNull(c.editing)
        assertTrue(c.solution.calibrated)
        assertEquals(ScaleBasis.TYPED_LENGTH, c.solution.basis)
        assertTrue(c.sizesVisible)
        val typedSeg = c.solution.segments.first { it.typed != null }
        assertEquals("15' 0 1/8\"", c.labelFor(typedSeg))
        val err = worstWallError()
        println("TRACE-UI worst wall error after one typed wall: %.2f in · ceiling works out to %s (true 8' 2\")".format(err / INCH, com.owlacoustics.capturelab.ft(c.solution.ceilingHeight)))
        assertTrue("worst wall error ${err / INCH} in", err < 2 * INCH)
        assertEquals(98 * INCH, c.solution.ceilingHeight, 1.5 * INCH)
        assertNull(c.solution.ceilingWarning)
        assertTrue(c.scaleTag().startsWith("Calibrated"))
        shot("07-calibrated-all-sizes")

        // an unreadable entry is refused in place
        draw()
        val other = c.labels.first { it.seg.typed == null }
        canvas.performTouchInput { click(other.rect.center) }
        rule.waitForIdle()
        rule.onNodeWithTag("trace-edit").performTextReplacement("abc")
        rule.onNodeWithTag("trace-edit").performImeAction()
        rule.waitForIdle()
        assertNotNull("still editing after a bad entry", c.editing)
        rule.onNodeWithTag("trace-edit-cancel").performClick()
        rule.waitForIdle()
        assertNull(c.editing)

        // --- marker: tap a line, Delete bubble removes it ---
        val seg = c.solution.segments.first { it.surface == Surface.FLOOR && it.typed == null }
        val vm = c.viewMap()!!
        val on = c.labels.first { it.seg.a == seg.a && it.seg.b == seg.b }.rect.center
        val (sa, sb) = vm.segment(seg.pa, seg.pb)!!
        val dir = (sb - sa) / (sb - sa).getDistance()
        val tapAt = on + dir * 150f
        canvas.performTouchInput { click(tapAt) }
        rule.waitForIdle()
        assertEquals(1, c.doc.floor.anchors.count { it.kind == AnchorKind.MARKER })
        assertEquals(4, c.doc.floor.corners.size)
        assertNotNull(c.selected)
        draw()
        shot("08-marker-with-delete")
        val del = c.deleteBox!!.center
        canvas.performTouchInput { click(del) }
        rule.waitForIdle()
        assertEquals(0, c.doc.floor.anchors.count { it.kind == AnchorKind.MARKER })

        // --- drag a corner, then undo it ---
        val corner = c.solution.floor.first { p -> c.viewMap()!!.project(p.pos)?.let { it.x > 60 && it.x < 740 && it.y > 60 && it.y < 1000 } == true }
        val from = c.viewMap()!!.project(corner.pos)!!
        val before = c.doc
        canvas.performTouchInput { down(from); moveTo(from + Offset(30f, 10f)); moveTo(from + Offset(60f, 24f)); up() }
        rule.waitForIdle()
        assertTrue("corner moved", c.solution.floor.first { it.id == corner.id }.pos.distanceTo(corner.pos) > 0.05)
        assertTrue(c.doc.floor.find(corner.id)!!.forced)
        rule.onNodeWithTag("trace-undo").performClick()
        assertEquals(before, c.doc)
        rule.onNodeWithTag("trace-redo").assertIsEnabled()

        // --- restart asks first, and undo still brings everything back ---
        rule.onNodeWithTag("trace-restart").performClick()
        rule.onNodeWithTag("trace-confirm-cancel").performClick()
        assertFalse(c.doc.isEmpty)
        rule.onNodeWithTag("trace-restart").performClick()
        rule.onNodeWithTag("trace-confirm-ok").performClick()
        rule.waitForIdle()
        assertTrue(c.doc.isEmpty)
        rule.onNodeWithTag("trace-save").assertIsNotEnabled()
        rule.onNodeWithTag("trace-undo").performClick()
        rule.waitForIdle()
        assertEquals(before, c.doc)

        // --- locked surface: with Ceiling selected, floor points cannot be grabbed ---
        rule.onNodeWithTag("trace-ceiling").performClick()
        rule.waitForIdle()
        assertTrue(c.hit(from, c.viewMap()!!) !is Hit.Anchor)
        rule.onNodeWithTag("trace-floor").performClick()

        // --- save ---
        rule.onNodeWithTag("trace-save").assertIsEnabled().performClick()
        assertEquals(1, saved)
        val result = resultOf(c, 0L)!!
        assertEquals(4, result.corners.size)
        assertTrue(result.note.contains("scale from 1 typed size(s)"))
        assertTrue(result.note.contains("pivot"))
        rule.onNodeWithTag("trace-close").performClick()
        assertEquals(1, closed)
    }

    @Test fun ceilingHeightCanBeTypedAndSwitchesWork() {
        start()
        traceLoop(room.zf, Surface.FLOOR)
        resume()
        rule.onNodeWithTag("trace-ceiling").performClick()
        traceLoop(room.zc, Surface.CEILING)
        assertEquals(4, c.doc.ceiling.corners.size)
        resume()
        // tap the scale tag and type the real ceiling height
        rule.onNodeWithTag("trace-scale").performClick()
        rule.waitForIdle()
        assertEquals(EditTarget.Ceiling, c.editing)
        rule.onNodeWithTag("trace-edit").performTextReplacement("8 2")
        rule.onNodeWithTag("trace-edit").performImeAction()
        rule.waitForIdle()
        assertEquals(ScaleBasis.CEILING_TYPED, c.solution.basis)
        assertTrue(c.solution.calibrated)
        val err = worstWallError()
        println("TRACE-UI worst wall error with typed ceiling height only: %.2f in".format(err / INCH))
        assertTrue("worst ${err / INCH} in", err < 2 * INCH)
        look(atan2(room.poly[2].y, room.poly[2].x), Math.toRadians(8.0))
        shot("09-both-loops-ceiling-typed")

        // settings panel opens without scrolling and its switches change behaviour
        rule.onNodeWithTag("trace-settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("trace-settings-panel").assertIsDisplayed()
        rule.onNodeWithTag("trace-pivot").assertIsDisplayed()
        shot("10-test-switches")
        assertEquals(0, rule.onAllNodes(SemanticsMatcher("scrollable") { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }).fetchSemanticsNodes().size)
        rule.onNodeWithTag("trace-settings-done").performClick()
        c.updateSettings(c.settings.copy(freeze = FreezeMode.OFF))
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag("trace-freeze")).fetchSemanticsNodes().size)
    }

    @Test @Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
    fun everythingFitsASmallPhoneWithoutScrolling() {
        start()
        lookAt(room.along(1.0, room.zf))
        for (tag in listOf("trace-settings", "trace-coach", "trace-close", "trace-scale", "trace-canvas", "trace-freeze", "trace-undo", "trace-redo", "trace-floor", "trace-ceiling", "trace-restart", "trace-save"))
            rule.onNodeWithTag(tag).assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(SemanticsMatcher("scrollable") {
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null || it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
        }).fetchSemanticsNodes().size)
        // controls are big enough to hit: at least 46 dp tall (xhdpi = 2 px per dp), and nothing wraps onto a second line
        for (tag in listOf("trace-undo", "trace-redo", "trace-floor", "trace-ceiling", "trace-restart", "trace-save")) {
            val size = rule.onNodeWithTag(tag).fetchSemanticsNode().size
            assertTrue("$tag height ${size.height}", size.height in 92..112)
        }
        assertTrue(rule.onNodeWithTag("trace-save").fetchSemanticsNode().size.width >= 100)
        shot("11-small-phone")
    }
}
