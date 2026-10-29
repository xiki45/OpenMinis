package com.openminis.app

import java.io.File

/**
 * Locates iOS Swift sources from an Android unit test, for parity guards that
 * compare a ported value or wire shape against its iOS declaration.
 *
 * Guards look a declaration up by its text, not by a file path: iOS files get
 * split and renamed (f492946c7 moved ChatSession, RawMessage, CompactMarker and
 * ChatFolder out of ChatStore.swift), and a hard-coded path then fails with
 * "declaration not found" although nothing about the invariant changed.
 *
 * Only app sources are scanned: MinisTests (which quote declarations in
 * strings) and build products are skipped.
 */
object IosSources {

    /** The repository's `src/ios` directory, or null outside a full checkout. */
    val root: File? by lazy {
        var dir: File? = ProductionSources.mainRoot() ?: File("").absoluteFile
        while (dir != null && !File(dir, "src/ios").isDirectory) dir = dir.parentFile
        dir?.let { File(it, "src/ios") }
    }

    /** Every app `.swift` file under `src/ios`. */
    val swiftFiles: List<File> by lazy {
        val r = root ?: error("src/ios not found from ${File("").absolutePath}")
        r.walkTopDown()
            .onEnter { it.name != "MinisTests" && it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "swift" }
            .toList()
    }

    private val texts = HashMap<File, String>()

    fun text(f: File): String = synchronized(texts) { texts.getOrPut(f) { f.readText() } }

    /**
     * The source of the ONE app file that contains [declaration] (e.g.
     * `"struct ChatSession: "`). Fails when it is missing or ambiguous, so a
     * guard can never silently read the wrong file.
     */
    fun declaring(declaration: String): String {
        val hits = swiftFiles.filter { text(it).contains(declaration) }
        require(hits.isNotEmpty()) { "declaration not found in src/ios: $declaration" }
        require(hits.size == 1) { "declaration is ambiguous in src/ios: $declaration in ${hits.map { it.name }}" }
        return text(hits.single())
    }

    /** Whether any app file matches [pattern]. */
    fun anyMatches(pattern: Regex): Boolean = swiftFiles.any { pattern.containsMatchIn(text(it)) }
}
