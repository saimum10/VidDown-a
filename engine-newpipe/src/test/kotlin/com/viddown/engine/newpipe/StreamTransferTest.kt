package com.viddown.engine.newpipe

import com.viddown.engine.api.DownloadEvent
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.direct.RetryPolicy
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamTransferTest {
    private lateinit var server: TestServer
    private lateinit var dir: File
    private val fast = RetryPolicy(maxRetries = 2, baseDelayMs = 1, maxDelayMs = 5)

    @Before fun setUp() {
        server = TestServer()
        dir = Files.createTempDirectory("st").toFile()
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun transfer(chunk: Long = 1000, retry: RetryPolicy = fast) = StreamTransfer(JdkTransport(), retry, chunk, progressIntervalMs = 0)

    private fun run(t: StreamTransfer, path: String, name: String = "clip.mp4", events: MutableList<DownloadEvent> = ArrayList()): DownloadEvent =
        runBlocking { t.run(StreamTransfer.Source(server.url(path), name), dir) { events += it } }

    @Test
    fun downloadsInBoundedChunksAndRenamesOnlyWhenComplete() {
        val data = testBytes(4500)
        server.route("/v") { ex, _ -> serveRange(ex, data) }
        val events = ArrayList<DownloadEvent>()
        val r = run(transfer(chunk = 1000), "/v", events = events)
        assertTrue(r.toString(), r is DownloadEvent.Completed)
        val file = File((r as DownloadEvent.Completed).outputPath)
        assertEquals("clip.mp4", file.name)
        assertArrayEquals(data, file.readBytes())
        assertFalse(File(dir, "download.part").exists())
        assertEquals(5, server.requests.size)
        assertEquals("bytes=0-999", server.requests[0].header("Range"))
        assertEquals("bytes=4000-4499", server.requests.last().header("Range"))
        val last = events.filterIsInstance<DownloadEvent.Progress>().last()
        assertEquals(4500L, last.bytesDownloaded)
        assertEquals(4500L, last.totalBytes)
    }

    @Test
    fun aServerThatIgnoresRangeAndSendsTheWholeFileStillWorks() {
        val data = testBytes(3000)
        server.route("/v") { ex, _ -> serveRange(ex, data, ranges = false) }
        val r = run(transfer(chunk = 1000), "/v")
        assertTrue(r.toString(), r is DownloadEvent.Completed)
        assertArrayEquals(data, File((r as DownloadEvent.Completed).outputPath).readBytes())
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aTotalThatIsNeverReportedEndsOnTheFirstRangeNotSatisfiable() {
        val data = testBytes(2500)
        server.route("/v") { ex, _ -> serveRange(ex, data, starSize = true) }
        val r = run(transfer(chunk = 1000), "/v")
        assertTrue(r.toString(), r is DownloadEvent.Completed)
        assertArrayEquals(data, File((r as DownloadEvent.Completed).outputPath).readBytes())
    }

    @Test
    fun aTransientServerErrorIsRetriedAndTheDownloadResumesWhereItStopped() {
        val data = testBytes(3000)
        var calls = 0
        server.route("/v") { ex, _ ->
            calls++
            if (calls == 2) {
                ex.sendResponseHeaders(503, -1)
            } else {
                serveRange(ex, data)
            }
        }
        val r = run(transfer(chunk = 1000), "/v")
        assertTrue(r.toString(), r is DownloadEvent.Completed)
        assertArrayEquals(data, File((r as DownloadEvent.Completed).outputPath).readBytes())
        // chunk 1 ok, 503 (retried at the same offset), then the remaining chunks
        assertEquals("bytes=1000-1999", server.requests[1].header("Range"))
        assertEquals("bytes=1000-1999", server.requests[2].header("Range"))
    }

    @Test
    fun forbiddenIsNotRetried() {
        server.route("/v") { ex, _ -> ex.sendResponseHeaders(403, -1) }
        val r = run(transfer(), "/v")
        assertTrue(r is DownloadEvent.Failed)
        assertEquals(ErrorKind.SERVER_REJECTED, (r as DownloadEvent.Failed).error.kind)
        assertEquals(403, r.error.httpStatus)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun givesUpAfterTheRetryLimitWithARetrySuggestion() {
        server.route("/v") { ex, _ -> ex.sendResponseHeaders(500, -1) }
        val r = run(transfer(retry = RetryPolicy(maxRetries = 2, baseDelayMs = 1, maxDelayMs = 2)), "/v")
        assertTrue(r is DownloadEvent.Failed)
        assertEquals(3, server.requests.size)
    }

    @Test
    fun aWebPageInsteadOfMediaIsRefusedAndNothingIsKept() {
        server.route("/v") { ex, _ ->
            ex.responseHeaders.set("Content-Type", "text/html")
            val b = "<html>sign in</html>".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        val r = run(transfer(), "/v")
        assertTrue(r is DownloadEvent.Failed)
        assertEquals(ErrorKind.RESOURCE_UNAVAILABLE, (r as DownloadEvent.Failed).error.kind)
        assertFalse(File(dir, "clip.mp4").exists())
    }

    @Test
    fun aFileThatChangedOnTheServerBetweenChunksIsRestartedNotGlued() {
        val a = testBytes(2000, seed = 1)
        val b = testBytes(2400, seed = 2)
        var calls = 0
        server.route("/v") { ex, _ ->
            calls++
            // first chunk from file A (total 2000), later requests from file B (total 2400): totals disagree
            if (calls == 1) serveRange(ex, a, etag = null) else serveRange(ex, b, etag = null)
        }
        val r = run(transfer(chunk = 1000), "/v")
        assertTrue(r.toString(), r is DownloadEvent.Completed)
        assertArrayEquals(b, File((r as DownloadEvent.Completed).outputPath).readBytes())
    }

    @Test
    fun aMissingServerTotalMismatchOnTheFinalSizeNeverCompletesAShortFile() {
        val data = testBytes(2000)
        server.route("/v") { ex, _ ->
            // claims 2000 bytes but delivers 1000, then closes
            ex.responseHeaders.set("Content-Type", "video/mp4")
            ex.sendResponseHeaders(200, 2000)
            ex.responseBody.write(data, 0, 1000)
            ex.responseBody.close()
        }
        val r = run(transfer(retry = RetryPolicy(maxRetries = 0)), "/v")
        assertTrue(r.toString(), r is DownloadEvent.Failed)
        assertFalse(File(dir, "clip.mp4").exists())
    }

    // The server stalls before it answers, so the blocked call is the connect/response wait. (Cancelling in the middle of a body is
    // OkHttp's call.cancel() in production, which closes the socket; the JDK test transport cannot abort a body read reliably.)
    @Test
    fun cancellingStopsABlockedRequestPromptlyAndLeavesNoFinalFile() = runBlocking {
        server.route("/v") { _, _ -> Thread.sleep(15_000) }
        val job = launch(Dispatchers.IO) { transfer().run(StreamTransfer.Source(server.url("/v"), "clip.mp4"), dir) { } }
        delay(500)
        withTimeout(4_000) { job.cancelAndJoin() }
        assertFalse(File(dir, "clip.mp4").exists())
    }

    /** A transport that answers every request with the same scripted reply, for answers a real server library will not produce. */
    private class ScriptedTransport(private val code: Int, private val headers: Map<String, String>, private val body: ByteArray) : com.viddown.engine.direct.HttpTransport {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        override fun newCall(request: com.viddown.engine.direct.HttpRequest) = object : com.viddown.engine.direct.HttpCall {
            override fun execute(): com.viddown.engine.direct.HttpResponse {
                calls.incrementAndGet()
                return object : com.viddown.engine.direct.HttpResponse {
                    override val code = this@ScriptedTransport.code
                    override val finalUrl = request.url
                    override fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
                    override val body: java.io.InputStream = java.io.ByteArrayInputStream(this@ScriptedTransport.body)
                    override fun close() {}
                }
            }
            override fun cancel() {}
        }
    }

    @Test
    fun aBodyLongerThanTheDeclaredTotalIsNeverCompleted() {
        // Content-Range says the file has 800 bytes, but 1000 arrive: the numbers disagree, so the file must not be accepted.
        val t = ScriptedTransport(206, mapOf("Content-Type" to "video/mp4", "Content-Range" to "bytes 0-999/800"), testBytes(1000))
        val r = runBlocking { StreamTransfer(t, fast, 1000, progressIntervalMs = 0).run(StreamTransfer.Source("http://x.test/v", "clip.mp4"), dir) { } }
        assertTrue(r.toString(), r is DownloadEvent.Failed)
        assertFalse(File(dir, "clip.mp4").exists())
        assertTrue(t.calls.get() <= 4) // restarted a bounded number of times, not forever
    }

    @Test
    fun resumesAnExistingPartialFileOnlyWithAValidatorAndDropsItOtherwise() {
        val data = testBytes(3000)
        server.route("/v") { ex, _ -> serveRange(ex, data) }
        // a partial file with no stored validator cannot be trusted: it is dropped and the file is fetched from zero
        File(dir, "download.part").writeBytes(ByteArray(500) { 9 })
        val r = run(transfer(chunk = 5000), "/v")
        assertTrue(r.toString(), r is DownloadEvent.Completed)
        assertArrayEquals(data, File((r as DownloadEvent.Completed).outputPath).readBytes())
        assertEquals("bytes=0-4999", server.requests.first().header("Range"))
        // and with a stored validator the partial data is kept and continued with If-Range
        server.requests.clear()
        File(dir, "clip.mp4").delete()
        File(dir, "download.part").writeBytes(data.copyOfRange(0, 1200))
        File(dir, "download.part.meta").writeText("etag=\"v1\"\n")
        val r2 = run(transfer(chunk = 5000), "/v")
        assertTrue(r2.toString(), r2 is DownloadEvent.Completed)
        assertArrayEquals(data, File((r2 as DownloadEvent.Completed).outputPath).readBytes())
        assertEquals("bytes=1200-6199", server.requests.first().header("Range"))
        assertEquals("\"v1\"", server.requests.first().header("If-Range"))
    }
}
