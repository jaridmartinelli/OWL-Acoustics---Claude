package com.owlacoustics.domain.history

import com.owlacoustics.domain.model.RoomModel
import kotlinx.serialization.Serializable

/** One meaningful, durable step: the model after the edit plus a plain-language label. */
@Serializable
data class HistoryEntry(val seq: Long, val label: String, val model: RoomModel, val timeMs: Long)

/**
 * Conventional linear undo/redo (D-11). entries[cursor] is the current state; entries after the cursor are the
 * redo branch. A new edit after undo replaces only the redo branch.
 */
data class History(val entries: List<HistoryEntry>, val cursor: Int) {
    init { require(entries.isNotEmpty() && cursor in entries.indices) { "invalid history" } }

    val current: RoomModel get() = entries[cursor].model
    val canUndo get() = cursor > 0
    val canRedo get() = cursor < entries.size - 1
    val undoLabel get() = if (canUndo) entries[cursor].label else null
    val redoLabel get() = if (canRedo) entries[cursor + 1].label else null

    fun push(model: RoomModel, label: String, timeMs: Long): History {
        val kept = entries.subList(0, cursor + 1)
        // unique across the discarded redo branch too, so the store can tell branches apart
        val seq = entries.maxOf { it.seq } + 1
        return History(kept + HistoryEntry(seq, label, model, timeMs), cursor + 1)
    }

    fun undo() = if (canUndo) copy(cursor = cursor - 1) else this
    fun redo() = if (canRedo) copy(cursor = cursor + 1) else this

    companion object {
        fun start(model: RoomModel, timeMs: Long) = History(listOf(HistoryEntry(0, "Create room", model, timeMs)), 0)
    }
}
