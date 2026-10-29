package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-android-retry-badge] One automatic retry of a request in the current
 * generation. Kept in memory only, for the badge's detail sheet, and dropped
 * when the generation ends; the retry itself is also logged.
 */
data class RetryRecord(
    /** 1-based retry number within its ladder. */
    val attempt: Int,
    val maxAttempts: Int,
    /** Wall-clock time the failure was seen and the retry scheduled. */
    val atMs: Long,
    /** Why the request failed. */
    val error: String,
    /** The model the failed request went to. */
    val model: String,
    /** The provider instance (endpoint) it went through, when known. */
    val endpoint: String?,
    /** Seconds waited before the retry. */
    val delaySec: Int,
    /** The model differs from the previous retry's in this generation. */
    val switchedModel: Boolean,
)

/** [T-android-retry-badge] Pure bookkeeping for the per-generation retry log. */
internal object RetryLog {
    /** Append a retry, marking whether the model changed since the previous one. */
    fun append(
        log: List<RetryRecord>,
        attempt: Int,
        maxAttempts: Int,
        atMs: Long,
        error: String,
        model: String,
        endpoint: String?,
        delaySec: Int,
    ): List<RetryRecord> {
        val previous = log.lastOrNull()
        val switched = previous != null && (previous.model != model || previous.endpoint != endpoint)
        return log + RetryRecord(attempt, maxAttempts, atMs, error, model, endpoint, delaySec, switched)
    }

    /** "1/3": the badge's text, or null when no retry is in progress. */
    fun badgeText(attempt: Int, maxAttempts: Int): String? =
        if (attempt <= 0) null else "$attempt/$maxAttempts"
}

/** Yellow, as iOS's retry badge; dark text keeps it readable on both themes. */
private val RetryBadgeFill = Color(0xFFFFCC00)
private val RetryBadgeText = Color(0xFF5C4400)

/**
 * [T-android-retry-badge] The "↻ 1/3" capsule in front of "<name> is
 * thinking…" while an automatic retry is in progress. Tap opens the retry log.
 */
@Composable
internal fun RetryBadge(attempt: Int, maxAttempts: Int, onClick: () -> Unit) {
    val text = RetryLog.badgeText(attempt, maxAttempts) ?: return
    val spoken = stringResource(R.string.retry_badge_a11y, attempt, maxAttempts)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 6.dp)
            .background(RetryBadgeFill, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = RetryBadgeText, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RetryBadgeText)
    }
}

/**
 * [T-android-retry-badge] The retries of this task so far: number, time,
 * error, model / endpoint, delay, and whether the model changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RetryLogSheet(records: List<RetryRecord>, onDismiss: () -> Unit) {
    val timeFormat = androidx.compose.runtime.remember {
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.retry_sheet_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.retry_sheet_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = ChatColors.secondaryText,
            )
            Spacer(Modifier.height(12.dp))
            if (records.isEmpty()) {
                Text(stringResource(R.string.retry_sheet_empty), color = ChatColors.secondaryText)
                Spacer(Modifier.height(24.dp))
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                items(records) { r ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.retry_record_title, r.attempt, r.maxAttempts),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(timeFormat.format(java.util.Date(r.atMs)), fontSize = 12.sp, color = ChatColors.secondaryText)
                        }
                        Text(stringResource(R.string.retry_record_error, r.error), fontSize = 13.sp)
                        Text(stringResource(R.string.retry_record_model, r.model), fontSize = 13.sp, color = ChatColors.secondaryText)
                        r.endpoint?.takeIf { it.isNotBlank() }?.let {
                            Text(stringResource(R.string.retry_record_endpoint, it), fontSize = 13.sp, color = ChatColors.secondaryText)
                        }
                        Text(stringResource(R.string.retry_record_delay, r.delaySec), fontSize = 13.sp, color = ChatColors.secondaryText)
                        if (r.switchedModel) {
                            Text(stringResource(R.string.retry_record_switched), fontSize = 13.sp, color = ChatColors.secondaryText)
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
