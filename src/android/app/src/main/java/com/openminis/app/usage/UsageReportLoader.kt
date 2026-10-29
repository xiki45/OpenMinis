package com.openminis.app.usage

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.UsageRowRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * [T-usage-step1] Builds a [UsageReport] straight from the database, off the
 * main thread: the query runs on Room's executor and the JSON parsing and
 * aggregation on [Dispatchers.Default]. There is no pre-aggregated table
 * (design Q10); a fixed range reads only rows from its first day on.
 */
object UsageReportLoader {

    /**
     * [T-usage-row-cache] How long rows read for one range serve the page's
     * other questions. Provider / Model, merging model variants, a rotation
     * and a narrower range only regroup rows already read; each used to scan
     * the messages table again (no created_at index, and the usage columns
     * sit after parts_json, so the scan follows every row's overflow pages).
     */
    const val ROWS_TTL_MS = 30_000L

    private class Rows(val sinceMs: Long, val readAtMs: Long, val rows: List<UsageRow>)

    @Volatile private var cached: Rows? = null

    /**
     * The report for [query]. Rows come from the cache when one read for the
     * same or a wider range less than [ROWS_TTL_MS] ago exists, unless
     * [fresh] (opening the page, Retry, a new day).
     */
    suspend fun load(dao: ChatDao, query: UsageQuery, fresh: Boolean = true): UsageReport {
        val since = sinceMs(query)
        val now = System.currentTimeMillis()
        val hit = cached?.takeIf { !fresh && it.sinceMs <= since && now - it.readAtMs in 0 until ROWS_TTL_MS }
        val rows = if (hit != null) {
            if (hit.sinceMs == since) hit.rows else hit.rows.filter { it.atMs >= since }
        } else {
            val records = dao.usageRowsSince(since)
            withContext(Dispatchers.Default) { records.map { it.toUsageRow() } }
                .also { cached = Rows(since, now, it) }
        }
        return withContext(Dispatchers.Default) {
            UsageReportBuilder.build(rows, query)
        }
    }

    /**
     * Lower bound for the SQL filter: the range's first local midnight. ALL
     * reads everything, since its start is the first counted row.
     */
    internal fun sinceMs(q: UsageQuery): Long {
        val today = Instant.ofEpochMilli(q.nowMs).atZone(q.zone).toLocalDate()
        val first = when (q.range) {
            UsageRange.TODAY -> today
            UsageRange.LAST_7_DAYS -> today.minusDays(6)
            UsageRange.LAST_30_DAYS -> today.minusDays(29)
            UsageRange.ALL -> return 0L
        }
        return first.atStartOfDay(q.zone).toInstant().toEpochMilli()
    }

    internal fun UsageRowRecord.toUsageRow() = UsageRow(
        atMs = createdAt,
        sessionId = sessionId,
        modelId = modelId,
        sessionModelId = sessionModelId,
        modelDisplayName = modelDisplayName,
        providerType = providerType,
        providerInstanceId = providerInstanceId,
        usageJson = tokenUsage,
    )
}
