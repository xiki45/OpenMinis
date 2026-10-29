package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.ui.chat.EmptyTurnFallback.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-b13-empty-fallback] An empty (or thinking-only) turn in a model group
 * moves to the next member instead of ending the run (iOS B13 f1d0c3beb,
 * 488fb0128, 1086b19a9; B15 wording 6ba931405).
 *
 * The agent loop needs a Context, Room and live providers, so the decision is
 * the pure [EmptyTurnFallback.route] and the walk below replays the loop's
 * empty-turn branch over a fake group with the same selection rule as
 * production (members after the current one in group order, minus every
 * member already spent); the wiring is pinned as source facts.
 */
class EmptyTurnFallbackTest {

    @Test
    fun `the route table`() {
        val d = FallbackStrategy.default
        val a = FallbackStrategy.always
        // strategy, inGroup, reminderDue, hasNext -> route
        val table = listOf(
            listOf(a, true, true, true) to Route.NEXT_MEMBER_WITH_REMINDER,
            listOf(a, true, false, true) to Route.NEXT_MEMBER,
            listOf(a, true, true, false) to Route.SAME_MODEL_REMINDER,
            listOf(a, true, false, false) to Route.GIVE_UP,
            listOf(d, true, true, true) to Route.SAME_MODEL_REMINDER,
            listOf(d, true, false, true) to Route.NEXT_MEMBER,
            listOf(d, true, true, false) to Route.SAME_MODEL_REMINDER,
            listOf(d, true, false, false) to Route.GIVE_UP,
            // A session pinned to one model never leaves it (as on iOS).
            listOf(a, false, true, true) to Route.SAME_MODEL_REMINDER,
            listOf(a, false, false, true) to Route.GIVE_UP,
            listOf(d, false, false, true) to Route.GIVE_UP,
        )
        table.forEach { (c, want) ->
            val got = EmptyTurnFallback.route(c[0] as FallbackStrategy, c[1] as Boolean, c[2] as Boolean, c[3] as Boolean)
            assertEquals(c.toString(), want, got)
        }
        assertTrue(EmptyTurnFallback.movesMember(Route.NEXT_MEMBER))
        assertTrue(EmptyTurnFallback.movesMember(Route.NEXT_MEMBER_WITH_REMINDER))
        assertFalse(EmptyTurnFallback.movesMember(Route.SAME_MODEL_REMINDER))
        assertFalse(EmptyTurnFallback.movesMember(Route.GIVE_UP))
    }

    @Test
    fun `the trail names a thinking-only reply as such`() {
        assertEquals("Empty response", EmptyTurnFallback.trailReason(reasoningOnly = false, afterReminder = false))
        assertEquals(
            "The model returned only its thinking — no reply and no tool call.",
            EmptyTurnFallback.trailReason(reasoningOnly = true, afterReminder = false),
        )
        assertEquals(
            "The model returned only its thinking after a tool result, even after a reminder — no reply and no tool call.",
            EmptyTurnFallback.trailReason(reasoningOnly = true, afterReminder = true),
        )
        assertTrue(EmptyTurnFallback.trailReason(reasoningOnly = false, afterReminder = true).startsWith("Empty response"))
        for (r in listOf(true, false)) for (a in listOf(true, false)) {
            assertFalse(EmptyTurnFallback.trailReason(r, a).contains("overloaded", ignoreCase = true))
        }
    }

    /** One request of the walk: which member answered and whether it carried the reminder. */
    private data class Req(val member: String, val reminder: Boolean)

    /**
     * Replays the loop's empty-turn branch. [answers] = members that reply with
     * content; everyone else comes back empty. The reminder is due on the
     * first empty turn of the run only (the once-per-run flag), and once
     * injected it stays in the history.
     */
    private fun walk(
        members: List<String>,
        strategy: FallbackStrategy,
        answers: Set<String>,
        inGroup: Boolean = true,
    ): Pair<List<Req>, Boolean> {
        val requests = mutableListOf<Req>()
        var current = members.first()
        var reminderSent = false
        val spent = mutableSetOf<String>()
        val tried = mutableSetOf(current)
        repeat(20) {
            requests += Req(current, reminderSent)
            if (current in answers) return requests to true
            spent += current
            val reminderDue = !reminderSent
            var route = EmptyTurnFallback.route(strategy, inGroup, reminderDue, hasNextMember = true)
            val idx = members.indexOf(current)
            val next = if (EmptyTurnFallback.movesMember(route)) {
                (1 until members.size).map { members[(idx + it) % members.size] }
                    .firstOrNull { it !in spent && it !in tried }
            } else null
            if (next == null && EmptyTurnFallback.movesMember(route)) {
                route = EmptyTurnFallback.route(strategy, inGroup, reminderDue, hasNextMember = false)
            }
            when (route) {
                Route.SAME_MODEL_REMINDER -> reminderSent = true
                Route.NEXT_MEMBER_WITH_REMINDER -> { reminderSent = true; current = next!!; tried += next }
                Route.NEXT_MEMBER -> { current = next!!; tried += next }
                Route.GIVE_UP -> return requests to false
            }
        }
        error("did not terminate: $requests")
    }

    @Test
    fun `always - A empty, B answers - A is asked once, the reminder goes to B`() {
        val (reqs, ok) = walk(listOf("A", "B"), FallbackStrategy.always, answers = setOf("B"))
        assertEquals(listOf(Req("A", false), Req("B", true)), reqs)
        assertTrue(ok)
    }

    @Test
    fun `always - every member empty - each asked once, then the hint`() {
        val (reqs, ok) = walk(listOf("A", "B", "C"), FallbackStrategy.always, answers = emptySet())
        assertEquals(listOf("A", "B", "C"), reqs.map { it.member })
        assertFalse(ok)
    }

    @Test
    fun `default - the reminder round stays on the same model, then the next member answers`() {
        val (reqs, ok) = walk(listOf("A", "B"), FallbackStrategy.default, answers = setOf("B"))
        assertEquals(listOf(Req("A", false), Req("A", true), Req("B", true)), reqs)
        assertTrue(ok)
    }

    @Test
    fun `default - every member empty - the walk ends`() {
        val (reqs, ok) = walk(listOf("A", "B", "C"), FallbackStrategy.default, answers = emptySet())
        assertEquals(listOf("A", "A", "B", "C"), reqs.map { it.member })
        assertFalse(ok)
    }

    @Test
    fun `one member, or a pinned model - the old reminder then hint`() {
        for (s in FallbackStrategy.values()) {
            assertEquals(listOf(Req("A", false), Req("A", true)), walk(listOf("A"), s, emptySet()).first)
            assertEquals(
                listOf(Req("A", false), Req("A", true)),
                walk(listOf("A", "B"), s, emptySet(), inGroup = false).first,
            )
        }
    }

    @Test
    fun `the walk starts after the current member and wraps`() {
        val (reqs, _) = walk(listOf("B", "C", "A"), FallbackStrategy.always, answers = setOf("A"))
        assertEquals(listOf("B", "C", "A"), reqs.map { it.member })
    }

    // ── production wiring ──────────────────────────────────────────────────

    private val loop by lazy {
        ProductionSources.read("ui/chat/ChatViewModel.kt").substringAfter("private suspend fun runAgentLoop(")
    }
    private val branch by lazy { loop.substringAfter("if (isEmptyTurn) {").substringBefore("val window = effectiveContextWindowTokens()") }

    @Test
    fun `the empty-turn branch routes through the group`() {
        assertTrue(branch.contains("EmptyTurnFallback.route(\n                        loopFallbackStrategy, inGroup, plan !is EmptyTurnRecovery.Plan.GiveUp, hasNextMember = true,"))
        assertTrue(branch.contains("val inGroup = _selectedGroupId.value != null"))
        // Never a member spent in this empty streak or failed in this error streak.
        assertTrue(branch.contains("it.entryId !in emptyStreakEntryIds && it.entryId !in triedEntryIds"))
        assertTrue(branch.contains("_activeEntryId.value?.let { emptyStreakEntryIds.add(it) }"))
        // The next member really serves the next request, with its own identity and prompt.
        val sw = branch.substringAfter("val next = emptyNext.provider")
        for (line in listOf(
            "currentProvider = next",
            "currentIdentity = providerIdentity(emptyNext.entryId)",
            "loopSystemPrompt = systemPromptFor(next, loopSystemPrompt)",
            "triedEntryIds.add(emptyNext.entryId)",
            "fallbackReasons.add(\"⚠️ \${currentProvider.model.displayName}: \$reason\")",
            "EmptyTurnFallback.trailReason(reasoningOnly, afterReminder = reminderAlreadySent)",
        )) assertTrue(line, sw.contains(line) || branch.contains(line))
        assertTrue(sw.indexOf("fallbackReasons.add(") < sw.indexOf("currentProvider = next"))
        assertTrue(sw.substringBefore("continue\n").isNotEmpty())
        // The same request goes to the next member without the empty assistant turn.
        assertTrue(branch.contains("agentHistory.removeAt(agentHistory.size - 1)\n                        }\n                        val next = emptyNext.provider"))
    }

    @Test
    fun `the empty streak is declared, cleared by content and by a user switch`() {
        assertTrue(loop.contains("val emptyStreakEntryIds = mutableSetOf<String>()"))
        assertTrue(
            loop.contains(
                "if (toolCalls.isNotEmpty() || EmptyTurnRecovery.turnHasText(turnText, allToolBlocks, turnStartBlockIndex)) {\n" +
                    "                emptyStreakEntryIds.clear()",
            ),
        )
        val userSwitch = loop.substringAfter("// A new model starts a new failure streak.").substringBefore("loopSystemPrompt = systemPromptFor(chosen")
        assertTrue(userSwitch.contains("emptyStreakEntryIds.clear()"))
    }

    @Test
    fun `the reminder is still once per run and still runs before the closing request`() {
        assertEquals(2, branch.split("didInjectEmptyToolReminder = true").size - 1)
        assertTrue(loop.indexOf("EmptyTurnRecovery.plan(") < loop.indexOf("if (closingRequestDue) {"))
    }
}
