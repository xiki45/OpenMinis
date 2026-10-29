package com.openminis.app.sandbox

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * [T-android-pshell-utf8-split] Decodes a UTF-8 byte stream that arrives in
 * arbitrary chunks.
 *
 * PersistentShell reads the shell's output up to 4096 bytes at a time and used
 * to decode every read on its own (`String(buffer, 0, n, UTF_8)`). `read()`
 * boundaries fall anywhere, so a multi-byte character (3 bytes for CJK, 4 for
 * an emoji) cut by one came out as U+FFFD on both sides and the character was
 * lost. iOS fixed the same class of bug in bfdebc44b (GH #428).
 *
 * Here the bytes of an unfinished character at the end of a chunk are kept and
 * decoded together with the next chunk. Bytes that are invalid wherever the
 * cut falls still become U+FFFD, as before. [finish] reports what is left
 * when the stream ends in the middle of a character.
 *
 * Not thread-safe: one instance per stream, used from its reader thread.
 */
internal class Utf8StreamDecoder {

    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** The first bytes of a character whose rest has not been read yet (at most 3). */
    private var pending = ByteArray(0)

    /** The text complete so far; may be empty when [length] bytes only continue a character. */
    fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        val input = ByteBuffer.allocate(pending.size + length)
        input.put(pending).put(bytes, offset, length).flip()
        // UTF-8 never yields more chars than bytes (a replacement is one char
        // for at least one byte).
        val out = CharBuffer.allocate(input.remaining())
        decoder.decode(input, out, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        return out.flip().toString()
    }

    /** End of stream: an unfinished character becomes U+FFFD. Empty when nothing was pending. */
    fun finish(): String {
        val input = ByteBuffer.wrap(pending)
        val out = CharBuffer.allocate(pending.size + 1)
        decoder.decode(input, out, true)
        decoder.flush(out)
        decoder.reset()
        pending = ByteArray(0)
        return out.flip().toString()
    }
}
