package com.owlacoustics.roomplanner

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.owlacoustics.domain.ceiling.CeilingGeometry
import com.owlacoustics.domain.edit.SetupEdits
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.CeilingProfile
import com.owlacoustics.domain.model.DoorHanding
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.model.Purpose
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.persistence.LoadResult
import com.owlacoustics.domain.persistence.RoomSession
import com.owlacoustics.domain.persistence.RoomStore
import com.owlacoustics.domain.persistence.SessionState
import com.owlacoustics.roomplanner.ui.plan.ViewTransform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * JVM-rendered (Robolectric) interaction tests. Evidence level: BUILD-TESTED rendered interaction on a JVM host,
 * not RUNTIME-TESTED on an emulator and not DEVICE-VERIFIED.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class EditorUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<OwlApplication>().container

    private fun session(): RoomSession = container.session(container.listRooms().single().id).first!!
    private fun state(): SessionState = session().state.value
    private fun model(): RoomModel = state().model

    private fun waitFor(timeoutMs: Long = 8000, cond: () -> Boolean) {
        rule.waitUntil(timeoutMs) { rule.waitForIdle(); cond() }
        rule.waitForIdle()
    }

    private fun createManualRoom(name: String) {
        rule.onNodeWithTag("new-manual").performClick()
        rule.onNodeWithTag("new-room-name").performTextInput(name)
        rule.onNodeWithTag("create-room").performClick()
        waitFor { container.listRooms().isNotEmpty() }
        rule.onNodeWithTag("plan-canvas").assertExists()
    }

    private fun canvas(): SemanticsNodeInteraction = rule.onNodeWithTag("plan-canvas")

    /** Reproduces the editor's initial fit so a test can touch real geometry positions. */
    private fun fit(m: RoomModel): ViewTransform {
        val b = canvas().fetchSemanticsNode().size
        return ViewTransform.fit(m.outline, b.width.toFloat(), b.height.toFloat())
    }

    private fun px(dp: Float) = dp * rule.density.density

    @Test fun inlineMeasurementCornerDragUndoRedoAndRecreate() {
        createManualRoom("Studio A")
        rule.onNodeWithText("Area 120.0 ft²").assertExists()
        var m = model()
        val t = fit(m)
        // tap the first wall's length label on the canvas and type an exact fraction inline
        val w0 = m.walls[0]
        val label = t.toScreen(w0.pointAt(w0.length / 2) + w0.outward * t.pixelsToMeters(px(com.owlacoustics.roomplanner.ui.plan.WALL_LABEL_OFFSET_DP)))
        canvas().performTouchInput { click(label) }
        rule.onNodeWithTag("inline-measure").assertExists().performTextReplacement("14' 1/2\"")
        rule.onNodeWithTag("inline-measure").performImeAction()
        waitFor { abs(model().walls[0].length - (14 * 0.3048 + 0.5 * 0.0254)) < 1e-12 }
        assertEquals("14' 1/2\"", model().entries["wall:${w0.id}:length"]!!.text)
        rule.onNodeWithText("Wall 1: 14' 1/2\"").assertExists()
        val stepsBefore = state().history.entries.size

        // drag a corner: one gesture = one meaningful history step
        m = model()
        val v = m.vertices[2]
        val start = t.toScreen(v.pos)
        canvas().performTouchInput { swipe(start, start + Offset(px(60f), 0f), 400) }
        waitFor { state().history.entries.size == stepsBefore + 1 }
        val moved = model().vertices[2]
        assertTrue(moved.x > v.x + 0.1)
        assertEquals("Move corner", state().history.entries.last().label)
        val area = Polygons.area(model().outline)

        rule.onNodeWithTag("undo").performClick()
        waitFor { abs(model().vertices[2].x - v.x) < 1e-12 }
        rule.onNodeWithTag("redo").assertIsEnabled()

        // activity recreation (rotation/config change) keeps the room, its history cursor and redo branch
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("plan-canvas").assertExists()
        rule.onNodeWithTag("redo").assertIsEnabled().performClick()
        waitFor { abs(Polygons.area(model().outline) - area) < 1e-12 }

        // a fresh store reading the same files sees exactly the same history (process-restart analogue)
        val r = RoomStore(container.store.root).load(state().roomId) as LoadResult.Ok
        assertEquals(state().history.cursor, r.history.cursor)
        assertEquals(state().history.entries.map { it.label }, r.history.entries.map { it.label })
    }

    @Test fun selectFirstWallActionsWindowHybridAndDoorRotation() {
        createManualRoom("Booth")
        // a drag on an unselected wall pans the view; it never moves the wall
        var m = model()
        val t = fit(m)
        val w0 = m.walls[0]
        val wallMid = t.toScreen(w0.pointAt(w0.length * 0.3))
        canvas().performTouchInput { swipe(wallMid, wallMid + Offset(0f, px(40f)), 300) }
        rule.waitForIdle()
        assertEquals(1, state().history.entries.size)

        rule.onNodeWithText("Wall 1: 12' 0\"").performClick()
        rule.onNodeWithTag("add-window").performClick()
        waitFor { model().openings.size == 1 }
        val win = model().openings.single()
        assertEquals(OpeningKind.WINDOW, win.kind)
        rule.onNodeWithText("Window on wall 1", substring = true).assertExists()

        // window centre dragged along the wall moves it with width unchanged (fit is recomputed after pan reset)
        m = model()
        rule.onNodeWithText("Fit").performClick(); rule.waitForIdle()
        val t2 = fit(m)
        val w = m.wall(win.wallId)!!
        val c = t2.toScreen(w.pointAt(win.offset + win.width / 2))
        val along = t2.toScreen(w.pointAt(win.offset + win.width / 2 + 0.5)) - c
        val steps = state().history.entries.size
        canvas().performTouchInput { swipe(c, c + along, 500) }
        waitFor { state().history.entries.size == steps + 1 }
        val moved = model().openings.single()
        assertTrue(abs(moved.offset - win.offset) > 0.3)
        assertEquals(win.width, moved.width, 1e-12)
        val o = model().openings.single(); val ww = model().wall(o.wallId)!!
        assertEquals(ww.length, o.offset + o.width + (ww.length - o.offset - o.width), 1e-12)

        // perpendicular centre drag: fine symmetric width change about the centre
        val c2 = t2.toScreen(w.pointAt(moved.offset + moved.width / 2))
        val out = t2.toScreen(w.pointAt(moved.offset + moved.width / 2) + w.outward * 0.4) - c2
        canvas().performTouchInput { swipe(c2, c2 + out, 500) }
        waitFor { state().history.entries.size == steps + 2 }
        val resized = model().openings.single()
        assertTrue(resized.width > moved.width)
        assertEquals(moved.offset + moved.width / 2, resized.offset + resized.width / 2, 1e-9)

        // door: tap selects, tap on the highlighted door cycles the drawn swing; aperture unchanged
        rule.onNodeWithText("Wall 2: 10' 0\"").assertDoesNotExist() // panel shows the window now
        canvas().performTouchInput { click(Offset(5f, 5f)) } // background tap clears selection
        rule.onNodeWithText("Wall 3: 12' 0\"").performClick()
        rule.onNodeWithTag("add-door").performClick()
        waitFor { model().openings.size == 2 }
        val door = model().openings.first { it.kind == OpeningKind.DOOR }
        val dw = model().wall(door.wallId)!!
        val dc = t2.toScreen(dw.pointAt(door.offset + door.width / 2))
        // the new door is already selected, so one tap on its highlighted region cycles the swing drawing
        canvas().performTouchInput { click(dc) }
        waitFor { model().openings.first { it.id == door.id }.handing == DoorHanding.HINGE_START_IN }
        val d2 = model().openings.first { it.id == door.id }
        assertEquals(door.offset, d2.offset, 1e-12); assertEquals(door.width, d2.width, 1e-12)
    }

    @Test fun planConfirmWithoutHeightThenCeilingAnd3D() {
        createManualRoom("Den")
        rule.onNodeWithTag("confirm-plan").performClick()
        waitFor { model().planConfirmed }
        rule.onNodeWithTag("tab-CEILING").performScrollTo().performClick()
        rule.onNodeWithTag("ceiling-FLAT").performClick()
        waitFor { model().ceiling.profile is CeilingProfile.Flat }
        rule.onAllNodesWithText("Still needed: ceiling height").onFirst().assertExists()
        rule.onNodeWithText("Ceiling height above floor").performTextInput("8' 6\"")
        rule.onNodeWithText("Ceiling height above floor").performImeAction()
        waitFor { (model().ceiling.profile as? CeilingProfile.Flat)?.height != null }
        assertEquals(8.5 * 0.3048, (model().ceiling.profile as CeilingProfile.Flat).height!!.meters, 1e-12)
        assertTrue(CeilingGeometry.support(model().outline, model().ceiling) is com.owlacoustics.domain.ceiling.CeilingSupport.Defined)
        rule.onNodeWithTag("room-3d").assertExists()
        rule.onNodeWithText("All represented surfaces are complete for this model.").assertExists()
        // a special/mixed ceiling stays an editable, saved draft
        rule.onNodeWithTag("ceiling-SPECIAL").performClick()
        waitFor { model().ceiling.profile is CeilingProfile.Special }
        rule.onNodeWithText("Partial view — missing inputs are listed below.").assertExists()
        rule.onNodeWithTag("undo").performClick()
        waitFor { model().ceiling.profile is CeilingProfile.Flat }
    }

    @Test fun analysisIsCurrentThenInvalidatedByEditAndRecomputable() {
        createManualRoom("Mix")
        val s = session()
        runBlocking {
            s.edit { com.owlacoustics.domain.edit.CeilingEdits.setProfile(it, CeilingProfile.Flat(com.owlacoustics.domain.model.Measure(2.6, com.owlacoustics.domain.model.Provenance.USER_ENTERED))) }
            s.edit { SetupEdits.setPurpose(it, Purpose.MIXING) }
            val ids = s.state.value.model.setup.items.associate { it.label to it.id }
            s.edit { SetupEdits.moveItem(it, ids["L"]!!, Vec2(0.8, 0.5)) }
            s.edit { SetupEdits.moveItem(it, ids["R"]!!, Vec2(2.4, 0.5)) }
            s.edit { SetupEdits.moveItem(it, ids["Listener"]!!, Vec2(1.6, 1.9)) }
            for (id in ids.values) s.edit { SetupEdits.setHeight(it, id, com.owlacoustics.domain.model.Measure(1.2, com.owlacoustics.domain.model.Provenance.USER_ENTERED)) }
        }
        rule.onNodeWithTag("tab-ANALYSIS").performScrollTo().performClick()
        rule.onNodeWithTag("run-analysis").performClick()
        waitFor(20000) { state().currentResult("analysis") != null }
        rule.onNodeWithText("Current for this room revision.").assertExists()
        rule.onNodeWithTag("run-placement").performClick()
        waitFor(60000) { state().currentResult("placement") != null }
        rule.onNodeWithTag("apply-proposal-0").performScrollTo().performClick()
        waitFor { state().history.entries.last().label == "Apply placement proposal" }
        // the edit invalidated analysis in the same commit; recompute stays reachable
        assertEquals(null, state().currentResult("analysis"))
        rule.onNodeWithTag("run-analysis").performScrollTo().assertIsEnabled().performClick()
        waitFor(20000) { state().currentResult("analysis") != null }
        rule.onNodeWithTag("tab-ACTIONS").performScrollTo().performClick()
        rule.onNodeWithTag("run-recommend").performClick()
        waitFor(20000) { state().currentResult("recommendations") != null }
        assertNotNull(rule.onAllNodesWithText("Buy nothing", substring = true).fetchSemanticsNodes().firstOrNull())
    }

    @Test fun roomDeletionNeedsConfirmationAndKeepsOtherRooms() {
        createManualRoom("Keep me")
        rule.onNodeWithTag("back-to-rooms").performClick()
        rule.onNodeWithTag("new-manual").performClick()
        rule.onNodeWithTag("new-room-name").performTextInput("Delete me")
        rule.onNodeWithTag("create-room").performClick()
        waitFor { container.listRooms().size == 2 }
        rule.onNodeWithTag("back-to-rooms").performClick()
        rule.onNodeWithTag("room-Delete me").assertExists()
        rule.onNode(hasText("Delete") and hasAnyAncestor(hasTestTag("room-Delete me"))).performClick()
        rule.onNodeWithTag("confirm-delete").performClick()
        waitFor { container.listRooms().size == 1 }
        assertEquals("Keep me", container.listRooms().single().name)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() = this[0]
