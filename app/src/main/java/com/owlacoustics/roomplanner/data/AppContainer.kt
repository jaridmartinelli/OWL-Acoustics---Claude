package com.owlacoustics.roomplanner.data

import android.content.Context
import com.owlacoustics.domain.capture.RemoteAnalysisConfig
import com.owlacoustics.domain.edit.StartShape
import com.owlacoustics.domain.edit.Templates
import com.owlacoustics.domain.persistence.EvidenceStore
import com.owlacoustics.domain.persistence.LoadResult
import com.owlacoustics.domain.persistence.MediaStore
import com.owlacoustics.domain.persistence.RoomSession
import com.owlacoustics.domain.persistence.RoomStore
import com.owlacoustics.domain.persistence.StoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

data class RoomListEntry(val id: String, val name: String, val updatedMs: Long, val finalized: Boolean, val problem: String?)

/**
 * App-wide dependencies. All room data lives in app-private storage (filesDir), excluded from backup/transfer (D-17).
 * Live remote services are unconfigured in this build (B-04, B-05, B-07).
 */
class AppContainer(context: Context) {
    private val root = context.filesDir.toPath()
    val store = RoomStore(root)
    val media = MediaStore(root)
    val evidence = EvidenceStore(store)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val remoteAnalysis = RemoteAnalysisConfig(serviceUrl = null, consentText = null, retentionPolicy = null)
    val billingConfigured = false

    private val sessions = HashMap<String, RoomSession>()

    init { runCatching { store.cleanupInterruptedDeletes() } }

    @Synchronized
    fun listRooms(): List<RoomListEntry> = store.listRoomIds().map { id ->
        when (val r = store.load(id)) {
            is LoadResult.Ok -> RoomListEntry(id, r.head.name, r.head.updatedMs, r.history.current.finalized, null)
            is LoadResult.UnknownFuture -> RoomListEntry(id, id, 0, false, "Saved by a newer app version (format ${r.schema}); left untouched.")
            is LoadResult.Corrupt -> RoomListEntry(id, id, 0, false, "Could not be read (${r.reason}); left untouched.")
            is LoadResult.Missing -> RoomListEntry(id, id, 0, false, "Missing")
        }
    }.sortedByDescending { it.updatedMs }

    @Synchronized
    fun createRoom(name: String, shape: StartShape): Result<String> {
        val id = "room-" + UUID.randomUUID().toString().take(8)
        return try {
            sessions[id] = RoomSession.create(store, Templates.newRoom(id, shape), name.ifBlank { "Untitled room" })
            Result.success(id)
        } catch (e: StoreException) { Result.failure(e) }
    }

    @Synchronized
    fun session(id: String): Pair<RoomSession?, String?> {
        sessions[id]?.let { if (!it.state.value.closed) return it to null }
        val (s, r) = RoomSession.open(store, id)
        if (s != null) { sessions[id] = s; return s to null }
        return null to when (r) {
            is LoadResult.UnknownFuture -> "This room was saved by a newer version of the app and is left untouched."
            is LoadResult.Corrupt -> "This room could not be read and is left untouched: ${r.reason}"
            else -> "Room not found."
        }
    }

    /** Deletes geometry, history and exclusively owned media; closes the session so late results are rejected. */
    @Synchronized
    fun deleteRoom(id: String): Result<Unit> = try {
        sessions.remove(id)?.close()
        store.delete(id)
        media.releaseRoom(id)
        Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }
}
