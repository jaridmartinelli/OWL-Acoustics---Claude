package com.owlacoustics.domain.persistence

import com.owlacoustics.domain.history.History
import com.owlacoustics.domain.history.HistoryEntry
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.SCHEMA_VERSION
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

val OwlJson = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = false
    classDiscriminator = "type"
}

/** Cached analysis output; current only when generation and input hash both match the room head. */
@Serializable
data class StoredResult(val kind: String, val generation: Long, val inputHash: String, val payload: String)

/** Room head, schema version 1 (C-10). */
@Serializable
data class RoomHead(
    val schema: Int = SCHEMA_VERSION,
    val roomId: String,
    val name: String,
    val createdMs: Long,
    val updatedMs: Long,
    val cursor: Int,
    val historyFile: String,
    val historyCount: Int,
    val generation: Long,
    val results: List<StoredResult> = emptyList(),
    /** Committed byte length of the history file; bytes beyond it are from an interrupted append. */
    val historyBytes: Long = -1,
)

@Serializable
data class RoomSummary(val roomId: String, val name: String, val updatedMs: Long, val finalized: Boolean)

sealed interface LoadResult {
    data class Ok(val head: RoomHead, val history: History) : LoadResult
    data class UnknownFuture(val roomId: String, val schema: Int) : LoadResult
    data class Corrupt(val roomId: String, val reason: String) : LoadResult
    data class Missing(val roomId: String) : LoadResult
}

class StoreException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/**
 * File-based transactional room store. Callers must serialise writes per room (see RoomSession).
 * Unknown-future or corrupt rooms are never overwritten.
 */
class RoomStore(val root: Path, private val io: DurableIo = DurableIo()) {
    private val roomsDir: Path get() = root.resolve("rooms")

    fun roomDir(id: String): Path {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "bad room id" }
        return roomsDir.resolve(id)
    }

    private fun headPath(id: String) = roomDir(id).resolve("head.json")

    fun listRoomIds(): List<String> {
        if (!Files.isDirectory(roomsDir)) return emptyList()
        return Files.list(roomsDir).use { s ->
            s.filter { Files.isDirectory(it) && Files.exists(it.resolve("head.json")) && !Files.exists(it.resolve("DELETING")) }
                .map { it.fileName.toString() }.toList()
        }
    }

    fun create(head: RoomHead, history: History): RoomHead {
        val dir = roomDir(head.roomId)
        if (Files.exists(dir.resolve("head.json"))) throw StoreException("room exists")
        val file = "history-1.jsonl"
        val bytes = history.entries.joinToString("") { OwlJson.encodeToString(HistoryEntry.serializer(), it) + "\n" }.toByteArray()
        try {
            io.writeAtomic(dir.resolve(file), bytes)
            val h = head.copy(historyFile = file, historyCount = history.entries.size,
                cursor = history.cursor, historyBytes = bytes.size.toLong())
            io.writeAtomic(headPath(head.roomId), encodeHead(h))
            return h
        } catch (e: Exception) {
            throw StoreException("Could not create the room on this device's storage.", e)
        }
    }

    fun encodeHead(h: RoomHead) = OwlJson.encodeToString(RoomHead.serializer(), h).toByteArray()

    fun load(id: String): LoadResult {
        val hp = headPath(id)
        val raw = try { io.readOrNull(hp) } catch (e: Exception) { return LoadResult.Corrupt(id, "unreadable head: ${e.message}") }
            ?: return LoadResult.Missing(id)
        val text = raw.toString(Charsets.UTF_8)
        val schema = try {
            OwlJson.parseToJsonElement(text).jsonObject["schema"]?.jsonPrimitive?.int ?: SCHEMA_VERSION
        } catch (e: Exception) { return LoadResult.Corrupt(id, "head is not valid JSON") }
        if (schema > SCHEMA_VERSION) return LoadResult.UnknownFuture(id, schema)
        val head = try { OwlJson.decodeFromString(RoomHead.serializer(), text) } catch (e: Exception) {
            return LoadResult.Corrupt(id, "head does not match schema: ${e.message}")
        }
        val hf = roomDir(id).resolve(head.historyFile)
        val hbytes = io.readOrNull(hf) ?: return LoadResult.Corrupt(id, "history file missing")
        val entries = ArrayList<HistoryEntry>(head.historyCount)
        var pos = 0
        var validBytes = 0L
        while (entries.size < head.historyCount) {
            val nl = indexOf(hbytes, '\n'.code.toByte(), pos)
            if (nl < 0) return LoadResult.Corrupt(id, "history shorter than head")
            val line = String(hbytes, pos, nl - pos, Charsets.UTF_8)
            val entry = try { OwlJson.decodeFromString(HistoryEntry.serializer(), line) } catch (e: Exception) {
                return LoadResult.Corrupt(id, "history entry ${entries.size} unreadable")
            }
            if (entry.model.schema > SCHEMA_VERSION) return LoadResult.UnknownFuture(id, entry.model.schema)
            entries += entry
            pos = nl + 1
            validBytes = pos.toLong()
        }
        if (head.cursor !in entries.indices) return LoadResult.Corrupt(id, "cursor out of range")
        if (hbytes.size > validBytes) {
            // Bytes beyond the committed count come from an interrupted append; the head never referenced them.
            try { io.truncate(hf, validBytes) } catch (_: Exception) { }
        }
        return LoadResult.Ok(head.copy(historyBytes = validBytes), History(entries, head.cursor))
    }

    private fun indexOf(b: ByteArray, v: Byte, from: Int): Int {
        for (i in from until b.size) if (b[i] == v) return i
        return -1
    }

    /**
     * Persists [next] given the previously durable [prev] state. Appends when the redo branch is untouched,
     * otherwise writes a new history epoch before switching the head. The head write is the commit point.
     */
    fun commit(prevHead: RoomHead, prev: History, next: History, nextHead: RoomHead): RoomHead {
        val id = prevHead.roomId
        val dir = roomDir(id)
        try {
            var historyFile = prevHead.historyFile
            var historyBytes = prevHead.historyBytes
            val count = next.entries.size
            val sameBranch = count >= prev.entries.size &&
                prev.entries.indices.all { prev.entries[it].seq == next.entries[it].seq }
            if (count == prev.entries.size && sameBranch) {
                // cursor-only change (undo/redo)
            } else if (sameBranch && count > prev.entries.size) {
                val extra = next.entries.subList(prev.entries.size, count)
                val hf = dir.resolve(historyFile)
                // discard bytes from an earlier failed commit so line positions stay aligned with the head
                if (historyBytes >= 0 && Files.size(hf) > historyBytes) io.truncate(hf, historyBytes)
                val add = extra.joinToString("") { OwlJson.encodeToString(HistoryEntry.serializer(), it) + "\n" }.toByteArray()
                io.append(hf, add)
                historyBytes = (if (historyBytes >= 0) historyBytes else Files.size(hf) - add.size) + add.size
            } else {
                val epoch = (historyFile.removePrefix("history-").removeSuffix(".jsonl").toIntOrNull() ?: 1) + 1
                historyFile = "history-$epoch.jsonl"
                val all = next.entries.joinToString("") { OwlJson.encodeToString(HistoryEntry.serializer(), it) + "\n" }.toByteArray()
                io.writeAtomic(dir.resolve(historyFile), all)
                historyBytes = all.size.toLong()
            }
            val h = nextHead.copy(historyFile = historyFile, historyCount = count, cursor = next.cursor, historyBytes = historyBytes)
            io.writeAtomic(headPath(id), encodeHead(h))
            if (historyFile != prevHead.historyFile) {
                try { Files.deleteIfExists(dir.resolve(prevHead.historyFile)) } catch (_: Exception) { }
            }
            return h
        } catch (e: Exception) {
            throw StoreException("Could not save to this device's storage.", e)
        }
    }

    /** Writes head-only metadata (rename, result cache). */
    fun writeHead(head: RoomHead) {
        try { io.writeAtomic(headPath(head.roomId), encodeHead(head)) } catch (e: Exception) {
            throw StoreException("Could not save to this device's storage.", e)
        }
    }

    /** Deletes a room directory. A marker makes a half-finished deletion invisible and resumable. */
    fun delete(id: String) {
        val dir = roomDir(id)
        if (!Files.exists(dir)) return
        io.writeAtomic(dir.resolve("DELETING"), ByteArray(0))
        io.deleteRecursively(dir)
    }

    /** Finishes deletions interrupted by process death. */
    fun cleanupInterruptedDeletes() {
        if (!Files.isDirectory(roomsDir)) return
        Files.list(roomsDir).use { s -> s.filter { Files.exists(it.resolve("DELETING")) }.toList() }.forEach { io.deleteRecursively(it) }
    }
}
