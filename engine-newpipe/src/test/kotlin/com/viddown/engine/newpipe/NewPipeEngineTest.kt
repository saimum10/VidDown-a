package com.viddown.engine.newpipe

import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.DownloadEvent
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineResult
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.MediaType
import com.viddown.engine.api.MergeResult
import com.viddown.engine.api.PlaylistInfo
import com.viddown.engine.api.StreamMerger
import com.viddown.engine.api.StreamKind
import com.viddown.engine.direct.RetryPolicy
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeGateway(var media: ExtractedMedia? = null, var error: DownloadError? = null, val supported: Boolean = true) : StreamGateway {
    val extractions = AtomicInteger()
    var lastToken: CancelToken? = null
    var closed = 0
    var block: (() -> Unit)? = null
    override fun supportsPage(url: String) = supported && url.contains("example.test") && !url.contains("example.test/playlist") // like the real extractor: a playlist is not a page
    override fun serviceNameFor(url: String) = if (supportsPage(url)) "Example" else null
    var playlist: ExtractedPlaylist? = null
    var lastPlaylistLimit: Int? = null
    override fun supportsPlaylist(url: String) = url.contains("example.test/playlist")
    override fun extractPlaylist(url: String, maxEntries: Int, cancel: CancelToken): ExtractedPlaylist {
        lastPlaylistLimit = maxEntries
        error?.let { throw GatewayException(it) }
        return playlist ?: throw IllegalStateException("no playlist")
    }
    override fun extract(url: String, cancel: CancelToken): ExtractedMedia {
        extractions.incrementAndGet()
        lastToken = cancel
        block?.invoke()
        error?.let { throw GatewayException(it) }
        return media ?: throw IllegalStateException("no media")
    }
    override fun close() { closed++ }
}

/** Puts the two files one after the other into the output; fails for the container extensions in [failFor]. */
private class FakeMerger(val failFor: Set<String> = emptySet()) : StreamMerger {
    var calls = 0
    var closed = 0
    val outputs = ArrayList<String>()
    override suspend fun merge(video: File, audio: File, output: File): MergeResult {
        calls++
        outputs += output.name
        if (output.extension in failFor) return MergeResult.Failed("fake merge failure for ${output.extension}")
        output.writeBytes(video.readBytes() + audio.readBytes())
        return MergeResult.Done
    }
    override fun close() { closed++ }
}

class NewPipeEngineTest {
    private lateinit var server: TestServer
    private lateinit var dir: File
    private val data = testBytes(2500)

    @Before fun setUp() {
        server = TestServer()
        server.route("/file") { ex, _ -> serveRange(ex, data) }
        dir = Files.createTempDirectory("npe").toFile()
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun muxed(id: String, height: Int, url: String = server.url("/file")) =
        ExtractedStream(id, StreamRole.VIDEO_AND_AUDIO, Delivery.PROGRESSIVE_HTTP, url, "mp4", "video/mp4", height = height)

    private fun media(vararg s: ExtractedStream, live: Boolean = false, title: String? = "My: video/clip") =
        ExtractedMedia("Example", title, 60, "https://img.example.test/t.jpg", "Someone", live, s.toList())

    private val page = "https://example.test/watch?v=1"
    private fun engine(g: FakeGateway) = NewPipeEngine(g, JdkTransport(), Dispatchers.IO, RetryPolicy(1, 1, 2), chunkBytes = 1000)
    private fun engineWith(g: FakeGateway, merger: StreamMerger) =
        NewPipeEngine(g, JdkTransport(), Dispatchers.IO, RetryPolicy(1, 1, 2), chunkBytes = 1000, merger = merger)

    private fun videoOnly(id: String, height: Int, url: String) = ExtractedStream(
        id, StreamRole.VIDEO_ONLY, Delivery.PROGRESSIVE_HTTP, url, "mp4", "video/mp4",
        height = height, videoCodec = "avc1.64001F", bitrateKbps = 2000,
    )

    private fun audioOnly(id: String, url: String) = ExtractedStream(
        id, StreamRole.AUDIO_ONLY, Delivery.PROGRESSIVE_HTTP, url, "m4a", "audio/mp4", audioCodec = "mp4a.40.2", bitrateKbps = 128,
    )
    private fun task(format: FormatOption?, title: String? = null) =
        DownloadTask("t1", EngineId.NEWPIPE, MediaRequest(page), format, title, createdAtMillis = 0)

    @Test
    fun describesItselfHonestly() {
        val e = engine(FakeGateway())
        assertEquals(EngineId.NEWPIPE, e.id)
        assertEquals(EngineAvailability.Available, e.availability())
        // pages of sites, and plain media files (the browser hands those over); never streaming playlists
        assertEquals(setOf(MediaType.PAGE, MediaType.DIRECT_MP4, MediaType.DIRECT_WEBM, MediaType.DIRECT_OTHER), e.capabilities.mediaTypes)
        assertFalse(e.capabilities.mayNeedFfmpeg)
        assertTrue(engineWith(FakeGateway(), FakeMerger()).capabilities.mayNeedFfmpeg)
        assertTrue(e.capabilities.supportsResume)
        assertTrue(e.capabilities.listsFormats)
        assertTrue(e.capabilities.handling(MediaType.HLS) is com.viddown.engine.api.EngineCapabilities.Handling.Unsupported)
        assertTrue(e.capabilities.handling(MediaType.DASH) is com.viddown.engine.api.EngineCapabilities.Handling.Unsupported)
        assertTrue(e.capabilities.handling(MediaType.DIRECT_MP4) is com.viddown.engine.api.EngineCapabilities.Handling.Supported)
    }

    @Test
    fun aPlaylistLinkIsListedAndNotProbedAsAFile() = runBlocking {
        val g = FakeGateway()
        g.playlist = ExtractedPlaylist(
            "Example", "My list", "Someone",
            listOf(
                ExtractedPlaylistEntry("https://example.test/watch?v=1", "One", 61, "https://img.example.test/1.jpg", "Someone"),
                ExtractedPlaylistEntry("https://example.test/watch?v=2", null, null, null, null),
            ),
            hasMore = true,
        )
        val e = engine(g)
        val list = (e.inspectPlaylist(MediaRequest("https://example.test/playlist?list=PL1")) as EngineResult.Success).value
        assertEquals(EngineId.NEWPIPE, list.engineId)
        assertEquals("My list", list.title)
        assertEquals(listOf("https://example.test/watch?v=1", "https://example.test/watch?v=2"), list.entries.map { it.url })
        assertEquals(61L, list.entries[0].durationSeconds)
        assertTrue(list.hasMore)
        assertEquals(PlaylistInfo.MAX_ENTRIES, g.lastPlaylistLimit)
        // read as one video it is reported as a playlist (not "this engine cannot handle this resource")
        val single = e.inspect(MediaRequest("https://example.test/playlist?list=PL1")) as EngineResult.Failure
        assertTrue(single.error.isPlaylist)
    }

    @Test
    fun inspectPlaylistRefusesALinkThatIsNotAPlaylistAndPassesExtractorErrorsOn() = runBlocking {
        val g = FakeGateway(media(muxed("18", 360)))
        val notAList = engine(g).inspectPlaylist(MediaRequest(page)) as EngineResult.Failure
        assertEquals(ErrorKind.UNSUPPORTED_RESOURCE, notAList.error.kind)
        assertFalse(notAList.error.isPlaylist)
        g.error = DownloadError.resourceUnavailable("gone")
        val failed = engine(g).inspectPlaylist(MediaRequest("https://example.test/playlist?list=PL1")) as EngineResult.Failure
        assertEquals(ErrorKind.RESOURCE_UNAVAILABLE, failed.error.kind)
    }

    @Test
    fun inspectReturnsOnlyTheRealDownloadableFormats() = runBlocking {
        val g = FakeGateway(media(muxed("18", 360), muxed("22", 720)))
        val r = engine(g).inspect(MediaRequest(page))
        assertTrue(r.toString(), r is EngineResult.Success)
        val info = (r as EngineResult.Success).value
        assertEquals("My: video/clip", info.title)
        assertEquals(60L, info.durationSeconds)
        assertEquals("Someone", info.uploader)
        assertEquals(listOf("av:22", "av:18"), info.formats.map { it.id })
        assertEquals(EngineId.NEWPIPE, info.engineId)
    }

    @Test
    fun anAddressNoServiceRecognisesThatIsAWebPageIsUnsupportedWithoutAnyExtraction() = runBlocking {
        server.route("/page") { ex, _ ->
            ex.responseHeaders.set("Content-Type", "text/html")
            if (ex.requestMethod == "HEAD") {
                ex.sendResponseHeaders(200, -1)
            } else {
                val body = "<html></html>".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        val g = FakeGateway(supported = false)
        val r = engine(g).inspect(MediaRequest(server.url("/page")))
        assertEquals(ErrorKind.UNSUPPORTED_RESOURCE, (r as EngineResult.Failure).error.kind)
        assertEquals(0, g.extractions.get())
    }

    @Test
    fun aPlainMediaFileThatNoServiceRecognisesIsOfferedAsTheFileAsServed() = runBlocking {
        // HEAD is refused, so the size has to come from the one-byte GET: that path is the one many real servers need.
        server.route("/clip.mp4") { ex, _ ->
            if (ex.requestMethod == "HEAD") ex.sendResponseHeaders(405, -1) else serveRange(ex, data)
        }
        val g = FakeGateway(supported = false)
        val r = engine(g).inspect(MediaRequest(server.url("/clip.mp4")))
        assertTrue(r.toString(), r is EngineResult.Success)
        val info = (r as EngineResult.Success).value
        assertEquals("clip.mp4", info.title)
        val only = info.formats.single()
        assertEquals(StreamKind.ORIGINAL_FILE, only.kind)
        assertEquals(data.size.toLong(), only.sizeBytes)
        assertEquals(EngineId.NEWPIPE, info.engineId)
        assertEquals(0, g.extractions.get())
    }

    @Test
    fun aPlainMediaFileIsSavedWithTheBrowsersHeaders() = runBlocking {
        server.route("/clip.mp4") { ex, _ -> serveRange(ex, data) }
        val g = FakeGateway(supported = false)
        val format = FormatOption(NewPipeEngine.ORIGINAL_FORMAT_ID, StreamKind.ORIGINAL_FILE, container = "mp4")
        val headers = mapOf("Referer" to "https://site.test/p", "Cookie" to "a=b")
        val t = DownloadTask("t1", EngineId.NEWPIPE, MediaRequest(server.url("/clip.mp4"), headers = headers), format, "clip.mp4", createdAtMillis = 0)
        val done = engine(g).download(t, dir.absolutePath).toList().last()
        assertTrue(done.toString(), done is DownloadEvent.Completed)
        val file = File((done as DownloadEvent.Completed).outputPath)
        assertEquals("clip.mp4", file.name)
        assertArrayEquals(data, file.readBytes())
        assertEquals(0, g.extractions.get())
        // the file was found with these headers, so they are sent for the file (unlike the streams of a site's page)
        assertTrue(server.requests.isNotEmpty())
        assertTrue(server.requests.all { it.header("referer") == "https://site.test/p" && it.header("cookie") == "a=b" })
    }

    @Test
    fun nonHttpAddressesAreRefusedWithoutAnyExtraction() = runBlocking {
        val g = FakeGateway()
        val r = engine(g).inspect(MediaRequest("ftp://example.test/x"))
        assertEquals(ErrorKind.UNSUPPORTED_RESOURCE, (r as EngineResult.Failure).error.kind)
        assertEquals(0, g.extractions.get())
    }

    @Test
    fun liveStreamsAreRefusedWithAnExplanation() = runBlocking {
        val r = engine(FakeGateway(media(muxed("1", 720), live = true))).inspect(MediaRequest(page))
        val e = (r as EngineResult.Failure).error
        assertEquals(ErrorKind.UNSUPPORTED_FORMAT, e.kind)
        assertTrue(e.userMessage.contains("live stream"))
    }

    @Test
    fun whenOnlySeparateStreamsExistTheErrorSaysFfmpegIsTheReason() = runBlocking {
        val v = ExtractedStream("137", StreamRole.VIDEO_ONLY, Delivery.PROGRESSIVE_HTTP, "https://x.test/v", "mp4", null, height = 1080)
        val a = ExtractedStream("140", StreamRole.AUDIO_ONLY, Delivery.DASH, "https://x.test/a", "m4a", null)
        val r = engine(FakeGateway(media(v, a))).inspect(MediaRequest(page))
        val e = (r as EngineResult.Failure).error
        assertEquals(ErrorKind.UNSUPPORTED_FORMAT, e.kind)
        assertTrue(e.userMessage, e.userMessage.contains("FFmpeg"))
        assertEquals(SuggestedActionCheck.CHANGE_ENGINE, SuggestedActionCheck.of(e))
    }

    @Test
    fun translatedGatewayErrorsPassThroughUnchanged() = runBlocking {
        val err = DownloadError.accessRestricted(EngineId.NEWPIPE, "This video is age-restricted.", "AgeRestrictedContentException")
        val r = engine(FakeGateway(error = err)).inspect(MediaRequest(page))
        assertEquals(err, (r as EngineResult.Failure).error)
        assertTrue(err.userMessage.contains("does not sign in"))
    }

    @Test
    fun anUnexpectedLibraryExceptionBecomesAnExtractionErrorNotACrash() = runBlocking {
        val r = engine(FakeGateway()).inspect(MediaRequest(page)) // the fake throws IllegalStateException when it has no media
        assertEquals(ErrorKind.EXTRACTION_FAILED, (r as EngineResult.Failure).error.kind)
    }

    @Test
    fun downloadReExtractsThenSavesTheChosenFormatUnderTheTitle() = runBlocking {
        val g = FakeGateway(media(muxed("18", 360), muxed("22", 720)))
        val e = engine(g)
        val info = (e.inspect(MediaRequest(page)) as EngineResult.Success).value
        val chosen = info.formats.first { it.id == "av:18" }
        val events = e.download(task(chosen, title = info.title), dir.absolutePath).toList()
        assertTrue(events.first() is DownloadEvent.Started)
        val done = events.last()
        assertTrue(done.toString(), done is DownloadEvent.Completed)
        val file = File((done as DownloadEvent.Completed).outputPath)
        assertEquals(2, g.extractions.get()) // once to inspect, once more when the download starts
        assertTrue(file.name, file.name.endsWith(".mp4"))
        assertFalse(file.name.contains("/") || file.name.contains(":"))
        assertArrayEquals(data, file.readBytes())
        assertEquals(data.size.toLong(), done.sizeBytes)
    }

    @Test
    fun aFormatTheSiteNoLongerOffersFailsInsteadOfSubstitutingAnotherQuality() = runBlocking {
        val g = FakeGateway(media(muxed("22", 720)))
        val gone = FormatOption("av:18", StreamKind.VIDEO_AND_AUDIO, container = "mp4", height = 360)
        val last = engine(g).download(task(gone), dir.absolutePath).toList().last()
        assertTrue(last is DownloadEvent.Failed)
        assertEquals(ErrorKind.UNSUPPORTED_FORMAT, (last as DownloadEvent.Failed).error.kind)
        assertEquals(0, server.requests.size) // nothing was fetched
    }

    @Test
    fun aDownloadWithoutAChosenFormatFails() = runBlocking {
        val last = engine(FakeGateway(media(muxed("22", 720)))).download(task(null), dir.absolutePath).toList().last()
        assertTrue(last is DownloadEvent.Failed)
    }

    @Test
    fun cancellingWhileTheExtractorIsBlockedCancelsItsNetworkCalls() = runBlocking {
        val g = FakeGateway(media(muxed("18", 360)))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        g.block = { entered.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        val e = engine(g)
        val job = launch(Dispatchers.Default) { e.inspect(MediaRequest(page)) }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val token = g.lastToken!!
        assertFalse(token.isCancelled)
        job.cancel()
        withTimeout(3_000) { while (!token.isCancelled) delay(10) }
        release.countDown()
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(token.isCancelled)
    }

    @Test
    fun closeIsSafeToRepeatAndReleasesTheGateway() = runBlocking {
        val g = FakeGateway()
        val e = engine(g)
        e.close(); e.close()
        assertEquals(2, g.closed)
        assertNull(null)
    }

    @Test
    fun supportsPageIsOfflineAndOnlyForHttpAddresses() {
        val e = engine(FakeGateway())
        assertTrue(e.supportsPage(page))
        assertFalse(e.supportsPage("https://other.invalid/"))
        assertFalse(e.supportsPage("file:///etc/passwd"))
    }

    // ------------------------------------------------------------------------------------------------ merged qualities

    private fun separateStreamsMedia(vData: ByteArray, aData: ByteArray): ExtractedMedia {
        server.route("/v") { ex, _ -> serveRange(ex, vData) }
        server.route("/a") { ex, _ -> serveRange(ex, aData, contentType = "audio/mp4") }
        return media(muxed("18", 360), videoOnly("137", 1080, server.url("/v")), audioOnly("140", server.url("/a")))
    }

    @Test
    fun withAMergerSeparateStreamsAreOfferedAsOneQualityAndSavedMerged() = runBlocking {
        val vData = testBytes(2500, 1)
        val aData = testBytes(1200, 2)
        val merger = FakeMerger()
        val e = engineWith(FakeGateway(separateStreamsMedia(vData, aData)), merger)
        val info = (e.inspect(MediaRequest(page)) as EngineResult.Success).value
        val hd = info.formats.first { it.requiresMerge }
        assertEquals("mrg:v:137+a:140", hd.id)
        assertEquals(StreamKind.VIDEO_AND_AUDIO, hd.kind)
        assertEquals(1080, hd.height)
        assertEquals("mp4", hd.container)
        assertTrue(info.formats.any { it.id == "av:18" }) // the site's own single-file quality is still there

        val events = e.download(task(hd, title = info.title), dir.absolutePath).toList()
        val done = events.last()
        assertTrue(done.toString(), done is DownloadEvent.Completed)
        val file = File((done as DownloadEvent.Completed).outputPath)
        assertTrue(file.name, file.name.endsWith(".mp4"))
        assertArrayEquals(vData + aData, file.readBytes())
        assertEquals(1, merger.calls)
        assertTrue(events.any { it is DownloadEvent.Processing })
        assertFalse(File(dir, "video").exists())
        assertFalse(File(dir, "audio").exists())
        // progress describes the whole download: video bytes first, then the audio bytes on top of them
        val last = events.filterIsInstance<DownloadEvent.Progress>().last()
        assertEquals((vData.size + aData.size).toLong(), last.bytesDownloaded)
    }

    @Test
    fun aMergeThatFailsInTheChosenContainerIsRetriedAsMatroska() = runBlocking {
        val merger = FakeMerger(failFor = setOf("mp4"))
        val e = engineWith(FakeGateway(separateStreamsMedia(testBytes(1500, 3), testBytes(900, 4))), merger)
        val hd = (e.inspect(MediaRequest(page)) as EngineResult.Success).value.formats.first { it.requiresMerge }
        val done = e.download(task(hd, title = "clip"), dir.absolutePath).toList().last()
        assertTrue(done.toString(), done is DownloadEvent.Completed)
        assertTrue((done as DownloadEvent.Completed).outputPath.endsWith(".mkv"))
        assertEquals(2, merger.calls)
    }

    @Test
    fun aMergeThatFailsEverywhereIsAProcessingFailureNotACompletedDownload() = runBlocking {
        val merger = FakeMerger(failFor = setOf("mp4", "mkv"))
        val e = engineWith(FakeGateway(separateStreamsMedia(testBytes(1500, 3), testBytes(900, 4))), merger)
        val hd = (e.inspect(MediaRequest(page)) as EngineResult.Success).value.formats.first { it.requiresMerge }
        val last = e.download(task(hd, title = "clip"), dir.absolutePath).toList().last()
        assertTrue(last.toString(), last is DownloadEvent.Failed)
        assertEquals(ErrorKind.FFMPEG_FAILURE, (last as DownloadEvent.Failed).error.kind)
        assertEquals(0, dir.listFiles { f -> f.isFile && f.extension in setOf("mp4", "mkv") }!!.size)
    }

    @Test
    fun aMergedQualityCannotBeSavedWithoutAMerger() = runBlocking {
        val g = FakeGateway(separateStreamsMedia(testBytes(1500, 3), testBytes(900, 4)))
        val merged = FormatOption("mrg:v:137+a:140", StreamKind.VIDEO_AND_AUDIO, container = "mp4", height = 1080, requiresMerge = true)
        val last = engine(g).download(task(merged), dir.absolutePath).toList().last()
        // this engine has no merger: it says so instead of saving something else
        assertTrue(last is DownloadEvent.Failed)
        assertEquals(ErrorKind.UNSUPPORTED_FORMAT, (last as DownloadEvent.Failed).error.kind)
    }

    @Test
    fun aMergedQualityWhoseStreamIsGoneFailsInsteadOfSubstitutingAnother() = runBlocking {
        val g = FakeGateway(media(muxed("18", 360), audioOnly("140", server.url("/a"))))
        val gone = FormatOption("mrg:v:137+a:140", StreamKind.VIDEO_AND_AUDIO, container = "mp4", height = 1080, requiresMerge = true)
        val last = engineWith(g, FakeMerger()).download(task(gone), dir.absolutePath).toList().last()
        assertTrue(last is DownloadEvent.Failed)
        assertEquals(ErrorKind.UNSUPPORTED_FORMAT, (last as DownloadEvent.Failed).error.kind)
        assertEquals(0, server.requests.size) // nothing was fetched
    }

    @Test
    fun closingTheEngineStopsTheMerger() = runBlocking {
        val merger = FakeMerger()
        val e = engineWith(FakeGateway(), merger)
        e.close()
        assertEquals(1, merger.closed)
    }

    private object SuggestedActionCheck {
        val CHANGE_ENGINE = com.viddown.engine.api.SuggestedAction.CHANGE_ENGINE
        fun of(e: DownloadError) = e.suggestedAction
    }
}
