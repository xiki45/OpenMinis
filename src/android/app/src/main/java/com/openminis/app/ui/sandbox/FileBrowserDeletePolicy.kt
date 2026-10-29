package com.openminis.app.ui.sandbox

import com.openminis.app.sandbox.SessionMounts
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * [T-filebrowser-delete-symlink-target] What deleting a row in Browse Files may
 * do, decided from the row's path in the Linux (PRoot) view, which is null when
 * the browser is not rooted in it (e.g. inside an external folder). Port of iOS
 * FileBrowserDeletePolicy (add538cd0).
 *
 * The old delete called `File.deleteRecursively()` on anything whose TARGET was
 * a directory. Its `walkBottomUp()` follows symlinks, so deleting a link to a
 * folder deleted the folder's contents, and a link nested anywhere inside a
 * deleted folder did the same to whatever it pointed at. Guest links resolve on
 * the host too: an absolute guest link such as `/sdcard/Download` names a real
 * host folder, and a relative one can climb out of the sandbox.
 */
internal object FileBrowserDeletePolicy {
    enum class Action {
        /** A regular file or folder: delete it, never following links inside it. */
        DELETE_ITEM,

        /** A symlink: delete the link only, never what it points to. */
        DELETE_LINK_ONLY,

        /**
         * A folder PRoot binds over. In a rootfs listing the row is the
         * placeholder the bind needs (PRoot skips a bind whose target is
         * missing), elsewhere it is the storage itself; neither is deleted here.
         */
        REFUSE_MOUNT_POINT,
    }

    /** Binds every PRoot launch adds (PRootKernel `-b` list). */
    private val PSEUDO_FS_MOUNT_POINTS = listOf("/dev", "/proc", "/sys")

    val FIXED_MOUNT_POINTS: Set<String> =
        (PSEUDO_FS_MOUNT_POINTS +
            SessionMounts.GLOBAL_SUBDIRS.map { "/var/minis/$it" } +
            SessionMounts.SESSION_SUBDIRS.map { "/var/minis/$it" }).toSet()

    fun action(linuxPath: String?, isSymlink: Boolean): Action {
        if (linuxPath != null && isMountPoint(linuxPath)) return Action.REFUSE_MOUNT_POINT
        return if (isSymlink) Action.DELETE_LINK_ONLY else Action.DELETE_ITEM
    }

    /** A fixed bind, or an external folder's root: /var/minis/mounts/<name>. */
    fun isMountPoint(linuxPath: String): Boolean {
        val parts = linuxPath.split('/').filter { it.isNotEmpty() }
        if ("/" + parts.joinToString("/") in FIXED_MOUNT_POINTS) return true
        return parts.size == 4 && parts[0] == "var" && parts[1] == "minis" && parts[2] == "mounts"
    }

    /** The row's Linux path: [dirLinuxPath] joined with [name], or null outside the Linux view. */
    fun rowLinuxPath(dirLinuxPath: String?, name: String): String? =
        dirLinuxPath?.let { "${it.trimEnd('/')}/$name" }

    /**
     * Carry out [action] on [file]. Returns false when something could not be
     * removed. Never call it with [Action.REFUSE_MOUNT_POINT].
     */
    fun delete(file: File, action: Action): Boolean {
        require(action != Action.REFUSE_MOUNT_POINT) { "mount points are not deleted" }
        val path = file.toPath()
        if (action == Action.DELETE_LINK_ONLY || Files.isSymbolicLink(path)) {
            // Files.delete does not follow the last component: the link goes.
            Files.deleteIfExists(path)
            return !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        }
        return deleteTreeNoFollow(path)
    }

    /**
     * Delete [root] and everything under it without following symlinks: a link
     * met on the way is unlinked as a link (the rule SessionStorage's session
     * cleanup already applies). Best effort, like deleteRecursively: keeps going
     * past failures and returns true only when nothing is left.
     */
    private fun deleteTreeNoFollow(root: Path): Boolean {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // Without FOLLOW_LINKS a link to a file or a folder lands here.
                        runCatching { Files.delete(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        runCatching { Files.delete(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.delete(dir) }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Exception) {
            // The existence check below is the verdict.
        }
        return !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }
}
