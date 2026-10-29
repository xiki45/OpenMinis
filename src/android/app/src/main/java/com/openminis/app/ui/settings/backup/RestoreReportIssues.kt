package com.openminis.app.ui.settings.backup

import com.openminis.app.backup.BackupImporter

/**
 * [T-android-restore-report-severity] What a finished restore has to say about
 * each category, and how serious each thing is.
 *
 * Kept out of the composable so the classification is plain data that can be
 * tested directly. The failure this guards against is visual and silent: a
 * counter drifting into the wrong bucket just paints a line the wrong colour,
 * either frightening the user about a deliberate choice or quietly downplaying
 * real damage.
 */
internal enum class RestoreIssueSeverity {
    /** Something broke — during the restore, or in the package itself. */
    ERROR,

    /** Left out on purpose when the backup was made. Nothing to fix. */
    NOTICE,
}

internal enum class RestoreIssueKind(val severity: RestoreIssueSeverity) {
    /** The category threw outright. */
    FAILED(RestoreIssueSeverity.ERROR),

    /**
     * The index lists a file WITHOUT a tombstone, but the package does not
     * contain it. Nobody chose this — the package is incomplete or damaged —
     * so it is an error even though it is about "a file not being there",
     * which is what makes it easy to lump in with the two notices below.
     */
    MISSING_FROM_PACKAGE(RestoreIssueSeverity.ERROR),

    /** A record could not be decoded. */
    UNREADABLE(RestoreIssueSeverity.ERROR),

    /** Tombstone `"size"`: capped out by the backup's own size limit. */
    SIZE_SKIPPED(RestoreIssueSeverity.NOTICE),

    /** Tombstone `"not_downloaded"`: never on the source device to begin with. */
    NOT_DOWNLOADED(RestoreIssueSeverity.NOTICE),
}

internal data class RestoreIssue(
    val kind: RestoreIssueKind,
    val categoryKey: String,
    val count: Int,
    /** FAILED only: the exception message the category threw with. */
    val detail: String? = null,
) {
    val severity: RestoreIssueSeverity get() = kind.severity
}

/**
 * Every per-category issue in [report], in report order, with the most serious
 * first within each category (a failure, then damage, then unreadable rows,
 * then the notices) — the same order iOS lists them.
 *
 * `report.warnings` is deliberately not included: those are already
 * human-readable sentences rather than counts, and the screen shows them with
 * the notices.
 */
internal fun restoreIssues(report: BackupImporter.Report): List<RestoreIssue> = buildList {
    for (c in report.categories) {
        c.failed?.let { add(RestoreIssue(RestoreIssueKind.FAILED, c.category, 1, it)) }
        if (c.missingBlobs > 0) {
            add(RestoreIssue(RestoreIssueKind.MISSING_FROM_PACKAGE, c.category, c.missingBlobs))
        }
        if (c.unreadable > 0) {
            add(RestoreIssue(RestoreIssueKind.UNREADABLE, c.category, c.unreadable))
        }
        if (c.sizeSkippedInPackage > 0) {
            add(RestoreIssue(RestoreIssueKind.SIZE_SKIPPED, c.category, c.sizeSkippedInPackage))
        }
        if (c.notDownloadedInPackage > 0) {
            add(RestoreIssue(RestoreIssueKind.NOT_DOWNLOADED, c.category, c.notDownloadedInPackage))
        }
    }
}

/**
 * The restore report's credentials line, keyed on the providers category
 * (iOS parity, fix 93cad55ae): keys written, keys kept, or none in the package.
 *
 * [B01 cross-platform read] null - no line - when the restore wrote no
 * provider: "restored providers have no credentials, enter each API key" was
 * shown under a provider_config.json that could not be read at all, where
 * nothing had been restored and the line sent the user to fix the wrong thing.
 */
internal enum class RestoreCredentialsNotice { RESTORED, KEPT, NONE_IN_PACKAGE }

internal fun restoreCredentialsNotice(providers: BackupImporter.CategoryReport): RestoreCredentialsNotice? = when {
    providers.credentialsRestored > 0 -> RestoreCredentialsNotice.RESTORED
    providers.credentialsKept > 0 -> RestoreCredentialsNotice.KEPT
    providers.imported + providers.updated > 0 -> RestoreCredentialsNotice.NONE_IN_PACKAGE
    else -> null
}
