package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.repository.SessionSearch

/**
 * [T-search-folder-expand] Folders in a search result set (iOS 6b0ee14c1,
 * ContentView.scheduleSearch / computeGroupedSessionIDs).
 *
 * While a result set is on screen every folder in it opens with its matching
 * members, and a folder whose NAME matches is a result in its own right: it
 * brings all its members along (the folder is the reason they match) and shows
 * even when it has none. Pure, so the rules are tested without Compose.
 */
internal object SearchFolders {

    /** Folders whose name holds any of the query's keywords (the same OR rule as sessions). */
    fun matchedFolderIds(folders: List<FolderEntity>, query: String): Set<String> {
        val terms = SessionSearch.searchTerms(query)
        if (terms.isEmpty()) return emptySet()
        return folders.filter { SessionSearch.firstHit(it.name, terms) != null }.map { it.id }.toSet()
    }

    /**
     * The result set: the sessions the search matched plus every member of a
     * name-matched folder, newest first like the normal list (the sort is
     * stable, so equal times keep the search's order).
     */
    fun withFolderMembers(
        results: List<ChatSessionEntity>,
        all: List<ChatSessionEntity>,
        matchedFolderIds: Set<String>,
    ): List<ChatSessionEntity> {
        if (matchedFolderIds.isEmpty()) return results
        val ids = results.mapTo(HashSet()) { it.id }
        val extra = all.filter { s -> s.folderId != null && s.folderId in matchedFolderIds && s.id !in ids }
        if (extra.isEmpty()) return results
        return (results + extra).sortedByDescending { it.updatedAt }
    }
}
