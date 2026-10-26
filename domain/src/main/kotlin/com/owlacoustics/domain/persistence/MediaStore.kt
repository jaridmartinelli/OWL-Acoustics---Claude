package com.owlacoustics.domain.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.nio.file.Path

@Serializable
enum class MediaKind { VIDEO, IMAGE, PDF }

/** App-private source media with explicit ownership. Deleted media keeps a tombstone so references show "unavailable". */
@Serializable
data class MediaRecord(
    val id: String,
    val kind: MediaKind,
    val fileName: String,
    val owners: List<String>,
    val purpose: String,
    val createdMs: Long,
    val durationMs: Long? = null,
    val deleted: Boolean = false,
    val note: String = "",
)

/** Measurement/provenance observations kept outside undo history (never removed by a new edit after undo). */
@Serializable
data class Observation(
    val id: String,
    val kind: String,
    val sourceMediaId: String?,
    val detail: String,
    val createdMs: Long,
)

class MediaStore(private val root: Path, private val io: DurableIo = DurableIo()) {
    private val dir get() = root.resolve("media")
    private val indexPath get() = dir.resolve("index.json")
    private val ser = ListSerializer(MediaRecord.serializer())

    @Synchronized
    fun all(): List<MediaRecord> {
        val b = io.readOrNull(indexPath) ?: return emptyList()
        return OwlJson.decodeFromString(ser, b.toString(Charsets.UTF_8))
    }

    private fun save(list: List<MediaRecord>) = io.writeAtomic(indexPath, OwlJson.encodeToString(ser, list).toByteArray())

    fun fileFor(r: MediaRecord): Path = dir.resolve(r.fileName)

    fun newFile(id: String, ext: String): Path { Files.createDirectories(dir); return dir.resolve("$id.$ext") }

    @Synchronized
    fun register(r: MediaRecord) = save(all().filter { it.id != r.id } + r)

    fun forRoom(roomId: String) = all().filter { roomId in it.owners }

    /** User deletion of one source: bytes removed, tombstone kept. Undo can never recreate the bytes. */
    @Synchronized
    fun deleteMedia(id: String) {
        val list = all()
        val r = list.firstOrNull { it.id == id } ?: return
        Files.deleteIfExists(fileFor(r))
        save(list.map { if (it.id == id) it.copy(deleted = true) else it })
    }

    /** Room deletion: drops this room's ownership; bytes are deleted only when no other room owns the source. */
    @Synchronized
    fun releaseRoom(roomId: String) {
        val list = all()
        val next = list.mapNotNull { r ->
            if (roomId !in r.owners) return@mapNotNull r
            val owners = r.owners - roomId
            if (owners.isEmpty()) { Files.deleteIfExists(fileFor(r)); null } else r.copy(owners = owners)
        }
        save(next)
    }
}

class EvidenceStore(private val store: RoomStore, private val io: DurableIo = DurableIo()) {
    private val ser = ListSerializer(Observation.serializer())
    private fun path(roomId: String) = store.roomDir(roomId).resolve("evidence.json")

    fun load(roomId: String): List<Observation> =
        io.readOrNull(path(roomId))?.let { OwlJson.decodeFromString(ser, it.toString(Charsets.UTF_8)) } ?: emptyList()

    @Synchronized
    fun append(roomId: String, o: Observation) = io.writeAtomic(path(roomId), OwlJson.encodeToString(ser, load(roomId) + o).toByteArray())
}
