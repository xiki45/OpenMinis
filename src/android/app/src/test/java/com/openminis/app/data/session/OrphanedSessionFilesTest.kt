package com.openminis.app.data.session

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * [T-storage-orphan-cleanup] Only session folders that no conversation owns,
 * that nothing is using and that have been quiet for an hour are removed
 * (port of iOS 20e1825f6), on a real tree in a temp dir.
 */
class OrphanedSessionFilesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val live = "11111111-1111-4111-8111-111111111111"
    private val orphanOld = "22222222-2222-4222-8222-222222222222"
    private val orphanOld2 = "33333333-3333-4333-8333-333333333333"
    private val orphanNew = "44444444-4444-4444-8444-444444444444"
    private val inUse = "55555555-5555-4555-8555-555555555555"
    private val now = 1_800_000_000_000L
    private val old = now - 3 * 60 * 60 * 1000L

    private fun write(f: File, bytes: Int, mtime: Long) {
        f.parentFile.mkdirs(); f.writeBytes(ByteArray(bytes)); f.setLastModified(mtime)
    }

    private fun tree(): Pair<SessionStorage, File> {
        val root = tmp.newFolder("files")
        val s = SessionStorage(root)
        for (id in listOf(live, orphanOld, orphanNew, inUse)) write(File(s.sessionDir(id), "a.txt"), 100, old)
        write(File(s.sessionDir(orphanNew), "fresh.txt"), 10, now - 60_000L)
        write(File(s.mediaRoot, "2026/05/01/$orphanOld/img.jpg"), 1000, old)
        write(File(s.mediaRoot, "2026/05/02/$orphanOld2/img.jpg"), 500, old)
        write(File(s.mediaRoot, "2026/05/02/$live/img.jpg"), 500, old)
        // Never candidates: non-UUID folders, a loose file, a UUID-named file and symlink.
        write(File(s.sessionsRoot, "shared/x.txt"), 50, old)
        write(File(s.sessionsRoot, "__new__draft/x.txt"), 50, old)
        write(File(s.sessionsRoot, "66666666-6666-4666-8666-666666666666"), 50, old)
        val target = tmp.newFolder("elsewhere"); write(File(target, "keep.txt"), 50, old)
        Files.createSymbolicLink(File(s.sessionsRoot, "77777777-7777-4777-8777-777777777777").toPath(), target.toPath())
        // Directory mtimes go last: writing files above touched them.
        root.walkTopDown().filter { it.isDirectory }.forEach { it.setLastModified(old) }
        File(s.sessionDir(orphanNew), "fresh.txt").setLastModified(now - 60_000L)
        return s to target
    }

    @Test
    fun `only UUID-named real folders are listed, from both roots`() {
        val (s, _) = tree()
        val listed = OrphanedSessionFiles(s).listFolders()
        assertEquals(setOf(live, orphanOld, orphanOld2, orphanNew, inUse), listed.keys)
        assertEquals(2, listed.getValue(orphanOld).size)
    }

    @Test
    fun `live, in-use and recently changed folders are never removable`() {
        val (s, _) = tree()
        val o = OrphanedSessionFiles(s)
        val scan = o.scan(o.listFolders(), setOf(live.uppercase()), setOf(inUse), now)
        assertEquals(setOf(orphanOld, orphanOld2), scan.removable.map { it.id }.toSet())
        assertEquals(listOf(orphanNew), scan.kept.map { it.id })
        assertEquals(100L + 1000 + 500, scan.removableBytes)
        assertEquals(110L, scan.keptBytes)
    }

    @Test
    fun `delete removes exactly the removable folders`() {
        val (s, target) = tree()
        val o = OrphanedSessionFiles(s)
        val r = o.delete(o.scan(o.listFolders(), setOf(live), setOf(inUse), now))
        assertEquals(2, r.removedCount)
        assertEquals(1600L, r.freedBytes)
        assertFalse(s.sessionDir(orphanOld).exists())
        assertFalse(File(s.mediaRoot, "2026/05/02/$orphanOld2").exists())
        for (id in listOf(live, orphanNew, inUse)) assertTrue(id, s.sessionDir(id).exists())
        assertTrue(File(s.mediaRoot, "2026/05/02/$live/img.jpg").exists())
        assertTrue(File(s.sessionsRoot, "shared/x.txt").exists())
        assertTrue(File(s.sessionsRoot, "__new__draft/x.txt").exists())
        assertTrue("symlink target untouched", File(target, "keep.txt").exists())
    }

    @Test
    fun `unreadable dates mean keep`() {
        assertNull(OrphanedSessionFiles.newestModified(listOf(File(tmp.root, "missing"))))
        assertTrue(OrphanedSessionFiles.isUuid(live))
        assertFalse(OrphanedSessionFiles.isUuid("__new__$live"))
    }

    @Test
    fun `the page lists folders before reading ids and excludes sessions in use`() {
        val vm = ProductionSources.read("ui/settings/StorageUsageViewModel.kt")
        val scan = vm.substringAfter("private suspend fun scanOrphans()").substringBefore("fun previewOrphanCleanup")
        assertTrue(scan.indexOf("orphans.listFolders()") in 0 until scan.indexOf("repo.dao.listSessions()"))
        assertTrue(scan.contains("sessionsInUse()"))
        val full = vm.substringAfter("private fun scanAll()").substringBefore("UiState(")
        assertTrue(full.indexOf("orphans.listFolders()") in 0 until full.indexOf("repo.dao.listSessions()"))
        val clean = vm.substringAfter("fun cleanOrphans()").substringBefore("fun consumeOrphanResult")
        assertTrue("re-scans instead of trusting the preview", clean.contains("orphans.delete(scanOrphans())"))
        val preview = vm.substringAfter("fun previewOrphanCleanup").substringBefore("fun cleanOrphans")
        assertFalse("the preview deletes nothing", preview.contains("delete("))
    }
}
