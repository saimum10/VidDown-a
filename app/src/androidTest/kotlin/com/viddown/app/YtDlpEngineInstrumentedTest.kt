package com.viddown.app

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.viddown.app.engines.AndroidNativeTools
import com.viddown.app.engines.YtDlpSupport
import com.viddown.engine.api.DownloadEvent
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineResult
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.ytdlp.YtDlpEngine
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 7 proof: the REAL yt-dlp engine (packaged CPython + yt-dlp + FFmpeg/ffprobe) against resources served by the test itself on
 * 127.0.0.1 (debug builds allow loopback http for exactly this). Like the Phase 1 tests it fails on purpose when no native runtime is
 * packaged: build with -PnativeAbis=<abi>. NOT YET RUN by anyone.
 *
 * Fixtures: app/src/androidTest/assets/media/ holds a tiny HLS stream (3 s, 64x64 H.264 + AAC) made with a host FFmpeg:
 *   master.m3u8 (video variant + a SEPARATE audio rendition: yt-dlp lists video-only + audio-only, so VidDown merges them) and
 *   muxed.m3u8 (one variant with audio inside the segments: yt-dlp lists one complete stream, MPEG-TS content, so VidDown remuxes it).
 */
class YtDlpEngineInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scratch: File

    @Before
    fun setUp() {
        assertTrue("No native runtime packaged. Build with -PnativeAbis=<abi>.", BuildConfig.NATIVE_ABIS.isNotBlank())
        assertNotNull("None of the packaged ABIs (${BuildConfig.NATIVE_ABIS}) matches this device ${Build.SUPPORTED_ABIS.toList()}", Abi.select())
        scratch = File(context.cacheDir, "ytdlp-it-${System.nanoTime()}").apply { mkdirs() }
        YtDlpSupport.reportRuntimeOk()
    }

    @After
    fun tearDown() {
        if (::scratch.isInitialized) scratch.deleteQuietly()
        YtDlpSupport.reportRuntimeOk() // the failing-runtime test must not leak its state into other tests
    }

    private fun engine() = YtDlpEngine(AndroidNativeTools(context), allowLoopbackHttp = BuildConfig.DEBUG)

    private fun task(url: String, format: FormatOption, title: String = "Fixture") = DownloadTask(
        id = "it-${System.nanoTime()}", engineId = EngineId.YTDLP, request = MediaRequest(url), format = format,
        title = title, durationSeconds = null, createdAtMillis = 0,
    )

    private fun hlsServer(): TestServer {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val names = assets.list("media").orEmpty()
        assertTrue("HLS fixtures are missing from the test APK (app/src/androidTest/assets/media)", names.contains("master.m3u8"))
        val routes = names.associate { name ->
            val type = when {
                name.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                name.endsWith(".ts") -> "video/mp2t"
                else -> "application/octet-stream"
            }
            "/hls/$name" to (type to assets.open("media/$name").use { it.readBytes() })
        }
        return TestServer(routes)
    }

    /** The codec types ffprobe finds in [file] (one entry per stream), read with the packaged ffprobe. */
    private fun streamTypes(file: File): List<String> {
        val probe = NativeRuntime(context).ffprobe("-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", file.absolutePath)
        assertTrue("ffprobe failed: ${probe.stderr}", probe.ok)
        return probe.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun ytDlpProcessCount(): Int = File("/proc").listFiles()?.count { dir ->
        dir.name.all { it.isDigit() } && try {
            File(dir, "cmdline").readBytes().toString(Charsets.ISO_8859_1).contains("yt_dlp")
        } catch (e: Exception) {
            false
        }
    } ?: 0

    // ------------------------------------------------------------------------------------------------ downloads

    @Test
    fun inspectsListsFormatsAndDownloadsAControlledLocalFile() {
        runBlocking {
            TestServer(mapOf("/tone.wav" to ("audio/wav" to TestMedia.wav(2.0)))).use { server ->
                val url = "http://127.0.0.1:${server.port}/tone.wav"
                val engine = engine()
                try {
                    val info = engine.inspect(MediaRequest(url))
                    assertTrue("inspect failed: $info", info is EngineResult.Success)
                    val formats = (info as EngineResult.Success).value.formats
                    assertTrue("yt-dlp reported no format", formats.isNotEmpty())

                    val events = engine.download(task(url, formats.first(), "Tone"), scratch.absolutePath).toList()
                    val last = events.last()
                    assertTrue("download did not complete: $last", last is DownloadEvent.Completed)
                    val done = last as DownloadEvent.Completed
                    val file = File(done.outputPath)
                    assertTrue(file.isFile)
                    assertEquals(file.length(), done.sizeBytes)
                    assertEquals(scratch.canonicalPath, file.canonicalFile.parentFile!!.path)
                    assertTrue("a processing step is reported before completion", events.any { it is DownloadEvent.Processing })
                    assertTrue(streamTypes(file).contains("audio"))
                } finally {
                    engine.close()
                }
            }
        }
    }

    @Test
    fun mergesTheSeparateVideoAndAudioOfAnHlsStreamIntoOneCheckedFile() {
        runBlocking {
            hlsServer().use { server ->
                val url = "http://127.0.0.1:${server.port}/hls/master.m3u8"
                val engine = engine()
                try {
                    val info = engine.inspect(MediaRequest(url))
                    assertTrue("inspect failed: $info", info is EngineResult.Success)
                    val formats = (info as EngineResult.Success).value.formats
                    val choice = formats.firstOrNull { it.requiresMerge }
                    assertNotNull("no merged video+audio choice was offered: $formats", choice)

                    val events = engine.download(task(url, choice!!, "Merged"), scratch.absolutePath).toList()
                    val last = events.last()
                    assertTrue("download did not complete: $last", last is DownloadEvent.Completed)
                    assertTrue(events.filterIsInstance<DownloadEvent.Processing>().any { it.stage.contains("Merging") })
                    val file = File((last as DownloadEvent.Completed).outputPath)
                    assertTrue("unexpected container: ${file.name}", file.extension in setOf("mp4", "mkv"))
                    val types = streamTypes(file)
                    assertTrue("video stream missing: $types", types.contains("video"))
                    assertTrue("audio stream missing: $types", types.contains("audio"))
                    // the two working files are gone, only the result is left in the folder
                    assertEquals(listOf(file.name), scratch.listFiles()!!.map { it.name })
                } finally {
                    engine.close()
                }
            }
        }
    }

    @Test
    fun remuxesAnHlsStreamWithMuxedAudioIntoARegularFile() {
        runBlocking {
            hlsServer().use { server ->
                val url = "http://127.0.0.1:${server.port}/hls/muxed.m3u8"
                val engine = engine()
                try {
                    val info = engine.inspect(MediaRequest(url))
                    assertTrue("inspect failed: $info", info is EngineResult.Success)
                    val format = (info as EngineResult.Success).value.formats.first { !it.requiresMerge }

                    val events = engine.download(task(url, format, "Remuxed"), scratch.absolutePath).toList()
                    val last = events.last()
                    assertTrue("download did not complete: $last", last is DownloadEvent.Completed)
                    assertTrue(events.filterIsInstance<DownloadEvent.Processing>().any { it.stage.contains("Converting") })
                    val file = File((last as DownloadEvent.Completed).outputPath)
                    assertTrue("MPEG-TS must have been converted, got ${file.name}", file.extension in setOf("mp4", "mkv"))
                    val types = streamTypes(file)
                    assertTrue(types.contains("video") && types.contains("audio"))
                } finally {
                    engine.close()
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ cancel and failure

    @Test
    fun cancellingARunningDownloadKillsThePythonProcess() {
        runBlocking {
            SlowServer().use { server ->
                val url = "http://127.0.0.1:${server.port}/slow.mp4"
                val engine = engine()
                try {
                    val info = engine.inspect(MediaRequest(url))
                    assertTrue("inspect failed: $info", info is EngineResult.Success)
                    val format = (info as EngineResult.Success).value.formats.first()

                    val events = CopyOnWriteArrayList<DownloadEvent>()
                    val job = launch(Dispatchers.Default) {
                        engine.download(task(url, format, "Slow"), scratch.absolutePath).collect { events += it }
                    }
                    withTimeout(120_000) {
                        while (events.none { it is DownloadEvent.Progress && it.bytesDownloaded > 0 }) delay(100)
                    }
                    assertTrue("yt-dlp should be running now", ytDlpProcessCount() > 0)
                    job.cancel()
                    job.join()
                    withTimeout(10_000) { while (ytDlpProcessCount() > 0) delay(100) }
                    assertFalse("a cancelled download must not complete", events.any { it is DownloadEvent.Completed })
                } finally {
                    engine.close()
                }
            }
        }
    }

    @Test
    fun aRuntimeThatFailsItsCheckIsReportedAndMakesTheEngineUnavailableUntilItWorksAgain() {
        runBlocking {
            val broken = AndroidNativeTools(context) {
                throw IOException("Native runtime verification failed: assets/ytdlp-site-packages.zip: SHA-256 mismatch")
            }
            val engine = YtDlpEngine(broken, allowLoopbackHttp = BuildConfig.DEBUG)
            val result = engine.inspect(MediaRequest("https://example.invalid/video"))
            assertTrue("expected a failure, got $result", result is EngineResult.Failure)
            assertEquals(ErrorKind.NATIVE_DEPENDENCY_FAILED, (result as EngineResult.Failure).error.kind)

            val availability = YtDlpSupport.availability()
            assertTrue("the engine must now be unavailable: $availability", availability is EngineAvailability.NotAvailable)
            assertTrue((availability as EngineAvailability.NotAvailable).reason.contains("SHA-256 mismatch"))

            AndroidNativeTools(context).prepare() // the real runtime verifies fine: the problem is cleared
            assertEquals(EngineAvailability.Available, YtDlpSupport.availability())
        }
    }

    @Test
    fun anAddressNobodyListensOnIsReportedAsAFailureNotACrash() {
        runBlocking {
            val engine = engine()
            try {
                val result = engine.inspect(MediaRequest("http://127.0.0.1:9/none.mp4"))
                assertTrue("expected a failure, got $result", result is EngineResult.Failure)
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun plainHttpToAnotherHostIsRefusedByTheEngine() {
        runBlocking {
            val engine = engine()
            val result = engine.inspect(MediaRequest("http://example.test/video.mp4"))
            assertEquals(ErrorKind.SERVER_REJECTED, (result as EngineResult.Failure).error.kind)
        }
    }
}

/** Answers HEAD at once and GET with an endless video/mp4 body of junk, 32 KB every 50 ms: a download that is still running when the test cancels it. */
private class SlowServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    @Volatile
    private var running = true

    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (running) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    try {
                        handle(socket)
                    } catch (e: IOException) {
                        // the client was killed: expected
                    } catch (e: InterruptedException) {
                        // closing
                    }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val method = (reader.readLine() ?: return).substringBefore(' ')
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }
            val out = s.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: $SIZE\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            if (method == "HEAD") return
            val chunk = ByteArray(32 * 1024)
            var sent = 0L
            while (running && sent < SIZE) {
                out.write(chunk)
                out.flush()
                sent += chunk.size
                Thread.sleep(50)
            }
        }
    }

    override fun close() {
        running = false
        try {
            server.close()
        } catch (e: IOException) {
            // already closed
        }
    }

    private companion object {
        const val SIZE = 200L * 1024 * 1024
    }
}
