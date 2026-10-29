package com.openminis.app.data.session

import java.io.File

/**
 * [T-storage-orphan-cleanup] Session folders that no conversation owns, and
 * the rules for removing them. Port of iOS SessionStorageCleanup (20e1825f6).
 *
 * An orphan is a `minis-sessions/<id>/` or `media/<y>/<m>/<d>/<id>/` folder
 * whose session row is gone - left by deletes that removed the row but not
 * all the files (builds before the storage delete fixes, an interrupted
 * delete, a crash mid-delete). The storage list is built from the database,
 * so these were in no row and could not be cleared without clearing data.
 *
 * What a clean-up may remove, and nothing else:
 * - only real directories (not symlinks) whose name is a UUID, at exactly
 *   those two places - nothing else under filesDir is ever listed;
 * - only when no session row has that id (compared without case);
 * - never a session the app is using right now (streaming, open, held by a
 *   scheduled task, processing), row or no row;
 * - never one with anything modified within [MIN_AGE_MS]: a new chat's files
 *   can be written before its row, and for that window the folder looks
 *   exactly like an orphan. Real leftovers are old;
 * - a folder whose dates cannot be read is kept: "do not know" never means
 *   "delete".
 * The folders are listed BEFORE the session ids are read, so a session
 * created in between is in the id set.
 */
class OrphanedSessionFiles(private val storage: SessionStorage) {

    /** Every on-disk folder of one session id. */
    data class Folder(val id: String, val dirs: List<File>, val size: Long, val newestModifiedMs: Long?)

    data class Scan(val removable: List<Folder>, val kept: List<Folder>) {
        val removableBytes: Long get() = removable.sumOf { it.size }
        val keptBytes: Long get() = kept.sumOf { it.size }
        val totalBytes: Long get() = removableBytes + keptBytes
        val isEmpty: Boolean get() = removable.isEmpty() && kept.isEmpty()
    }

    data class Result(val removedCount: Int, val freedBytes: Long, val keptBytes: Long, val failedPaths: List<String>)

    /** Step 1: every UUID-named session folder on disk, grouped by id. */
    fun listFolders(): Map<String, List<File>> {
        val out = LinkedHashMap<String, MutableList<File>>()
        storage.sessionsRoot.listFiles()?.forEach { d ->
            if (isUuid(d.name) && isRealDir(d)) out.getOrPut(d.name) { mutableListOf() }.add(d)
        }
        // media/<yyyy>/<MM>/<dd>/<sessionId>: exactly four levels down.
        storage.mediaRoot.listFiles()?.filter { isRealDir(it) }?.forEach { y ->
            y.listFiles()?.filter { isRealDir(it) }?.forEach { m ->
                m.listFiles()?.filter { isRealDir(it) }?.forEach { day ->
                    day.listFiles()?.forEach { d ->
                        if (isUuid(d.name) && isRealDir(d)) out.getOrPut(d.name) { mutableListOf() }.add(d)
                    }
                }
            }
        }
        return out
    }

    /** Step 2, with the session ids read AFTER [listed]. */
    fun scan(listed: Map<String, List<File>>, knownIds: Set<String>, inUse: Set<String>, nowMs: Long): Scan {
        val known = knownIds.mapTo(HashSet()) { it.lowercase() }
        val busy = inUse.mapTo(HashSet()) { it.lowercase() }
        val removable = ArrayList<Folder>()
        val kept = ArrayList<Folder>()
        for ((id, dirs) in listed) {
            if (id.lowercase() in known || id.lowercase() in busy) continue
            val present = dirs.filter { it.exists() }
            if (present.isEmpty()) continue
            val newest = newestModified(present)
            val folder = Folder(id, present, present.sumOf { SessionStorage.directorySize(it) }, newest)
            if (newest == null || nowMs - newest < MIN_AGE_MS) kept.add(folder) else removable.add(folder)
        }
        return Scan(removable, kept)
    }

    /** Remove [scan]'s removable folders only, through the session delete path. */
    fun delete(scan: Scan): Result {
        var freed = 0L
        var removed = 0
        val failed = ArrayList<String>()
        for (f in scan.removable) {
            val r = storage.deleteFilesDetailed(f.id)
            freed += r.freed
            if (r.isComplete) removed++ else failed += r.failedPaths
        }
        return Result(removed, freed, scan.keptBytes, failed)
    }

    companion object {
        const val MIN_AGE_MS = 60 * 60 * 1000L

        private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun isUuid(name: String): Boolean = UUID_RE.matches(name)

        private fun isRealDir(f: File): Boolean = try {
            java.nio.file.Files.isDirectory(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            false
        }

        /** Newest modification time anywhere in [dirs] (symlinks not followed); null if any is unreadable. */
        fun newestModified(dirs: List<File>): Long? {
            var newest = 0L
            for (d in dirs) {
                val seq = d.walkTopDown().onEnter { it == d || !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                for (f in seq) {
                    val t = try {
                        java.nio.file.Files.getLastModifiedTime(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis()
                    } catch (_: Exception) {
                        return null
                    }
                    if (t <= 0L) return null
                    if (t > newest) newest = t
                }
            }
            // Nothing could be read at all: unknown, so keep.
            return newest.takeIf { it > 0L }
        }
    }
}
