package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.agent.userask.UserAskAnswer
import com.openminis.app.agent.userask.UserAskOption
import com.openminis.app.agent.userask.UserAskQuestion
import com.openminis.app.agent.userask.UserAskQuestionType
import com.openminis.app.agent.userask.UserAskResolver
import com.openminis.app.agent.userask.UserAskResultStatus
import com.openminis.app.agent.userask.UserAskRisk
import com.openminis.app.ui.chat.UserAskTileLine.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-user-ask-tile] The user_ask mini preview tile is a miniature of
 * the card (iOS T-user-ask-tile-rich), not a wrench.
 */
class UserAskTileTest {

    private val size = UserAskQuestion(
        UserAskQuestionType.OPTIONS, "Which size?", header = "Size",
        options = listOf(UserAskOption("Small"), UserAskOption("Medium"), UserAskOption("Large")),
    )
    private val confirm = UserAskQuestion(
        UserAskQuestionType.CONFIRM, "rm -rf build/", impact = "deletes\nbuild output", risk = UserAskRisk.HIGH,
    )
    private val open = UserAskQuestion(UserAskQuestionType.OPTIONS, "Branch name?")

    @Test fun `pending options show title, header, progress and options with the pick`() {
        val c = UserAskTileModel.pendingTile(listOf(open, size), 1, UserAskAnswer(selected = listOf(1)))
        assertEquals(UserAskTileTitle.NEEDS_ANSWER, c.title)
        assertEquals("Size", c.header)
        assertEquals("2/2", c.trailing)
        assertEquals(UserAskTileLine(Kind.Question, "Which size?", 2), c.lines[0])
        assertEquals(
            listOf(Kind.Option("○", false), Kind.Option("●", true), Kind.Option("○", false)),
            c.lines.drop(1).map { it.kind },
        )
    }

    @Test fun `typed Other text shows as the picked line, multi-select uses boxes`() {
        val c = UserAskTileModel.pendingTile(listOf(size.copy(multiSelect = true)), 0, UserAskAnswer(selected = listOf(0), otherText = "XL"))
        assertEquals("", c.trailing)
        assertEquals(listOf("☑", "☐", "☐", "☑"), c.lines.drop(1).map { (it.kind as Kind.Option).marker })
        assertEquals("XL", c.lines.last().text)
    }

    @Test fun `a confirm shows the command and the impact, titled by risk`() {
        val c = UserAskTileModel.pendingTile(listOf(confirm), 0, null)
        assertEquals(UserAskTileTitle.HIGH_RISK, c.title)
        assertEquals(listOf(Kind.Command, Kind.Impact), c.lines.map { it.kind })
        assertEquals("deletes build output", c.lines[1].text)
        assertEquals(UserAskTileTitle.NEEDS_APPROVAL, UserAskTileModel.cardTitle(confirm.copy(risk = UserAskRisk.MEDIUM)))
    }

    @Test fun `resolved shows each question with its answer, non-choices muted`() {
        val results = listOf(
            UserAskResolver.Parsed(UserAskResultStatus.OTHER, listOf("feature/x"), null),
            UserAskResolver.Parsed(UserAskResultStatus.ANSWERED, listOf("Medium"), null),
            UserAskResolver.Parsed(UserAskResultStatus.DENIED, emptyList(), null),
        )
        val c = UserAskTileModel.resolvedTile(listOf(open, size, confirm), results)
        assertEquals(UserAskTileTitle.QUESTION_FOR_YOU, c.title)
        assertEquals(
            listOf("Branch name?", "feature/x", "Which size?", "Medium", "rm -rf build/", ""),
            c.lines.map { it.text },
        )
        assertEquals(Kind.Answer(UserAskResultStatus.DENIED, muted = true), c.lines.last().kind)
        assertEquals(Kind.Answer(UserAskResultStatus.ANSWERED, muted = false), c.lines[3].kind)
    }

    @Test fun `a stopped ask reads skipped for every question`() {
        val c = UserAskTileModel.resolvedTile(listOf(size), emptyList())
        assertEquals(Kind.Answer(UserAskResultStatus.DISMISSED, muted = true), c.lines.last().kind)
        assertEquals("one question gets two lines for its text", 2, c.lines.first().maxLines)
    }

    @Test fun `the tile fits six lines and folds the rest into +N`() {
        assertEquals(6, UserAskTileModel.capacity())
        val many = UserAskTileModel.pendingTile(
            listOf(size.copy(options = (1..4).map { UserAskOption("O$it") })), 0, null,
        ).lines // question (2) + 4 options = 6
        assertEquals(0, UserAskTileModel.fitTile(many, 6).second)
        val (shown, more) = UserAskTileModel.fitTile(many + many.last(), 6)
        assertEquals(2, more)
        assertEquals(5, shown.sumOf { it.maxLines })
    }

    @Test fun `the status bar tile uses it for user_ask`() {
        val src = ProductionSources.read("ui/chat/ChatComposerWidgets.kt")
        assertTrue(src.contains("UserAskPromptText.TOOL_NAME ->\n                UserAskThumbContent(block)"))
    }
}
