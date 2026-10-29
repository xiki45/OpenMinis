package com.openminis.app.tools

/**
 * [T-batch-read-after-write] Which calls of one tool batch must wait for which
 * (iOS BatchToolOrder, 1eb3e2bef; Windows `writers_before`). A reader
 * (`read_image`, `file_read`) waits for every writer (`shell_execute`,
 * `file_write`, `file_edit`) that precedes it in the batch, because models
 * routinely write a file and read it in the same turn. Nothing else is
 * ordered: writers, browser calls, sub agents and readers with no writer
 * before them still start together.
 */
object BatchToolOrder {
    val WRITERS: Set<String> = setOf("shell_execute", FileWriteTool.NAME, FileEditTool.NAME)
    val READERS: Set<String> = setOf(ReadImageTool.NAME, FileReadTool.NAME)

    /** Indices of the writers the call at [index] must wait for (empty unless it is a reader). */
    fun writersBefore(index: Int, names: List<String>): List<Int> {
        if (index !in names.indices || names[index] !in READERS) return emptyList()
        return (0 until index).filter { names[it] in WRITERS }
    }
}
