package com.owlacoustics.domain.persistence

import com.owlacoustics.domain.edit.EditResult
import com.owlacoustics.domain.history.History
import com.owlacoustics.domain.model.RoomModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

fun inputHash(m: RoomModel): String {
    val bytes = OwlJson.encodeToString(RoomModel.serializer(), m).toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Identifies the exact inputs a background job was started from (C-12). */
data class JobTicket(val roomId: String, val generation: Long, val inputHash: String)

/** Work that is visible but not durable because the last write failed. */
data class UnsavedWork(val attempted: History, val reason: String)

data class SessionState(
    val roomId: String,
    val name: String,
    /** What the editor shows: durable history, or the attempted history while unsaved. */
    val history: History,
    val durable: History,
    val generation: Long,
    val unsaved: UnsavedWork?,
    val results: List<StoredResult>,
    val closed: Boolean = false,
) {
    val model: RoomModel get() = history.current
    val saved: Boolean get() = unsaved == null
    fun currentResult(kind: String): StoredResult? {
        val h = inputHash(model)
        return results.firstOrNull { it.kind == kind && it.generation == generation && it.inputHash == h }
    }
}

sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data class Failed(val reason: String) : SaveOutcome
    data class Blocked(val reason: String) : SaveOutcome
}

/**
 * One open room. All durable writes for the room are serialised by [mutex]; model, history cursor and
 * result invalidation change together in one head write (the commit point).
 */
class RoomSession private constructor(
    private val store: RoomStore,
    private var head: RoomHead,
    durable: History,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private var generation: Long = head.generation + 1 // reload never rewinds generation (C-12)
    private val _state = MutableStateFlow(
        SessionState(
            head.roomId, head.name, durable, durable, generation, null,
            // results current at the last durable write stay current across reload
            head.results.filter { it.generation == head.generation }.map { it.copy(generation = generation) },
        ),
    )
    val state: StateFlow<SessionState> = _state
    val roomId get() = head.roomId

    private fun publish(f: (SessionState) -> SessionState) { _state.value = f(_state.value) }

    suspend fun apply(edit: EditResult.Ok): SaveOutcome = mutex.withLock {
        val s = _state.value
        if (s.closed) return SaveOutcome.Blocked("Room is closed.")
        if (s.unsaved != null) return SaveOutcome.Blocked("Save or discard the unsaved change first.")
        commitLocked(s.durable, s.durable.push(edit.model, edit.label, clock()))
    }

    suspend fun undo(): SaveOutcome = mutex.withLock {
        val s = _state.value
        if (s.closed || s.unsaved != null || !s.durable.canUndo) return SaveOutcome.Blocked("Nothing to undo.")
        commitLocked(s.durable, s.durable.undo())
    }

    suspend fun redo(): SaveOutcome = mutex.withLock {
        val s = _state.value
        if (s.closed || s.unsaved != null || !s.durable.canRedo) return SaveOutcome.Blocked("Nothing to redo.")
        commitLocked(s.durable, s.durable.redo())
    }

    suspend fun retry(): SaveOutcome = mutex.withLock {
        val s = _state.value
        val u = s.unsaved ?: return SaveOutcome.Saved
        commitLocked(s.durable, u.attempted)
    }

    /** Deliberately abandons unsaved work and returns to the last durable state. */
    suspend fun discard() = mutex.withLock {
        generation += 1
        publish { it.copy(history = it.durable, unsaved = null, generation = generation) }
    }

    private fun commitLocked(prev: History, next: History): SaveOutcome {
        generation += 1
        val nextHead = head.copy(updatedMs = clock(), generation = generation, results = emptyList())
        // expose the attempted state immediately with invalidated results; it is unsaved until the write succeeds
        publish { it.copy(history = next, generation = generation, results = emptyList()) }
        return try {
            head = store.commit(head, prev, next, nextHead)
            publish { it.copy(durable = next, history = next, unsaved = null) }
            SaveOutcome.Saved
        } catch (e: StoreException) {
            publish { it.copy(unsaved = UnsavedWork(next, e.message ?: "Storage error")) }
            SaveOutcome.Failed(e.message ?: "Storage error")
        }
    }

    suspend fun rename(name: String): SaveOutcome = mutex.withLock {
        val clean = name.trim().take(80)
        if (clean.isEmpty()) return SaveOutcome.Blocked("Name cannot be empty.")
        val h = head.copy(name = clean, updatedMs = clock(), generation = generation)
        try {
            store.writeHead(h); head = h
            publish { it.copy(name = clean) }
            SaveOutcome.Saved
        } catch (e: StoreException) { SaveOutcome.Failed(e.message ?: "Storage error") }
    }

    fun ticket(): JobTicket {
        val s = _state.value
        return JobTicket(s.roomId, s.generation, inputHash(s.model))
    }

    /** Accepts a background result only if it was computed from the current inputs of this open room. */
    suspend fun acceptResult(ticket: JobTicket, kind: String, payload: String): Boolean = mutex.withLock {
        val s = _state.value
        if (s.closed || ticket.roomId != s.roomId || ticket.generation != s.generation) return false
        if (ticket.inputHash != inputHash(s.model)) return false
        val r = StoredResult(kind, s.generation, ticket.inputHash, payload)
        val results = s.results.filter { it.kind != kind } + r
        publish { it.copy(results = results) }
        if (s.unsaved == null) {
            val h = head.copy(generation = generation, results = results)
            try { store.writeHead(h); head = h } catch (_: StoreException) { /* result stays in memory, recomputable */ }
        }
        true
    }

    fun close() { publish { it.copy(closed = true) } }

    companion object {
        fun create(store: RoomStore, model: RoomModel, name: String, clock: () -> Long = System::currentTimeMillis): RoomSession {
            val now = clock()
            val history = History.start(model, now)
            val head = RoomHead(roomId = model.id, name = name, createdMs = now, updatedMs = now, cursor = 0,
                historyFile = "history-1.jsonl", historyCount = 1, generation = 0)
            return RoomSession(store, store.create(head, history), history, clock)
        }

        fun open(store: RoomStore, roomId: String, clock: () -> Long = System::currentTimeMillis): Pair<RoomSession?, LoadResult> {
            val r = store.load(roomId)
            return if (r is LoadResult.Ok) RoomSession(store, r.head, r.history, clock) to r else null to r
        }
    }
}
