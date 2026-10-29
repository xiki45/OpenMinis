package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.userask.UserAskAnswer
import com.openminis.app.agent.userask.UserAskCenter
import com.openminis.app.agent.userask.UserAskParser
import com.openminis.app.agent.userask.UserAskQuestion
import com.openminis.app.agent.userask.UserAskQuestionType
import com.openminis.app.agent.userask.UserAskResolver
import com.openminis.app.agent.userask.UserAskResultStatus
import com.openminis.app.agent.userask.UserAskRisk
import com.openminis.app.agent.userask.UserAskState

/**
 * [T-android-user-ask-tile] The 100x65dp mini preview tile of a user_ask call
 * in the tool status bar: a miniature of the card instead of a bare wrench
 * (iOS T-user-ask-tile-rich, UserAskSummary.pendingTile / resolvedTile).
 *
 * Waiting: the card's title with the header chip and "1/3", the question (in
 * monospace for a confirm's command), then the options with what is picked so
 * far (○ / ●, ☐ / ☑ for multi-select) or the confirm's ⚠ impact line - tinted
 * blue / orange / red like the card. Answered: each question, with its answer
 * under it ("→ Pear", muted for a timeout / skip / cancel).
 */

// ── Pure model (unit-tested) ───────────────────────────────────────────────

internal enum class UserAskTileTitle { NEEDS_ANSWER, NEEDS_APPROVAL, HIGH_RISK, QUESTION_FOR_YOU }

internal data class UserAskTileLine(val kind: Kind, val text: String, val maxLines: Int) {
    sealed class Kind {
        data object Question : Kind()
        data object Command : Kind()
        data object Impact : Kind()
        data object Topic : Kind()
        data class Option(val marker: String, val selected: Boolean) : Kind()
        /** [text] is the user's own answer; empty = show the status's word instead. */
        data class Answer(val status: UserAskResultStatus, val muted: Boolean) : Kind()
    }
}

internal data class UserAskTileContent(
    val title: UserAskTileTitle,
    /** The question's header chip, shown after the title ("Needs your answer · Size"). */
    val header: String?,
    /** "1/3" on a multi-question ask, else empty. */
    val trailing: String,
    val lines: List<UserAskTileLine>,
)

internal object UserAskTileModel {
    /** The fixed tile height and the sizes it is laid out with (iOS UserAskSummaryView.tileSize). */
    const val TILE_HEIGHT = 65f
    const val TITLE_SIZE = 7f
    const val LINE_SIZE = 5.5f

    private fun flat(s: String?) = s.orEmpty().replace('\n', ' ').trim()

    fun cardTitle(q: UserAskQuestion): UserAskTileTitle = when {
        q.type == UserAskQuestionType.OPTIONS -> UserAskTileTitle.NEEDS_ANSWER
        q.risk == UserAskRisk.HIGH -> UserAskTileTitle.HIGH_RISK
        else -> UserAskTileTitle.NEEDS_APPROVAL
    }

    /** Waiting for an answer: question [current] as a miniature card. */
    fun pendingTile(questions: List<UserAskQuestion>, current: Int, answer: UserAskAnswer?): UserAskTileContent {
        val q = questions.getOrNull(current) ?: return UserAskTileContent(UserAskTileTitle.QUESTION_FOR_YOU, null, "", emptyList())
        val lines = mutableListOf<UserAskTileLine>()
        val text = flat(q.question)
        if (text.isNotEmpty()) {
            val kind = if (q.type == UserAskQuestionType.CONFIRM) UserAskTileLine.Kind.Command else UserAskTileLine.Kind.Question
            lines += UserAskTileLine(kind, text, 2)
        }
        if (q.type == UserAskQuestionType.CONFIRM) {
            flat(q.impact).takeIf { it.isNotEmpty() }?.let { lines += UserAskTileLine(UserAskTileLine.Kind.Impact, it, 2) }
        } else {
            val picked = answer?.selected.orEmpty().toSet()
            q.options.orEmpty().forEachIndexed { i, o ->
                val on = i in picked
                val marker = if (q.multiSelect) (if (on) "☑" else "☐") else (if (on) "●" else "○")
                lines += UserAskTileLine(UserAskTileLine.Kind.Option(marker, on), flat(o.label), 1)
            }
            flat(answer?.otherText).takeIf { it.isNotEmpty() }?.let {
                lines += UserAskTileLine(UserAskTileLine.Kind.Option(if (q.multiSelect) "☑" else "●", true), it, 1)
            }
        }
        return UserAskTileContent(
            title = cardTitle(q),
            header = q.header?.trim()?.takeIf { it.isNotEmpty() },
            trailing = if (questions.size > 1) "${current + 1}/${questions.size}" else "",
            lines = lines,
        )
    }

    /** Not the user's own choice: drawn neutral instead of in the tint. */
    fun isMuted(s: UserAskResultStatus): Boolean = when (s) {
        UserAskResultStatus.DENIED, UserAskResultStatus.TIMEOUT,
        UserAskResultStatus.DISMISSED, UserAskResultStatus.UNAVAILABLE -> true
        else -> false
    }

    /** Settled: each question's text, then its answer. A question with no result entry reads as skipped. */
    fun resolvedTile(questions: List<UserAskQuestion>, results: List<UserAskResolver.Parsed>): UserAskTileContent {
        val lines = mutableListOf<UserAskTileLine>()
        questions.forEachIndexed { i, q ->
            val topic = flat(q.question).ifEmpty { flat(q.header) }
            if (topic.isNotEmpty()) lines += UserAskTileLine(UserAskTileLine.Kind.Topic, topic, if (questions.size == 1) 2 else 1)
            val r = results.getOrNull(i)
            val status = r?.status ?: UserAskResultStatus.DISMISSED
            val given = if (status == UserAskResultStatus.ANSWERED || status == UserAskResultStatus.OTHER) {
                r?.answer.orEmpty().filter { it.isNotEmpty() }.joinToString(", ")
            } else {
                ""
            }
            lines += UserAskTileLine(UserAskTileLine.Kind.Answer(status, isMuted(status)), given, 1)
        }
        return UserAskTileContent(UserAskTileTitle.QUESTION_FOR_YOU, null, "", lines)
    }

    /** How many lines fit under the title: line height ~ 1.25 x size plus 1dp spacing (iOS capacity). */
    fun capacity(tileHeight: Float = TILE_HEIGHT, titleSize: Float = TITLE_SIZE, lineSize: Float = LINE_SIZE): Int {
        val usable = tileHeight - 4 - 3 - (titleSize * 1.25f + 1)
        return maxOf(1, (usable / (lineSize * 1.25f + 1)).toInt())
    }

    /**
     * Entries that fit [capacity] lines (each costs its maxLines; a two-line
     * entry squeezed into the last free line keeps one). What does not fit
     * becomes "+N", which needs a line of its own.
     */
    fun fitTile(lines: List<UserAskTileLine>, capacity: Int): Pair<List<UserAskTileLine>, Int> {
        if (lines.sumOf { it.maxLines } <= capacity) return lines to 0
        var budget = maxOf(0, capacity - 1)
        val shown = mutableListOf<UserAskTileLine>()
        for (l in lines) {
            if (budget <= 0) break
            val take = minOf(l.maxLines, budget)
            budget -= take
            shown += l.copy(maxLines = take)
        }
        return shown to (lines.size - shown.size)
    }
}

// ── Tile ──────────────────────────────────────────────────────────────────

/**
 * The user_ask tile's content, or [fallback] (the generic tool tile) for a
 * call that never became a question.
 */
@Composable
internal fun UserAskThumbContent(block: AssistantBlock, fallback: @Composable () -> Unit) {
    val asks by UserAskCenter.asks.collectAsState()
    val live = asks.values.firstOrNull { it.toolCallId == block.id }
    val request = remember(block.toolArgs) { UserAskParser.parse(block.toolArgs).getOrNull() }
    val stored = remember(block.content) { UserAskResolver.parseResults(block.content) }
    val phase = UserAskCardPhase.resolve(
        livePending = live?.state == UserAskState.PENDING,
        liveTerminal = live?.state?.isTerminal == true,
        hasResults = stored.isNotEmpty(),
        requestParses = request != null,
        status = block.toolStatus,
    )
    val (content, tint) = when (phase) {
        UserAskCardPhase.PENDING -> live!!.let { a ->
            val i = a.currentIndex.coerceIn(a.questions.indices)
            UserAskTileModel.pendingTile(a.questions, i, a.answers.getOrNull(i)) to UserAskTint.forQuestion(a.questions.getOrNull(i))
        }
        UserAskCardPhase.WAITING -> {
            val qs = request?.questions
            if (qs.isNullOrEmpty()) { fallback(); return }
            UserAskTileModel.pendingTile(qs, 0, null) to UserAskTint.forQuestion(qs.first())
        }
        UserAskCardPhase.RESOLVED_LIVE -> live!!.let {
            UserAskTileModel.resolvedTile(it.questions, UserAskResolver.parseResults(it.resultJson)) to UserAskTint.question
        }
        UserAskCardPhase.RESOLVED_STORED -> UserAskTileModel.resolvedTile(request!!.questions, stored) to UserAskTint.question
        UserAskCardPhase.STOPPED_STORED -> UserAskTileModel.resolvedTile(request!!.questions, emptyList()) to UserAskTint.question
        UserAskCardPhase.NOT_ASKED -> { fallback(); return }
    }
    UserAskTile(content, tint)
}

@Composable
private fun UserAskTile(content: UserAskTileContent, tint: Color) {
    val (lines, more) = UserAskTileModel.fitTile(content.lines, UserAskTileModel.capacity())
    val titleSize = UserAskTileModel.TITLE_SIZE.sp
    val lineSize = UserAskTileModel.LINE_SIZE.sp
    val title = stringResource(
        when (content.title) {
            UserAskTileTitle.NEEDS_ANSWER -> R.string.user_ask_needs_answer
            UserAskTileTitle.NEEDS_APPROVAL -> R.string.user_ask_needs_approval
            UserAskTileTitle.HIGH_RISK -> R.string.user_ask_high_risk
            UserAskTileTitle.QUESTION_FOR_YOU -> R.string.user_ask_question_for_you
        },
    ) + (content.header?.let { " · $it" } ?: "")
    val statusWords = statusWords()
    Column(modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 1.dp)) {
            Text(
                title, fontSize = titleSize, lineHeight = titleSize * 1.25f, fontWeight = FontWeight.Bold,
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (content.trailing.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                // Its own line height: the theme's would make the title row
                // twice as tall as the title.
                Text(
                    content.trailing, fontSize = lineSize, lineHeight = lineSize * 1.25f,
                    fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1,
                )
            }
        }
        for (l in lines) {
            Text(
                tileLineText(l, tint, statusWords),
                fontSize = lineSize, lineHeight = lineSize * 1.25f,
                maxLines = l.maxLines, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 1.dp),
            )
        }
        if (more > 0) {
            Text("+$more", fontSize = lineSize, lineHeight = lineSize * 1.25f, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.45f))
        }
    }
}

@Composable
private fun statusWords(): Map<UserAskResultStatus, String> = mapOf(
    UserAskResultStatus.ANSWERED to stringResource(R.string.user_ask_answered),
    UserAskResultStatus.OTHER to stringResource(R.string.user_ask_answered),
    UserAskResultStatus.DELEGATED to stringResource(R.string.user_ask_left_to_minis),
    UserAskResultStatus.APPROVED to stringResource(R.string.user_ask_allow),
    UserAskResultStatus.DENIED to stringResource(R.string.user_ask_cancel),
    UserAskResultStatus.TIMEOUT to stringResource(R.string.user_ask_timed_out),
    UserAskResultStatus.DISMISSED to stringResource(R.string.user_ask_skipped),
    UserAskResultStatus.UNAVAILABLE to stringResource(R.string.user_ask_could_not_ask),
)

private fun tileLineText(l: UserAskTileLine, tint: Color, words: Map<UserAskResultStatus, String>): AnnotatedString =
    buildAnnotatedString {
        val white = Color.White
        when (val k = l.kind) {
            UserAskTileLine.Kind.Question ->
                withStyle(SpanStyle(color = white.copy(alpha = 0.9f), fontWeight = FontWeight.Medium)) { append(l.text) }
            UserAskTileLine.Kind.Command ->
                withStyle(SpanStyle(color = white.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace)) { append(l.text) }
            UserAskTileLine.Kind.Impact -> {
                withStyle(SpanStyle(color = tint)) { append("⚠ ") }
                withStyle(SpanStyle(color = white.copy(alpha = 0.6f))) { append(l.text) }
            }
            UserAskTileLine.Kind.Topic ->
                withStyle(SpanStyle(color = white.copy(alpha = 0.6f))) { append(l.text) }
            is UserAskTileLine.Kind.Option -> {
                withStyle(SpanStyle(color = if (k.selected) tint else white.copy(alpha = 0.45f))) { append(k.marker + " ") }
                withStyle(
                    SpanStyle(
                        color = if (k.selected) tint else white.copy(alpha = 0.7f),
                        fontWeight = if (k.selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                ) { append(l.text) }
            }
            is UserAskTileLine.Kind.Answer -> {
                withStyle(SpanStyle(color = white.copy(alpha = 0.35f))) { append("→ ") }
                withStyle(SpanStyle(color = if (k.muted) white.copy(alpha = 0.45f) else tint, fontWeight = FontWeight.Medium)) {
                    append(l.text.ifEmpty { words[k.status].orEmpty() })
                }
            }
        }
    }
