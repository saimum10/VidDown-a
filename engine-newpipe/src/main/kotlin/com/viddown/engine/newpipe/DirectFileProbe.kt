package com.viddown.engine.newpipe

import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.FileNames
import com.viddown.engine.api.MediaMimeTypes
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.direct.Classification
import com.viddown.engine.direct.ContentTypePolicy
import com.viddown.engine.direct.HttpCall
import com.viddown.engine.direct.HttpRequest
import com.viddown.engine.direct.HttpTransport
import com.viddown.engine.direct.MalformedRequestException
import com.viddown.engine.direct.RejectKind
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Looks at an address that no extractor service recognises, and says whether it is one plain video or audio file the NewPipe engine
 * can save as it is: a file the browser detected, or a link to one. Nothing is downloaded here; only the headers (and, when a server
 * refuses HEAD, the first byte) are read.
 *
 * It only answers "is this a media file, how big, what is it called". The transfer itself is [StreamTransfer], the same loop that
 * saves the streams of a page, so a file saved this way resumes, retries and cancels exactly like any other.
 */
internal class DirectFileProbe(
    private val transport: HttpTransport,
    private val userAgent: String = StreamTransfer.NEWPIPE_USER_AGENT,
) {
    sealed interface Result {
        /** [name] is the file name to save as, with an extension when one could be told. [sizeBytes] is null when the server did not say. */
        data class Media(val name: String, val sizeBytes: Long?) : Result

        data class Refused(val error: DownloadError) : Result
    }

    private class Reply(val code: Int, val finalUrl: String, val contentType: String?, val disposition: String?, val length: Long?)

    private sealed interface Probe {
        data class Got(val reply: Reply) : Probe
        data class Error(val error: DownloadError) : Probe
    }

    suspend fun probe(request: MediaRequest): Result {
        val head = probe(request, head = true)
        val usable = (head as? Probe.Got)?.reply?.takeIf { it.code in 200..299 }
        val reply = if (usable != null) {
            usable
        } else {
            // Many servers refuse HEAD (405/403/501). Ask for the first byte instead; the body is never read.
            when (val get = probe(request, head = false)) {
                is Probe.Error -> return Result.Refused(get.error)
                is Probe.Got -> if (get.reply.code in 200..299) get.reply else return Result.Refused(DownloadError.serverRejected(get.reply.code, "HTTP ${get.reply.code}"))
            }
        }
        val name = FileNames.fromContentDisposition(reply.disposition) ?: FileNames.fromUrl(reply.finalUrl) ?: FileNames.fromUrl(request.url)
        return when (val cls = ContentTypePolicy.classify(reply.contentType, name)) {
            is Classification.Reject -> Result.Refused(
                when (cls.kind) {
                    RejectKind.WEB_PAGE -> DownloadError.unsupportedResource(EngineId.NEWPIPE, cls.detail)
                    RejectKind.STREAMING_PLAYLIST, RejectKind.NOT_MEDIA -> DownloadError.unsupportedFormat(EngineId.NEWPIPE, cls.detail)
                },
            )
            is Classification.Accept -> Result.Media(resolveName(name, cls.extension), reply.length)
        }
    }

    private suspend fun probe(request: MediaRequest, head: Boolean): Probe {
        val headers = headersFor(request.headers).toMutableMap()
        if (!head) headers["Range"] = "bytes=0-0"
        val call = try {
            transport.newCall(HttpRequest(request.url, if (head) "HEAD" else "GET", headers))
        } catch (e: MalformedRequestException) {
            return Probe.Error(DownloadError.unsupportedResource(EngineId.NEWPIPE, e.message))
        }
        return guarded(call) {
            try {
                call.execute().use { r ->
                    val length = if (r.code == 206) {
                        contentRangeTotal(r.header("Content-Range"))
                    } else {
                        r.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                    }
                    Probe.Got(Reply(r.code, r.finalUrl, r.header("Content-Type"), r.header("Content-Disposition"), length))
                }
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                Probe.Error(StreamTransfer.errorFromIo(e))
            }
        }
    }

    /** The browser's own headers (Referer, Cookie, User-Agent) are sent: the file was found with them. Range and the like are ours. */
    private fun headersFor(taskHeaders: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["User-Agent"] = userAgent
        out["Accept"] = "*/*"
        for ((name, value) in taskHeaders) {
            if (name.lowercase() in MANAGED_HEADERS) continue
            out.keys.removeAll { it.equals(name, ignoreCase = true) }
            out[name] = value
        }
        out["Accept-Encoding"] = "identity" // exact bytes, so Content-Length means what it says
        return out
    }

    private fun resolveName(raw: String?, extension: String?): String {
        var name = FileNames.sanitize(raw, "download")
        if (extension != null && !MediaMimeTypes.isKnownMediaExtension(FileNames.extensionOf(name))) {
            name = FileNames.withExtension(name, extension)
        }
        return name
    }

    private fun contentRangeTotal(header: String?): Long? =
        CONTENT_RANGE.matchEntire(header?.trim() ?: return null)?.groupValues?.get(1)?.toLongOrNull()

    /** Runs [block] and cancels [call] as soon as the surrounding coroutine is cancelled, so a blocked connect or read ends at once. */
    private suspend fun <T> guarded(call: HttpCall, block: suspend () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) call.cancel()
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    private companion object {
        val MANAGED_HEADERS = setOf("range", "if-range", "accept-encoding", "host", "content-length", "connection", "transfer-encoding")
        val CONTENT_RANGE = Regex("^bytes\\s+\\d+-\\d+/(\\d+)$", RegexOption.IGNORE_CASE)
    }
}
