package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tool-duration-persist] The folded "Ran N tools" row and each tool pill
 * lost their time after any reload: AssistantBlock.durationMs was set only in
 * memory during the live run, never written with the result and never read
 * back. It is now persisted as iOS's `snapshot.duration` (seconds) and
 * restored in ms.
 */
class ToolDurationPersistTest {

    /** The toolResult part exactly as ChatViewModel.persistToolResultMessage writes it (guarded below). */
    private fun persistedPart(id: String, output: String, durationMs: Long?): String {
        val q = { s: String -> JSONObject.quote(s) }
        return """{"type":"toolResult","value":{"toolUseId":${q(id)},"name":"shell_execute","output":${q(output)},"success":true,"snapshot":{"type":"text","text":${q(output)}${ToolDurationWire.snapshotField(durationMs)}}}}"""
    }

    private fun reload(partsJson: String): Map<String, Long> {
        val arr = JSONArray(partsJson)
        return (0 until arr.length()).associate { i ->
            val v = arr.getJSONObject(i).getJSONObject("value")
            v.getString("toolUseId") to ToolDurationWire.restoreMs(v)
        }
    }

    @Test fun `round trip - a live 2300 ms call reloads as 2300 ms, written in iOS seconds`() {
        val row = "[" + persistedPart("tu1", "ok", 2300) + "]"
        val snapshot = JSONArray(row).getJSONObject(0).getJSONObject("value").getJSONObject("snapshot")
        assertEquals("iOS key and unit (seconds)", 2.3, snapshot.getDouble("duration"), 1e-9)
        assertEquals("text", snapshot.getString("type"))
        assertEquals(2300L, reload(row)["tu1"])
        for (ms in listOf(1L, 999L, 61_234L, 3_600_000L)) {
            assertEquals(ms, reload("[" + persistedPart("t", "x", ms) + "]")["t"])
        }
    }

    @Test fun `fold header after a reload - Ran 3 tools with the summed time`() {
        val row = "[" + listOf(
            persistedPart("a", "1", 1200), persistedPart("b", "2", 800), persistedPart("c", "3", 7000),
        ).joinToString(",") + "]"
        val restored = reload(row)
        val blocks = listOf("a", "b", "c").map {
            AssistantBlock(id = it, kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS, toolName = "shell_execute", durationMs = restored.getValue(it))
        } + AssistantBlock(id = "r", kind = "text", content = "Done.\nAll good.")
        val run = ToolFoldPlanner.foldedRuns(blocks).single()
        assertEquals(3, run.group.toolCount)
        assertEquals(9000L, run.durationMs)
        assertEquals("9s", formatToolDuration(run.durationMs!!))
    }

    @Test fun `old and unrecorded rows give 0 - no badge, no crash, nothing invented`() {
        assertEquals("", ToolDurationWire.snapshotField(null))
        assertEquals("", ToolDurationWire.snapshotField(0))
        assertEquals("", ToolDurationWire.snapshotField(-5))
        assertFalse(persistedPart("t", "x", null).contains("duration"))
        // A row written before this change.
        assertEquals(0L, reload("""[{"type":"toolResult","value":{"toolUseId":"t","output":"x","success":true,"snapshot":{"type":"text","text":"x"}}}]""")["t"])
        // Even older: no snapshot at all.
        assertEquals(0L, reload("""[{"type":"toolResult","value":{"toolUseId":"t","output":"x","success":true}}]""")["t"])
        for (bad in listOf("null", "0", "-1.5", "\"abc\"", "{}")) {
            val row = """[{"type":"toolResult","value":{"toolUseId":"t","output":"x","success":true,"snapshot":{"type":"text","duration":$bad}}}]"""
            assertEquals("duration=$bad", 0L, reload(row)["t"])
        }
        assertEquals(0L, ToolDurationWire.restoreMs(null))
        // No time recorded on reload: the fold shows none (unchanged behaviour).
        val blocks = listOf(
            AssistantBlock(id = "a", kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS, toolName = "shell_execute", durationMs = 0),
            AssistantBlock(id = "b", kind = "tool_use", toolStatus = ToolBlockStatus.SUCCESS, toolName = "shell_execute", durationMs = 0),
            AssistantBlock(id = "r", kind = "text", content = "Done.\nAll good."),
        )
        assertEquals(null, ToolFoldPlanner.foldedRuns(blocks).single().durationMs)
    }

    @Test fun `an iOS row carries its duration to Android`() {
        // ToolResult as iOS's synthesized Codable writes it (ChatStoreModels ToolSnapshot.duration: TimeInterval?).
        val ios = """[{"type":"toolResult","value":{"toolUseId":"toolu_01","output":"done","success":true,"mediaRef":null,"pageURL":null,"status":"success",
            "snapshot":{"type":"text","text":"done","mediaRef":null,"duration":9.4217}}}]"""
        assertEquals(9422L, reload(ios)["toolu_01"])
        // An integral TimeInterval may be encoded without a fraction.
        assertEquals(3000L, reload(ios.replace("9.4217", "3"))["toolu_01"])
    }

    @Test fun `the wire matches iOS's declaration`() {
        val ios = com.openminis.app.IosSources.declaring("struct ToolSnapshot: ")
        assertTrue(ios.contains("let duration: TimeInterval?  // Execution duration in seconds"))
        assertEquals("duration", ToolDurationWire.KEY)
    }

    @Test fun `ChatViewModel persists the measured time and restores it onto the block`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("persistToolResultMessage(resultParts, blockMeta.mapValues { it.value.durationMs })"))
        // [T-image-real-tests] persistToolResultMessage builds its parts JSON through
        // ChatPartsJson.toolResultPartsJson (same file); the template is checked there.
        // [T-ctx-builder] ...through ToolResultWrap.persist (same file), which the agent loop's
        // persistToolResultMessage delegates to with the repository as writer.
        assertTrue(vm.substringAfter("private suspend fun persistToolResultMessage(").substringBefore("\n    private ")
            .contains("ToolResultWrap.persist(parts, durationsMs, { realSessionId.ifEmpty { sessionId } })"))
        assertTrue(vm.substringAfter("internal object ToolResultWrap {").substringBefore("\n}\n")
            .contains("val partsJson = ChatPartsJson.toolResultPartsJson(results, durationsMs)"))
        val persist = vm.substringAfter("    fun toolResultPartsJson(").substringBefore("\n    }\n")
        assertTrue(
            "same template as persistedPart() above",
            persist.contains(""""success":${'$'}{!result.isError},"snapshot":{"type":"text","text":${'$'}snapshotText${'$'}{ToolDurationWire.snapshotField(durationsMs[result.id])}}}}"""),
        )
        val load = vm.substringAfter("private fun List<MessageEntity>.toChatMessages()").substringBefore("\n    }\n\n")
        assertTrue(load.contains("durationMs = ToolDurationWire.restoreMs(value),"))
        assertTrue(load.contains("durationMs = result?.durationMs ?: 0L,"))
    }
}
