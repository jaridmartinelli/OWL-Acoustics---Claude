package com.owlacoustics.domain

import com.owlacoustics.domain.edit.EditResult
import com.owlacoustics.domain.edit.Edits
import com.owlacoustics.domain.edit.Openings
import com.owlacoustics.domain.edit.Templates
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.persistence.DurableIo
import com.owlacoustics.domain.persistence.FaultInjector
import com.owlacoustics.domain.persistence.LoadResult
import com.owlacoustics.domain.persistence.MediaKind
import com.owlacoustics.domain.persistence.MediaRecord
import com.owlacoustics.domain.persistence.MediaStore
import com.owlacoustics.domain.persistence.RoomSession
import com.owlacoustics.domain.persistence.RoomStore
import com.owlacoustics.domain.persistence.SaveOutcome
import com.owlacoustics.domain.workflow.Workflow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class PersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private var failing: String? = null
    private val faults = FaultInjector { stage, _ -> if (stage == failing) throw IOException("injected $stage failure") }

    private fun store() = RoomStore(tmp.root.toPath(), DurableIo(faults))

    private fun edit(s: RoomSession, f: (com.owlacoustics.domain.model.RoomModel) -> EditResult) = runBlocking {
        val r = f(s.state.value.model) as EditResult.Ok
        s.apply(r)
    }

    private fun reopen(st: RoomStore, id: String): RoomSession = RoomSession.open(st, id).first!!

    @Test fun historyCursorAndRedoSurviveRestartAndFinalize() = runBlocking {
        val st = store()
        val s = RoomSession.create(st, Templates.newRoom("roomA"), "Studio")
        edit(s) { Edits.moveWall(it, it.vertices[1].wallId, 0.5) }
        edit(s) { Openings.add(it, it.vertices[0].wallId, OpeningKind.WINDOW) }
        edit(s) { Workflow.setFinalized(it, true) }
        assertEquals(SaveOutcome.Saved, s.undo()) // undo finalize
        assertEquals(SaveOutcome.Saved, s.undo()) // undo window
        val before = s.state.value
        // "process restart": new store + session from disk only
        val s2 = reopen(RoomStore(tmp.root.toPath()), "roomA")
        val after = s2.state.value
        assertEquals(before.history.cursor, after.history.cursor)
        assertEquals(before.history.entries.map { it.model }, after.history.entries.map { it.model })
        assertTrue(after.history.canRedo)
        assertEquals(SaveOutcome.Saved, s2.redo())
        assertEquals(1, s2.state.value.model.openings.size)
        assertEquals(SaveOutcome.Saved, s2.redo())
        assertTrue(s2.state.value.model.finalized)
        assertEquals("Studio", s2.state.value.name)
    }

    @Test fun newEditAfterUndoReplacesOnlyRedoBranch() = runBlocking {
        val st = store()
        val a = RoomSession.create(st, Templates.newRoom("roomA"), "A")
        val b = RoomSession.create(st, Templates.newRoom("roomB"), "B")
        edit(b) { Edits.moveWall(it, it.vertices[0].wallId, 0.3) }
        edit(a) { Edits.moveWall(it, it.vertices[1].wallId, 0.5) }
        edit(a) { Edits.moveWall(it, it.vertices[2].wallId, 0.5) }
        a.undo()
        edit(a) { Openings.add(it, it.vertices[0].wallId, OpeningKind.DOOR) }
        val ra = reopen(RoomStore(tmp.root.toPath()), "roomA").state.value
        assertEquals(3, ra.history.entries.size)
        assertFalse(ra.history.canRedo)
        assertEquals("Add door", ra.history.entries.last().label)
        val rb = reopen(RoomStore(tmp.root.toPath()), "roomB").state.value
        assertEquals(2, rb.history.entries.size)
        assertEquals(b.state.value.model, rb.model)
    }

    @Test fun failedWriteKeepsDurableDataAndOffersRetry() = runBlocking {
        val st = store()
        val s = RoomSession.create(st, Templates.newRoom("roomA"), "A")
        val durableBefore = s.state.value.model
        val headBytes = Files.readAllBytes(st.roomDir("roomA").resolve("head.json"))
        failing = "append"
        val out = edit(s) { Edits.moveWall(it, it.vertices[1].wallId, 0.5) }
        assertTrue(out is SaveOutcome.Failed)
        val st1 = s.state.value
        assertFalse(st1.saved)
        assertNotNull(st1.unsaved)
        assertEquals(durableBefore, st1.durable.current)
        assertTrue(st1.model != durableBefore) // the edit stays visible as unsaved
        assertTrue(st1.results.isEmpty())
        // prior bytes on disk untouched
        assertArrayEquals(headBytes, Files.readAllBytes(st.roomDir("roomA").resolve("head.json")))
        // further edits are blocked rather than silently claiming success
        assertTrue(edit(s) { Edits.moveWall(it, it.vertices[2].wallId, 0.5) } is SaveOutcome.Blocked)
        failing = null
        assertEquals(SaveOutcome.Saved, s.retry())
        assertTrue(s.state.value.saved)
        assertEquals(s.state.value.model, reopen(RoomStore(tmp.root.toPath()), "roomA").state.value.model)
    }

    @Test fun failedHeadRenameThenSuccessfulCommitStaysConsistent() = runBlocking {
        val st = store()
        val s = RoomSession.create(st, Templates.newRoom("roomA"), "A")
        edit(s) { Edits.moveWall(it, it.vertices[1].wallId, 0.5) }
        failing = "rename" // history line appended, head not switched
        assertTrue(edit(s) { Edits.moveWall(it, it.vertices[2].wallId, 0.5) } is SaveOutcome.Failed)
        s.discard()
        failing = null
        edit(s) { Edits.moveWall(it, it.vertices[3].wallId, 0.25) }
        val r = reopen(RoomStore(tmp.root.toPath()), "roomA").state.value
        assertEquals(s.state.value.model, r.model)
        assertEquals(listOf("Create room", "Move wall", "Move wall"), r.history.entries.map { it.label })
    }

    @Test fun interruptedAppendBytesAreIgnoredAndRepaired() = runBlocking {
        val st = store()
        val s = RoomSession.create(st, Templates.newRoom("roomA"), "A")
        edit(s) { Edits.moveWall(it, it.vertices[1].wallId, 0.5) }
        val dir = st.roomDir("roomA")
        val hf = Files.list(dir).use { it.filter { p -> p.fileName.toString().startsWith("history-") }.findFirst().get() }
        Files.write(hf, "{\"seq\":99, partial".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        val s2 = reopen(RoomStore(tmp.root.toPath()), "roomA")
        assertEquals(2, s2.state.value.history.entries.size)
        edit(s2) { Edits.moveWall(it, it.vertices[2].wallId, 0.5) }
        assertEquals(3, reopen(RoomStore(tmp.root.toPath()), "roomA").state.value.history.entries.size)
    }

    @Test fun corruptAndFutureFilesAreNotOverwritten() {
        val st = store()
        RoomSession.create(st, Templates.newRoom("roomA"), "A")
        RoomSession.create(st, Templates.newRoom("roomB"), "B")
        val headA = st.roomDir("roomA").resolve("head.json")
        val headB = st.roomDir("roomB").resolve("head.json")
        Files.write(headA, "{not json".toByteArray())
        val future = String(Files.readAllBytes(headB)).replaceFirst("{", "{\"schema\":99,")
        Files.write(headB, future.toByteArray())
        val a = st.load("roomA"); val b = st.load("roomB")
        assertTrue(a is LoadResult.Corrupt)
        assertTrue(b is LoadResult.UnknownFuture)
        assertNull(RoomSession.open(st, "roomA").first)
        assertEquals("{not json", String(Files.readAllBytes(headA)))
        assertEquals(future, String(Files.readAllBytes(headB)))
    }

    @Test fun staleJobsRejected() = runBlocking {
        val st = store()
        val a = RoomSession.create(st, Templates.newRoom("roomA"), "A")
        val b = RoomSession.create(st, Templates.newRoom("roomB"), "B")
        val t0 = a.ticket()
        assertTrue(a.acceptResult(t0, "modes", "x"))
        assertNotNull(a.state.value.currentResult("modes"))
        // result from room A never lands in room B
        assertFalse(b.acceptResult(t0, "modes", "x"))
        edit(a) { Edits.moveWall(it, it.vertices[1].wallId, 0.5) }
        assertNull(a.state.value.currentResult("modes")) // invalidated in the same commit
        val t1 = a.ticket()
        assertFalse(a.acceptResult(t0, "modes", "late"))
        // undo returns to the same model and cursor, but the old ticket stays obsolete
        a.undo()
        assertEquals(t0.inputHash, a.ticket().inputHash)
        assertFalse(a.acceptResult(t0, "modes", "late"))
        assertFalse(a.acceptResult(t1, "modes", "late"))
        // restart: tickets from before are obsolete; current results survive reopen
        val tNow = a.ticket()
        assertTrue(a.acceptResult(tNow, "modes", "fresh"))
        val a2 = reopen(RoomStore(tmp.root.toPath()), "roomA")
        assertEquals("fresh", a2.state.value.currentResult("modes")!!.payload)
        assertFalse(a2.acceptResult(tNow, "modes", "late"))
        // closed (deleted) room rejects
        a2.close()
        assertFalse(a2.acceptResult(a2.ticket(), "modes", "x"))
    }

    @Test fun roomDeletionRemovesOnlyThatRoomAndOwnedMedia() {
        val root = tmp.root.toPath()
        val st = store()
        RoomSession.create(st, Templates.newRoom("roomA"), "A")
        RoomSession.create(st, Templates.newRoom("roomB"), "B")
        val media = MediaStore(root)
        val own = media.newFile("m1", "mp4"); Files.write(own, byteArrayOf(1))
        val shared = media.newFile("m2", "pdf"); Files.write(shared, byteArrayOf(2))
        media.register(MediaRecord("m1", MediaKind.VIDEO, "m1.mp4", listOf("roomA"), "capture", 0))
        media.register(MediaRecord("m2", MediaKind.PDF, "m2.pdf", listOf("roomA", "roomB"), "import", 0))
        Files.createDirectories(root.resolve("orders")); Files.write(root.resolve("orders/o1.json"), "{}".toByteArray())
        st.delete("roomA"); media.releaseRoom("roomA")
        assertEquals(listOf("roomB"), st.listRoomIds())
        assertFalse(Files.exists(own))
        assertTrue(Files.exists(shared))
        assertEquals(listOf("roomB"), media.all().single { it.id == "m2" }.owners)
        assertTrue(Files.exists(root.resolve("orders/o1.json")))
    }

    @Test fun deletedMediaIsNotResurrectedByHistory() {
        val root = tmp.root.toPath()
        val media = MediaStore(root)
        val f: Path = media.newFile("m1", "mp4"); Files.write(f, byteArrayOf(1, 2))
        media.register(MediaRecord("m1", MediaKind.VIDEO, "m1.mp4", listOf("roomA"), "capture", 0))
        media.deleteMedia("m1")
        assertFalse(Files.exists(f))
        assertTrue(media.all().single().deleted)
    }

    @Test fun interruptedDeletionIsCompleted() {
        val st = store()
        RoomSession.create(st, Templates.newRoom("roomA"), "A")
        Files.write(st.roomDir("roomA").resolve("DELETING"), ByteArray(0))
        assertTrue(st.listRoomIds().isEmpty())
        st.cleanupInterruptedDeletes()
        assertFalse(Files.exists(st.roomDir("roomA")))
    }

    @Test fun renameIsNotAnUndoStep() = runBlocking {
        val st = store()
        val s = RoomSession.create(st, Templates.newRoom("roomA"), "A")
        s.rename("Vocal booth")
        assertFalse(s.state.value.history.canUndo)
        assertEquals("Vocal booth", reopen(RoomStore(tmp.root.toPath()), "roomA").state.value.name)
    }
}
