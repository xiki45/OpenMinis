package com.openminis.app.agent.userask

import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-port-user-ask-fix2] Pixel 4a round 3: a single-choice question with the
 * recommended option preselected, Other -> "Green", then Submit, answered
 * "Red, Green". Last action wins now: typed text replaces a pick, a pick
 * replaces typed text; multi-select still combines them.
 */
class UserAskFix2Test {

    @Before fun setUp() = UserAskCenter.resetForTest()
    @After fun tearDown() = UserAskCenter.resetForTest()

    private val single = UserAskQuestion(
        UserAskQuestionType.OPTIONS, "What's your favorite color?", header = "Favorite Color",
        options = listOf(UserAskOption("Red", "Like fire"), UserAskOption("Blue", "Like sky")),
    )
    private val multi = UserAskQuestion(
        UserAskQuestionType.OPTIONS, "Toppings?", multiSelect = true,
        options = listOf(UserAskOption("A"), UserAskOption("B"), UserAskOption("C")),
    )

    private fun begin(q: UserAskQuestion): String {
        UserAskCenter.begin(PendingAsk.create("r1", "s1", "tc1", listOf(q), 1_000L, 5), ownedByToolCall = false)
        return "r1"
    }

    private fun answer(rid: String) = UserAskCenter.asks.value[rid]!!.answers[0]

    /** What the card's Other dialog does on every keystroke (draft), commit or not. */
    private fun type(rid: String, q: UserAskQuestion, text: String) =
        UserAskCenter.updateAnswer(rid, 0, answer(rid).withOtherText(text, q))

    @Test fun `the device repro - typed Other replaces the preselected option even without Done`() {
        val rid = begin(single)
        assertEquals("Red starts preselected", listOf(0), answer(rid).selected)
        // Keystrokes only; the dialog is closed by back / tapping outside (no commit).
        for (t in listOf("G", "Gr", "Green")) type(rid, single, t)
        assertTrue("the pick is gone from the first keystroke", answer(rid).selected.isEmpty())
        UserAskCenter.submitQuestion(rid, 0)
        val r = UserAskResolver.parseResults(UserAskCenter.asks.value[rid]!!.resultJson)
        assertEquals(UserAskResultStatus.OTHER, r[0].status)
        assertEquals(listOf("Green"), r[0].answer)
    }

    @Test fun `the reverse - picking an option after typing drops the typed text`() {
        val rid = begin(single)
        type(rid, single, "Green")
        UserAskCenter.updateAnswer(rid, 0, answer(rid).withOption(1, single))
        assertNull(answer(rid).otherText)
        assertEquals(listOf(1), answer(rid).selected)
        UserAskCenter.submitQuestion(rid, 0)
        val r = UserAskResolver.parseResults(UserAskCenter.asks.value[rid]!!.resultJson)
        assertEquals(UserAskResultStatus.ANSWERED, r[0].status)
        assertEquals(listOf("Blue"), r[0].answer)
    }

    @Test fun `multi-select - typed text adds to the ticks, ticking keeps the text`() {
        val a0 = UserAskAnswer().withOption(0, multi).withOption(2, multi).withOtherText("D", multi)
        assertEquals(listOf(0, 2), a0.selected)
        val a1 = a0.withOption(1, multi).withOption(0, multi)
        assertEquals("toggle off / on", listOf(2, 1), a1.selected)
        assertEquals("D", a1.otherText)
        assertEquals(listOf("C", "B", "D").sorted(), UserAskResolver.answered(multi, 0, a1).answer!!.sorted())
    }

    @Test fun `clearing the text keeps whatever was picked, typing ends You decide`() {
        val picked = UserAskAnswer(selected = listOf(0)).withOtherText("", single)
        assertEquals(listOf(0), picked.selected)
        val delegated = UserAskAnswer(delegated = true).withOtherText("mine", single)
        assertFalse(delegated.delegated)
        assertEquals(UserAskResultStatus.OTHER, UserAskResolver.answered(single, 0, delegated).status)
    }

    @Test fun `backstop - a single-choice answer never combines a pick with typed text`() {
        val both = UserAskAnswer(selected = listOf(0), otherText = "Green")
        val e = UserAskResolver.answered(single, 0, both)
        assertEquals(UserAskResultStatus.OTHER, e.status)
        assertEquals(listOf("Green"), e.answer)
    }

    // ── UI source guards ────────────────────────────────────────────────────

    private val card = ProductionSources.read("ui/chat/UserAskCard.kt")

    @Test fun `the card applies the rules on every keystroke and every tap`() {
        val save = card.substringAfter("private fun saveInput(").substringBefore("\n}\n")
        assertTrue(save.contains("InputKind.OTHER -> cur.withOtherText(text, q)"))
        assertFalse("no commit-only clearing any more", save.contains("if (commit"))
        assertTrue(card.contains("UserAskCenter.updateAnswer(ask.requestId, index, answer.withOption(i, q))"))
    }

    @Test fun `the confirm command box uses the code block colours`() {
        assertTrue(card.contains("color = if (isConfirm) ChatColors.codeBlockText else ChatColors.primaryText,"))
        assertTrue(card.contains(".background(ChatColors.codeBlockBg)"))
    }

    @Test fun `the capped body fades at its lower edge`() {
        assertTrue(card.contains("if (bodyScroll.canScrollForward) {"))
        assertTrue(card.contains(".padding(bottom = 6.dp),"))
    }

    @Test fun `Question for you is localized wherever a tool title is drawn`() {
        assertTrue(card.contains("stringResource(R.string.user_ask_question_for_you)"))
        for (f in listOf("ui/chat/ChatComposerWidgets.kt", "ui/chat/ChatToolDetailUI.kt", "ui/chat/ChatAssistantMessageUI.kt")) {
            assertTrue(f, ProductionSources.read(f).contains("displayToolTitle(block)"))
        }
        val res = ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile.resolve("res")
        for (l in listOf("values-zh", "values-ja", "values-zh-rTW")) {
            val xml = res.resolve("$l/strings.xml").readText()
            val v = Regex("""<string name="user_ask_question_for_you">([^<]*)</string>""").find(xml)!!.groupValues[1]
            assertFalse("$l is translated", v == "Question for you")
        }
    }
}
