package com.openminis.app.scheduled

import com.openminis.app.ProductionSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [OpenMinis#445] `minis-scheduled create --model` accepts the same forms as
 * `minis-config set session.primaryModel` (`entry:<id>`, `group:<id>`), a bare
 * entry id (`<instanceId>/<modelId>`), and a bare model id only when exactly
 * one visible entry serves it. Anything else fails at create time instead of
 * being stored verbatim and silently falling back to the default model.
 */
class ScheduledModelSourceTest {

    private val oauth = ScheduledModelSource.EntryRef("inst-oauth/claude-opus", "claude-opus", visible = true)
    private val apiKey = ScheduledModelSource.EntryRef("inst-key/claude-opus", "claude-opus", visible = true)
    private val flash = ScheduledModelSource.EntryRef("inst-key/flash", "flash", visible = true)
    private val hidden = ScheduledModelSource.EntryRef("inst-key/old", "old", visible = false)
    private val entries = listOf(oauth, apiKey, flash, hidden)
    private val groups = listOf(ScheduledModelSource.GroupRef("g1"), ScheduledModelSource.GroupRef("g-empty"))

    private fun resolve(raw: String) = ScheduledModelSource.resolve(raw, entries, groups)

    private fun assertRejected(raw: String, vararg contains: String) {
        try {
            val r = resolve(raw)
            fail("expected rejection for '$raw', got $r")
        } catch (e: IllegalArgumentException) {
            for (c in contains) assertTrue("message was: ${e.message}", e.message!!.contains(c))
        }
    }

    private fun entryIdOf(binding: String) = JSONObject(binding).let {
        assertEquals("entry", it.getString("type")); it.getString("entryId")
    }

    @Test
    fun `entry prefix pins that exact entry, even when another instance serves the same model`() {
        val r = resolve("entry:inst-key/claude-opus")
        assertEquals("inst-key/claude-opus", entryIdOf(r.bindingJson))
        assertEquals("claude-opus", r.modelId)
        assertEquals("entry:inst-key/claude-opus", r.display)
    }

    @Test
    fun `a bare entry id is the same as entry prefix`() {
        assertEquals(resolve("entry:inst-oauth/claude-opus"), resolve("inst-oauth/claude-opus"))
    }

    @Test
    fun `a hidden entry named exactly is still accepted`() {
        assertEquals("inst-key/old", entryIdOf(resolve("entry:inst-key/old").bindingJson))
    }

    @Test
    fun `a bare model id served by one visible entry pins that entry`() {
        val r = resolve("flash")
        assertEquals("inst-key/flash", entryIdOf(r.bindingJson))
        assertEquals("flash", r.modelId)
    }

    @Test
    fun `a bare model id served by two instances is refused and lists both entry ids`() {
        assertRejected("claude-opus", "2 model entries", "entry:inst-oauth/claude-opus", "entry:inst-key/claude-opus")
    }

    @Test
    fun `a bare model id only a hidden entry serves is not guessed`() {
        assertRejected("old", "minis-config get models")
    }

    @Test
    fun `group prefix binds the group and leaves the model to the group`() {
        val r = resolve("group:g1")
        val o = JSONObject(r.bindingJson)
        assertEquals("group", o.getString("type"))
        assertEquals("g1", o.getString("groupId"))
        assertNull(r.modelId)
        assertEquals("group:g1", r.display)
    }

    @Test
    fun `unknown ids are refused with a pointer to minis-config`() {
        assertRejected("entry:nope/x", "nope/x", "minis-config get models")
        assertRejected("group:nope", "nope")
        assertRejected("no-such-model", "no-such-model", "minis-config get models")
        assertRejected("entry:", "empty")
        assertRejected("group:", "empty")
        assertRejected("   ", "empty")
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(resolve("entry:inst-key/flash"), resolve("  entry:inst-key/flash "))
    }

    @Test
    fun `display reads back a stored binding in the CLI's own words`() {
        assertEquals("entry:a/b", ScheduledModelSource.display("""{"type":"entry","entryId":"a/b"}""", "b"))
        assertEquals("group:g1", ScheduledModelSource.display("""{"type":"group","groupId":"g1"}""", null))
        assertEquals("legacy-model", ScheduledModelSource.display(null, "legacy-model"))
        assertNull(ScheduledModelSource.display(null, null))
    }

    // A task created by an older build stored --model verbatim in modelId.
    // When it fires, a value that names an entry or group is honoured; a bare
    // model id keeps its old path (no binding) so nothing that worked changes.
    @Test
    fun `a legacy task whose modelId names an entry or group heals at fire time`() {
        assertEquals("inst-key/claude-opus", entryIdOf(ScheduledModelSource.healLegacy("entry:inst-key/claude-opus", entries, groups)!!.bindingJson))
        assertEquals("inst-key/claude-opus", entryIdOf(ScheduledModelSource.healLegacy("inst-key/claude-opus", entries, groups)!!.bindingJson))
        assertEquals("g1", JSONObject(ScheduledModelSource.healLegacy("group:g1", entries, groups)!!.bindingJson).getString("groupId"))
        assertNull("bare model id keeps the old path", ScheduledModelSource.healLegacy("flash", entries, groups))
        assertNull("ambiguous bare id keeps the old path", ScheduledModelSource.healLegacy("claude-opus", entries, groups))
        assertNull("unknown entry keeps the old path", ScheduledModelSource.healLegacy("entry:gone/x", entries, groups))
    }

    @Test
    fun `create stores the resolved binding, not the raw flag`() {
        val src = ProductionSources.read("sandbox/offload/ScheduledTaskOffloadHandler.kt")
        assertFalse("modelId must not be the raw --model value", src.contains("modelId = args.get(\"model\", \"m\")"))
        assertTrue(src.contains("ScheduledModelSource.resolve("))
        assertTrue("--model is refused for follow-up/rerun", src.contains("--model only applies to --target new"))
        assertTrue("help names the accepted forms", src.contains("--model entry:<id>|group:<id>|<modelId>"))
        val runner = ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        assertTrue("legacy tasks heal at fire time", runner.contains("ScheduledModelSource.healLegacy("))
    }
}
