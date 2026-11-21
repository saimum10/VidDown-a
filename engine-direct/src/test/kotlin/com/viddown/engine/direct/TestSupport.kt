package com.viddown.engine.direct

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** HttpURLConnection-based transport: real HTTP on the JVM without OkHttp, so the engine logic runs against a real server. */
class JdkTransport : HttpTransport {
    override fun newCall(request: HttpRequest): HttpCall = object : HttpCall {
        @Volatile private var connection: HttpURLConnection? = null
        @Volatile private var cancelled = false

        override fun execute(): HttpResponse {
            val conn = try {
                URL(request.url).openConnection() as HttpURLConnection
            } catch (e: java.net.MalformedURLException) {
                throw MalformedRequestException("bad url", e)
            }
            connection = conn
            if (cancelled) throw IOException("Canceled")
            conn.requestMethod = request.method
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            for ((k, v) in request.headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            val stream: InputStream = (if (code >= 400) conn.errorStream else conn.inputStream) ?: InputStream.nullInputStream()
            return object : HttpResponse {
                override val code: Int = code
                override val finalUrl: String = conn.url.toString()
                override fun header(name: String): String? = conn.getHeaderField(name)
                override val body: InputStream = stream
                override fun close() {
                    try { stream.close() } catch (_: IOException) {}
                    conn.disconnect()
                }
            }
        }

        override fun cancel() {
            cancelled = true
            connection?.disconnect()
        }
    }
}

data class Recorded(val method: String, val path: String, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** A local HTTP server whose behaviour each test scripts. */
class TestServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val requests = CopyOnWriteArrayList<Recorded>()
    val port: Int get() = server.address.port
    fun url(path: String) = "http://127.0.0.1:$port$path"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    fun route(path: String, handler: (HttpExchange, Recorded) -> Unit) {
        server.createContext(path) { ex ->
            val headers = HashMap<String, String>()
            for ((k, v) in ex.requestHeaders) headers[k.lowercase()] = v.firstOrNull() ?: ""
            val rec = Recorded(ex.requestMethod, ex.requestURI.toString(), headers)
            requests += rec
            try {
                handler(ex, rec)
            } catch (_: IOException) {
                // the client went away (cancelled or timed out): expected in several tests
            } finally {
                try { ex.close() } catch (_: Exception) {}
            }
        }
    }

    fun requestsFor(path: String) = requests.filter { it.path.substringBefore('?') == path }

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }
}

fun testBytes(size: Int, seed: Int = 7): ByteArray = ByteArray(size).also { java.util.Random(seed.toLong()).nextBytes(it) }

/** Serves [data] like a normal file server: Range and If-Range aware when [ranges] is true. */
fun serveBytes(
    ex: HttpExchange,
    data: ByteArray,
    etag: String? = "\"v1\"",
    contentType: String? = "video/mp4",
    ranges: Boolean = true,
    disposition: String? = null,
) {
    contentType?.let { ex.responseHeaders.set("Content-Type", it) }
    etag?.let { ex.responseHeaders.set("ETag", it) }
    disposition?.let { ex.responseHeaders.set("Content-Disposition", it) }
    if (ranges) ex.responseHeaders.set("Accept-Ranges", "bytes")
    if (ex.requestMethod == "HEAD") {
        ex.responseHeaders.set("Content-Length", data.size.toString())
        ex.sendResponseHeaders(200, -1)
        return
    }
    val rangeHeader = ex.requestHeaders.getFirst("Range")
    val ifRange = ex.requestHeaders.getFirst("If-Range")
    val rangeStart = if (ranges && rangeHeader != null && (ifRange == null || ifRange == etag)) {
        Regex("bytes=(\\d+)-").matchEntire(rangeHeader)?.groupValues?.get(1)?.toLong()
    } else {
        null
    }
    if (rangeStart != null) {
        if (rangeStart >= data.size) {
            ex.responseHeaders.set("Content-Range", "bytes */${data.size}")
            ex.sendResponseHeaders(416, -1)
            return
        }
        val len = data.size - rangeStart.toInt()
        ex.responseHeaders.set("Content-Range", "bytes $rangeStart-${data.size - 1}/${data.size}")
        ex.sendResponseHeaders(206, len.toLong())
        ex.responseBody.use { it.write(data, rangeStart.toInt(), len) }
    } else {
        ex.sendResponseHeaders(200, data.size.toLong())
        ex.responseBody.use { it.write(data) }
    }
}
