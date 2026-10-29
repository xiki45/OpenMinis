package com.openminis.app.ui.sandbox

import android.app.Application
import com.openminis.app.ProductionSources
import com.openminis.app.ui.sandbox.FileBrowserDeletePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * [T-filebrowser-delete-symlink-target] Deleting a row in Browse Files that is
 * a symlink to a folder deleted the folder's contents (iOS add538cd0): the old
 * delete ran `File.deleteRecursively()`, whose walk follows links.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FileBrowserDeleteSymlinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A real folder holding one file, and a link to it in another folder. */
    private fun linkedTree(): Triple<File, File, File> {
        val target = tmp.newFolder("real")
        val precious = File(target, "precious.txt").apply { writeText("keep me") }
        val browsed = tmp.newFolder("browsed")
        val link = File(browsed, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        return Triple(target, precious, link)
    }

    private fun exists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    @Test
    fun `the old deleteRecursively on a link deletes what the link points to`() {
        val (target, precious, link) = linkedTree()
        link.deleteRecursively()
        // The reported bug, reproduced: the folder behind the link lost its file.
        assertFalse(precious.exists())
        assertTrue(target.isDirectory)
    }

    @Test
    fun `deleting a link removes only the link`() {
        val (target, precious, link) = linkedTree()
        assertTrue(FileBrowserDeletePolicy.delete(link, Action.DELETE_LINK_ONLY))
        assertFalse(exists(link))
        assertTrue(target.isDirectory)
        assertEquals("keep me", precious.readText())
    }

    @Test
    fun `a link to a file and a dangling link are removed as links`() {
        val file = tmp.newFile("a.txt").apply { writeText("a") }
        val dir = tmp.newFolder("d")
        val fileLink = File(dir, "f")
        Files.createSymbolicLink(fileLink.toPath(), file.toPath())
        val dangling = File(dir, "gone")
        Files.createSymbolicLink(dangling.toPath(), File(tmp.root, "missing").toPath())
        assertTrue(FileBrowserDeletePolicy.delete(fileLink, Action.DELETE_LINK_ONLY))
        assertTrue(FileBrowserDeletePolicy.delete(dangling, Action.DELETE_LINK_ONLY))
        assertEquals("a", file.readText())
        assertFalse(exists(fileLink))
        assertFalse(exists(dangling))
    }

    @Test
    fun `deleting a folder does not follow a link inside it`() {
        val outside = tmp.newFolder("outside")
        val kept = File(outside, "kept.txt").apply { writeText("x") }
        val folder = tmp.newFolder("folder")
        File(folder, "sub").mkdirs()
        File(folder, "sub/own.txt").writeText("own")
        Files.createSymbolicLink(File(folder, "sub/escape").toPath(), outside.toPath())
        assertTrue(FileBrowserDeletePolicy.delete(folder, Action.DELETE_ITEM))
        assertFalse(exists(folder))
        assertTrue(kept.exists())
    }

    @Test
    fun `a stale DELETE_ITEM on what is now a link still removes only the link`() {
        // The row's snapshot can predate the link; delete re-checks the path.
        val (target, precious, link) = linkedTree()
        assertTrue(FileBrowserDeletePolicy.delete(link, Action.DELETE_ITEM))
        assertFalse(exists(link))
        assertTrue(precious.exists() && target.isDirectory)
    }

    @Test
    fun `plain files and folders are still deleted`() {
        val f = tmp.newFile("plain.txt")
        val d = tmp.newFolder("plain")
        File(d, "x/y").mkdirs()
        File(d, "x/y/z.txt").writeText("z")
        assertTrue(FileBrowserDeletePolicy.delete(f, Action.DELETE_ITEM))
        assertTrue(FileBrowserDeletePolicy.delete(d, Action.DELETE_ITEM))
        assertFalse(f.exists())
        assertFalse(d.exists())
    }

    @Test
    fun `the policy table`() {
        val cases = listOf(
            // linuxPath, isSymlink -> action
            Triple("/var/minis/skills", false, Action.REFUSE_MOUNT_POINT),
            Triple("/var/minis/memory", true, Action.REFUSE_MOUNT_POINT),
            Triple("/var/minis/shared/", false, Action.REFUSE_MOUNT_POINT),
            Triple("//var//minis/mcp-servers", false, Action.REFUSE_MOUNT_POINT),
            Triple("/var/minis/workspace", false, Action.REFUSE_MOUNT_POINT),
            Triple("/var/minis/attachments", false, Action.REFUSE_MOUNT_POINT),
            Triple("/var/minis/offloads", false, Action.REFUSE_MOUNT_POINT),
            Triple("/var/minis/browser", false, Action.REFUSE_MOUNT_POINT),
            Triple("/proc", false, Action.REFUSE_MOUNT_POINT),
            Triple("/dev", false, Action.REFUSE_MOUNT_POINT),
            Triple("/sys", false, Action.REFUSE_MOUNT_POINT),
            Triple("/var/minis/mounts/Docs", false, Action.REFUSE_MOUNT_POINT),
            Triple("/var/minis/mounts/Docs", true, Action.REFUSE_MOUNT_POINT),
            // Inside a bind: ordinary rows.
            Triple("/var/minis/skills/foo", false, Action.DELETE_ITEM),
            Triple("/var/minis/workspace/out.png", false, Action.DELETE_ITEM),
            Triple("/var/minis/workspace/link", true, Action.DELETE_LINK_ONLY),
            Triple("/var/minis/mounts/Docs/a.txt", false, Action.DELETE_ITEM),
            Triple("/var/minis/mounts", false, Action.DELETE_ITEM),
            Triple("/var/minis", false, Action.DELETE_ITEM),
            Triple("/proc2", false, Action.DELETE_ITEM),
            Triple("/root/link", true, Action.DELETE_LINK_ONLY),
            // Outside the Linux view (e.g. an external folder's own browser).
            Triple(null, false, Action.DELETE_ITEM),
            Triple(null, true, Action.DELETE_LINK_ONLY),
        )
        cases.forEach { (path, link, want) ->
            assertEquals("$path link=$link", want, FileBrowserDeletePolicy.action(path, link))
        }
    }

    @Test
    fun `row linux paths join the shown directory and the name`() {
        assertEquals("/proc", FileBrowserDeletePolicy.rowLinuxPath("", "proc"))
        assertEquals("/var/minis/skills", FileBrowserDeletePolicy.rowLinuxPath("/var/minis", "skills"))
        assertEquals("/var/minis/x", FileBrowserDeletePolicy.rowLinuxPath("/var/minis/", "x"))
        assertNull(FileBrowserDeletePolicy.rowLinuxPath(null, "x"))
    }

    private fun idleUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `the view model deletes only the link and refuses a bind target`() {
        // A rootfs-shaped tree browsed with its Linux paths, like Browse Files.
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "var/minis/skills").mkdirs()
        val target = tmp.newFolder("userdata")
        val precious = File(target, "precious.txt").apply { writeText("keep me") }
        val root = File(rootfs, "root").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "link").toPath(), target.toPath())

        val atRoot = FileBrowserViewModel(rootPath = rootfs, initialPath = root, displayLinuxPrefix = "/")
        idleUntil { atRoot.uiState.value.items.isNotEmpty() }
        val linkRow = atRoot.uiState.value.items.single { it.name == "link" }
        assertTrue(linkRow.isSymlink && linkRow.isDirectory)
        atRoot.deleteItem(linkRow)
        idleUntil { !exists(File(root, "link")) }
        assertFalse(exists(File(root, "link")))
        assertEquals("keep me", precious.readText())

        val atVarMinis = FileBrowserViewModel(
            rootPath = rootfs,
            initialPath = File(rootfs, "var/minis"),
            displayLinuxPrefix = "/",
        )
        idleUntil { atVarMinis.uiState.value.items.isNotEmpty() }
        val skills = atVarMinis.uiState.value.items.single { it.name == "skills" }
        atVarMinis.deleteItem(skills)
        assertEquals("skills", atVarMinis.uiState.value.refusedMountPointName)
        assertTrue(File(rootfs, "var/minis/skills").isDirectory)
        atVarMinis.dismissError()
        assertNull(atVarMinis.uiState.value.refusedMountPointName)
    }

    @Test
    fun `production wiring`() {
        val vm = ProductionSources.read("ui/sandbox/FileBrowserViewModel.kt")
        val delete = vm.substringAfter("fun deleteItem(item: FileItem)").substringBefore("fun dismissError()")
        assertTrue(delete.contains("FileBrowserDeletePolicy.action("))
        assertTrue(delete.contains("FileBrowserDeletePolicy.delete(item.file, action)"))
        assertFalse("deleteRecursively follows links", delete.contains(".deleteRecursively()"))

        val screen = ProductionSources.read("ui/sandbox/FileBrowserScreen.kt")
        assertTrue(screen.contains("R.string.filebrowser_delete_mount_point_refused"))

        // Browse Files from Rootfs settings names rows by their Linux path.
        val nav = ProductionSources.read("ui/navigation/AppNavigation.kt")
        val rootfsBrowse = nav.substringAfter("composable(Routes.ROOTFS_MANAGEMENT)").substringBefore("onMirrorCategoryClick")
        assertTrue(rootfsBrowse.contains("displayLinuxPrefix = \"/\""))

        // The fixed binds are the ones PRootKernel registers and SessionMounts mounts.
        val kernel = ProductionSources.read("sandbox/PRootKernel.kt")
        assertTrue(kernel.contains("cmd.add(\"/dev\")") && kernel.contains("cmd.add(\"/proc\")") && kernel.contains("cmd.add(\"/sys\")"))
        assertTrue(kernel.contains("listOf(\"memory\", \"skills\", \"shared\", \"mcp-servers\")"))
        assertEquals(
            listOf("memory", "skills", "shared", "mcp-servers"),
            com.openminis.app.sandbox.SessionMounts.GLOBAL_SUBDIRS,
        )
    }

    @Test
    fun `the refusal message is in every locale`() {
        val res = ProductionSources.mainRoot()!!.resolve("../../../../res").canonicalFile
        val dirs = res.listFiles()!!.filter { it.name.startsWith("values") && File(it, "strings.xml").exists() }
        assertTrue(dirs.size >= 20)
        dirs.forEach { dir ->
            val f = File(dir, "strings_file_browser_delete.xml")
            assertTrue("${dir.name} missing", f.exists())
            assertTrue(f.readText().contains("name=\"filebrowser_delete_mount_point_refused\""))
            assertTrue("${dir.name} placeholder", f.readText().contains("%1\$s"))
        }
    }
}
