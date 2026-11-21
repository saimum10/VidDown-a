package com.viddown.app.presentation

import com.viddown.core.model.AppSettings
import com.viddown.core.model.QualityPreference
import com.viddown.download.DeleteResult
import com.viddown.download.DownloadManager
import com.viddown.download.DownloadStore
import com.viddown.download.ManagerSettings
import com.viddown.download.NetworkState
import com.viddown.download.OutputStore
import com.viddown.download.PublishResult
import com.viddown.engine.api.DownloadEngine
import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.DownloadEvent
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineCapabilities
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineMode
import com.viddown.engine.api.EngineResult
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaInfo
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.MediaType
import com.viddown.engine.api.PlaylistEntry
import com.viddown.engine.api.PlaylistInfo
import com.viddown.engine.api.StreamKind
import com.viddown.engine.selector.EngineDescriptor
import com.viddown.engine.selector.EngineRegistry
import com.viddown.engine.selector.EngineSelector
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDownloaderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp = java.nio.file.Files.createTempDirectory("link").toFile()

    @After fun tearDown() { scope.cancel(); tmp.deleteRecursively() }

    private class Store : DownloadStore {
        val tasks = ConcurrentHashMap<String, DownloadTask>()
        override suspend fun save(task: DownloadTask) { tasks[task.id] = task }
        override suspend fun get(id: String) = tasks[id]
        override suspend fun tasksIn(states: Set<DownloadState>) = tasks.values.filter { it.state in states }.sortedBy { it.createdAtMillis }
        override suspend fun deleteRecord(id: String) { tasks.remove(id) }
    }

    private class Out : OutputStore {
        override suspend fun publish(source: File, displayName: String, mimeType: String?) = PublishResult.Published("content://x/$displayName", displayName)
        override suspend fun delete(location: String) = DeleteResult.Deleted
        override suspend fun exists(location: String) = true
    }

    private class StubEngine(private val inspectResult: EngineResult<MediaInfo>) : DownloadEngine {
        override val id = EngineId.NEWPIPE
        override val displayName = "NewPipe"
        override val description = "x"
        override val capabilities = EngineCapabilities(setOf(MediaType.DIRECT_MP4), supportsResume = true, listsFormats = false, mayNeedFfmpeg = false)
        var inspected = 0
        override fun availability() = EngineAvailability.Available
        override suspend fun inspect(request: MediaRequest): EngineResult<MediaInfo> { inspected++; return inspectResult }
        override suspend fun listFormats(request: MediaRequest): EngineResult<List<FormatOption>> = EngineResult.Success(emptyList())
        override fun download(task: DownloadTask, destinationDir: String): Flow<DownloadEvent> = emptyFlow()
        override suspend fun close() {}
    }

    private val original = FormatOption("original", StreamKind.ORIGINAL_FILE, container = "mp4", sizeBytes = 2_097_152)
    private fun info(formats: List<FormatOption> = listOf(original)) = MediaInfo(EngineId.NEWPIPE, "https://x.test/a.mp4", title = "a.mp4", formats = formats)

    private class Setup(val downloader: LinkDownloader, val store: Store, val engine: StubEngine)

    private fun setup(result: EngineResult<MediaInfo>, ask: Boolean = true, quality: QualityPreference = QualityPreference.BEST, available: Boolean = true): Setup {
        val engine = StubEngine(result)
        val registry = object : EngineRegistry {
            override val descriptors = listOf(EngineDescriptor(EngineId.NEWPIPE, "d") { if (available) EngineAvailability.Available else EngineAvailability.NotAvailable("Not available in this build") })
            override fun create(id: EngineId): DownloadEngine = engine
        }
        val selector = EngineSelector(registry, MutableStateFlow(EngineMode.NEWPIPE))
        val store = Store()
        val manager = DownloadManager(
            store = store, engines = selector, output = Out(), tempRoot = File(tmp, "dl"),
            settings = MutableStateFlow(ManagerSettings()), network = MutableStateFlow(NetworkState.OFFLINE), scope = scope,
        )
        val downloader = LinkDownloader(selector, manager) { AppSettings(askBeforeDownloading = ask, defaultQuality = quality) }
        return Setup(downloader, store, engine)
    }

    private class IdEngine(override val id: EngineId) : DownloadEngine {
        override val displayName = id.label
        override val description = "x"
        override val capabilities = EngineCapabilities(setOf(MediaType.DIRECT_MP4), supportsResume = true, listsFormats = false, mayNeedFfmpeg = false)
        val inspected = mutableListOf<String>()
        override fun availability() = EngineAvailability.Available
        override suspend fun inspect(request: MediaRequest): EngineResult<MediaInfo> {
            inspected += request.url
            return EngineResult.Success(MediaInfo(id, request.url, title = "t", formats = listOf(FormatOption("original", StreamKind.ORIGINAL_FILE, container = "mp4", sizeBytes = 1_048_576))))
        }
        override suspend fun listFormats(request: MediaRequest): EngineResult<List<FormatOption>> = EngineResult.Success(emptyList())
        override fun download(task: DownloadTask, destinationDir: String): Flow<DownloadEvent> = emptyFlow()
        override suspend fun close() {}
    }

    @Test fun inAutoYouTubeLinksUseNewPipeOtherLinksUseYtDlpAndTheQueuedDownloadKeepsThatEngine() = runBlocking {
        val engines = EngineId.entries.associateWith { IdEngine(it) }
        val registry = object : EngineRegistry {
            override val descriptors = EngineId.entries.map { EngineDescriptor(it, "d") { EngineAvailability.Available } }
            override fun create(id: EngineId): DownloadEngine = engines.getValue(id)
        }
        val selector = EngineSelector(registry, MutableStateFlow(EngineMode.AUTO))
        val store = Store()
        val manager = DownloadManager(
            store = store, engines = selector, output = Out(), tempRoot = File(tmp, "dl-auto"),
            settings = MutableStateFlow(ManagerSettings()), network = MutableStateFlow(NetworkState.OFFLINE), scope = scope,
        )
        val downloader = LinkDownloader(selector, manager) { AppSettings(askBeforeDownloading = true) }
        val youTube = downloader.check("https://youtu.be/abc") as LinkState.Ready
        assertEquals(EngineId.NEWPIPE, youTube.engineId)
        assertEquals(1, engines.getValue(EngineId.NEWPIPE).inspected.size)
        assertTrue("yt-dlp is not asked about a YouTube link", engines.getValue(EngineId.YTDLP).inspected.isEmpty())
        val other = downloader.check("https://vimeo.com/123") as LinkState.Ready
        assertEquals(EngineId.YTDLP, other.engineId)
        val queued = downloader.confirm(other) as LinkState.Queued
        assertEquals(EngineId.YTDLP, store.tasks.getValue(queued.id).engineId)
    }

    @Test fun askingFirstShowsTheRealFileAndQueuesNothingYet() = runBlocking {
        val s = setup(EngineResult.Success(info()))
        val state = s.downloader.check("https://x.test/a.mp4") as LinkState.Ready
        assertEquals("a.mp4", state.fileName)
        assertEquals("2.0 MB", state.sizeText)
        assertEquals("MP4", state.typeText)
        assertEquals("NewPipe", state.engineLabel)
        assertTrue(s.store.tasks.isEmpty())
    }

    @Test fun confirmingQueuesTheDownloadWithTheEngineAndFormat() = runBlocking {
        val s = setup(EngineResult.Success(info()))
        val ready = s.downloader.check("https://x.test/a.mp4") as LinkState.Ready
        val queued = s.downloader.confirm(ready) as LinkState.Queued
        val t = s.store.tasks.getValue(queued.id)
        assertEquals(EngineId.NEWPIPE, t.engineId)
        assertEquals("https://x.test/a.mp4", t.request.url)
        assertEquals("original", t.format?.id)
        assertEquals("a.mp4", t.title)
        assertEquals(DownloadState.QUEUED, t.state)
    }

    @Test fun withoutAskingTheDownloadIsQueuedAtOnce() = runBlocking {
        val s = setup(EngineResult.Success(info()), ask = false)
        val state = s.downloader.check("https://x.test/a.mp4")
        assertTrue(state is LinkState.Queued)
        assertEquals(1, s.store.tasks.size)
    }

    @Test fun anAudioOnlyPreferenceThatCannotBeMetStillAsksInsteadOfDownloadingSomethingElse() = runBlocking {
        val s = setup(EngineResult.Success(info()), ask = false, quality = QualityPreference.AUDIO_ONLY)
        assertTrue(s.downloader.check("https://x.test/a.mp4") is LinkState.Ready)
        assertTrue(s.store.tasks.isEmpty())
    }

    @Test fun everyListedOptionIsShownAndChoosingOneChangesWhatIsDownloaded() = runBlocking {
        val hd = FormatOption("hd", StreamKind.VIDEO_AND_AUDIO, container = "mp4", height = 1080, videoCodec = "avc1", audioCodec = "aac", sizeBytes = 8_388_608)
        val sd = FormatOption("sd", StreamKind.VIDEO_AND_AUDIO, container = "webm", height = 360, videoCodec = "vp9", audioCodec = "opus", sizeBytes = 2_097_152, sizeIsEstimate = true)
        val s = setup(EngineResult.Success(info(listOf(hd, sd))))
        val ready = s.downloader.check("https://x.test/a.mp4") as LinkState.Ready
        assertEquals(listOf("hd", "sd"), ready.rows.map { it.id })
        assertTrue(ready.rows.first { it.id == "sd" }.details.contains("WEBM"))
        assertTrue(ready.rows.first { it.id == "sd" }.details.contains("vp9"))
        val picked = s.downloader.select(ready, "sd")
        assertEquals("sd", picked.format?.id)
        assertEquals("about 2.0 MB", picked.sizeText)
        assertEquals("WEBM", picked.typeText)
        assertEquals(ready, s.downloader.select(ready, "missing"))
        val queued = s.downloader.confirm(picked) as LinkState.Queued
        assertEquals("sd", s.store.tasks.getValue(queued.id).format?.id)
    }

    @Test fun anUnknownSizeIsNotInvented() = runBlocking {
        val s = setup(EngineResult.Success(info(listOf(FormatOption("original", StreamKind.ORIGINAL_FILE, container = "mp4")))))
        assertNull((s.downloader.check("https://x.test/a.mp4") as LinkState.Ready).sizeText)
    }

    @Test fun anInspectionFailureIsShownAndNothingIsQueued() = runBlocking {
        val s = setup(EngineResult.Failure(DownloadError.unsupportedResource(EngineId.NEWPIPE, "The server returned a web page")))
        val f = s.downloader.check("https://x.test/page") as LinkState.Failed
        assertTrue(f.message, f.message.contains("NewPipe"))
        assertEquals(com.viddown.engine.api.SuggestedAction.CHANGE_ENGINE, f.action)
        assertTrue(s.store.tasks.isEmpty())
    }

    @Test fun anUnavailableEngineIsReportedBeforeAnyNetworkWork() = runBlocking {
        val s = setup(EngineResult.Success(info()), available = false)
        val f = s.downloader.check("https://x.test/a.mp4") as LinkState.Failed
        assertEquals(0, s.engine.inspected)
        assertTrue(f.message.isNotBlank())
    }

    @Test fun textThatIsNotALinkIsRefusedWithoutInspecting() = runBlocking {
        val s = setup(EngineResult.Success(info()))
        for (text in listOf("", "   ", "funny cats", "ftp://x.test/a.mp4", "javascript:alert(1)", "file:///etc/passwd")) {
            assertTrue("'$text'", s.downloader.check(text) is LinkState.Failed)
        }
        assertEquals(0, s.engine.inspected)
        assertTrue(s.store.tasks.isEmpty())
    }

    @Test fun aLinkWithoutASchemeGetsHttps() = runBlocking {
        val s = setup(EngineResult.Success(info()))
        val ready = s.downloader.check("x.test/a.mp4") as LinkState.Ready
        assertEquals("https://x.test/a.mp4", ready.request.url)
    }

    @Test fun readingALinkNeverQueuesEvenWhenAskingIsOff() = runBlocking {
        val s = setup(EngineResult.Success(info()), ask = false)
        val read = s.downloader.inspect("https://x.test/a.mp4") as LinkState.Ready
        assertTrue(read.autoDownload)
        assertTrue(s.store.tasks.isEmpty())
        assertTrue(s.downloader.proceed(read) is LinkState.Queued)
        assertEquals(1, s.store.tasks.size)
    }

    @Test fun onlyRealLinksAreReadInTheBackground() = runBlocking {
        val s = setup(EngineResult.Success(info()))
        assertTrue(s.downloader.isLink("https://x.test/a.mp4"))
        assertTrue(s.downloader.isLink("x.test/a.mp4"))
        assertTrue(!s.downloader.isLink("x.test/a.mp4", requireScheme = true))
        for (text in listOf("", "funny cats", "javascript:alert(1)", "file:///etc/passwd")) assertTrue("'$text'", !s.downloader.isLink(text, requireScheme = true))
    }

    // ------------------------------------------------------------------------------------------ LinkSession

    private suspend fun LinkSession.awaitBackground(link: String?) = withTimeout(5_000) {
        while (backgroundLink != link) delay(10)
    }

    @Test fun theClipboardLinkIsReadInTheBackgroundAndAnEmptyFieldShowsItAtOnce() = runBlocking {
        val s = setup(EngineResult.Success(info()))
        val session = LinkSession(s.downloader, scope, debounceMillis = 0)
        session.prefetchClipboard("  https://x.test/a.mp4\n")
        session.settle()
        session.awaitBackground("https://x.test/a.mp4")
        val ready = session.tryDownload("") as LinkState.Ready
        assertEquals("https://x.test/a.mp4", ready.request.url)
        assertEquals(1, s.engine.inspected)
        assertTrue(s.store.tasks.isEmpty())
    }

    @Test fun pastingTheSameLinkKeepsTheBackgroundReadRunning() = runBlocking {
        val s = setup(EngineResult.Success(info()))
        val session = LinkSession(s.downloader, scope, debounceMillis = 0)
        session.prefetchClipboard("https://x.test/a.mp4")
        session.settle()
        session.awaitBackground("https://x.test/a.mp4")
        session.fieldChanged("https://x.test/a.mp4")
        assertEquals("https://x.test/a.mp4", session.backgroundLink)
        assertTrue(session.tryDownload("https://x.test/a.mp4") is LinkState.Ready)
        assertEquals(1, s.engine.inspected)
    }

    @Test fun anotherLinkDropsTheBackgroundReadAndOnlyItsOwnResultIsShown() = runBlocking {
        val s = setup(EngineResult.Success(info()))
        val session = LinkSession(s.downloader, scope, debounceMillis = 60_000)
        session.prefetchClipboard("https://x.test/a.mp4")
        session.settle()
        session.awaitBackground("https://x.test/a.mp4")
        session.fieldChanged("https://x.test/b.mp4")
        assertNull(session.backgroundLink)
        val ready = session.tryDownload("https://x.test/b.mp4") as LinkState.Ready
        assertEquals("https://x.test/b.mp4", ready.request.url)
    }

    @Test fun aLinkTypedIntoTheFieldIsReadInTheBackgroundToo() = runBlocking {
        val s = setup(EngineResult.Success(info()))
        val session = LinkSession(s.downloader, scope, debounceMillis = 0)
        session.fieldChanged("https://x.test/b.mp4")
        session.settle()
        session.awaitBackground("https://x.test/b.mp4")
        val ready = session.tryDownload("https://x.test/b.mp4") as LinkState.Ready
        assertEquals("https://x.test/b.mp4", ready.request.url)
        assertEquals(1, s.engine.inspected)
    }

    @Test fun textThatIsNotALinkIsNeverReadInTheBackground() = runBlocking {
        val s = setup(EngineResult.Success(info()))
        val session = LinkSession(s.downloader, scope, debounceMillis = 0)
        session.prefetchClipboard("some notes I copied")
        session.settle()
        session.fieldChanged("funny cats")
        session.settle()
        assertNull(session.backgroundLink)
        assertEquals(0, s.engine.inspected)
        assertTrue(session.tryDownload("funny cats") is LinkState.Failed)
        assertTrue(session.tryDownload("") is LinkState.Failed)
    }

    @Test fun theBackgroundReadNeverQueuesUntilTryDownloadIsPressed() = runBlocking {
        val s = setup(EngineResult.Success(info()), ask = false)
        val session = LinkSession(s.downloader, scope, debounceMillis = 0)
        session.prefetchClipboard("https://x.test/a.mp4")
        session.settle()
        session.awaitBackground("https://x.test/a.mp4")
        delay(100)
        assertTrue(s.store.tasks.isEmpty())
        assertTrue(session.tryDownload("") is LinkState.Queued)
        assertEquals(1, s.store.tasks.size)
        assertEquals(1, s.engine.inspected)
    }

    @Test fun aFailedBackgroundReadIsShownOnceAndTheNextTapReadsTheLinkAgain() = runBlocking {
        val s = setup(EngineResult.Failure(DownloadError.unsupportedResource(EngineId.NEWPIPE, "The server returned a web page")))
        val session = LinkSession(s.downloader, scope, debounceMillis = 0)
        session.prefetchClipboard("https://x.test/page")
        session.settle()
        session.awaitBackground("https://x.test/page")
        assertTrue(session.tryDownload("") is LinkState.Failed)
        assertNull(session.backgroundLink)
        assertTrue(session.tryDownload("https://x.test/page") is LinkState.Failed)
        assertEquals(2, s.engine.inspected)
    }

    @Test fun closingTheDialogDropsTheBackgroundRead() = runBlocking {
        val s = setup(EngineResult.Success(info()))
        val session = LinkSession(s.downloader, scope, debounceMillis = 0)
        session.prefetchClipboard("https://x.test/a.mp4")
        session.settle()
        session.awaitBackground("https://x.test/a.mp4")
        session.close()
        assertNull(session.backgroundLink)
    }

    @Test fun theLinkShowsTheSamePanelAsTheBrowserAndTheAdvancedOptionsChangeWhatIsQueued() = runBlocking {
        val avc = FormatOption("136+140", StreamKind.VIDEO_AND_AUDIO, container = "mp4", height = 720, videoCodec = "avc1.4d401f", audioCodec = "mp4a.40.2", sizeBytes = 22_000_000, requiresMerge = true)
        val vp9 = FormatOption("247+251", StreamKind.VIDEO_AND_AUDIO, container = "webm", height = 720, videoCodec = "vp09.00.40.08", audioCodec = "opus", sizeBytes = 20_000_000, requiresMerge = true)
        val s = setup(EngineResult.Success(info(listOf(avc, vp9))))
        val ready = s.downloader.check("https://x.test/a.mp4") as LinkState.Ready
        assertEquals("136+140", ready.quality.selectedId)
        assertEquals(ready.quality.panel, QualityPanelBuilder.build(ready.info.formats, ready.quality.advanced, ready.info.subtitles))
        val other = s.downloader.setAdvanced(ready, AdvancedOptions(codec = "vp9"))
        assertEquals("247+251", other.quality.selectedId)
        assertEquals("WEBM", other.typeText)
        val queued = s.downloader.confirm(other) as LinkState.Queued
        assertEquals("247+251", s.store.tasks.getValue(queued.id).format?.id)
    }

    // ------------------------------------------------------------------------------------------------ playlists

    /** Lists a playlist, and reads single videos; videos in [broken] cannot be read. */
    private class PlaylistEngine(
        val list: PlaylistInfo,
        val broken: Set<String> = emptySet(),
        /** Reading this address as one video reports "this is a playlist" (a site the app does not recognise by its address). */
        val playlistOnSingleRead: String? = null,
    ) : DownloadEngine {
        override val id = EngineId.NEWPIPE
        override val displayName = "NewPipe"
        override val description = "x"
        override val capabilities = EngineCapabilities(setOf(MediaType.PAGE), supportsResume = true, listsFormats = true, mayNeedFfmpeg = false)
        val inspected = java.util.concurrent.CopyOnWriteArrayList<String>()
        var listed = 0
        override fun availability() = EngineAvailability.Available
        override suspend fun inspect(request: MediaRequest): EngineResult<MediaInfo> {
            inspected += request.url
            if (request.url == playlistOnSingleRead) return EngineResult.Failure(DownloadError.playlistLink(id))
            if (request.url in broken) return EngineResult.Failure(DownloadError.resourceUnavailable("gone"))
            val formats = listOf(
                FormatOption("18", StreamKind.VIDEO_AND_AUDIO, container = "mp4", height = 360, videoCodec = "avc1", audioCodec = "mp4a", sizeBytes = 1_000_000),
                FormatOption("22", StreamKind.VIDEO_AND_AUDIO, container = "mp4", height = 720, videoCodec = "avc1", audioCodec = "mp4a", sizeBytes = 3_000_000),
            )
            return EngineResult.Success(MediaInfo(id, request.url, title = "title of ${request.url.substringAfterLast('=')}", formats = formats))
        }
        override suspend fun inspectPlaylist(request: MediaRequest, maxEntries: Int): EngineResult<PlaylistInfo> { listed++; return EngineResult.Success(list) }
        override suspend fun listFormats(request: MediaRequest): EngineResult<List<FormatOption>> = EngineResult.Success(emptyList())
        override fun download(task: DownloadTask, destinationDir: String): Flow<DownloadEvent> = emptyFlow()
        override suspend fun close() {}
    }

    private fun video(n: Int) = PlaylistEntry("https://www.youtube.com/watch?v=v$n", "Video $n", 60L * n)
    private fun playlistOf(count: Int) = PlaylistInfo(EngineId.NEWPIPE, "https://www.youtube.com/playlist?list=PL1", "My list", "Someone", (1..count).map { video(it) })

    private class PlaylistSetup(val downloader: LinkDownloader, val store: Store, val engine: PlaylistEngine)

    private fun playlistSetup(engine: PlaylistEngine): PlaylistSetup {
        val registry = object : EngineRegistry {
            override val descriptors = listOf(EngineDescriptor(EngineId.NEWPIPE, "d") { EngineAvailability.Available })
            override fun create(id: EngineId): DownloadEngine = engine
        }
        val selector = EngineSelector(registry, MutableStateFlow(EngineMode.NEWPIPE))
        val store = Store()
        val manager = DownloadManager(
            store = store, engines = selector, output = Out(), tempRoot = File(tmp, "dl-playlist"),
            settings = MutableStateFlow(ManagerSettings()), network = MutableStateFlow(NetworkState.OFFLINE), scope = scope,
        )
        return PlaylistSetup(LinkDownloader(selector, manager) { AppSettings(askBeforeDownloading = true) }, store, engine)
    }

    @Test fun aPlaylistLinkListsEveryVideoWithNothingSelectedAndQueuesNothing() = runBlocking {
        val s = playlistSetup(PlaylistEngine(playlistOf(5)))
        val state = s.downloader.check("https://www.youtube.com/playlist?list=PL1") as LinkState.Playlist
        assertEquals(5, state.info.entries.size)
        assertTrue(state.selected.isEmpty())
        assertFalse(state.allSelected)
        assertEquals("NewPipe", state.engineLabel)
        assertEquals(1, s.engine.listed)
        assertTrue("the playlist address is not read as one video", s.engine.inspected.isEmpty())
        assertTrue(s.store.tasks.isEmpty())
    }

    @Test fun aVideoLinkThatCarriesAPlaylistShowsTheWholePlaylist() = runBlocking {
        val s = playlistSetup(PlaylistEngine(playlistOf(3)))
        assertTrue(s.downloader.check("https://www.youtube.com/watch?v=v1&list=PL1") is LinkState.Playlist)
        // a plain video link is still one video
        assertTrue(s.downloader.check("https://www.youtube.com/watch?v=v1") is LinkState.Ready)
    }

    @Test fun aSiteThatOnlyTheEngineRecognisesAsAPlaylistIsListedToo() = runBlocking {
        val s = playlistSetup(PlaylistEngine(playlistOf(2), playlistOnSingleRead = "https://other.test/set/1"))
        assertTrue(s.downloader.check("https://other.test/set/1") is LinkState.Playlist)
        assertEquals(1, s.engine.listed)
    }

    @Test fun anEmptyPlaylistIsAFailureNotAnEmptyList() = runBlocking {
        val s = playlistSetup(PlaylistEngine(playlistOf(0)))
        assertTrue(s.downloader.check("https://www.youtube.com/playlist?list=PL1") is LinkState.Failed)
    }

    @Test fun selectAllTicksEverythingAndThenUnticksEverything() = runBlocking {
        val s = playlistSetup(PlaylistEngine(playlistOf(4)))
        var state = s.downloader.check("https://www.youtube.com/playlist?list=PL1") as LinkState.Playlist
        state = s.downloader.togglePlaylistEntry(state, video(2).url)
        assertEquals(setOf(video(2).url), state.selected)
        assertFalse("one of four is not all", state.allSelected)
        state = s.downloader.togglePlaylistAll(state)
        assertEquals(4, state.selectedCount)
        assertTrue(state.allSelected) // the button now reads "Unselect all"
        state = s.downloader.togglePlaylistAll(state)
        assertEquals(0, state.selectedCount)
        assertFalse(state.allSelected)
        // ticking the last missing video by hand also makes it "all"
        (1..4).forEach { state = s.downloader.togglePlaylistEntry(state, video(it).url) }
        assertTrue(state.allSelected)
        // an address that is not in the list changes nothing
        assertEquals(state, s.downloader.togglePlaylistEntry(state, "https://elsewhere.test/x"))
    }

    @Test fun onlyTheSelectedVideosAreQueuedEachWithItsOwnEngineAndAFormat() = runBlocking {
        val s = playlistSetup(PlaylistEngine(playlistOf(6)))
        var state = s.downloader.check("https://www.youtube.com/playlist?list=PL1") as LinkState.Playlist
        listOf(5, 1, 3).forEach { state = s.downloader.togglePlaylistEntry(state, video(it).url) }
        val progress = java.util.concurrent.CopyOnWriteArrayList<LinkState.QueueingPlaylist>()
        val result = s.downloader.confirmPlaylist(state) { progress += it } as LinkState.PlaylistQueued
        assertEquals(3, result.queued)
        assertEquals(0, result.failed)
        assertEquals(setOf(video(1).url, video(3).url, video(5).url), s.store.tasks.values.map { it.request.url }.toSet())
        s.store.tasks.values.forEach {
            assertEquals(EngineId.NEWPIPE, it.engineId)
            assertTrue("a format was chosen", it.format != null)
            assertEquals(DownloadState.QUEUED, it.state)
        }
        assertEquals(LinkState.QueueingPlaylist(3, 3, 3, 0), progress.last())
        assertEquals("title of v1", s.store.tasks.values.first { it.request.url == video(1).url }.title)
    }

    @Test fun aVideoThatCannotBeReadIsCountedAndTheOthersAreStillQueued() = runBlocking {
        val s = playlistSetup(PlaylistEngine(playlistOf(4), broken = setOf(video(2).url)))
        var state = s.downloader.check("https://www.youtube.com/playlist?list=PL1") as LinkState.Playlist
        state = s.downloader.togglePlaylistAll(state)
        val result = s.downloader.confirmPlaylist(state) as LinkState.PlaylistQueued
        assertEquals(3, result.queued)
        assertEquals(1, result.failed)
        assertTrue(result.firstProblem != null)
        assertEquals(3, s.store.tasks.size)
    }

    @Test fun confirmingWithNothingSelectedQueuesNothing() = runBlocking {
        val s = playlistSetup(PlaylistEngine(playlistOf(3)))
        val state = s.downloader.check("https://www.youtube.com/playlist?list=PL1") as LinkState.Playlist
        assertEquals(state, s.downloader.confirmPlaylist(state))
        assertTrue(s.store.tasks.isEmpty())
    }

    // ---- the quality sheet of a playlist (the same kind of choice as for one video) ----

    @Test fun theQualitySheetOpensOnlyWithSomethingTickedAndStartsOnTheDefaultQuality() = runBlocking {
        val s = playlistSetup(PlaylistEngine(playlistOf(3)))
        val state = s.downloader.check("https://www.youtube.com/playlist?list=PL1") as LinkState.Playlist
        assertEquals("nothing ticked: nothing to choose a quality for", state, s.downloader.openPlaylistQuality(state))
        val ticked = s.downloader.togglePlaylistEntry(state, video(1).url)
        val opened = s.downloader.openPlaylistQuality(ticked)
        assertTrue(opened.choosingQuality)
        assertEquals(PlaylistQuality.BEST, opened.qualityKey)
        assertTrue("opening the sheet queues nothing", s.store.tasks.isEmpty())
        // back to the list: the ticks and the chosen quality stay
        val picked = s.downloader.selectPlaylistQuality(opened, "v480")
        val back = s.downloader.closePlaylistQuality(picked)
        assertFalse(back.choosingQuality)
        assertEquals(setOf(video(1).url), back.selected)
        assertEquals("v480", back.qualityKey)
        // a key the sheet does not offer changes nothing
        assertEquals(back, s.downloader.selectPlaylistQuality(back, "v999"))
    }

    @Test fun everyVideoIsSavedInTheChosenQualityOrTheClosestBelowIt() = runBlocking {
        suspend fun queuedFormatFor(key: String): List<String?> {
            val s = playlistSetup(PlaylistEngine(playlistOf(2)))
            var state = s.downloader.check("https://www.youtube.com/playlist?list=PL1") as LinkState.Playlist
            state = s.downloader.togglePlaylistAll(state)
            state = s.downloader.selectPlaylistQuality(s.downloader.openPlaylistQuality(state), key)
            s.downloader.confirmPlaylist(state)
            return s.store.tasks.values.map { it.format?.id }
        }
        assertEquals(listOf("22", "22"), queuedFormatFor("v720"))
        assertEquals("the best the video has", listOf("22", "22"), queuedFormatFor(PlaylistQuality.BEST))
        assertEquals("360p is the closest below 480p", listOf("18", "18"), queuedFormatFor("v480"))
        assertEquals("nothing is that small: the smallest one", listOf("18", "18"), queuedFormatFor("v144"))
    }

    @Test fun aVideoWithoutTheChosenKindOfQualityIsCountedAndNothingElseIsDownloadedForIt() = runBlocking {
        // the test videos have no audio-only stream, so "Music" fits none of them
        val s = playlistSetup(PlaylistEngine(playlistOf(2)))
        var state = s.downloader.check("https://www.youtube.com/playlist?list=PL1") as LinkState.Playlist
        state = s.downloader.togglePlaylistAll(state)
        state = s.downloader.selectPlaylistQuality(s.downloader.openPlaylistQuality(state), "a:fast")
        val result = s.downloader.confirmPlaylist(state) as LinkState.PlaylistQueued
        assertEquals(0, result.queued)
        assertEquals(2, result.failed)
        assertTrue(s.store.tasks.isEmpty())
    }

    @Test fun theSheetStartsOnTheLastQualityElseOnTheDefaultQualityOfTheSettings() {
        assertEquals("best", PlaylistQuality.initialKey(AppSettings()))
        assertEquals("v720", PlaylistQuality.initialKey(AppSettings(defaultQuality = QualityPreference.P720)))
        assertEquals("v1080", PlaylistQuality.initialKey(AppSettings(defaultQuality = QualityPreference.P1080)))
        assertEquals("a:high", PlaylistQuality.initialKey(AppSettings(defaultQuality = QualityPreference.AUDIO_ONLY)))
        assertEquals("the last download wins", "a:fast", PlaylistQuality.initialKey(AppSettings(defaultQuality = QualityPreference.P720, lastQuality = "a:fast")))
        assertEquals("a resolution the sheet does not list: the closest below", "v480", PlaylistQuality.initialKey(AppSettings(lastQuality = "v540")))
        assertEquals("an unusable last quality: the default", "v360", PlaylistQuality.initialKey(AppSettings(defaultQuality = QualityPreference.P360, lastQuality = "orig")))
    }

    // ---- playlists from the in-app browser ----

    @Test fun theBrowserListsAPlaylistAddressWithoutQueueingAnything() = runBlocking {
        val s = playlistSetup(PlaylistEngine(playlistOf(4)))
        val listed = s.downloader.listPlaylist("https://www.youtube.com/playlist?list=PL1", pageUrl = "https://www.youtube.com/playlist?list=PL1")
        val state = listed as LinkState.Playlist
        assertEquals(4, state.info.entries.size)
        assertTrue(state.selected.isEmpty())
        assertEquals(1, s.engine.listed)
        assertTrue("the address is not read as one video", s.engine.inspected.isEmpty())
        assertTrue(s.store.tasks.isEmpty())
        // the ticked videos then go through the same quality step and are queued like from "Download from link"
        val picked = s.downloader.selectPlaylistQuality(s.downloader.openPlaylistQuality(s.downloader.togglePlaylistAll(state)), "v720")
        val result = s.downloader.confirmPlaylist(picked) as LinkState.PlaylistQueued
        assertEquals(4, result.queued)
        assertEquals(setOf("22"), s.store.tasks.values.map { it.format?.id }.toSet())
    }

    @Test fun anEmptyPlaylistIsAFailureNotASilentSingleVideo() = runBlocking {
        val s = playlistSetup(PlaylistEngine(playlistOf(0)))
        assertTrue(s.downloader.listPlaylist("https://www.youtube.com/playlist?list=PL1") is LinkState.Failed)
    }
}
