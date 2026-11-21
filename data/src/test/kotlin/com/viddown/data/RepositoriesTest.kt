package com.viddown.data

import com.viddown.data.db.BookmarkDao
import com.viddown.data.db.BookmarkEntity
import com.viddown.data.db.DownloadDao
import com.viddown.data.db.DownloadEntity
import com.viddown.data.db.PageVisitDao
import com.viddown.data.db.PageVisitEntity
import com.viddown.data.db.toEntity
import com.viddown.data.repo.BookmarkRepository
import com.viddown.data.repo.DownloadRepository
import com.viddown.data.repo.HistoryRepository
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.MediaRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDownloadDao : DownloadDao {
    val rows = MutableStateFlow<List<DownloadEntity>>(emptyList())
    override suspend fun upsert(entity: DownloadEntity) { rows.value = rows.value.filter { it.id != entity.id } + entity }
    override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<DownloadEntity>> = rows.map { it.sortedByDescending { r -> r.createdAtMillis } }
    override suspend fun getByStates(states: List<String>) = rows.value.filter { it.state in states }
    override suspend fun delete(id: String) { rows.value = rows.value.filter { it.id != id } }
    override suspend fun deleteByStates(states: List<String>): Int { val n = rows.value.count { it.state in states }; rows.value = rows.value.filter { it.state !in states }; return n }
}

private class FakeVisitDao : PageVisitDao {
    val rows = MutableStateFlow<List<PageVisitEntity>>(emptyList())
    override suspend fun insert(entity: PageVisitEntity) { rows.value = rows.value + entity.copy(id = rows.value.size + 1L) }
    override fun observeRecent(limit: Int): Flow<List<PageVisitEntity>> = rows.map { all ->
        all.groupBy { it.url }.map { (_, v) -> v.maxByOrNull { it.id }!! }.sortedByDescending { it.visitedAtMillis }.take(limit)
    }
    override suspend fun clear(): Int { val n = rows.value.size; rows.value = emptyList(); return n }
    override suspend fun count() = rows.value.size
    override suspend fun deleteByUrl(url: String): Int { val n = rows.value.count { it.url == url }; rows.value = rows.value.filter { it.url != url }; return n }
}

private class FakeBookmarkDao : BookmarkDao {
    val rows = MutableStateFlow<List<BookmarkEntity>>(emptyList())
    override suspend fun insert(entity: BookmarkEntity): Long {
        if (rows.value.any { it.url == entity.url }) return -1
        val id = rows.value.size + 1L
        rows.value = rows.value + entity.copy(id = id)
        return id
    }
    override fun observeAll(): Flow<List<BookmarkEntity>> = rows.map { it.sortedWith(compareByDescending<BookmarkEntity> { r -> r.createdAtMillis }.thenByDescending { r -> r.id }) }
    override fun observeCount(url: String): Flow<Int> = rows.map { all -> all.count { it.url == url } }
    override suspend fun deleteByUrl(url: String): Int { val n = rows.value.count { it.url == url }; rows.value = rows.value.filter { it.url != url }; return n }
    override suspend fun clear(): Int { val n = rows.value.size; rows.value = emptyList(); return n }
}

class RepositoriesTest {
    private fun task(id: String, state: DownloadState, created: Long) =
        DownloadTask(id, EngineId.NEWPIPE, MediaRequest("https://x.test/$id.mp4"), null, state = state, createdAtMillis = created)

    @Test
    fun tasksInReturnsTheRequestedStatesOldestFirstAndSkipsUnknownRows() = runBlocking {
        val dao = FakeDownloadDao()
        val repo = DownloadRepository(dao)
        repo.save(task("late", DownloadState.QUEUED, 30))
        repo.save(task("early", DownloadState.QUEUED, 10))
        repo.save(task("busy", DownloadState.DOWNLOADING, 20))
        repo.save(task("done", DownloadState.COMPLETED, 5))
        dao.upsert(task("future", DownloadState.QUEUED, 1).toEntity(0).copy(engine = "engine_from_the_future"))
        assertEquals(listOf("early", "late"), repo.tasksIn(setOf(DownloadState.QUEUED)).map { it.id })
        assertEquals(listOf("early", "busy", "late"), repo.tasksIn(setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING)).map { it.id })
        assertTrue(repo.tasksIn(emptySet()).isEmpty())
    }

    @Test
    fun savedDownloadsComeBackNewestFirstAndRowsFromTheFutureAreSkipped() = runBlocking {
        val dao = FakeDownloadDao()
        val repo = DownloadRepository(dao) { 50L }
        repo.save(task("a", DownloadState.COMPLETED, 1))
        repo.save(task("b", DownloadState.QUEUED, 2))
        dao.upsert(task("c", DownloadState.QUEUED, 3).toEntity(0).copy(engine = "engine_from_the_future"))
        assertEquals(listOf("b", "a"), repo.downloads.first().map { it.id })
        assertEquals(50L, dao.getById("a")!!.updatedAtMillis)
    }

    @Test
    fun deleteRecordRemovesOnlyThatRecord() = runBlocking {
        val repo = DownloadRepository(FakeDownloadDao())
        repo.save(task("a", DownloadState.COMPLETED, 1))
        repo.save(task("b", DownloadState.COMPLETED, 2))
        repo.deleteRecord("a")
        assertNull(repo.get("a"))
        assertEquals("b", repo.get("b")!!.id)
    }

    @Test
    fun clearFinishedKeepsRunningAndQueuedDownloads() = runBlocking {
        val repo = DownloadRepository(FakeDownloadDao())
        for ((i, s) in listOf(DownloadState.COMPLETED, DownloadState.FAILED, DownloadState.CANCELLED, DownloadState.DOWNLOADING, DownloadState.QUEUED, DownloadState.PAUSED).withIndex()) {
            repo.save(task("t$i", s, i.toLong()))
        }
        assertEquals(3, repo.clearFinished())
        assertEquals(setOf("t3", "t4", "t5"), repo.downloads.first().map { it.id }.toSet())
    }

    @Test
    fun historyRecordsOnlyWebPagesAndShowsEachUrlOnce() = runBlocking {
        var now = 0L
        val repo = HistoryRepository(FakeVisitDao()) { ++now }
        assertTrue(repo.record("https://a.test/", "A"))
        assertTrue(repo.record("http://b.test/", null))
        assertTrue(repo.record("https://a.test/", "A again"))
        assertFalse(repo.record("javascript:alert(1)", "x"))
        assertFalse(repo.record("file:///etc/passwd", "x"))
        val recent = repo.recent(10).first()
        assertEquals(listOf("https://a.test/", "http://b.test/"), recent.map { it.url })
        assertEquals("A again", recent.first().title)
    }

    @Test
    fun clearingBrowsingHistoryDoesNotTouchDownloads() = runBlocking {
        val downloads = DownloadRepository(FakeDownloadDao())
        val history = HistoryRepository(FakeVisitDao())
        downloads.save(task("a", DownloadState.COMPLETED, 1))
        history.record("https://a.test/", "A")
        assertEquals(1, history.clear())
        assertTrue(history.recent().first().isEmpty())
        assertEquals(1, downloads.downloads.first().size)
        assertEquals(1, downloads.clearFinished())
        assertTrue(downloads.downloads.first().isEmpty())
    }

    @Test
    fun removingOnePageFromHistoryKeepsTheOthers() = runBlocking {
        val repo = HistoryRepository(FakeVisitDao())
        repo.record("https://a.test/", "A")
        repo.record("https://b.test/", "B")
        repo.record("https://a.test/", "A2")
        assertEquals(2, repo.remove("https://a.test/"))
        assertEquals(listOf("https://b.test/"), repo.recent(10).first().map { it.url })
    }

    @Test
    fun bookmarksAreWebPagesSavedOnceAndToggleable() = runBlocking {
        var now = 0L
        val repo = BookmarkRepository(FakeBookmarkDao()) { ++now }
        assertTrue(repo.add("https://a.test/x#frag", "  A  "))
        assertFalse("same address again", repo.add("https://a.test/x", "A"))
        assertFalse(repo.add("javascript:alert(1)", "x"))
        assertFalse(repo.add("file:///etc/passwd", "x"))
        assertTrue(repo.isBookmarked("https://a.test/x#other").first())
        assertTrue(repo.add("https://b.test/", null))
        val list = repo.bookmarks.first()
        assertEquals(listOf("https://b.test/", "https://a.test/x"), list.map { it.url })
        assertEquals("A", list.last().title.orEmpty().trim())
        assertFalse(repo.toggle("https://b.test/", "B"))
        assertFalse(repo.isBookmarked("https://b.test/").first())
        assertTrue(repo.toggle("https://b.test/", "B"))
        assertTrue(repo.remove("https://a.test/x"))
        assertFalse(repo.remove("https://a.test/x"))
        assertEquals(1, repo.clear())
        assertFalse(repo.toggle("mailto:a@b.c", null))
    }
}
