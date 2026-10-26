package com.owlacoustics.domain.persistence

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Test hook to simulate full/unwritable storage and interrupted writes. */
fun interface FaultInjector {
    /** Throw to simulate a failure at [stage] for [path]. */
    fun check(stage: String, path: Path)
}

/** Durable file primitives: temp + fsync + atomic rename, fsynced append, safe truncate. */
class DurableIo(private val faults: FaultInjector? = null) {

    fun writeAtomic(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        faults?.check("write", target)
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { ch ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            faults?.check("fsync", target)
            ch.force(true)
        }
        faults?.check("rename", target)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("atomic rename unsupported", e)
        }
    }

    fun append(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        faults?.check("append", target)
        FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
    }

    fun truncate(target: Path, size: Long) {
        FileChannel.open(target, StandardOpenOption.WRITE).use { it.truncate(size); it.force(true) }
    }

    fun readOrNull(p: Path): ByteArray? = if (Files.exists(p)) Files.readAllBytes(p) else null

    fun deleteRecursively(p: Path) {
        if (!Files.exists(p)) return
        Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
