package com.viddown.app.presentation

import com.viddown.browser.DetectedMedia
import com.viddown.browser.DetectionMethod
import com.viddown.browser.DetectionStatus
import com.viddown.browser.PageLimitation
import com.viddown.browser.Protection
import com.viddown.browser.TabDetectionState
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
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaInfo
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.MediaType
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPresentersTest {
    private val direct = EngineCapabilities(setOf(MediaType.DIRECT_MP4, MediaType.DIRECT_WEBM, MediaType.DIRECT_OTHER), supportsResume = true, listsFormats = false, mayNeedFfmpeg = false)
    private val directInfo = EngineInfo(EngineId.NEWPIPE, "NewPipe", EngineAvailability.Available, direct)

    private fun media(url: String, type: MediaType, size: Long? = 3_145_728, ext: String? = "mp4", method: DetectionMethod = DetectionMethod.CONTENT_TYPE, protection: Protection = Protection.NONE, title: String? = null) =
        DetectedMedia(key = url, tabId = "t", url = url, type = type, title = title, extension = ext, sizeBytes = size, method = method, protection = protection)

    @Test
    fun detectionsShowRealFactsAndSayWhenSomethingIsUnknown() {
        val m = media("https://x.test/a.mp4", MediaType.DIRECT_MP4, title = "a.mp4").copy(durationSeconds = 75, qualityInfo = "1280\u00D7720")
        assertEquals("MP4 video \u00B7 3.0 MB \u00B7 1280\u00D7720 \u00B7 1:15", DetectionPresenter.details(m))
        assertTrue(DetectionPresenter.details(m.copy(sizeBytes = null)).contains("Size unknown"))
        assertTrue(DetectionPresenter.details(m.copy(method = DetectionMethod.URL_EXTENSION)).endsWith("Not verified"))
        assertEquals("Media from x.test", DetectionPresenter.title(m.copy(title = null, url = "https://www.x.test/q")))
        val hls = media("https://x.test/a.m3u8", MediaType.HLS, size = null, ext = "m3u8").copy(qualityInfo = "up to 1080p, 3 variants", isLive = false)
        assertEquals("HLS stream \u00B7 up to 1080p, 3 variants", DetectionPresenter.details(hls))
        assertEquals("MP3 audio", DetectionPresenter.typeLabel(media("https://x.test/a.mp3", MediaType.DIRECT_OTHER, ext = "mp3")))
    }

    @Test
    fun theWholePageCanBeDownloadedOnlyWhenTheSelectedEngineReadsPages() {
        val pages = EngineCapabilities(setOf(MediaType.PAGE, MediaType.DIRECT_MP4), supportsResume = true, listsFormats = true, mayNeedFfmpeg = true)
        val ytdlp = EngineInfo(EngineId.YTDLP, "yt-dlp", EngineAvailability.Available, pages)
        assertTrue(DetectionPresenter.build(TabDetectionState(), ytdlp).pageDownload)
        assertFalse(DetectionPresenter.build(TabDetectionState(), directInfo).pageDownload)
        val broken = EngineInfo(EngineId.YTDLP, "yt-dlp", EngineAvailability.NotAvailable("not installed"), null)
        assertFalse(DetectionPresenter.build(TabDetectionState(), broken).pageDownload)
    }

    @Test
    fun inAutoEachResourceAndThePageAreJudgedByTheEngineThatWouldDownloadThem() {
        val pages = EngineCapabilities(setOf(MediaType.PAGE, MediaType.DIRECT_MP4), supportsResume = true, listsFormats = true, mayNeedFfmpeg = true)
        val ytdlp = EngineInfo(EngineId.YTDLP, "yt-dlp", EngineAvailability.Available, pages)
        val route = EngineRoute.auto(directInfo, ytdlp) // the test NewPipe reads direct files only
        val state = TabDetectionState(items = listOf(media("https://x.test/a.mp4", MediaType.DIRECT_MP4)))
        val onVimeo = DetectionPresenter.build(state, route, "https://vimeo.com/123")
        assertEquals("Auto", onVimeo.engineLabel)
        assertTrue("a page that is not YouTube goes to yt-dlp, which reads pages", onVimeo.pageDownload)
        assertEquals(1, onVimeo.downloadableCount)
        val onYouTube = DetectionPresenter.build(state, route, "https://www.youtube.com/watch?v=abc")
        assertFalse("a YouTube page goes to NewPipe, which does not read pages in this test", onYouTube.pageDownload)
    }

    @Test
    fun inAutoOneUnavailableEngineOnlyAffectsTheLinksThatNeedIt() {
        val ytdlpOff = EngineInfo(EngineId.YTDLP, "yt-dlp", EngineAvailability.NotAvailable("not installed"), null)
        val route = EngineRoute.auto(directInfo, ytdlpOff)
        val state = TabDetectionState(items = listOf(media("https://x.test/a.mp4", MediaType.DIRECT_MP4), media("https://www.youtube.com/a.mp4", MediaType.DIRECT_MP4)))
        val ui = DetectionPresenter.build(state, route, null)
        assertEquals(1, ui.downloadableCount)
        assertTrue(ui.items.first().media.url.contains("youtube"))
        assertTrue(ui.items.last().status is DetectionStatus.EngineUnavailable)
    }

    @Test
    fun theDownloadActionOnlyExistsWhereTheSelectedEngineCanDoTheJob() {
        val mp4 = media("https://x.test/a.mp4", MediaType.DIRECT_MP4)
        val hls = media("https://x.test/a.m3u8", MediaType.HLS, size = null, ext = "m3u8")
        val drm = media("https://x.test/a.mpd", MediaType.DASH, size = null, ext = "mpd", protection = Protection.DRM)
        val ui = DetectionPresenter.build(TabDetectionState(items = listOf(hls, drm, mp4)), directInfo)
        assertEquals(listOf("a.mp4", "a.m3u8", "a.mpd"), ui.items.map { it.media.url.substringAfterLast('/') })
        assertEquals(1, ui.downloadableCount)
        assertTrue(ui.items[0].status.canDownload)
        assertTrue(ui.items[1].status is DetectionStatus.UnsupportedByEngine)
        assertTrue(ui.items[2].status is DetectionStatus.Protected)
        assertNull(ui.emptyExplanation)
    }

    @Test
    fun anUnavailableEngineMakesEverythingUnavailableNotDownloadable() {
        val off = EngineInfo(EngineId.NEWPIPE, "NewPipe", EngineAvailability.NotAvailable("Not available in this build"), null)
        val ui = DetectionPresenter.build(TabDetectionState(items = listOf(media("https://x.test/a.mp4", MediaType.DIRECT_MP4))), off)
        assertEquals(0, ui.downloadableCount)
        assertTrue(ui.items.single().status is DetectionStatus.EngineUnavailable)
    }

    @Test
    fun withNoEngineSelectedNothingIsDownloadableAndNoEngineIsNamed() {
        val none = EngineInfo(null, "No engine", EngineAvailability.NotAvailable("No download engine is selected."), null)
        val ui = DetectionPresenter.build(
            TabDetectionState(items = listOf(media("https://x.test/a.mp4", MediaType.DIRECT_MP4), media("https://x.test/d.mpd", MediaType.DASH, protection = Protection.DRM))),
            none,
        )
        assertEquals(0, ui.downloadableCount)
        val unavailable = ui.items.filter { it.status is DetectionStatus.EngineUnavailable }
        assertEquals(1, unavailable.size)
        assertTrue((unavailable.single().status as DetectionStatus.EngineUnavailable).explanation.contains("No download engine is selected"))
        assertTrue("DRM is still reported as DRM", ui.items.any { it.status is DetectionStatus.Protected })
    }

    @Test
    fun emptyExplanationsAreHonest() {
        assertTrue(DetectionsUi().emptyExplanation!!.contains("No downloadable media"))
        assertTrue(DetectionsUi(checking = true).emptyExplanation!!.startsWith("Checking"))
        assertEquals(PageLimitation.DRM_PLAYER.message, DetectionsUi(limitation = PageLimitation.DRM_PLAYER).emptyExplanation)
    }

    @Test
    fun formatRowsShowOnlyWhatTheEngineReportedAndGroupThem() {
        val formats = listOf(
            FormatOption("a", StreamKind.AUDIO_ONLY, container = "m4a", audioCodec = "mp4a.40.2", bitrateKbps = 128, sizeBytes = 1_048_576, sizeIsEstimate = true),
            FormatOption("v", StreamKind.VIDEO_ONLY, container = "mp4", height = 2160, videoCodec = "av01", requiresMerge = true),
            FormatOption("m", StreamKind.VIDEO_AND_AUDIO, container = "mp4", height = 720, videoCodec = "avc1", audioCodec = "mp4a", sizeBytes = 10_485_760, fps = 60.0),
            FormatOption("o", StreamKind.ORIGINAL_FILE),
        )
        val rows = QualityPresenter.rows(formats)
        assertEquals(listOf("m", "o", "v", "a"), rows.map { it.id })
        assertEquals("720p 60 fps", rows[0].title)
        assertEquals("MP4 \u00B7 avc1 \u00B7 mp4a \u00B7 video + audio \u00B7 10.0 MB", rows[0].details)
        assertEquals("Original file", rows[1].title)
        assertEquals("as served \u00B7 Size unknown", rows[1].details)
        assertEquals("2160p", rows[2].title)
        assertTrue(rows[2].details.contains("video only, no sound"))
        assertTrue(rows[2].details.contains("merged with audio afterwards"))
        assertEquals("Audio \u00B7 128 kbps", rows[3].title)
        assertTrue(rows[3].details.contains("about 1.0 MB"))
        assertEquals(FormatGroup.AUDIO_ONLY, rows[3].group)
    }

    // ------------------------------------------------------------------------------------------- MediaDownloader

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp = java.nio.file.Files.createTempDirectory("mdl").toFile()

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

    private class StubEngine(val result: EngineResult<MediaInfo>, override val capabilities: EngineCapabilities) : DownloadEngine {
        override val id = EngineId.NEWPIPE
        override val displayName = "NewPipe"
        override val description = "x"
        var lastRequest: MediaRequest? = null
        override fun availability() = EngineAvailability.Available
        override suspend fun inspect(request: MediaRequest): EngineResult<MediaInfo> { lastRequest = request; return result }
        override suspend fun listFormats(request: MediaRequest): EngineResult<List<FormatOption>> = EngineResult.Success(emptyList())
        override fun download(task: DownloadTask, destinationDir: String): Flow<DownloadEvent> = emptyFlow()
        override suspend fun close() {}
    }

    private class Setup(val downloader: MediaDownloader, val store: Store, val engine: StubEngine)

    private val original = FormatOption("original", StreamKind.ORIGINAL_FILE, container = "mp4", sizeBytes = 2_097_152)

    private fun setup(formats: List<FormatOption> = listOf(original), ask: Boolean = true, quality: QualityPreference = QualityPreference.BEST, caps: EngineCapabilities = direct, failure: DownloadError? = null): Setup {
        val engine = StubEngine(
            failure?.let { EngineResult.Failure(it) } ?: EngineResult.Success(MediaInfo(EngineId.NEWPIPE, "https://x.test/a.mp4", title = "a.mp4", durationSeconds = 9, formats = formats)),
            caps,
        )
        val registry = object : EngineRegistry {
            override val descriptors = listOf(EngineDescriptor(EngineId.NEWPIPE, "d") { EngineAvailability.Available })
            override fun create(id: EngineId): DownloadEngine = engine
        }
        val selector = EngineSelector(registry, MutableStateFlow(EngineMode.NEWPIPE))
        val store = Store()
        val manager = DownloadManager(store = store, engines = selector, output = Out(), tempRoot = File(tmp, "dl"), settings = MutableStateFlow(ManagerSettings()), network = MutableStateFlow(NetworkState.OFFLINE), scope = scope)
        val downloader = MediaDownloader(selector, manager, { AppSettings(askBeforeDownloading = ask, defaultQuality = quality) }) { page, _ -> mapOf("Referer" to page.orEmpty(), "Cookie" to "s=1") }
        return Setup(downloader, store, engine)
    }

    private val target = DownloadTarget("https://x.test/a.mp4", "https://page.test/", "a.mp4", MediaType.DIRECT_MP4)

    @Test fun theSheetShowsWhatTheEngineReturnedAndQueuesNothingYet() = runBlocking {
        val s = setup()
        val ready = s.downloader.inspect(target) as QualityState.Ready
        assertEquals(listOf("original"), ready.rows.map { it.id })
        assertEquals("original", ready.selectedId)
        assertEquals("NewPipe", ready.engineLabel)
        assertNotNull(ready.notice)
        assertTrue(ready.canConfirm)
        assertTrue(s.store.tasks.isEmpty())
        assertEquals("https://page.test/", s.engine.lastRequest!!.headers["Referer"])
    }

    @Test fun aPlaylistReportedByTheEngineIsFlaggedSoTheBrowserCanListItInsteadOfFailing() = runBlocking {
        val s = setup(failure = DownloadError.playlistLink(EngineId.NEWPIPE))
        val state = s.downloader.inspect(target) as QualityState.Failed
        assertTrue(state.isPlaylist)
        val other = setup(failure = DownloadError.resourceUnavailable("gone"))
        assertFalse((other.downloader.inspect(target) as QualityState.Failed).isPlaylist)
    }

    @Test fun confirmingStoresTheEngineTheFormatAndKeepsHeadersOutOfTheRecord() = runBlocking {
        val s = setup()
        val ready = s.downloader.inspect(target) as QualityState.Ready
        val queued = s.downloader.confirm(ready, "original")
        assertTrue(queued is QualityState.Queued)
        val t = s.store.tasks.values.single()
        assertEquals(EngineId.NEWPIPE, t.engineId)
        assertEquals("original", t.format?.id)
        assertEquals("a.mp4", t.title)
        assertEquals(9L, t.durationSeconds)
        assertTrue(t.request.headers.isEmpty())
        assertEquals("https://page.test/", t.request.pageUrl)
    }

    @Test fun withoutAskingTheDefaultIsQueuedAtOnceButOnlyWhenItExists() = runBlocking {
        val auto = setup(ask = false)
        val state = auto.downloader.inspect(target)
        assertTrue(state is QualityState.Queued && state.automatic)
        val unmet = setup(ask = false, quality = QualityPreference.AUDIO_ONLY)
        assertTrue(unmet.downloader.inspect(target) is QualityState.Ready)
        assertTrue(unmet.store.tasks.isEmpty())
    }

    @Test fun anEngineThatReturnsNoFormatsIsExplainedAndOffersTheOriginalOnlyWhenItReallyCan() = runBlocking {
        val s = setup(formats = emptyList())
        val ready = s.downloader.inspect(target) as QualityState.Ready
        assertTrue(ready.rows.isEmpty())
        assertTrue(ready.notice!!.contains("did not report any quality options"))
        assertTrue(ready.originalFileAllowed)
        assertTrue(ready.canConfirm)
        assertTrue(s.downloader.confirm(ready, null) is QualityState.Queued)
        assertNull(s.store.tasks.values.single().format)

        val hlsOnly = EngineCapabilities(setOf(MediaType.HLS), supportsResume = false, listsFormats = true, mayNeedFfmpeg = true)
        val s2 = setup(formats = emptyList(), caps = hlsOnly)
        val r2 = s2.downloader.inspect(DownloadTarget("https://x.test/a.m3u8", null, null, MediaType.HLS)) as QualityState.Ready
        assertFalse(r2.originalFileAllowed)
        assertFalse(r2.canConfirm)
        assertTrue(s2.downloader.confirm(r2, null) is QualityState.Failed)
        assertTrue(s2.store.tasks.isEmpty())
    }

    @Test fun aResourceTheEngineCannotHandleFailsBeforeAnyRequestIsSent() = runBlocking {
        val s = setup()
        val state = s.downloader.inspect(DownloadTarget("https://x.test/a.m3u8", null, null, MediaType.HLS))
        assertTrue(state is QualityState.Failed)
        assertNull(s.engine.lastRequest)
    }

    @Test fun withoutAskingAVideoOnlyDefaultIsNeverQueuedByItself() = runBlocking {
        // The sharpest stream (137) has no sound and there is nothing to merge it with: the panel has no row for it, so the row
        // that is shown (and has sound) is what is queued.
        val formats = listOf(
            FormatOption("18", StreamKind.VIDEO_AND_AUDIO, container = "mp4", height = 360, videoCodec = "avc1", audioCodec = "mp4a.40.2", sizeBytes = 5_000_000),
            FormatOption("137", StreamKind.VIDEO_ONLY, container = "mp4", height = 1080, videoCodec = "avc1", sizeBytes = 40_000_000),
        )
        val caps = EngineCapabilities(setOf(MediaType.DIRECT_MP4, MediaType.PAGE), supportsResume = false, listsFormats = true, mayNeedFfmpeg = true)
        val s = setup(formats = formats, ask = false, caps = caps)
        val state = s.downloader.inspect(DownloadTarget("https://x.test/watch", "https://x.test/watch", null, MediaType.PAGE))
        assertTrue(state is QualityState.Queued && state.automatic)
        assertEquals("18", s.store.tasks.values.single().format?.id)
    }
}
