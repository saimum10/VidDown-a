package com.viddown.browser

import com.viddown.engine.api.MediaMimeTypes
import com.viddown.engine.api.Redactor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Asks a server what a URL is, the way the page itself would: one GET with a one-byte (or, for manifests, 64 KB) Range, a bounded
 * body read for manifests only, never the whole file. Cookies and Referer are only sent to the host they were meant for: they are
 * dropped when a redirect leaves it. TLS validation is the platform default and is never weakened.
 */
class HttpUrlMediaProber(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Int = 8_000,
    private val maxRedirects: Int = 5,
    private val maxBodyBytes: Int = 64 * 1024,
    private val userAgent: String = BrowserUserAgent.facebookInApp(""),
) : MediaProber {

    override suspend fun probe(request: ProbeRequest): ProbeResult = coroutineScope {
        val current = AtomicReference<HttpURLConnection?>()
        val work = async(ioDispatcher) { run(request, current) }
        try {
            work.await()
        } catch (e: CancellationException) {
            // A blocked socket read ignores thread interruption; disconnecting is what makes it return.
            current.get()?.disconnect()
            throw e
        }
    }

    private fun run(request: ProbeRequest, current: AtomicReference<HttpURLConnection?>): ProbeResult {
        var url = request.url
        val headers = LinkedHashMap(request.headers)
        for (hop in 0..maxRedirects) {
            if (!UrlPolicy.isHttp(url)) return ProbeResult.Failed("unsupported scheme")
            val conn = try {
                URL(url).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                return ProbeResult.Failed("bad address")
            }
            current.set(conn)
            try {
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.useCaches = false
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.setRequestProperty("User-Agent", userAgent)
                for ((name, value) in headers) {
                    if (name.equals("Range", ignoreCase = true) || name.equals("Accept-Encoding", ignoreCase = true)) continue
                    conn.setRequestProperty(name, value)
                }
                conn.setRequestProperty("Range", if (request.readManifestBody) "bytes=0-${maxBodyBytes - 1}" else "bytes=0-0")
                val code = conn.responseCode
                if (code in REDIRECTS) {
                    val location = conn.getHeaderField("Location") ?: return ProbeResult.Failed("redirect without a location")
                    val next = try {
                        URL(URL(url), location).toString()
                    } catch (e: Exception) {
                        return ProbeResult.Failed("bad redirect")
                    }
                    if (UrlPolicy.hostOf(next) != UrlPolicy.hostOf(url)) {
                        headers.keys.removeAll { it.equals("Cookie", true) || it.equals("Authorization", true) }
                    }
                    url = next
                    continue
                }
                return reply(conn, code, url, request)
            } catch (e: IOException) {
                return ProbeResult.Failed(Redactor.redact("${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"))
            } catch (e: RuntimeException) {
                return ProbeResult.Failed("${e.javaClass.simpleName}")
            } finally {
                conn.disconnect()
            }
        }
        return ProbeResult.Failed("too many redirects")
    }

    private fun reply(conn: HttpURLConnection, code: Int, finalUrl: String, request: ProbeRequest): ProbeResult {
        val contentType = conn.getHeaderField("Content-Type")
        val rangeTotal = if (code == 206) parseContentRangeTotal(conn.getHeaderField("Content-Range")) else null
        val length = rangeTotal ?: if (code == 206) null else conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        var body: String? = null
        var truncated = false
        if (request.readManifestBody && code in 200..299 && shouldReadBody(contentType, finalUrl)) {
            val read = readBounded(conn)
            if (read != null) {
                body = String(read.first, Charsets.UTF_8)
                truncated = read.second || (rangeTotal != null && rangeTotal > read.first.size)
            }
        }
        return ProbeResult.Reply(
            code = code, finalUrl = finalUrl, contentType = contentType, contentLength = length,
            contentDisposition = conn.getHeaderField("Content-Disposition"), bodyPrefix = body, bodyTruncated = truncated,
        )
    }

    /** Reads at most [maxBodyBytes]; second = more data was available. Null when the body cannot be read. */
    private fun readBounded(conn: HttpURLConnection): Pair<ByteArray, Boolean>? = try {
        conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            var more = false
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val room = maxBodyBytes - total
                if (n > room) {
                    out.write(buf, 0, room.coerceAtLeast(0))
                    more = true
                    break
                }
                out.write(buf, 0, n)
                total += n
                if (total >= maxBodyBytes) {
                    more = input.read() >= 0
                    break
                }
            }
            out.toByteArray() to more
        }
    } catch (e: IOException) {
        null
    }

    /** Only text-like answers are read: a response typed as video or audio is never opened. */
    internal fun shouldReadBody(contentType: String?, url: String): Boolean {
        val mime = MediaMimeTypes.normalize(contentType) ?: return true
        if (mime in READABLE) return true
        // A manifest address with an unusual text type is still worth reading; a real video or audio type never is.
        return UrlPolicy.pathExtension(url) in MANIFEST_EXTENSIONS && !mime.startsWith("video/") && !mime.startsWith("audio/")
    }

    private fun parseContentRangeTotal(header: String?): Long? =
        header?.substringAfter('/', "")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val MANIFEST_EXTENSIONS = setOf("m3u8", "mpd")
        val READABLE = setOf(
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl", "audio/mpegurl", "audio/x-mpegurl",
            "application/dash+xml", "application/xml", "text/xml", "text/plain", "application/octet-stream", "binary/octet-stream",
        )
    }
}
