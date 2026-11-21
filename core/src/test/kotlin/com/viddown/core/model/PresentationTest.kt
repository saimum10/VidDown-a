package com.viddown.core.model

import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationTest {
    private val engine = SearchEngine.DUCKDUCKGO

    private fun parse(s: String) = UrlInput.parse(s, engine)

    // ---- UrlInput -------------------------------------------------------------------------------------------------

    @Test
    fun emptyInput() {
        assertEquals(UrlInputResult.Empty, parse("   "))
    }

    @Test
    fun fullHttpAddressesAreKept() {
        assertEquals(UrlInputResult.Url("https://example.com/a?b=1"), parse(" https://example.com/a?b=1 "))
        assertEquals(UrlInputResult.Url("http://example.com"), parse("http://example.com"))
    }

    @Test
    fun bareDomainsGetHttps() {
        assertEquals(UrlInputResult.Url("https://example.com"), parse("example.com"))
        assertEquals(UrlInputResult.Url("https://sub.example.co.uk/path"), parse("sub.example.co.uk/path"))
        assertEquals(UrlInputResult.Url("https://example.com:8443/x"), parse("example.com:8443/x"))
    }

    @Test
    fun localhostAndIpAddressesUseHttp() {
        assertEquals(UrlInputResult.Url("http://localhost:8080"), parse("localhost:8080"))
        assertEquals(UrlInputResult.Url("http://192.168.1.10:3000/x"), parse("192.168.1.10:3000/x"))
    }

    @Test
    fun wordsBecomeASearch() {
        val r = parse("cats playing piano") as UrlInputResult.Search
        assertEquals("https://duckduckgo.com/?q=cats+playing+piano", r.url)
        assertTrue(parse("hello") is UrlInputResult.Search)
    }

    @Test
    fun dangerousSchemesAreRefusedNotOpened() {
        for (text in listOf("javascript:alert(1)", "JAVASCRIPT:alert(1)", "file:///etc/passwd", "content://x/y", "intent://x#Intent;end", "data:text/html,<b>", "ftp://example.com/f", "mailto:a@b.c", "about:blank")) {
            assertTrue("$text -> ${parse(text)}", parse(text) is UrlInputResult.UnsupportedScheme)
        }
        assertEquals(UrlInputResult.UnsupportedScheme("javascript"), parse("javascript:alert(1)"))
    }

    @Test
    fun hostlessAddressIsInvalid() {
        assertTrue(parse("https://") is UrlInputResult.Invalid)
    }

    // ---- Formatters -----------------------------------------------------------------------------------------------

    @Test
    fun bytesAreHumanReadableAndUnknownStaysNull() {
        assertNull(Formatters.bytes(null))
        assertEquals("0 B", Formatters.bytes(0))
        assertEquals("1023 B", Formatters.bytes(1023))
        assertEquals("1.0 KB", Formatters.bytes(1024))
        assertEquals("1.5 MB", Formatters.bytes(1_572_864))
        assertEquals("2.0 GB", Formatters.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun durationsAndFileNames() {
        assertNull(Formatters.duration(null))
        assertEquals("0:07", Formatters.duration(7))
        assertEquals("3:07", Formatters.duration(187))
        assertEquals("1:02:03", Formatters.duration(3723))
        assertEquals("a video.mp4", Formatters.fileNameFromUrl("https://x.test/dir/a%20video.mp4?token=1#t"))
        assertNull(Formatters.fileNameFromUrl("https://x.test/"))
        assertEquals("clip.mp4", Formatters.fileNameFromPath("/storage/emulated/0/Movies/clip.mp4"))
    }

    // ---- DownloadUi -----------------------------------------------------------------------------------------------

    private fun task(
        id: String = "1", state: DownloadState = DownloadState.DOWNLOADING, done: Long = 0, total: Long? = null,
        title: String? = null, format: FormatOption? = null, path: String? = null, created: Long = 0, url: String = "https://x.test/v/a.mp4",
        error: DownloadError? = null, engineId: EngineId = EngineId.NEWPIPE,
    ) = DownloadTask(id, engineId, MediaRequest(url), format, title, null, state, done, total, path, error, created)

    @Test
    fun titleFallsBackFromTitleToFileToUrl() {
        assertEquals("My video", DownloadUi.title(task(title = "My video", path = "/x/f.mp4")))
        assertEquals("f.mp4", DownloadUi.title(task(path = "/x/f.mp4")))
        assertEquals("a.mp4", DownloadUi.title(task()))
    }

    @Test
    fun progressIsNullWhenTotalUnknownAndTextNeverInventsATotal() {
        val unknown = DownloadUi.item(task(done = 5_000_000))
        assertNull(unknown.progress)
        assertEquals("4.8 MB downloaded", unknown.progressText)
        val known = DownloadUi.item(task(done = 500, total = 1000))
        assertEquals(0.5f, known.progress!!, 0.001f)
        assertEquals("500 B of 1000 B", known.progressText)
        assertNull(DownloadUi.item(task(done = 0, total = null)).progressText)
    }

    @Test
    fun completedShowsFullProgressAndEngineIsAlwaysNamed() {
        val i = DownloadUi.item(task(state = DownloadState.COMPLETED, done = 10, total = 10, engineId = EngineId.YTDLP))
        assertEquals(1f, i.progress!!, 0.001f)
        assertEquals("yt-dlp", i.engineLabel)
        assertTrue(i.canDeleteRecord)
        assertFalse(DownloadUi.item(task(state = DownloadState.DOWNLOADING)).canDeleteRecord)
    }

    @Test
    fun qualityLabels() {
        fun fmt(kind: StreamKind, h: Int? = null, c: String? = null) = FormatOption("f", kind, container = c, height = h)
        assertEquals("1080p · MP4", DownloadUi.qualityLabel(task(format = fmt(StreamKind.VIDEO_AND_AUDIO, 1080, "mp4"))))
        assertEquals("Original file · MP4", DownloadUi.qualityLabel(task(format = fmt(StreamKind.ORIGINAL_FILE, null, "mp4"))))
        assertEquals("Audio only · M4A", DownloadUi.qualityLabel(task(format = fmt(StreamKind.AUDIO_ONLY, null, "m4a"))))
        assertNull(DownloadUi.qualityLabel(task(format = null)))
    }

    @Test
    fun errorsAreShownWithTheirMessage() {
        val i = DownloadUi.item(task(state = DownloadState.FAILED, error = DownloadError.serverRejected(404, "GET /a")))
        assertTrue(i.errorMessage!!.contains("404"))
        assertEquals("GET /a", i.errorDetail)
    }

    @Test
    fun summaryReportsProgressOnlyWhenEveryRunningTotalIsKnown() {
        val s1 = DownloadUi.summary(listOf(task("a", done = 50, total = 100), task("b", done = 150, total = 300), task("c", DownloadState.QUEUED)))
        assertEquals(2, s1.active)
        assertEquals(1, s1.queued)
        assertEquals(0.5f, s1.progress!!, 0.001f)
        val s2 = DownloadUi.summary(listOf(task("a", done = 50, total = 100), task("b", done = 150, total = null)))
        assertNull(s2.progress)
        val s3 = DownloadUi.summary(listOf(task("a", DownloadState.COMPLETED)))
        assertFalse(s3.hasWork)
    }

    // ---- LibraryQuery ---------------------------------------------------------------------------------------------

    private fun done(id: String, title: String, created: Long, size: Long, kind: StreamKind = StreamKind.VIDEO_AND_AUDIO, ext: String? = "mp4") =
        task(id, DownloadState.COMPLETED, done = size, total = size, title = title, created = created, format = FormatOption("f", kind, container = ext))

    @Test
    fun libraryOnlyContainsCompletedDownloads() {
        val list = listOf(done("1", "A", 1, 10), task("2", DownloadState.FAILED), task("3", DownloadState.DOWNLOADING))
        assertEquals(listOf("1"), LibraryQuery.apply(list, "", LibrarySort.NEWEST, LibraryFilter.ALL).map { it.id })
    }

    @Test
    fun searchIsCaseInsensitiveAndSortsWork() {
        val list = listOf(done("1", "Banana song", 1, 300), done("2", "apple clip", 3, 100), done("3", "Cherry film", 2, 200))
        assertEquals(listOf("2"), LibraryQuery.apply(list, "  APPLE ", LibrarySort.NEWEST, LibraryFilter.ALL).map { it.id })
        assertEquals(listOf("2", "3", "1"), LibraryQuery.apply(list, "", LibrarySort.NEWEST, LibraryFilter.ALL).map { it.id })
        assertEquals(listOf("1", "3", "2"), LibraryQuery.apply(list, "", LibrarySort.OLDEST, LibraryFilter.ALL).map { it.id })
        assertEquals(listOf("2", "1", "3"), LibraryQuery.apply(list, "", LibrarySort.NAME, LibraryFilter.ALL).map { it.id })
        assertEquals(listOf("1", "3", "2"), LibraryQuery.apply(list, "", LibrarySort.SIZE, LibraryFilter.ALL).map { it.id })
    }

    @Test
    fun audioAndVideoFilters() {
        val list = listOf(
            done("v", "Video", 1, 1),
            done("a", "Audio", 2, 1, kind = StreamKind.AUDIO_ONLY, ext = "m4a"),
            done("o", "Original mp3", 3, 1, kind = StreamKind.ORIGINAL_FILE, ext = "mp3"),
        )
        assertEquals(setOf("a", "o"), LibraryQuery.apply(list, "", LibrarySort.NEWEST, LibraryFilter.AUDIO).map { it.id }.toSet())
        assertEquals(listOf("v"), LibraryQuery.apply(list, "", LibrarySort.NEWEST, LibraryFilter.VIDEO).map { it.id })
    }

    // ---- Phase 4: controls, speed and time left -------------------------------------------------------------------

    @Test
    fun controlsFollowTheState() {
        for (state in DownloadState.entries) {
            val i = DownloadUi.item(task(state = state))
            assertEquals("$state cancel", state == DownloadState.QUEUED || state.isActive, i.canCancel)
            assertEquals("$state retry", state == DownloadState.FAILED || state == DownloadState.CANCELLED, i.canRetry)
            assertFalse("$state: never both", i.canCancel && i.canRetry)
        }
    }

    @Test
    fun speedAndEtaFormatting() {
        assertEquals("1.5 MB/s", Formatters.speed(1_572_864))
        assertEquals("512 B/s", Formatters.speed(512))
        assertNull(Formatters.speed(null))
        assertNull(Formatters.speed(0))
        assertEquals("45 s left", Formatters.eta(45))
        assertEquals("1 min left", Formatters.eta(60))
        assertEquals("3 min left", Formatters.eta(150))
        assertEquals("1 h left", Formatters.eta(3600))
        assertEquals("1 h 5 min left", Formatters.eta(3900))
        assertEquals("2 h left", Formatters.eta(3600 + 3590))
        assertNull(Formatters.eta(null))
        assertNull(Formatters.eta(-1))
    }

    @Test
    fun liveDetailsAreOnlyShownWhereTheyMakeSense() {
        val downloading = DownloadUi.item(task(state = DownloadState.DOWNLOADING, done = 5, total = 10))
        val live = DownloadUi.withLive(downloading, 2048, 30, null, "Waiting for Wi-Fi")
        assertEquals("2.0 KB/s", live.speedText)
        assertEquals("30 s left", live.etaText)
        assertNull("a waiting note does not belong to a running download", live.note)
        assertNull(DownloadUi.withLive(downloading, null, null, null, null).speedText)

        val queued = DownloadUi.withLive(DownloadUi.item(task(state = DownloadState.QUEUED)), 999, 9, null, "Waiting for Wi-Fi")
        assertEquals("Waiting for Wi-Fi", queued.note)
        assertNull("no speed for something that is not downloading", queued.speedText)

        val processing = DownloadUi.withLive(DownloadUi.item(task(state = DownloadState.PROCESSING)), null, null, "Saving", null)
        assertEquals("Saving", processing.note)

        val done = DownloadUi.item(task(state = DownloadState.COMPLETED))
        assertEquals(done, DownloadUi.withLive(done, 1, 1, "x", "y"))
    }

    // ---- Diagnostics ------------------------------------------------------------------------------------------------

    @Test
    fun diagnosticsContainNoLinksTitlesOrSecrets() {
        val failed = task(
            id = "f1", state = DownloadState.FAILED, title = "My private holiday video", url = "https://cdn.example.org/secret/path/video.mp4?token=TOPSECRET&sig=abc",
            error = DownloadError.serverRejected(403, "GET https://cdn.example.org/secret/path/video.mp4?token=TOPSECRET failed; Authorization: Bearer abc123"),
        )
        val ok = task(id = "c1", state = DownloadState.COMPLETED, title = "Another title")
        val report = DiagnosticsReport.build(
            appVersion = "1.2.3", androidApi = 34, abis = listOf("arm64-v8a"),
            settings = AppSettings(downloadTreeUri = "content://com.android.externalstorage.documents/tree/primary%3APrivate"),
            engines = listOf("NewPipe" to "Available", "yt-dlp" to "Not available in this build"),
            tasks = listOf(failed, ok),
        )
        assertTrue(report, report.contains("cdn.example.org"))
        assertTrue(report, report.contains("SERVER_REJECTED"))
        assertTrue(report, report.contains("http=403"))
        assertTrue(report, report.contains("NewPipe: Available"))
        assertTrue(report, report.contains("FAILED: 1") && report.contains("COMPLETED: 1"))
        assertTrue(report, report.contains("Save location: custom folder"))
        for (secret in listOf("TOPSECRET", "abc123", "/secret/path", "holiday", "Another title", "externalstorage", "Private")) {
            assertFalse("leaked '$secret'", report.contains(secret))
        }
    }

    @Test
    fun diagnosticsListAtMostTenFailuresNewestFirstAndHandleNothingToReport() {
        val many = (1..15).map { task(id = "t$it", state = DownloadState.FAILED, url = "https://h$it.test/a.mp4", created = it.toLong(), error = DownloadError.serverRejected(500)) }
        val report = DiagnosticsReport.build("1", 30, emptyList(), AppSettings(), emptyList(), many)
        assertEquals(10, report.lines().count { it.contains("kind=SERVER_REJECTED") })
        assertTrue(report.indexOf("h15.test") < report.indexOf("h6.test"))
        assertFalse(report.contains("h5.test"))
        assertTrue(report.contains("Device ABIs: unknown"))

        val empty = DiagnosticsReport.build("1", 30, listOf("x86_64"), AppSettings(), emptyList(), emptyList())
        assertTrue(empty.contains("Downloads: 0"))
        assertFalse(empty.contains("Latest failures"))
    }
}
