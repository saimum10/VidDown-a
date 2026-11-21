package com.viddown.engine.ytdlp

import com.viddown.engine.api.DownloadEvent
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineResult
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.StreamKind
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A process that has already finished. */
private class DoneProcess(stdoutText: String = "", stderrText: String = "", private val code: Int = 0) : RunningProcess {
    override val stdout: InputStream = ByteArrayInputStream(stdoutText.toByteArray())
    override val stderr: InputStream = ByteArrayInputStream(stderrText.toByteArray())
    override fun exitCodeOrNull(): Int? = code
    override fun kill() {}
}

/** A process that runs until it is killed. */
private class HangingProcess : RunningProcess {
    private val dead = CountDownLatch(1)

    @Volatile
    var killed = false

    private val blocking = object : InputStream() {
        override fun read(): Int {
            dead.await()
            return -1
        }
    }
    override val stdout: InputStream = blocking
    override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
    override fun exitCodeOrNull(): Int? = if (killed) 137 else null
    override fun kill() {
        killed = true
        dead.countDown()
    }
}

private class FakeTools(private val script: (Tool, List<String>) -> RunningProcess) : NativeTools {
    var prepareCalls = 0
    var prepareFailure: NativeToolsException? = null
    val started = CopyOnWriteArrayList<Pair<Tool, List<String>>>()

    override fun prepare() {
        prepareCalls++
        prepareFailure?.let { throw it }
    }

    override fun start(tool: Tool, args: List<String>): RunningProcess {
        started += tool to args
        return script(tool, args)
    }

    fun count(tool: Tool) = started.count { it.first == tool }
}

class YtDlpEngineTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ytdlp-engine-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val listing = """
    {"title":"Sample","duration":120,"formats":[
      {"format_id":"18","ext":"mp4","protocol":"https","vcodec":"avc1","acodec":"mp4a.40.2","height":360,"filesize":1000},
      {"format_id":"137","ext":"mp4","protocol":"https","vcodec":"avc1.640028","acodec":"none","height":1080,"filesize":3000},
      {"format_id":"140","ext":"m4a","protocol":"https","vcodec":"none","acodec":"mp4a.40.2","abr":128,"filesize":500}]}
    """.trimIndent()

    private val probeBoth = """{"streams":[{"codec_type":"video","codec_name":"h264"},{"codec_type":"audio","codec_name":"aac"}],"format":{"format_name":"mov,mp4,m4a","duration":"120"}}"""
    private val probeVideoOnly = """{"streams":[{"codec_type":"video","codec_name":"h264"}],"format":{"format_name":"mov,mp4,m4a","duration":"120"}}"""
    private val probeTs = """{"streams":[{"codec_type":"video","codec_name":"h264"},{"codec_type":"audio","codec_name":"aac"}],"format":{"format_name":"mpegts","duration":"120"}}"""

    private fun arg(args: List<String>, flag: String) = args[args.indexOf(flag) + 1]

    /** Plays yt-dlp: writes the file the real tool would write and prints the lines the engine reads. */
    private fun ytDlpDownload(args: List<String>, ext: String, bytes: Int): RunningProcess {
        val folder = File(arg(args, "-P"))
        val name = arg(args, "-o").replace("%(ext)s", ext)
        val file = File(folder, name)
        file.writeBytes(ByteArray(bytes) { 1 })
        return DoneProcess("VDPROG|${bytes / 2}|$bytes|NA|1000|5\nVDPROG|$bytes|$bytes|NA|1000|0\nVDFILE|${file.absolutePath}\n")
    }

    private fun task(format: FormatOption?, title: String? = "My Title", url: String = "https://site.test/v/1") = DownloadTask(
        id = "t1", engineId = EngineId.YTDLP, request = MediaRequest(url), format = format, title = title, durationSeconds = 120, createdAtMillis = 0,
    )

    private val complete = FormatOption(id = "18", kind = StreamKind.VIDEO_AND_AUDIO, container = "mp4", sizeBytes = 1000)
    private val merged = FormatOption(
        id = "137+140", kind = StreamKind.VIDEO_AND_AUDIO, container = "mp4", videoCodec = "avc1.640028", audioCodec = "mp4a.40.2",
        sizeBytes = 3500, requiresMerge = true,
    )

    private fun engine(tools: FakeTools, loopback: Boolean = false) =
        YtDlpEngine(tools, allowLoopbackHttp = loopback, idleTimeoutMs = 0)

    // ------------------------------------------------------------------------------------------------ inspection

    @Test
    fun inspectListsOnlyWhatYtDlpReportedAndAsksOnlyOnce() = runBlocking {
        val tools = FakeTools { tool, _ -> if (tool == Tool.YT_DLP) DoneProcess(listing) else DoneProcess() }
        val e = engine(tools)
        val first = e.inspect(MediaRequest("https://site.test/v/1")) as EngineResult.Success
        assertEquals("Sample", first.value.title)
        assertEquals(setOf("18", "137", "137+140", "140"), first.value.formats.map { it.id }.toSet())
        val again = e.listFormats(MediaRequest("https://site.test/v/1")) as EngineResult.Success
        assertEquals(4, again.value.size)
        assertEquals(1, tools.count(Tool.YT_DLP))
        assertEquals(1, tools.prepareCalls)
    }

    @Test
    fun theBrowsersCookiesReachYtDlpOnlyThroughATemporaryFileThatIsDeletedAfterTheRun() = runBlocking {
        var cookiePath: String? = null
        var cookieText: String? = null
        val tools = FakeTools { tool, args ->
            val at = args.indexOf("--cookies")
            if (tool == Tool.YT_DLP && at >= 0) {
                cookiePath = args[at + 1]
                cookieText = File(args[at + 1]).readText()
            }
            DoneProcess(listing)
        }
        val request = MediaRequest("https://www.site.test/v/1", headers = mapOf("Cookie" to "sid=abc; theme=dark", "Authorization" to "Bearer x"))
        assertTrue(YtDlpEngine(tools, idleTimeoutMs = 0, cookieDir = dir).inspect(request) is EngineResult.Success)
        val commandLine = tools.started.last().second.joinToString(" ")
        assertFalse(commandLine.contains("sid=abc"))
        assertFalse(commandLine.contains("Bearer"))
        assertTrue(cookieText!!.contains(".site.test\tTRUE\t/\tTRUE\t"))
        assertTrue(cookieText!!.contains("\tsid\tabc\n"))
        assertFalse(cookieText!!.contains("Bearer"))
        assertFalse(File(cookiePath!!).exists())
    }

    @Test
    fun aRequestWithoutCookiesStartsYtDlpWithoutACookieFile() = runBlocking {
        val tools = FakeTools { _, _ -> DoneProcess(listing) }
        assertTrue(YtDlpEngine(tools, idleTimeoutMs = 0, cookieDir = dir).inspect(MediaRequest("https://www.site.test/v/1")) is EngineResult.Success)
        assertFalse(tools.started.last().second.contains("--cookies"))
        assertEquals(0, dir.listFiles()!!.count { it.name.startsWith("vd-cookies-") })
    }

    @Test
    fun plainHttpIsRefusedBeforeAnythingStarts() = runBlocking {
        val tools = FakeTools { _, _ -> DoneProcess(listing) }
        val r = engine(tools).inspect(MediaRequest("http://site.test/v/1")) as EngineResult.Failure
        assertEquals(ErrorKind.SERVER_REJECTED, r.error.kind)
        assertEquals(0, tools.prepareCalls)
        assertEquals(0, tools.started.size)
    }

    @Test
    fun loopbackHttpIsOnlyAllowedWhenAskedForAndNeverWithCredentials() = runBlocking {
        val tools = FakeTools { _, _ -> DoneProcess(listing) }
        assertTrue(engine(tools, loopback = true).inspect(MediaRequest("http://127.0.0.1:8080/a.mp4")) is EngineResult.Success)
        val tricky = engine(FakeTools { _, _ -> DoneProcess(listing) }, loopback = true)
        assertTrue(tricky.inspect(MediaRequest("http://127.0.0.1:80@evil.test/a.mp4")) is EngineResult.Failure)
    }

    @Test
    fun readingAPlaylistAsOneVideoIsReportedAsAPlaylistSoTheCallerCanListIt() = runBlocking {
        val tools = FakeTools { _, _ -> DoneProcess("""{"_type":"playlist","entries":[]}""") }
        val r = engine(tools).inspect(MediaRequest("https://site.test/list")) as EngineResult.Failure
        assertEquals(ErrorKind.UNSUPPORTED_RESOURCE, r.error.kind)
        assertTrue(r.error.isPlaylist)
        assertTrue(r.error.userMessage.contains("playlist"))
    }

    private val playlistJson = """
    {"_type":"playlist","title":"My list","uploader":"Someone","playlist_count":3,"entries":[
      {"_type":"url","ie_key":"Youtube","id":"aaaaaaaaaaa","url":"https://www.youtube.com/watch?v=aaaaaaaaaaa","title":"First","duration":61.0,"uploader":"Someone"},
      {"_type":"url","ie_key":"Youtube","id":"bbbbbbbbbbb","url":"https://www.youtube.com/watch?v=bbbbbbbbbbb","title":"[Private video]"},
      {"_type":"url","ie_key":"Youtube","id":"ccccccccccc","title":"Third","thumbnails":[{"url":"https://i.test/small.jpg"},{"url":"https://i.test/big.jpg"}]},
      {"_type":"url","ie_key":"Youtube","id":"aaaaaaaaaaa","url":"https://www.youtube.com/watch?v=aaaaaaaaaaa","title":"First again"}
    ]}
    """.trimIndent()

    @Test
    fun inspectPlaylistListsTheVideosAndAsksYtDlpForAFlatListWithoutNoPlaylist() = runBlocking {
        val tools = FakeTools { _, _ -> DoneProcess(playlistJson) }
        val r = engine(tools).inspectPlaylist(MediaRequest("https://www.youtube.com/playlist?list=PL123")) as EngineResult.Success
        val list = r.value
        assertEquals("My list", list.title)
        // the private placeholder and the repeated address are left out; an entry without an address gets one from its id
        assertEquals(
            listOf("https://www.youtube.com/watch?v=aaaaaaaaaaa", "https://www.youtube.com/watch?v=ccccccccccc"),
            list.entries.map { it.url },
        )
        assertEquals(61L, list.entries[0].durationSeconds)
        assertEquals("https://i.test/big.jpg", list.entries[1].thumbnailUrl)
        assertFalse(list.hasMore)
        val args = tools.started.single { it.first == Tool.YT_DLP }.second
        assertTrue("--flat-playlist" in args)
        assertEquals("100", arg(args, "--playlist-end"))
        assertFalse("--no-playlist" in args)
    }

    @Test
    fun inspectPlaylistCutsAtTheLimitAndSaysThereIsMore() = runBlocking {
        val entries = (1..150).joinToString(",") { """{"ie_key":"Youtube","id":"vid${"%08d".format(it)}","url":"https://www.youtube.com/watch?v=vid${"%08d".format(it)}","title":"V$it"}""" }
        val tools = FakeTools { _, _ -> DoneProcess("""{"_type":"playlist","title":"Big","playlist_count":150,"entries":[$entries]}""") }
        val r = engine(tools).inspectPlaylist(MediaRequest("https://site.test/list")) as EngineResult.Success
        assertEquals(100, r.value.entries.size)
        assertTrue(r.value.hasMore)
    }

    @Test
    fun inspectPlaylistRefusesALinkThatIsOneVideo() = runBlocking {
        val tools = FakeTools { _, _ -> DoneProcess(listing) }
        val r = engine(tools).inspectPlaylist(MediaRequest("https://site.test/v/1")) as EngineResult.Failure
        assertEquals(ErrorKind.UNSUPPORTED_RESOURCE, r.error.kind)
        assertFalse(r.error.isPlaylist)
    }

    @Test
    fun singleVideoCommandsStillCarryNoPlaylist() {
        val request = MediaRequest("https://www.youtube.com/watch?v=aaaaaaaaaaa&list=PL123")
        assertTrue("--no-playlist" in YtDlpArgs.inspect(request))
        assertTrue("--no-playlist" in YtDlpArgs.download(request, "18", "/tmp/x", "vd-stage-1.%(ext)s"))
        assertFalse("--no-playlist" in YtDlpArgs.playlist(request, 100))
    }

    @Test
    fun aRuntimeThatCannotBePreparedIsReportedAsNativeDependencyFailure() = runBlocking {
        val tools = FakeTools { _, _ -> DoneProcess(listing) }
        tools.prepareFailure = NativeToolsException("SHA-256 mismatch")
        val r = engine(tools).inspect(MediaRequest("https://site.test/v/1")) as EngineResult.Failure
        assertEquals(ErrorKind.NATIVE_DEPENDENCY_FAILED, r.error.kind)
        assertEquals(0, tools.started.size)
    }

    @Test
    fun anEngineThatIsNotAvailableDoesNotStartAnything() = runBlocking {
        val tools = FakeTools { _, _ -> DoneProcess(listing) }
        val e = YtDlpEngine(tools, availabilityProvider = { EngineAvailability.NotAvailable("no runtime") })
        val r = e.inspect(MediaRequest("https://site.test/v/1")) as EngineResult.Failure
        assertEquals(ErrorKind.ENGINE_UNAVAILABLE, r.error.kind)
        assertEquals(0, tools.prepareCalls)
    }

    @Test
    fun yTDlpFailuresAreMappedNotHidden() = runBlocking {
        val tools = FakeTools { _, _ -> DoneProcess("", "ERROR: unable to download video data: HTTP Error 403: Forbidden", 1) }
        val r = engine(tools).inspect(MediaRequest("https://site.test/v/1")) as EngineResult.Failure
        assertEquals(403, r.error.httpStatus)
    }

    // ------------------------------------------------------------------------------------------------ download

    @Test
    fun downloadsOneStreamChecksItAndNamesItAfterTheTitle() = runBlocking {
        val tools = FakeTools { tool, args ->
            when (tool) {
                Tool.YT_DLP -> ytDlpDownload(args, "mp4", 1000)
                Tool.FFPROBE -> DoneProcess(probeBoth)
                Tool.FFMPEG -> DoneProcess()
            }
        }
        val events = engine(tools).download(task(complete), dir.absolutePath).toList()
        assertEquals(DownloadEvent.Started, events.first())
        val progress = events.filterIsInstance<DownloadEvent.Progress>()
        assertTrue(progress.isNotEmpty())
        assertEquals(1000L, progress.last().bytesDownloaded)
        assertTrue(events.any { it is DownloadEvent.Processing })
        val done = events.last() as DownloadEvent.Completed
        assertEquals(File(dir, "My Title.mp4").absolutePath, done.outputPath)
        assertEquals(1000L, done.sizeBytes)
        assertTrue(File(done.outputPath).isFile)
        assertEquals(0, tools.count(Tool.FFMPEG)) // nothing to convert: FFmpeg is only used when needed
        assertEquals("18", arg(tools.started.first().second, "-f"))
    }

    @Test
    fun mergesVideoAndAudioWithStreamCopyAndRemovesTheParts() = runBlocking {
        val tools = FakeTools { tool, args ->
            when (tool) {
                Tool.YT_DLP -> if (arg(args, "-f") == "137") ytDlpDownload(args, "mp4", 3000) else ytDlpDownload(args, "m4a", 500)
                Tool.FFMPEG -> {
                    File(args.last()).writeBytes(ByteArray(3400) { 2 })
                    DoneProcess()
                }
                Tool.FFPROBE -> DoneProcess(probeBoth)
            }
        }
        val events = engine(tools).download(task(merged), dir.absolutePath).toList()
        val stages = events.filterIsInstance<DownloadEvent.Processing>().map { it.stage }
        assertTrue(stages.any { it.contains("Merging") })
        val done = events.last() as DownloadEvent.Completed
        assertEquals(File(dir, "My Title.mp4").absolutePath, done.outputPath)
        val ffmpegArgs = tools.started.first { it.first == Tool.FFMPEG }.second
        assertEquals("copy", ffmpegArgs[ffmpegArgs.indexOf("-c") + 1])
        assertTrue(ffmpegArgs.contains("+faststart"))
        // video first, then audio; the parts and their markers are gone, only the result is left
        assertEquals(listOf("137", "140"), tools.started.filter { it.first == Tool.YT_DLP }.map { arg(it.second, "-f") })
        assertEquals(listOf("My Title.mp4"), dir.listFiles()!!.map { it.name })
        // progress of a merged download counts both streams
        val last = events.filterIsInstance<DownloadEvent.Progress>().last()
        assertEquals(3500L, last.bytesDownloaded)
        assertEquals(3500L, last.totalBytes)
    }

    @Test
    fun fallsBackToMatroskaWhenMp4CannotBeWritten() = runBlocking {
        var ffmpegRuns = 0
        val tools = FakeTools { tool, args ->
            when (tool) {
                Tool.YT_DLP -> if (arg(args, "-f") == "137") ytDlpDownload(args, "mp4", 3000) else ytDlpDownload(args, "m4a", 500)
                Tool.FFMPEG -> {
                    ffmpegRuns++
                    if (args.last().endsWith(".mp4")) {
                        DoneProcess("", "Malformed AAC bitstream detected", 1)
                    } else {
                        File(args.last()).writeBytes(ByteArray(3400) { 2 })
                        DoneProcess()
                    }
                }
                Tool.FFPROBE -> DoneProcess(probeBoth)
            }
        }
        val done = engine(tools).download(task(merged), dir.absolutePath).toList().last() as DownloadEvent.Completed
        assertEquals(2, ffmpegRuns)
        assertEquals("My Title.mkv", File(done.outputPath).name)
    }

    @Test
    fun aMergeThatFailsEverywhereFailsTheDownloadAndKeepsTheFinishedParts() = runBlocking {
        val tools = FakeTools { tool, args ->
            when (tool) {
                Tool.YT_DLP -> if (arg(args, "-f") == "137") ytDlpDownload(args, "mp4", 3000) else ytDlpDownload(args, "m4a", 500)
                Tool.FFMPEG -> DoneProcess("", "Invalid data found", 1)
                Tool.FFPROBE -> DoneProcess(probeBoth)
            }
        }
        val e = engine(tools)
        val failed = e.download(task(merged), dir.absolutePath).toList().last() as DownloadEvent.Failed
        assertEquals(ErrorKind.FFMPEG_FAILURE, failed.error.kind)
        assertEquals(2, tools.count(Tool.YT_DLP))
        // a second attempt does not download the finished streams again
        e.download(task(merged), dir.absolutePath).toList()
        assertEquals(2, tools.count(Tool.YT_DLP))
    }

    @Test
    fun anOutputThatFailsTheCheckIsNeverCompleted() = runBlocking {
        val tools = FakeTools { tool, args ->
            when (tool) {
                Tool.YT_DLP -> ytDlpDownload(args, "mp4", 1000)
                Tool.FFPROBE -> DoneProcess(probeVideoOnly) // the format promised audio
                Tool.FFMPEG -> DoneProcess()
            }
        }
        val events = engine(tools).download(task(complete), dir.absolutePath).toList()
        assertFalse(events.any { it is DownloadEvent.Completed })
        assertEquals(ErrorKind.FFMPEG_FAILURE, (events.last() as DownloadEvent.Failed).error.kind)
    }

    @Test
    fun hlsTransportStreamsAreRemuxedIntoMp4() = runBlocking {
        var probes = 0
        val tools = FakeTools { tool, args ->
            when (tool) {
                Tool.YT_DLP -> ytDlpDownload(args, "mp4", 1000)
                Tool.FFPROBE -> DoneProcess(if (probes++ == 0) probeTs else probeBoth)
                Tool.FFMPEG -> {
                    File(args.last()).writeBytes(ByteArray(900) { 3 })
                    DoneProcess()
                }
            }
        }
        val done = engine(tools).download(task(complete), dir.absolutePath).toList().last() as DownloadEvent.Completed
        assertEquals("My Title.mp4", File(done.outputPath).name)
        assertEquals(900L, done.sizeBytes)
        assertEquals(1, tools.count(Tool.FFMPEG))
    }

    @Test
    fun aDownloadErrorFromYtDlpEndsTheFlowWithAMappedFailure() = runBlocking {
        val tools = FakeTools { tool, _ ->
            if (tool == Tool.YT_DLP) DoneProcess("", "ERROR: Sign in to confirm you’re not a bot", 1) else DoneProcess()
        }
        val events = engine(tools).download(task(complete), dir.absolutePath).toList()
        val failed = events.last() as DownloadEvent.Failed
        assertEquals(ErrorKind.RESOURCE_UNAVAILABLE, failed.error.kind)
        assertEquals(0, tools.count(Tool.FFMPEG))
    }

    @Test
    fun invalidFormatsAndAddressesAreRefusedWithoutStartingAnything() = runBlocking {
        val tools = FakeTools { _, _ -> DoneProcess() }
        val e = engine(tools)
        val noFormat = e.download(task(null), dir.absolutePath).toList().last() as DownloadEvent.Failed
        assertEquals(ErrorKind.UNSUPPORTED_FORMAT, noFormat.error.kind)
        val badId = e.download(task(complete.copy(id = "18+140")), dir.absolutePath).toList().last() as DownloadEvent.Failed
        assertEquals(ErrorKind.UNSUPPORTED_FORMAT, badId.error.kind)
        val http = e.download(task(complete, url = "http://site.test/v"), dir.absolutePath).toList().last() as DownloadEvent.Failed
        assertEquals(ErrorKind.SERVER_REJECTED, http.error.kind)
        assertEquals(0, tools.started.size)
    }

    @Test
    fun cancellingTheCollectorKillsTheRunningProcess() = runBlocking {
        val hanging = HangingProcess()
        val tools = FakeTools { tool, _ -> if (tool == Tool.YT_DLP) hanging else DoneProcess() }
        val job = launch { engine(tools).download(task(complete), dir.absolutePath).toList() }
        withTimeout(10_000) { while (tools.count(Tool.YT_DLP) == 0) kotlinx.coroutines.delay(10) }
        job.cancel()
        job.join()
        assertTrue("the process must be killed when the download is cancelled", hanging.killed)
    }

    @Test
    fun closingTheEngineKillsWhatIsStillRunning() = runBlocking {
        val hanging = HangingProcess()
        val tools = FakeTools { tool, _ -> if (tool == Tool.YT_DLP) hanging else DoneProcess() }
        val e = engine(tools)
        val job = launch { e.download(task(complete), dir.absolutePath).toList() }
        withTimeout(10_000) { while (tools.count(Tool.YT_DLP) == 0) kotlinx.coroutines.delay(10) }
        e.close()
        assertTrue(hanging.killed)
        job.cancel()
        job.join()
        assertTrue(hanging.exitCodeOrNull() != null)
    }
}
