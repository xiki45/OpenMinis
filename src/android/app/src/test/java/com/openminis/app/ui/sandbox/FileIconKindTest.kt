package com.openminis.app.ui.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-file-row-polish] The row tile's kind by extension, as iOS `FileIconKind`
 * (0340fd920): any case, folders win, unknown is OTHER.
 */
class FileIconKindTest {

    @Test
    fun `every category maps like iOS`() {
        val expect = mapOf(
            FileIconKind.CODE to listOf("swift", "py", "js", "ts", "kt", "rs", "go", "html", "json", "yaml", "toml"),
            FileIconKind.SHELL to listOf("sh", "bash", "zsh", "fish"),
            FileIconKind.IMAGE to listOf("png", "jpg", "jpeg", "gif", "svg", "webp", "heic", "ico"),
            FileIconKind.AUDIO to listOf("mp3", "wav", "flac", "m4a", "ogg", "opus"),
            FileIconKind.VIDEO to listOf("mp4", "mov", "mkv", "webm", "avi", "m4v"),
            FileIconKind.TEXT to listOf("txt", "md", "markdown", "log", "csv", "rtf", "doc", "docx"),
            FileIconKind.PDF to listOf("pdf"),
            FileIconKind.ARCHIVE to listOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst"),
            FileIconKind.OTHER to listOf("", "bin", "so", "dmg"),
        )
        for ((kind, exts) in expect) {
            for (e in exts) assertEquals("extension '$e'", kind, FileIconKind.of(e, isDirectory = false))
        }
    }

    @Test
    fun `case does not matter and folders win`() {
        assertEquals(FileIconKind.IMAGE, FileIconKind.of("PNG", isDirectory = false))
        assertEquals(FileIconKind.SHELL, FileIconKind.of("Sh", isDirectory = false))
        assertEquals(FileIconKind.FOLDER, FileIconKind.of("png", isDirectory = true))
        assertEquals(FileIconKind.FOLDER, FileIconKind.of("", isDirectory = true))
    }
}
