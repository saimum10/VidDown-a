package com.viddown.data.repo

import com.viddown.data.db.BookmarkDao
import com.viddown.data.db.BookmarkEntity
import com.viddown.data.db.DownloadDao
import com.viddown.data.db.PageVisitDao
import com.viddown.data.db.PageVisitEntity
import com.viddown.data.db.toEntity
import com.viddown.data.db.toTask
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DownloadRepository(
    private val dao: DownloadDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Newest first. Rows this app version cannot understand are skipped, never shown half-broken. */
    val downloads: Flow<List<DownloadTask>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toTask() } }

    suspend fun save(task: DownloadTask) = dao.upsert(task.toEntity(clock()))

    suspend fun get(id: String): DownloadTask? = dao.getById(id)?.toTask()

    /** Records in the given states, oldest first (the order the download queue runs in). */
    suspend fun tasksIn(states: Set<DownloadState>): List<DownloadTask> =
        dao.getByStates(states.map { it.name }).mapNotNull { it.toTask() }.sortedBy { it.createdAtMillis }

    /** Removes the record only. Files are never touched here: deleting a file is a separate, explicitly confirmed action. */
    suspend fun deleteRecord(id: String) = dao.delete(id)

    /** Removes every finished record (completed, failed, cancelled). Running and queued downloads are kept. */
    suspend fun clearFinished(): Int =
        dao.deleteByStates(DownloadState.entries.filter { it.isTerminal }.map { it.name })
}

data class PageVisit(val url: String, val title: String?, val visitedAtMillis: Long)

class HistoryRepository(
    private val dao: PageVisitDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun recent(limit: Int = 10): Flow<List<PageVisit>> =
        dao.observeRecent(limit).map { rows -> rows.map { PageVisit(it.url, it.title, it.visitedAtMillis) } }

    /** Only http(s) pages are recorded (never javascript:, file:, data: ...). Returns whether it was stored. */
    suspend fun record(url: String, title: String?): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        dao.insert(PageVisitEntity(url = url, title = title?.takeIf { it.isNotBlank() }, visitedAtMillis = clock()))
        return true
    }

    suspend fun clear(): Int = dao.clear()

    /** Removes one page (every visit of that address) from the history. */
    suspend fun remove(url: String): Int = dao.deleteByUrl(url)
}

data class Bookmark(val url: String, val title: String?, val createdAtMillis: Long)

class BookmarkRepository(
    private val dao: BookmarkDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Newest first. */
    val bookmarks: Flow<List<Bookmark>> = dao.observeAll().map { rows -> rows.map { Bookmark(it.url, it.title, it.createdAtMillis) } }

    fun isBookmarked(url: String): Flow<Boolean> = dao.observeCount(normalize(url) ?: "").map { it > 0 }

    /** Saves an http(s) page. Returns false when the address is not a web page or was already saved. */
    suspend fun add(url: String, title: String?): Boolean {
        val clean = normalize(url) ?: return false
        return dao.insert(BookmarkEntity(url = clean, title = title?.trim()?.takeIf { it.isNotEmpty() }, createdAtMillis = clock())) > 0
    }

    suspend fun remove(url: String): Boolean = normalize(url)?.let { dao.deleteByUrl(it) > 0 } ?: false

    /** Saves the page, or removes it when it is already saved. Returns true when it is saved afterwards. */
    suspend fun toggle(url: String, title: String?): Boolean {
        val clean = normalize(url) ?: return false
        return if (dao.deleteByUrl(clean) > 0) false else add(clean, title)
    }

    suspend fun clear(): Int = dao.clear()

    /** Only web pages are bookmarked, and #fragments are not part of the address. */
    private fun normalize(url: String): String? {
        val trimmed = url.trim().substringBefore('#')
        val lower = trimmed.lowercase()
        return trimmed.takeIf { lower.startsWith("http://") || lower.startsWith("https://") }
    }
}
