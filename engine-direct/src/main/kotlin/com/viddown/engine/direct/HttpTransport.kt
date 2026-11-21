package com.viddown.engine.direct

import java.io.IOException
import java.io.InputStream

/** A request the engine wants to send. Only GET and HEAD are ever used. */
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
)

/** A response whose body is read from [body]. Must be closed; closing releases the connection. */
interface HttpResponse : AutoCloseable {
    val code: Int

    /** The URL after redirects. */
    val finalUrl: String

    /** First value of the header, matched case-insensitively. */
    fun header(name: String): String?

    /** Valid until [close]. Reading may throw [IOException] (connection lost, call cancelled). */
    val body: InputStream
}

interface HttpCall {
    /** Blocking. Throws [IOException] for every network-level failure (and when [cancel] was called). */
    @Throws(IOException::class)
    fun execute(): HttpResponse

    /** Thread-safe and idempotent. Makes a blocked [execute] or body read fail with [IOException]. */
    fun cancel()
}

/**
 * The only thing the engine needs from an HTTP stack. Production uses [OkHttpTransport]; the JVM tests use a transport
 * built on HttpURLConnection against a local server, so the resume/retry/error logic runs against real HTTP behaviour.
 */
interface HttpTransport {
    /** Throws [MalformedRequestException] when the URL or a header value cannot be sent at all. */
    @Throws(MalformedRequestException::class)
    fun newCall(request: HttpRequest): HttpCall

    /** Releases threads and pooled connections. Safe to call more than once. */
    fun close() {}
}

/** The request itself is invalid (bad URL, illegal header value). Retrying cannot help. */
class MalformedRequestException(message: String, cause: Throwable? = null) : IOException(message, cause)
