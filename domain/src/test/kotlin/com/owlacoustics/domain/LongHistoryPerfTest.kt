package com.owlacoustics.domain

import com.owlacoustics.domain.edit.EditResult
import com.owlacoustics.domain.edit.Edits
import com.owlacoustics.domain.edit.Openings
import com.owlacoustics.domain.edit.StartShape
import com.owlacoustics.domain.edit.Templates
import com.owlacoustics.domain.model.OpeningKind
import com.owlacoustics.domain.persistence.RoomSession
import com.owlacoustics.domain.persistence.RoomStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * F-37 engineering workload (C-11): 2,000 meaningful edits on a 12-vertex room with 6 openings.
 * Budget (JVM host only): median commit ≤ 50 ms, reopen ≤ 2 s. Device numbers must be measured separately.
 */
class LongHistoryPerfTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun longHistoryWithinBudget() = runBlocking {
        val st = RoomStore(tmp.root.toPath())
        var m = Templates.newRoom("perf", StartShape.ALCOVE)
        m = (Edits.splitWall(m, m.vertices[0].wallId, 1.0) as EditResult.Ok).model
        m = (Edits.splitWall(m, m.vertices[2].wallId, 0.5) as EditResult.Ok).model
        m = (Edits.splitWall(m, m.vertices[4].wallId, 0.3) as EditResult.Ok).model
        m = (Edits.splitWall(m, m.vertices[6].wallId, 0.3) as EditResult.Ok).model
        assertEquals(12, m.vertices.size)
        val s = RoomSession.create(st, m, "perf")
        var cur = m
        val addOn = cur.walls.sortedByDescending { it.length }.take(6)
        for (w in addOn) { val r = Openings.add(cur, w.id, OpeningKind.WINDOW) as EditResult.Ok; s.apply(r); cur = r.model }
        val times = ArrayList<Long>()
        val n = 2000
        for (i in 0 until n) {
            // alternate small corner drags so every step is a valid, meaningful edit
            val vx = cur.vertices[(i * 5) % cur.vertices.size]
            val r = Edits.moveVertex(cur, vx.id, vx.pos + com.owlacoustics.domain.geometry.Vec2(if (i % 2 == 0) 0.001 else -0.001, 0.0005))
            val ok = r as? EditResult.Ok ?: continue
            val t0 = System.nanoTime()
            s.apply(ok)
            times += System.nanoTime() - t0
            cur = ok.model
        }
        val sorted = times.sorted()
        val median = sorted[sorted.size / 2] / 1e6
        val p95 = sorted[(sorted.size * 95) / 100] / 1e6
        val bytes = Files.walk(st.roomDir("perf")).use { w -> w.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        val t0 = System.nanoTime()
        val reopened = RoomSession.open(RoomStore(tmp.root.toPath()), "perf").first!!
        val reopenMs = (System.nanoTime() - t0) / 1e6
        println("F-37 JVM: edits=${times.size} vertices=${cur.vertices.size} openings=${cur.openings.size} bytes=$bytes medianCommitMs=%.2f p95CommitMs=%.2f reopenMs=%.1f".format(median, p95, reopenMs))
        assertEquals(s.state.value.history.entries.size, reopened.state.value.history.entries.size)
        assertTrue(times.size > 1500)
        assertTrue("median commit $median ms", median <= 50.0)
        assertTrue("reopen $reopenMs ms", reopenMs <= 2000.0)
    }
}
