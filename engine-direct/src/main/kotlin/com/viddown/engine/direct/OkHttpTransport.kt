package com.viddown.engine.direct

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The production transport. TLS validation, host-name verification and the platform trust store are OkHttp's defaults:
 * nothing here weakens them. No interceptor logs requests, because headers may carry cookies or tokens.
 */
class OkHttpTransport(private val client: OkHttpClient = defaultClient()) : HttpTransport {

    override fun newCall(request: HttpRequest): HttpCall {
        val built = try {
            val builder = Request.Builder().url(request.url)
            for ((name, value) in request.headers) builder.header(name, value)
            if (request.method.equals("HEAD", ignoreCase = true)) builder.head() else builder.get()
            builder.build()
        } catch (e: IllegalArgumentException) {
            // Bad URL or an illegal header value. The message never contains header values.
            throw MalformedRequestException("The request could not be built", e)
        }
        return OkHttpCall(client.newCall(built))
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private class OkHttpCall(private val call: Call) : HttpCall {
        override fun execute(): HttpResponse = OkHttpResponse(call.execute())
        override fun cancel() = call.cancel()
    }

    private class OkHttpResponse(private val response: Response) : HttpResponse {
        override val code: Int get() = response.code
        override val finalUrl: String get() = response.request.url.toString()
        override fun header(name: String): String? = response.header(name)
        override val body: InputStream
            get() = response.body?.byteStream() ?: throw IOException("The response has no body")
        override fun close() = response.close()
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // no overall limit: a large file may legitimately take a long time
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
