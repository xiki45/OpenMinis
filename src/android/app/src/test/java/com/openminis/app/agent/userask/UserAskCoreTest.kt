package com.openminis.app.agent.userask

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-port-user-ask] The pure user_ask core, with the vectors of iOS
 * MinisTests/Standalone/UserAskCoreTests.swift (final, no-policy state).
 */
class UserAskCoreTest {

    private fun args(vararg qs: Map<String, Any?>): JSONObject =
        JSONObject().put("questions", JSONArray(qs.map { JSONObject(it) }))

    private fun q(m: Map<String, Any?>): UserAskQuestion = UserAskParser.parse(args(m)).getOrThrow().questions[0]

    private fun err(a: JSONObject): String = UserAskParser.parse(a).exceptionOrNull()?.message ?: "OK"

    private fun opts(vararg labels: String) = labels.map { mapOf("label" to it) }

    private val opt = q(mapOf("type" to "options", "question" to "Which?", "header" to "Pick",
        "options" to listOf(mapOf("label" to "A (Recommended)", "description" to "first"), mapOf("label" to "B")),
        "default" to 0, "multi_select" to false))
    private val optNoDefault = q(mapOf("type" to "options", "question" to "x", "options" to opts("A", "B")))
    private val optDefault1 = q(mapOf("type" to "options", "question" to "x", "options" to opts("A", "B"), "default" to 1))
    private val open = q(mapOf("type" to "options", "question" to "Name it?"))
    private val medCancel = q(mapOf("type" to "confirm", "question" to "x", "risk" to "medium", "default" to "cancel"))
    private val medApprove = q(mapOf("type" to "confirm", "question" to "x", "risk" to "medium", "default" to "approve"))
    private val highNone = q(mapOf("type" to "confirm", "question" to "x", "risk" to "high"))
    private val multi = q(mapOf("type" to "options", "question" to "x", "multi_select" to true, "options" to opts("A", "B", "C")))

    private val t0 = 1_000_000_000L

    // ── 1. Parsing and validation ──────────────────────────────────────────

    @Test fun `bad calls are rejected with a message that says how to fix them`() {
        assertTrue(err(JSONObject().put("questions", JSONArray())).contains("call user_ask again with 1-4 question objects, each with `type` and `question`"))
        assertTrue(err(args(*Array(5) { mapOf("type" to "options", "question" to "x") })).contains("At most 4"))
        assertTrue(err(args(mapOf("type" to "choice", "question" to "x"))).contains("\"options\" or \"confirm\""))
        assertTrue(err(args(mapOf("type" to "options", "question" to "x", "options" to opts("A")))).contains("at least 2"))
        assertTrue(err(args(mapOf("type" to "options", "question" to "x", "options" to (0..4).map { mapOf("label" to "L$it") }))).contains("at most 4"))
        assertTrue(err(args(mapOf("type" to "confirm", "question" to "rm x", "options" to opts("Y", "N")))).contains("takes no `options`"))
        assertTrue(err(args(mapOf("type" to "options", "question" to "x", "options" to opts("A", "B"), "default" to 5))).contains("0-based index"))
        assertTrue(err(args(mapOf("type" to "options"))).contains("question is required"))
        assertTrue(err(args(mapOf("type" to "options", "question" to "x", "options" to listOf(mapOf("description" to "no label"), mapOf("label" to "B"))))).contains("options[0].label is required"))
        assertTrue(err(args(mapOf("type" to "confirm", "question" to "x", "risk" to "extreme"))).contains("\"medium\" or \"high\""))
        assertTrue("not an object", err(JSONObject().put("questions", JSONArray().put("text"))).contains("must be an object"))
        assertTrue("unparseable json", UserAskParser.parse("{nope").isFailure)
    }

    // ── [T-android-user-ask-infer-type] missing / unknown type, inferred ────
    // The 9 checks of iOS UserAskCoreTests (021a988c1), plus Android's
    // string-encoded options.

    private val twoOpts = listOf(
        mapOf("label" to "我的 Mac", "description" to "SSH"),
        mapOf("label" to "HubSign", "description" to "3rd party"),
    )

    @Test fun `missing type + options infers options - the 2026-10-07 deepseek-flash call`() {
        // The call as sent: question, header, two labelled options, no type.
        val r = q(mapOf("question" to "用哪种方式签名？", "header" to "签名方式", "options" to twoOpts))
        assertEquals(UserAskQuestionType.OPTIONS, r.type)
        assertEquals(2, r.options?.size)
        assertEquals("HubSign", r.options?.get(1)?.label)
        assertEquals("签名方式", r.header)
    }

    @Test fun `unknown type value + options infers options`() {
        assertEquals(UserAskQuestionType.OPTIONS, q(mapOf("type" to "single_choice", "question" to "x", "options" to twoOpts)).type)
    }

    @Test fun `default without options is inferred as options, then validated as before`() {
        assertTrue(err(args(mapOf("question" to "x", "default" to "0"))).contains("0-based index"))
    }

    @Test fun `missing type + options + multi_select infers options and keeps multi`() {
        val r = q(mapOf("question" to "x", "options" to twoOpts, "multi_select" to true))
        assertEquals(UserAskQuestionType.OPTIONS, r.type)
        assertTrue(r.multiSelect)
    }

    @Test fun `missing type + impact and risk infers confirm`() {
        val r = q(mapOf("question" to "rm -rf build", "impact" to "deletes build output", "risk" to "high"))
        assertEquals(UserAskQuestionType.CONFIRM, r.type)
        assertEquals(UserAskRisk.HIGH, r.risk)
        assertEquals("deletes build output", r.impact)
    }

    @Test fun `missing type + risk only infers confirm`() {
        assertEquals(UserAskQuestionType.CONFIRM, q(mapOf("question" to "git push", "risk" to "medium")).type)
    }

    @Test fun `a bare question without type is still rejected - open ask vs approval is ambiguous`() {
        val e = err(args(mapOf("question" to "Name it?")))
        assertTrue(e, e.contains("type must be \"options\" or \"confirm\""))
        assertTrue("says when to use which", e.contains("confirm for approval of one action"))
    }

    @Test fun `options and confirm fields together without type are rejected`() {
        assertTrue(err(args(mapOf("question" to "x", "options" to twoOpts, "impact" to "y"))).contains("type must be"))
    }

    @Test fun `an explicit type is never overridden by inference`() {
        assertTrue(err(args(mapOf("type" to "confirm", "question" to "x", "options" to twoOpts))).contains("takes no `options`"))
    }

    @Test fun `string-encoded options count as options evidence (Android dialect)`() {
        val r = q(mapOf("question" to "x", "options" to JSONArray(twoOpts.map { JSONObject(it) }).toString()))
        assertEquals(UserAskQuestionType.OPTIONS, r.type)
        assertEquals(2, r.options?.size)
        // A string that is not a JSON array is no evidence: still the type error.
        assertTrue(err(args(mapOf("question" to "x", "options" to "not json"))).contains("type must be"))
    }

    @Test fun `a confirm's default and scope_key are ignored, never an error`() {
        assertEquals("OK", err(args(mapOf("type" to "confirm", "question" to "rm x", "default" to "approve", "scope_key" to "rm"))))
    }

    @Test fun `parsed fields`() {
        assertEquals("(Recommended) is stripped", "A", opt.options!![0].label)
        assertEquals(0, opt.defaultIndex)
        assertEquals("Pick", opt.header)
        assertTrue(open.isOpenEnded)
        val conf = q(mapOf("type" to "confirm", "question" to "git push --force", "impact" to "rewrites remote", "risk" to "high", "default" to "approve", "scope_key" to "git.push.force"))
        assertEquals(UserAskRisk.HIGH, conf.risk)
        assertNull(conf.defaultIndex)
        assertEquals("rewrites remote", conf.impact)
        assertEquals(UserAskRisk.MEDIUM, q(mapOf("type" to "confirm", "question" to "delete tmp", "default" to "cancel")).risk)
        assertFalse("multi_select needs options", q(mapOf("type" to "options", "question" to "x", "multi_select" to true)).multiSelect)
        assertEquals("a string index is accepted", 1, q(mapOf("type" to "options", "question" to "x", "options" to opts("A", "B"), "default" to "1")).defaultIndex)
        assertEquals("plain string options", listOf("P", "Q"), q(mapOf("type" to "options", "question" to "x", "options" to listOf("P", "Q"))).options!!.map { it.label })
        assertNull("impact only on confirm", q(mapOf("type" to "options", "question" to "x", "impact" to "ignored")).impact)
    }

    // ── 2. Timeout: the app takes no action ────────────────────────────────

    private fun to(question: UserAskQuestion) = UserAskResolver.encode(listOf(UserAskResolver.timedOut(question, 0, 5)))
    private val noteFor5 = "No answer within 5 min. Nothing was applied or approved."

    @Test fun `a timeout applies nothing and only suggests`() {
        assertEquals("""{"results":[{"default":"B","i":0,"note":"$noteFor5","status":"timeout"}]}""", to(optDefault1))
        assertTrue(to(opt).contains("\"default\":\"A\"") && to(opt).contains("\"status\":\"timeout\""))
        assertTrue("no default given: the recommended (first) option", to(optNoDefault).contains("\"default\":\"A\""))
        assertEquals(noteFor5, UserAskResolver.timedOut(opt, 0, 5).note)
        assertFalse("open-ended: no default", to(open).contains("default"))
        for (c in listOf(medApprove, medCancel, highNone)) {
            val s = to(c)
            assertTrue(s, s.contains("\"status\":\"timeout\"") && !s.contains("\"default\"") && !s.contains("\"answer\":"))
        }
        assertEquals("No answer within 7 min. Nothing was applied or approved.", UserAskResolver.timedOut(highNone, 0, 7).note)
    }

    // ── 3. Answers -> results ──────────────────────────────────────────────

    private fun a(question: UserAskQuestion, ans: UserAskAnswer) = UserAskResolver.answered(question, 0, ans)

    @Test fun `answers map to results`() {
        assertEquals(listOf("B"), a(opt, UserAskAnswer(selected = listOf(1), note = "not config.json")).answer)
        assertEquals("not config.json", a(opt, UserAskAnswer(selected = listOf(1), note = "not config.json")).note)
        assertEquals(UserAskResultStatus.OTHER, a(opt, UserAskAnswer(otherText = "backup first")).status)
        assertEquals(UserAskResultStatus.DELEGATED, a(opt, UserAskAnswer(delegated = true)).status)
        assertEquals(UserAskResultStatus.APPROVED, a(medCancel, UserAskAnswer(confirmChoice = UserAskConfirmChoice.APPROVE)).status)
        val deny = a(medCancel, UserAskAnswer(note = "use revert", confirmChoice = UserAskConfirmChoice.DENY))
        assertEquals(UserAskResultStatus.DENIED, deny.status)
        assertEquals("use revert", deny.note)
        assertEquals(listOf("A", "C", "D"), a(multi, UserAskAnswer(selected = listOf(0, 2), otherText = "D")).answer)
        assertEquals(1000, a(opt, UserAskAnswer(otherText = "x".repeat(5000))).answer!!.first().length)
        assertEquals("""{"results":[{"answer":"A","i":0,"status":"answered"}]}""",
            UserAskResolver.encode(listOf(a(opt, UserAskAnswer(selected = listOf(0))))))
        assertEquals("multi answers are an array", """{"answer":["A","C"],"i":0,"status":"answered"}""",
            a(multi, UserAskAnswer(selected = listOf(0, 2))).toJson())
        assertEquals("nothing picked reads as delegated", UserAskResultStatus.DELEGATED, a(opt, UserAskAnswer()).status)
        assertEquals("quotes escaped", """{"answer":"say \"hi\"","i":0,"status":"other"}""", a(opt, UserAskAnswer(otherText = "say \"hi\"")).toJson())
    }

    @Test fun `legacy statuses and choices read as today's values`() {
        assertEquals("timeout,timeout,timeout,timeout,approved,timeout,denied,nil",
            listOf("timeout_default", "timeout_abandoned", "timeout_cancelled", "timeout_approved", "approved_always", "timeout", "denied", "bogus")
                .joinToString(",") { UserAskResultStatus.parse(it)?.wire ?: "nil" })
        assertEquals(UserAskConfirmChoice.APPROVE, UserAskConfirmChoice.parse("approveAlways"))
    }

    @Test fun `stored results parse back for the card`() {
        val json = UserAskResolver.encode(listOf(
            a(multi, UserAskAnswer(selected = listOf(0, 2))),
            UserAskResolver.timedOut(opt, 1, 5),
        ))
        val parsed = UserAskResolver.parseResults(json)
        assertEquals(listOf(UserAskResultStatus.ANSWERED, UserAskResultStatus.TIMEOUT), parsed.map { it.status })
        assertEquals(listOf("A", "C"), parsed[0].answer)
        assertTrue(UserAskResolver.parseResults("Error: something").isEmpty())
        assertTrue(UserAskResolver.parseResults(null).isEmpty())
    }

    // ── 4. State machine ───────────────────────────────────────────────────

    @Test fun `deadline, interaction, first resolution wins`() {
        var p = PendingAsk.create("r1", "s", "tc1", listOf(opt, medCancel), t0, 5)
        assertEquals(300_000L, p.expiresAtMs!! - t0)
        assertFalse(p.isExpired(t0 + 299_000))
        assertTrue(p.isExpired(t0 + 300_000))
        p = p.noteInteraction(t0 + 200_000)
        assertEquals("any interaction restarts the countdown", 500_000L, p.expiresAtMs!! - t0)
        p = p.setAnswer(UserAskAnswer(selected = listOf(1)), 0)
        p = p.resolveAnswered(UserAskAnswerSource.APP)!!
        assertNull("second resolution is refused", p.resolveTimedOut())
        assertNull(p.resolveDismissed(UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE))
        assertEquals(UserAskState.ANSWERED, p.state)
        assertEquals(UserAskAnswerSource.APP, p.source)
        assertEquals("drafts cannot change a resolved ask", listOf(1), p.setAnswer(UserAskAnswer(selected = listOf(0)), 0).answers[0].selected)
    }

    @Test fun `a partial timeout keeps submitted answers and never sends a draft`() {
        var p = PendingAsk.create("r2", "s", "tc2", listOf(opt, optNoDefault, medApprove), t0, 5)
        p = p.setAnswer(UserAskAnswer(selected = listOf(1)), 0).copy(submitted = setOf(0))
        p = p.setAnswer(UserAskAnswer(selected = listOf(0)), 1)   // drafted, not submitted
        p = p.resolveTimedOut()!!
        assertEquals(listOf(UserAskResultStatus.ANSWERED, UserAskResultStatus.TIMEOUT, UserAskResultStatus.TIMEOUT), p.resultStatuses)
        assertFalse(p.resultJson!!.contains("\"answer\":\"A\""))
        assertEquals(UserAskAnswerSource.TIMEOUT, p.source)
    }

    @Test fun `stop cancels, a new message dismisses, zero minutes waits forever`() {
        val sup = PendingAsk.create("r3", "s", "tc3", listOf(opt), t0, 5).resolveDismissed(UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE)!!
        assertEquals(UserAskState.DISMISSED, sup.state)
        assertEquals(listOf(UserAskResultStatus.DISMISSED), sup.resultStatuses)
        val stop = PendingAsk.create("r4", "s", "tc4", listOf(opt), t0, 5).resolveDismissed(UserAskDismissReason.STOPPED)!!
        assertEquals(UserAskState.CANCELLED, stop.state)
        val forever = PendingAsk.create("r5", "s", "tc5", listOf(opt), t0, 0)
        assertNull(forever.expiresAtMs)
        assertFalse(forever.isExpired(t0 + 10_000_000_000L))
        assertEquals("no countdown to restart", forever, forever.noteInteraction(t0 + 5))
    }

    // ── 5. Wizard navigation ───────────────────────────────────────────────

    private fun f(o: UserAskSubmitOutcome) = when (o) {
        UserAskSubmitOutcome.Rejected -> "rejected"
        is UserAskSubmitOutcome.Advanced -> "adv${o.to}"
        UserAskSubmitOutcome.Complete -> "complete"
    }

    @Test fun `options, options, confirm - previous never submits and keeps the confirm undecided`() {
        assertEquals("false|true|true", (0..2).joinToString("|") { PendingAsk.showsPrevious(it).toString() })
        var w = PendingAsk.create("w", "s", "tw", listOf(opt, optNoDefault, medApprove), t0, 5)
        w = w.setAnswer(UserAskAnswer(selected = listOf(0)), 0)
        w.submit(0, t0 + 10_000).let { (n, o) -> w = n; assertEquals("adv1", f(o)) }
        assertEquals("incomplete is rejected", "rejected", f(w.submit(1, t0 + 20_000).second))
        w = w.setAnswer(UserAskAnswer(selected = listOf(1)), 1)
        w.submit(1, t0 + 30_000).let { (n, o) -> w = n; assertEquals("adv2", f(o)) }
        assertEquals(2, w.currentIndex)
        val tBack = t0 + 200_000
        w = w.goTo(1, tBack)!!
        assertEquals(1, w.currentIndex)
        assertEquals(setOf(0, 1), w.submitted)
        assertEquals(listOf(1), w.answers[1].selected)
        assertNull(w.answers[2].confirmChoice)
        assertEquals("Previous restarts the countdown", 300_000L, w.expiresAtMs!! - tBack)
        w = w.setAnswer(UserAskAnswer(selected = listOf(0)), 1)
        w.submit(1, t0 + 220_000).let { (n, o) -> w = n; assertEquals("adv2", f(o)) }
        assertEquals("confirm unanswered is rejected", "rejected", f(w.submit(2, t0 + 230_000).second))
        w = w.setAnswer(UserAskAnswer(confirmChoice = UserAskConfirmChoice.APPROVE), 2)
        w.submit(2, t0 + 240_000).let { (n, o) -> w = n; assertEquals("complete", f(o)) }
        w = w.resolveAnswered(UserAskAnswerSource.APP)!!
        assertNull("no navigation once resolved", w.goTo(0, t0 + 250_000))
        assertEquals("rejected", f(w.submit(0, t0 + 250_000).second))
        assertNull(w.goTo(7, t0))
    }

    @Test fun `next goes to index plus one, and only the last question jumps back to the first open one`() {
        var b = PendingAsk.create("b2", "s", "tb2", listOf(opt, optNoDefault, medApprove), t0, 5)
        b = b.setAnswer(UserAskAnswer(selected = listOf(0)), 0).submit(0, t0).first
        b = b.setAnswer(UserAskAnswer(selected = listOf(1)), 1).submit(1, t0).first
        b = b.goTo(1, t0)!!.goTo(0, t0)!!
        b.submit(0, t0).let { (n, o) -> b = n; assertEquals("adv1", f(o)) }
        b.submit(1, t0).let { (n, o) -> b = n; assertEquals("adv2", f(o)) }

        var j = PendingAsk.create("j", "s", "tj", listOf(opt, optNoDefault, opt), t0, 5)
        j = j.setAnswer(UserAskAnswer(selected = listOf(0)), 0).submit(0, t0).first
        j = j.goTo(2, t0)!!.setAnswer(UserAskAnswer(selected = listOf(0)), 2)
        j.submit(2, t0).let { (n, o) -> j = n; assertEquals("from the last question: back to the open one", "adv1", f(o)) }
        j = j.setAnswer(UserAskAnswer(selected = listOf(0)), 1)
        assertEquals("complete", f(j.submit(1, t0).second))
    }

    @Test fun `the recommended option is preselected by the state machine, only when untouched`() {
        val p = PendingAsk.create("p", "s", "tp", listOf(opt, multi, open, medApprove, optNoDefault), t0, 5)
        assertEquals(listOf(0), PendingAsk.preselectRecommended(p, 0).answers[0].selected)
        assertTrue("not for multi-select", PendingAsk.preselectRecommended(p, 1).answers[1].selected.isEmpty())
        assertTrue("not for open-ended", PendingAsk.preselectRecommended(p, 2).answers[2].selected.isEmpty())
        assertTrue("not for confirm", PendingAsk.preselectRecommended(p, 3).answers[3].selected.isEmpty())
        val touched = p.setAnswer(UserAskAnswer(selected = listOf(1)), 4)
        assertEquals("a choice the user made is kept", listOf(1), PendingAsk.preselectRecommended(touched, 4).answers[4].selected)
        val typed = p.setAnswer(UserAskAnswer(otherText = "mine"), 4)
        assertTrue(PendingAsk.preselectRecommended(typed, 4).answers[4].selected.isEmpty())
        assertEquals("preselection is not an interaction", p.expiresAtMs, PendingAsk.preselectRecommended(p, 0).expiresAtMs)
    }

    // ── 6. Prompt text and definition ──────────────────────────────────────

    @Test fun `prompt text is iOS's final version, English only, said once`() {
        val all = UserAskPromptText.TOOL_LIST_LINE + UserAskPromptText.TOOL_DESCRIPTION
        assertFalse("no CJK", all.any { it.code in 0x2E80..0x9FFF || it.code in 0xFF00..0xFFEF })
        assertEquals(1, all.split("ask with this tool rather than in plain text").size - 1)
        // [T-user-ask-prompt-converge] iOS 4715e2313: "wait for the answer" read
        // as "only ask while the user is watching"; the bar is for asking, not
        // for the card; a vague request is not an approval.
        assertFalse(all.contains("wait for the answer"))
        assertFalse(all.contains("prefer this over"))
        assertTrue(UserAskPromptText.TOOL_DESCRIPTION.contains("\n\nWhether to ask: only when the answer changes what you do next."))
        assertTrue(UserAskPromptText.TOOL_DESCRIPTION.contains("Not for reversible actions or ones the user explicitly asked for."))
        assertFalse(UserAskPromptText.TOOL_DESCRIPTION.contains("deferred"))
        for (w in listOf("Always allow", "always allow", "report", "scheduled", "worker")) assertFalse(w, all.contains(w))
        assertTrue(UserAskPromptText.TOOL_DESCRIPTION.startsWith("Ask the user questions on an in-chat card"))
        assertTrue(UserAskPromptText.TOOL_DESCRIPTION.endsWith("take that as the answer."))
        assertEquals("- user_ask: Ask the user questions on an in-chat card.", UserAskPromptText.TOOL_LIST_LINE)
    }

    @Test fun `the general rules no longer tell the model not to confirm`() {
        // [T-user-ask-prompt-converge] The "Tool call style" tail "...or asking
        // the user to confirm" contradicted user_ask's confirm rule. When to ask
        // lives only in the tool description, so the general rules name no tool.
        val vm = com.openminis.app.ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("- When a tool exists for an action, use it directly instead of explaining what you plan to do.\n"))
        assertFalse(vm.contains("asking the user to confirm"))
        val style = vm.substringAfter("Tool call style:\n").substringBefore("\n\n")
        assertFalse(style.contains("user_ask"))
    }

    @Test fun `the setting description states only what the app does`() {
        val cfg = com.openminis.app.ProductionSources.read("config/ConfigBuiltins.kt")
        val desc = cfg.substringAfter("path = \"agentTools.userAsk.enabled\"").substringBefore("prefs = prefs")
        assertFalse(desc.contains("wait for the answer"))
        // Nothing tells the model to avoid risky actions when the switch is off.
        assertFalse(desc.contains("told not to perform risky actions"))
        assertTrue(desc.contains("When off, the tool is removed."))
        // [T-user-ask-deferred] iOS's text: unwatched runs get the card in deferred mode.
        assertTrue(desc.contains("Never offered to sub agents. In a run you are not watching, the card stays open and your answer is sent as your next message."))
    }

    @Test fun `the description is byte-identical to iOS`() {
        val ios = com.openminis.app.ProductionSources.mainRoot()?.let { root ->
            var dir: java.io.File? = root
            repeat(8) { dir = dir?.parentFile }   // .../src/android/app/src/main/java/com/openminis/app -> .../src
            java.io.File(dir, "ios/Agent/Chat/UserAskCore.swift").takeIf { it.exists() }?.readText()
        } ?: return   // iOS sources absent (Android-only checkout)
        val block = ios.substringAfter("static let toolDescription = \"\"\"\n").substringBefore("\n    \"\"\"")
        val swift = block.lines().joinToString("\n") { it.removePrefix("    ") }
        assertEquals(swift, UserAskPromptText.TOOL_DESCRIPTION)
    }

    @Test fun `definition carries the nested questions schema`() {
        val d = UserAskPromptText.definition()
        assertEquals("user_ask", d.name)
        assertEquals(listOf("questions"), d.required)
        val p = d.parameters.getValue("questions").toJson()
        assertEquals("array", p.getString("type"))
        assertEquals(4, p.getInt("maxItems"))
        val item = p.getJSONObject("items")
        assertEquals(listOf("type", "question"), (0 until item.getJSONArray("required").length()).map { item.getJSONArray("required").getString(it) })
        val props = item.getJSONObject("properties")
        for (k in listOf("type", "question", "header", "options", "multi_select", "impact", "risk", "default")) assertTrue(k, props.has(k))
        assertEquals("label", props.getJSONObject("options").getJSONObject("items").getJSONArray("required").getString(0))
    }

    @Test fun `exposure - switch, sub agent, interactive`() {
        assertTrue(UserAskExposure.decision(enabled = true, isHelper = false, interactive = true).exposed)
        assertFalse(UserAskExposure.decision(enabled = false, isHelper = false, interactive = true).exposed)
        assertFalse(UserAskExposure.decision(enabled = true, isHelper = true, interactive = true).exposed)
        // [T-user-ask-deferred] A headless run now has the tool, in deferred mode.
        val headless = UserAskExposure.decision(enabled = true, isHelper = false, interactive = false)
        assertTrue(headless.exposed && headless.deferred)
        assertEquals(listOf(1, 2, 5, 10, 30, 60, 0), UserAskSettingsSpec.TIMEOUT_CHOICES)
        assertEquals(0, UserAskSettingsSpec.clampTimeout(-3))
        assertEquals(1440, UserAskSettingsSpec.clampTimeout(99_999))
        assertEquals("agent.tools.userAsk.enabled", UserAskSettingsSpec.ENABLED_KEY)
    }
}
