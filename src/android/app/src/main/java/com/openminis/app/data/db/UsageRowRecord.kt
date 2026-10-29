package com.openminis.app.data.db

/** [T-usage-step1] Raw row of [ChatDao.usageRowsSince]. */
data class UsageRowRecord(
    val createdAt: Long,
    val sessionId: String,
    /** Per-message snapshot; null on rows written before snapshots existed. */
    val modelId: String?,
    /** The session's current model; null when the session row is gone (LEFT JOIN). */
    val sessionModelId: String?,
    val modelDisplayName: String?,
    val providerType: String?,
    val providerInstanceId: String?,
    val tokenUsage: String?,
)
