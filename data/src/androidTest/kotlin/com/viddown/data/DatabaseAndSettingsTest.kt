package com.viddown.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.viddown.core.model.AppSettings
import com.viddown.core.model.ThemeMode
import com.viddown.data.db.VidDownDatabase
import com.viddown.data.db.toEntity
import com.viddown.data.db.toTask
import com.viddown.data.repo.HistoryRepository
import com.viddown.data.settings.DataStoreSettingsRepository
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.MediaRequest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs on a device or emulator: real Room (in-memory) and real DataStore files. */
class DatabaseAndSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: VidDownDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, VidDownDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        storeFile = File(context.cacheDir, "settings-test-${System.nanoTime()}.preferences_pb")
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        storeFile.delete()
    }

    private fun task(id: String, state: DownloadState, created: Long) = DownloadTask(
        id = id, engineId = EngineId.NEWPIPE, request = MediaRequest("https://x.test/$id.mp4"), format = null,
        state = state, createdAtMillis = created,
    )

    private fun repo() = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { storeFile })

    @Test
    fun downloadRecordsAreStoredQueriedAndDeleted() = runBlocking {
        val dao = db.downloadDao()
        dao.upsert(task("a", DownloadState.COMPLETED, 1).toEntity(10))
        dao.upsert(task("b", DownloadState.DOWNLOADING, 2).toEntity(10))
        dao.upsert(task("c", DownloadState.QUEUED, 3).toEntity(10))
        assertEquals(listOf("c", "b", "a"), dao.observeAll().first().map { it.id })
        assertEquals(listOf("c"), dao.getByStates(listOf("QUEUED")).map { it.id })
        assertNotNull(dao.getById("b")!!.toTask())
        dao.upsert(task("b", DownloadState.COMPLETED, 2).toEntity(20)) // update in place
        assertEquals(DownloadState.COMPLETED.name, dao.getById("b")!!.state)
        assertEquals(1, dao.deleteByStates(listOf("QUEUED")))
        dao.delete("a")
        assertNull(dao.getById("a"))
        assertEquals(listOf("b"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun settingsDefaultThenPersistAcrossRepositoryInstances() = runBlocking {
        val first = repo()
        assertEquals(AppSettings(), first.settings.first())
        first.update { it.copy(themeMode = ThemeMode.DARK, wifiOnly = true, maxSimultaneousDownloads = 99) }
        val read = first.settings.first()
        assertEquals(ThemeMode.DARK, read.themeMode)
        assertEquals(10, read.maxSimultaneousDownloads) // sanitised on write
        scope.cancel() // close the first DataStore, then open the same file again as after an app restart
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val second = repo().settings.first()
        assertEquals(ThemeMode.DARK, second.themeMode)
        assertTrue(second.wifiOnly)
    }

    @Test
    fun browsingHistoryKeepsTheLatestVisitPerPageAndClearsIndependentlyOfDownloads() = runBlocking {
        val history = HistoryRepository(db.pageVisitDao())
        history.record("https://a.test/", "A")
        history.record("https://b.test/", "B")
        history.record("https://a.test/", "A again")
        history.record("javascript:alert(1)", "bad")
        db.downloadDao().upsert(task("keep", DownloadState.COMPLETED, 1).toEntity(1))
        val recent = history.recent(10).first()
        assertEquals(listOf("https://a.test/", "https://b.test/"), recent.map { it.url })
        assertEquals("A again", recent.first().title)
        assertEquals(3, history.clear())
        assertTrue(history.recent(10).first().isEmpty())
        assertNotNull(db.downloadDao().getById("keep"))
    }
}
