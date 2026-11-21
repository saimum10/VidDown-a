package com.viddown.engine.newpipe.adapter

import com.viddown.engine.newpipe.CancelToken
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * The extractor's HTTP stack, on OkHttp. TLS validation and host-name verification are OkHttp's defaults and nothing here weakens
 * them. No cookies are stored or sent, so extraction is always anonymous (VidDown never signs in anywhere). Nothing is logged,
 * because request headers and bodies can carry tokens.
 *
 * The extractor calls [execute] synchronously on the caller's thread. [withCancel] ties every call made inside its block to a
 * [CancelToken], so cancelling the token aborts the call that is blocked right now.
 */
class OkHttpDownloader(
    private val client: OkHttpClient,
    private val userAgent: String = USER_AGENT,
) : Downloader() {

    private val current = ThreadLocal<CancelToken?>()

    fun <T> withCancel(token: CancelToken, block: () -> T): T {
        current.set(token)
        try {
            return block()
        } finally {
            current.remove()
        }
    }

    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val token = current.get()
        if (token?.isCancelled == true) throw IOException("The request was cancelled")

        val url = request.url()
        val builder = okhttp3.Request.Builder()
        val built = try {
            builder.url(url)
            builder.header("User-Agent", userAgent)
            for ((name, values) in request.headers()) {
                builder.removeHeader(name)
                for (value in values) builder.addHeader(name, value)
            }
            val data = request.dataToSend()
            when (val method = request.httpMethod().uppercase()) {
                "GET" -> builder.get()
                "HEAD" -> builder.head()
                else -> builder.method(method, (data ?: ByteArray(0)).toRequestBody())
            }
            builder.build()
        } catch (e: IllegalArgumentException) {
            // Bad address or an illegal header value. The message never contains header values.
            throw IOException("The request could not be built", e)
        }

        val call = client.newCall(built)
        val unregister = token?.onCancel { call.cancel() }
        try {
            call.execute().use { response ->
                // The convention of NewPipe's own downloader: HTTP 429 is how sites ask for a captcha / slow down.
                if (response.code == 429) throw ReCaptchaException("The site asked for a captcha check (HTTP 429)", url)
                val body = response.body?.string()
                return ExtractorResponse(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    body,
                    response.request.url.toString(),
                )
            }
        } finally {
            unregister?.invoke()
        }
    }

    companion object {
        /** Same convention as NewPipe's own downloader: sites answer unknown agents differently. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
