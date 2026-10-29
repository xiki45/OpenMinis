package com.openminis.app.ui.chat.search

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.agent.userask.UserAskPromptText
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.chat.helperControlSummaryText

/**
 * [T-in-chat-search] The texts of rows that are worked out with Android
 * resources, the same way the views draw them.
 */
internal class ResourceChatSearchTextSource(private val res: Resources) : ChatSearchUnits.TextSource {
    /**
     * ToolCallPill's line: `controlSummary ?: displayToolTitle(block)`. A
     * user_ask call is drawn as its card, and a real sub agent delegation as
     * HelperToolBlock's card - both phase 2, so neither is a capsule here.
     */
    override fun toolCapsule(block: AssistantBlock): String? {
        if (block.toolName == UserAskPromptText.TOOL_NAME) return null
        if (HelperRunner.isSubAgentToolName(block.toolName)) {
            return helperControlSummaryText(block) { id, count ->
                if (count == null) res.getString(id) else res.getString(id, count)
            }
        }
        return block.toolTitle.ifEmpty { block.toolName }
    }
}

/**
 * [T-in-chat-search] The search bar, in the composer's place (iOS
 * ChatSearchBar): query field, "3/12" counter, previous (older), next
 * (newer), Done. A "wrapped round" notice floats above it.
 */
@Composable
internal fun ChatSearchBar(controller: ChatSearchController, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(controller.focusRequest) {
        if (controller.focusRequest > 0) runCatching { focus.requestFocus() }
    }
    val placeholder = stringResource(R.string.chat_search_placeholder)
    val q = controller.query
    val hasQuery = ChatSearchMatcher.normalizedQuery(q).isNotEmpty()
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        controller.notice?.let { n ->
            Text(
                text = stringResource(
                    if (n == ChatSearchController.Notice.WRAPPED_TO_FIRST) R.string.chat_search_wrapped_first
                    else R.string.chat_search_wrapped_last,
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .background(ChatColors.toolCapsuleBg, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChatColors.inputBg, RoundedCornerShape(20.dp))
                .padding(start = 12.dp, end = 4.dp)
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search, contentDescription = null,
                tint = ChatColors.secondaryText, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (q.isEmpty()) {
                    Text(placeholder, fontSize = 16.sp, color = ChatColors.secondaryText, maxLines = 1)
                }
                BasicTextField(
                    value = q,
                    onValueChange = { controller.updateQuery(it) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // Return steps to the next result and keeps the keyboard
                    // up (iOS ChatSearchBar.onSubmit: the user is stepping
                    // through results).
                    keyboardActions = KeyboardActions(onSearch = { controller.next() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = placeholder },
                )
            }
            if (q.isNotEmpty()) {
                IconButton(onClick = { controller.updateQuery("") }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = stringResource(R.string.chat_search_clear),
                        tint = ChatColors.secondaryText,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            when {
                controller.isSearching -> CircularProgressIndicator(
                    strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 6.dp).size(16.dp),
                )
                hasQuery && controller.matchCount == 0 -> Text(
                    stringResource(R.string.chat_search_no_results),
                    fontSize = 13.sp, color = ChatColors.secondaryText,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                hasQuery -> {
                    val index = controller.currentIndex
                    val a11y = if (index != null) {
                        stringResource(R.string.chat_search_result_of, index + 1, controller.matchCount)
                    } else ""
                    Text(
                        controller.counterText,
                        fontSize = 13.sp,
                        color = ChatColors.secondaryText,
                        modifier = Modifier.padding(horizontal = 4.dp).semantics { contentDescription = a11y },
                    )
                }
            }
            val canStep = controller.matchCount > 0
            IconButton(onClick = { controller.previous() }, enabled = canStep, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.chat_search_previous))
            }
            IconButton(onClick = { controller.next() }, enabled = canStep, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_search_next))
            }
            TextButton(onClick = { controller.deactivate() }) {
                Text(stringResource(R.string.chat_search_done), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

