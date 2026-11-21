package com.viddown.browser

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.viddown.engine.api.MediaType
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The prober and the detection pipeline against a real HTTP server that serves real-looking resources: an MP4 (ftyp box) and a
 * WebM (EBML header) with Range support, HLS and DASH manifests, a redirect, an HTML page behind a .mp4 name, an error page.
 * A WebView cannot run on the JVM, so the page's requests are replayed as the WebViewClient would report them.
 */
class LocalServerIntegrationTest {
    private lateinit var server: HttpServer
    private val lastCookie = AtomicReference<String?>()
    private val lastRange = AtomicReference<String?>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prober = HttpUrlMediaProber(timeoutMs = 3_000)
    private lateinit var base: String

    private fun mp4Bytes(size: Int) = ByteArray(size).also { b ->
        byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()).copyInto(b)
    }

    private fun webmBytes(size: Int) = ByteArray(size).also { byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()).copyInto(it) }

    private fun serve(x: HttpExchange, type: String, body: ByteArray, extra: Map<String, String> = emptyMap()) {
        lastCookie.set(x.requestHeaders.getFirst("Cookie"))
        val range = x.requestHeaders.getFirst("Range")
        lastRange.set(range)
        extra.forEach { (k, v) -> x.responseHeaders.add(k, v) }
        x.responseHeaders.add("Content-Type", type)
        x.responseHeaders.add("Accept-Ranges", "bytes")
        if (range != null && range.startsWith("bytes=") && type.startsWith("video/")) {
            val (from, to) = range.removePrefix("bytes=").split('-').let { it[0].toInt() to (it[1].toIntOrNull() ?: (body.size - 1)) }
            val end = minOf(to, body.size - 1)
            val slice = body.copyOfRange(from, end + 1)
            x.responseHeaders.add("Content-Range", "bytes $from-$end/${body.size}")
            x.sendResponseHeaders(206, slice.size.toLong())
            x.responseBody.use { it.write(slice) }
        } else {
            x.sendResponseHeaders(200, if (x.requestMethod == "HEAD") -1 else body.size.toLong())
            if (x.requestMethod != "HEAD") x.responseBody.use { it.write(body) }
        }
    }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val hlsMaster = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\nhigh.m3u8\n"
        val dash = "<?xml version=\"1.0\"?><MPD type=\"static\" mediaPresentationDuration=\"PT30S\"><Period><AdaptationSet><Representation id=\"v\" height=\"1080\"/></AdaptationSet></Period></MPD>"
        val dashDrm = dash.replace("<Period>", "<Period><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9\"/>")
        server.createContext("/") { x ->
            when (x.requestURI.path) {
                "/clip.mp4" -> serve(x, "video/mp4", mp4Bytes(300_000))
                "/clip.webm" -> serve(x, "video/webm", webmBytes(200_000))
                "/stream/master.m3u8" -> serve(x, "application/vnd.apple.mpegurl", hlsMaster.toByteArray())
                "/stream/manifest.mpd" -> serve(x, "application/dash+xml", dash.toByteArray())
                "/stream/protected.mpd" -> serve(x, "application/dash+xml", dashDrm.toByteArray())
                "/fake.mp4" -> serve(x, "text/html", "<html>login</html>".toByteArray())
                "/missing.mp4" -> { x.sendResponseHeaders(404, -1); x.close() }
                "/redirect.mp4" -> { x.responseHeaders.add("Location", "/clip.mp4"); x.sendResponseHeaders(302, -1); x.close() }
                "/loop.mp4" -> { x.responseHeaders.add("Location", "/loop.mp4"); x.sendResponseHeaders(302, -1); x.close() }
                "/dl" -> serve(x, "application/octet-stream", mp4Bytes(150_000), mapOf("Content-Disposition" to "attachment; filename=\"saved.mp4\""))
                "/stream-id" -> serve(x, "video/mp4", mp4Bytes(250_000))
                else -> { x.sendResponseHeaders(404, -1); x.close() }
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() {
        server.stop(0)
        scope.cancel()
    }

    private fun probe(path: String, manifest: Boolean = false, headers: Map<String, String> = emptyMap()) =
        runBlocking { prober.probe(ProbeRequest("$base$path", headers, manifest)) }

    @Test
    fun anMp4AnswersWithItsRealTypeAndSizeAfterAOneByteRangeRequest() {
        val r = probe("/clip.mp4") as ProbeResult.Reply
        assertEquals(206, r.code)
        assertEquals("video/mp4", r.contentType)
        assertEquals(300_000L, r.contentLength)
        assertEquals("bytes=0-0", lastRange.get())
        assertNull(r.bodyPrefix)
    }

    @Test
    fun aWebmAnswersWithItsRealType() {
        val r = probe("/clip.webm") as ProbeResult.Reply
        assertEquals("video/webm", r.contentType)
        assertEquals(200_000L, r.contentLength)
    }

    @Test
    fun manifestsAreReadBoundedAndHtmlIsNotMistakenForMedia() {
        val m = probe("/stream/master.m3u8", manifest = true) as ProbeResult.Reply
        assertTrue(m.bodyPrefix!!.startsWith("#EXTM3U"))
        assertEquals(false, m.bodyTruncated)
        val mp4 = probe("/clip.mp4", manifest = true) as ProbeResult.Reply
        assertNull("video bodies are never opened", mp4.bodyPrefix)
    }

    @Test
    fun redirectsAreFollowedByHandAndLoopsGiveUp() {
        val r = probe("/redirect.mp4") as ProbeResult.Reply
        assertEquals("$base/clip.mp4", r.finalUrl)
        assertEquals("video/mp4", r.contentType)
        assertTrue(probe("/loop.mp4") is ProbeResult.Failed)
    }

    @Test
    fun cookiesAndRefererAreSentToTheServer() {
        probe("/clip.mp4", headers = mapOf("Cookie" to "session=abc", "Referer" to "https://page.test/"))
        assertEquals("session=abc", lastCookie.get())
    }

    @Test
    fun unreachableServersAndBadSchemesFailCleanly() {
        assertTrue(runBlocking { prober.probe(ProbeRequest("file:///etc/passwd")) } is ProbeResult.Failed)
        server.stop(0)
        assertTrue(runBlocking { prober.probe(ProbeRequest("$base/clip.mp4")) } is ProbeResult.Failed)
    }

    @Test
    fun cancellationStopsAProbeAtOnce() {
        val slow = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        slow.createContext("/") { x -> Thread.sleep(10_000); x.close() }
        slow.start()
        try {
            val p = HttpUrlMediaProber(timeoutMs = 20_000)
            val started = System.nanoTime()
            runBlocking {
                val deferred = async(Dispatchers.Default) { p.probe(ProbeRequest("http://127.0.0.1:${slow.address.port}/a.mp4")) }
                Thread.sleep(300)
                deferred.cancel()
                withTimeout(3_000) { runCatching { deferred.await() } }
            }
            assertTrue("probe must stop promptly", (System.nanoTime() - started) / 1_000_000 < 5_000)
        } finally {
            slow.stop(0)
        }
    }

    @Test
    fun aReplayedPageIsClassifiedFromRealResponses() {
        val manager = MediaDetectionManager(prober, scope, probeHeaders = { _, _ -> mapOf("Referer" to "$base/page.html") })
        manager.beginPage("tab", "$base/page.html")
        val requests = listOf(
            "$base/clip.mp4", "$base/clip.webm", "$base/stream/master.m3u8", "$base/stream/manifest.mpd", "$base/stream/protected.mpd",
            "$base/fake.mp4", "$base/missing.mp4", "$base/clip.mp4", "$base/app.js", "$base/seg-1.ts", "$base/redirect.mp4",
        )
        requests.forEach { manager.onRequest("tab", ResourceEvidence(it)) }
        manager.onPlayerReport("tab", PagePlayerReport(listOf(PlayerElement(false, "$base/stream-id", false, null, 12, 640, 360, false))))
        val s = runBlocking {
            try {
                withTimeout(8_000) { manager.state("tab").first { !it.checking && it.items.size >= 6 } }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("detections so far: " + manager.current("tab").let { st -> st.checking.toString() + " " + st.items.map { it.url.removePrefix(base) + "=" + it.type } })
            }
        }
        val byType = s.items.groupBy { it.type }
        assertEquals("mp4 files: clip.mp4 (redirect.mp4 resolves to the same file), stream-id", 2, byType.getValue(MediaType.DIRECT_MP4).size)
        assertEquals(1, byType.getValue(MediaType.DIRECT_WEBM).size)
        assertEquals(1, byType.getValue(MediaType.HLS).size)
        assertEquals(2, byType.getValue(MediaType.DASH).size)
        assertEquals("up to 720p, 2 variants", byType.getValue(MediaType.HLS).single().qualityInfo)
        assertEquals(Protection.DRM, byType.getValue(MediaType.DASH).first { it.url.endsWith("protected.mpd") }.protection)
        assertEquals(Protection.NONE, byType.getValue(MediaType.DASH).first { it.url.endsWith("manifest.mpd") }.protection)
        assertTrue(s.items.none { it.url.endsWith("fake.mp4") || it.url.endsWith("missing.mp4") || it.url.endsWith("app.js") || it.url.endsWith(".ts") })
        assertEquals(12L, s.items.first { it.url.endsWith("stream-id") }.durationSeconds)
        assertTrue(s.items.all { it.tabId == "tab" && it.pageUrl == "$base/page.html" })
        assertEquals(s.items.size, s.items.map { it.key }.distinct().size)
    }

    @Test
    fun aDownloadLinkWithAGenericTypeIsNamedFromContentDisposition() {
        val manager = MediaDetectionManager(prober, scope)
        manager.beginPage("tab", "$base/page.html")
        manager.onDownloadLink("tab", "$base/dl")
        val s = runBlocking { withTimeout(5_000) { manager.state("tab").first { it.items.isNotEmpty() } } }
        assertEquals("saved.mp4", s.items.single().title)
    }
}
