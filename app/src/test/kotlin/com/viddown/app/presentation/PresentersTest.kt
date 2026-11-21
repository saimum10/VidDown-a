package com.viddown.app.presentation

import com.viddown.core.model.AppSettings
import com.viddown.core.model.LibraryFilter
import com.viddown.core.model.LibrarySort
import com.viddown.core.model.SearchEngine
import com.viddown.data.repo.PageVisit
import com.viddown.engine.api.DownloadEngine
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineMode
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.StreamKind
import com.viddown.engine.selector.EngineDescriptor
import com.viddown.engine.selector.EngineRegistry
import com.viddown.engine.selector.EngineSelector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentersTest {
    private fun task(id: String, state: DownloadState, created: Long = 0, title: String? = null, size: Long = 0, duration: Long? = null) =
        DownloadTask(id, EngineId.NEWPIPE, MediaRequest("https://x.test/$id.mp4"), FormatOption("f", StreamKind.VIDEO_AND_AUDIO, height = 720, container = "mp4"),
            title, duration, state, size, size.takeIf { it > 0 }, null, null, created)

    @Test
    fun homeShowsTwoRecentPagesAndTheActiveSummary() = runBlocking {
        val tasks = MutableStateFlow(listOf(task("a", DownloadState.DOWNLOADING), task("b", DownloadState.COMPLETED), task("c", DownloadState.QUEUED), task("d", DownloadState.FAILED)))
        val pages = MutableStateFlow((1..8).map { PageVisit("https://p$it.test/", null, it.toLong()) })
        val p = HomePresenter(tasks, pages, MutableStateFlow(AppSettings(searchEngine = SearchEngine.BING)))
        val s = p.state.first()
        assertEquals(2, s.recentPages.size)
        assertEquals(1, s.active.active)
        assertEquals(1, s.active.queued)
        assertEquals(SearchEngine.BING, s.searchEngine)
    }

    @Test
    fun addressFieldInterpretation() {
        val e = SearchEngine.DUCKDUCKGO
        assertEquals(HomeAction.OpenInBrowser("https://example.com"), HomePresenter.interpret("example.com", e))
        assertTrue((HomePresenter.interpret("cat videos", e) as HomeAction.OpenInBrowser).url.startsWith("https://duckduckgo.com/?q="))
        assertTrue((HomePresenter.interpret("", e) as HomeAction.ShowError).message.contains("Enter"))
        assertTrue((HomePresenter.interpret("javascript:alert(1)", e) as HomeAction.ShowError).message.contains("javascript"))
        assertTrue(HomePresenter.interpret("https://", e) is HomeAction.ShowError)
    }

    @Test
    fun downloadsStateEmptyContentAndError() = runBlocking {
        assertEquals(ListState.Empty, DownloadsPresenter(MutableStateFlow(emptyList())).state.first())
        val content = DownloadsPresenter(MutableStateFlow(listOf(task("a", DownloadState.COMPLETED)))).state.first()
        assertEquals(1, (content as ListState.Content).items.size)
        val broken: Flow<List<DownloadTask>> = flow { throw IllegalStateException("database is locked") }
        val error = DownloadsPresenter(broken).state.first() as ListState.Error
        assertTrue(error.message, error.message.contains("downloads"))
        assertEquals("database is locked", error.detail)
    }

    @Test
    fun libraryShowsOnlyCompletedAndExplainsEmptyResults() = runBlocking {
        val tasks = MutableStateFlow(listOf(task("a", DownloadState.COMPLETED, 1, "Alpha clip", 2_000_000, 125), task("b", DownloadState.FAILED, 2)))
        val query = MutableStateFlow(LibraryQueryState())
        val p = LibraryPresenter(tasks, query)
        val all = p.state.first()
        assertFalse(all.isFiltered)
        val item = (all.list as ListState.Content).items.single()
        assertEquals("Alpha clip", item.title)
        assertEquals("720p · 2:05 · 1.9 MB · NewPipe", item.details)
        query.value = LibraryQueryState(search = "zzz")
        val none = p.state.first()
        assertTrue(none.isFiltered)
        assertEquals(ListState.Empty, none.list)
        query.value = LibraryQueryState(sort = LibrarySort.NAME, filter = LibraryFilter.AUDIO)
        assertTrue(p.state.first().isFiltered)
    }

    @Test
    fun libraryDetailsOmitWhatIsUnknown() = runBlocking {
        val t = DownloadTask("u", EngineId.YTDLP, MediaRequest("https://x.test/u"), null, null, null, DownloadState.COMPLETED, 0, null, null, null, 0)
        val item = ((LibraryPresenter(MutableStateFlow(listOf(t)), MutableStateFlow(LibraryQueryState())).state.first()).list as ListState.Content).items.single()
        assertEquals("yt-dlp", item.details)
    }

    private class Registry(private val availability: Map<EngineId, EngineAvailability>) : EngineRegistry {
        override val descriptors = EngineId.entries.map { id -> EngineDescriptor(id, "d") { availability.getValue(id) } }
        override fun create(id: EngineId): DownloadEngine = throw AssertionError("must not be created")
    }

    private val none = EngineAvailability.NotAvailable("Not available in this build")

    @Test
    fun engineSettingsWarnWhenTheSelectedEngineIsUnavailableAndRefuseUnavailableChoices() = runBlocking {
        val registry = Registry(EngineId.entries.associateWith { none })
        val p = EnginePresenter(EngineSelector(registry, MutableStateFlow(EngineMode.NEWPIPE)))
        val s = p.state.first()
        assertEquals(3, s.engines.size) // Auto, NewPipe, yt-dlp
        assertTrue(s.engines.none { it.selectable })
        assertTrue(s.warning!!.contains("NewPipe"))
        assertTrue(s.warning!!.contains("Not available in this build"))
        assertEquals(EngineChoice.Refused("Not available in this build"), p.choose(EngineMode.YTDLP))
    }

    @Test
    fun noWarningWhenTheSelectedEngineWorksAndAvailableEnginesCanBeChosen() = runBlocking {
        val registry = Registry(mapOf(EngineId.NEWPIPE to none, EngineId.YTDLP to EngineAvailability.Available))
        val p = EnginePresenter(EngineSelector(registry, MutableStateFlow(EngineMode.YTDLP)))
        assertNull(p.state.first().warning)
        assertEquals(EngineChoice.Persist(EngineMode.YTDLP), p.choose(EngineMode.YTDLP))
        assertTrue(p.choose(EngineMode.NEWPIPE) is EngineChoice.Refused)
    }

    @Test
    fun noSelectedEngineIsReportedAndNoEngineIsSubstituted() = runBlocking {
        val registry = Registry(EngineId.entries.associateWith { EngineAvailability.Available }) // all available: none may be picked
        val p = EnginePresenter(EngineSelector(registry, MutableStateFlow<EngineMode?>(null)))
        val s = p.state.first()
        assertTrue(s.engines.none { it.selected })
        assertTrue(s.warning!!.contains("No download engine is selected"))
        assertEquals(EngineChoice.Persist(EngineMode.NEWPIPE), p.choose(EngineMode.NEWPIPE))
    }

    @Test
    fun theSwitchNoticeIsOnlyShownWhenPendingDownloadsKeepAnotherEngine() {
        val n = EngineMode.NEWPIPE
        val d = EngineMode.YTDLP
        val nId = EngineId.NEWPIPE
        val dId = EngineId.YTDLP
        assertNull("nothing pending", EnginePresenter.switchNotice(n, d, emptySet()))
        assertNull("same engine chosen again", EnginePresenter.switchNotice(n, n, setOf(dId)))
        assertNull("pending downloads already use the new engine", EnginePresenter.switchNotice(n, d, setOf(nId)))
        val text = EnginePresenter.switchNotice(n, d, setOf(dId, nId))!!
        assertTrue(text, text.startsWith("NewPipe will be used for new downloads"))
        assertTrue(text, text.contains("(yt-dlp)"))
        assertTrue("leaving an unrecognised choice also explains it", EnginePresenter.switchNotice(n, null, setOf(dId)) != null)
        assertNull("Auto may use either engine: nothing to explain", EnginePresenter.switchNotice(EngineMode.AUTO, d, setOf(dId, nId)))
        assertTrue("leaving Auto for one engine explains what pending downloads keep", EnginePresenter.switchNotice(n, EngineMode.AUTO, setOf(dId)) != null)
    }

    @Test
    fun autoIsListedFirstIsSelectableWhenOneEngineWorksAndNamesTheMissingEngine() = runBlocking {
        val registry = Registry(mapOf(EngineId.NEWPIPE to EngineAvailability.Available, EngineId.YTDLP to none))
        val p = EnginePresenter(EngineSelector(registry, MutableStateFlow(EngineMode.AUTO)))
        val s = p.state.first()
        assertEquals(listOf(EngineMode.AUTO, EngineMode.NEWPIPE, EngineMode.YTDLP), s.engines.map { it.mode })
        assertTrue(s.engines.first().selected && s.engines.first().selectable)
        assertTrue(s.engines.first().description.contains("yt-dlp is not available"))
        assertNull(s.warning)
        assertEquals(EngineChoice.Persist(EngineMode.AUTO), p.choose(EngineMode.AUTO))
    }

    @Test
    fun autoCannotBeChosenWhenNoEngineIsAvailable() = runBlocking {
        val registry = Registry(EngineId.entries.associateWith { none })
        val p = EnginePresenter(EngineSelector(registry, MutableStateFlow(EngineMode.AUTO)))
        assertTrue(p.choose(EngineMode.AUTO) is EngineChoice.Refused)
        assertTrue(p.state.first().warning!!.contains("Auto"))
    }

    // ---- Phase 4: live details in the Downloads list --------------------------------------------------------------

    private fun liveTask(id: String, state: DownloadState, done: Long = 0, total: Long? = null) = DownloadTask(
        id = id, engineId = EngineId.NEWPIPE, request = MediaRequest("https://x.test/$id.mp4"), format = null,
        title = id, state = state, bytesDownloaded = done, totalBytes = total, createdAtMillis = 1,
    )

    @Test
    fun downloadsListShowsSpeedAndWaitingNotesOnlyWhereTheyBelong() = runBlocking {
        val tasks = listOf(
            liveTask("run", DownloadState.DOWNLOADING, 10, 100),
            liveTask("wait", DownloadState.QUEUED),
            liveTask("done", DownloadState.COMPLETED, 100, 100),
        )
        val live = mapOf("run" to com.viddown.download.LiveProgress(2048, 45, null), "done" to com.viddown.download.LiveProgress(1, 1, "x"))
        val work = com.viddown.download.WorkState(1, 1, com.viddown.download.BlockReason.WIFI_REQUIRED)
        val state = DownloadsPresenter(kotlinx.coroutines.flow.flowOf(tasks), kotlinx.coroutines.flow.flowOf(live), kotlinx.coroutines.flow.flowOf(work)).state.first()
        val items = (state as ListState.Content).items.associateBy { it.id }
        assertEquals("2.0 KB/s", items.getValue("run").speedText)
        assertEquals("45 s left", items.getValue("run").etaText)
        assertEquals("Waiting for Wi-Fi", items.getValue("wait").note)
        assertNull(items.getValue("wait").speedText)
        assertNull(items.getValue("done").speedText)
        assertNull(items.getValue("done").note)
        assertTrue(items.getValue("run").canCancel && items.getValue("wait").canCancel)
        assertFalse(items.getValue("done").canCancel)
    }

    @Test
    fun downloadsListWithoutLiveDataStillShowsTheRecords() = runBlocking {
        val state = DownloadsPresenter(kotlinx.coroutines.flow.flowOf(listOf(liveTask("a", DownloadState.FAILED)))).state.first()
        val item = (state as ListState.Content).items.single()
        assertTrue(item.canRetry)
        assertNull(item.note)
        assertEquals(ListState.Empty, DownloadsPresenter(kotlinx.coroutines.flow.flowOf(emptyList())).state.first())
    }

    @Test
    fun noNetworkIsExplainedOnQueuedItems() = runBlocking {
        val work = kotlinx.coroutines.flow.flowOf(com.viddown.download.WorkState(0, 1, com.viddown.download.BlockReason.NO_NETWORK))
        val state = DownloadsPresenter(kotlinx.coroutines.flow.flowOf(listOf(liveTask("q", DownloadState.QUEUED))), kotlinx.coroutines.flow.flowOf(emptyMap()), work).state.first()
        assertEquals("Waiting for network", (state as ListState.Content).items.single().note)
    }
}
