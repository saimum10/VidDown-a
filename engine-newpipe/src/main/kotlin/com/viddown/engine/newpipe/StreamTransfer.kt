package com.viddown.engine.newpipe

import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.DownloadEvent
import com.viddown.engine.direct.Classification
import com.viddown.engine.direct.ContentTypePolicy
import com.viddown.engine.direct.HttpCall
import com.viddown.engine.direct.HttpRequest
import com.viddown.engine.direct.HttpResponse
import com.viddown.engine.direct.HttpTransport
import com.viddown.engine.direct.MalformedRequestException
import com.viddown.engine.direct.ResumeMeta
import com.viddown.engine.direct.RetryPolicy
import com.viddown.engine.direct.SpeedMeter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Downloads ONE stream address (found by the extractor, so the media type is already known) into a temporary directory.
 * This is the NewPipe engine's own transfer loop: it shares only the HTTP primitives with the Direct HTTP engine, never the engine.
 *
 *  * The file is requested in bounded Range chunks ([chunkBytes], default 10 MiB). Large sites throttle or refuse one unbounded
 *    request for a whole file; bounded chunks are what well-behaved downloaders send. A server that ignores Range and answers
 *    200 with the whole file is handled too.
 *  * Data goes to `download.part`; the file gets its final name only after every byte arrived and the size matched what the
 *    server reported. Nothing is "completed" before that.
 *  * An interrupted transfer resumes where it stopped (Range + If-Range). After an app restart a partial file is only reused when
 *    the server gave a validator (ETag or Last-Modified) that is checked again.
 *  * Transient failures (timeouts, resets, 408, 429, 5xx) are retried with backoff. 4xx, TLS failures and storage errors are not.
 *    An expired address (403/410) is a plain failure: the engine extracts again on the next attempt.
 *  * Cancelling the collecting coroutine aborts the in-flight call at once.
 */
class StreamTransfer(
    private val transport: HttpTransport,
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    private val userAgent: String = NEWPIPE_USER_AGENT,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val progressIntervalMs: Long = 250,
) {
    init {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
    }

    /** What to fetch. [fileName] is final (sanitised, with its extension) and is also used to judge the Content-Type. */
    class Source(
        val url: String,
        val fileName: String,
        val headers: Map<String, String> = emptyMap(),
        /** The size the extractor reported, if any. Only used to show a total before the server has answered. */
        val sizeHint: Long? = null,
    ) {
        override fun toString(): String = "Source(fileName=$fileName)" // the address may carry tokens
    }

    private class State(val source: Source, val dir: File) {
        val part = File(dir, PART_NAME)
        val metaFile = File(dir, META_NAME)
        var validator: String? = null

        /** The full size as the SERVER reported it; null until it did. */
        var serverTotal: Long? = null
        val meter = SpeedMeter()

        fun partLength(): Long = if (part.isFile) part.length() else 0L

        fun dropPartial() {
            part.delete()
            metaFile.delete()
            validator = null
            serverTotal = null
        }

        fun shownTotal(): Long? = serverTotal ?: source.sizeHint?.takeIf { it > 0 }
    }

    private sealed interface Step {
        data class Done(val file: File) : Step

        /** One chunk arrived and more is needed. */
        data object More : Step
        data class Retry(val error: DownloadError, val retryAfterMs: Long? = null) : Step
        data class Fatal(val error: DownloadError) : Step

        /** The partial file does not fit what the server says now; it was dropped and the next attempt starts from zero. */
        data object Restart : Step
    }

    /** Returns [DownloadEvent.Completed] or [DownloadEvent.Failed]. Progress goes through [emit]. Throws only for cancellation. */
    suspend fun run(source: Source, dir: File, emit: suspend (DownloadEvent) -> Unit): DownloadEvent {
        if (!FormatMapper.isHttpAddress(source.url)) {
            return DownloadEvent.Failed(DownloadError.unsupportedResource(null, "Only http and https addresses can be downloaded"))
        }
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            return DownloadEvent.Failed(DownloadError.invalidDestination("The temporary folder could not be created"))
        }
        val s = State(source, dir)
        val stored = ResumeMeta.read(s.metaFile)
        if (s.partLength() > 0 && stored?.validator == null) {
            s.dropPartial() // cannot prove it is the same file any more
        } else {
            s.validator = stored?.validator
        }

        var failures = 0
        var restarts = 0
        while (true) {
            coroutineContext.ensureActive()
            val before = s.partLength()
            when (val step = attempt(s, emit)) {
                is Step.Done -> return DownloadEvent.Completed(step.file.absolutePath, step.file.length())
                is Step.Fatal -> return DownloadEvent.Failed(step.error)
                Step.More -> failures = 0
                Step.Restart -> {
                    restarts++
                    if (restarts > MAX_RESTARTS) {
                        return DownloadEvent.Failed(DownloadError.connectionInterrupted("The server's range answers were inconsistent"))
                    }
                }
                is Step.Retry -> {
                    // Only attempts that moved nothing count towards the limit.
                    failures = if (s.partLength() > before) 1 else failures + 1
                    if (failures > retryPolicy.maxRetries) return DownloadEvent.Failed(step.error)
                    s.meter.reset()
                    delay(retryPolicy.delayMs(failures, step.retryAfterMs))
                }
            }
        }
    }

    private suspend fun attempt(s: State, emit: suspend (DownloadEvent) -> Unit): Step {
        val offset = s.partLength()
        val total = s.serverTotal
        if (total != null) {
            if (offset == total) return finish(s) // every byte is already here (for example a resumed download that had just finished)
            if (offset > total) {
                s.dropPartial()
                return Step.Restart
            }
        }
        var lastByte = offset + chunkBytes - 1
        if (total != null) lastByte = minOf(lastByte, total - 1)

        val call = try {
            transport.newCall(HttpRequest(s.source.url, "GET", headersFor(s, offset, lastByte)))
        } catch (e: MalformedRequestException) {
            return Step.Fatal(DownloadError.unsupportedResource(null, e.message))
        }
        return guarded(call) {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                return@guarded stepFromIo(e)
            }
            response.use { handle(s, offset, it, emit) }
        }
    }

    private suspend fun handle(s: State, offsetBefore: Long, r: HttpResponse, emit: suspend (DownloadEvent) -> Unit): Step {
        var offset = offsetBefore
        val code = r.code
        when {
            code == 200 || code == 206 -> Unit
            code == 416 -> {
                if (offset == 0L) return Step.Fatal(httpError(code))
                // Asked for bytes beyond the end. When the size was never reported that is the only end marker there is.
                if (s.serverTotal == null) return finish(s)
                s.dropPartial()
                return Step.Restart
            }
            code == 408 || code == 429 || code in 500..599 -> return Step.Retry(httpError(code), retryAfterMs(r))
            else -> return Step.Fatal(httpError(code))
        }

        when (val cls = ContentTypePolicy.classify(r.header("Content-Type"), s.source.fileName)) {
            is Classification.Reject -> {
                // While resuming, an unexpected page (captive portal, error page) must not destroy the bytes we already have.
                if (offset > 0) return Step.Retry(DownloadError.connectionInterrupted("Unexpected response while resuming: ${cls.detail}"))
                return Step.Fatal(DownloadError.resourceUnavailable("The server sent something other than the media: ${cls.detail}"))
            }
            is Classification.Accept -> Unit
        }

        val expectedBody: Long?
        if (code == 206) {
            val range = parseContentRange(r.header("Content-Range"))
            if (range == null || range.start != offset) {
                s.dropPartial()
                return Step.Restart
            }
            val known = s.serverTotal
            if (known != null && range.total != null && known != range.total) {
                s.dropPartial() // the file on the server changed under us
                return Step.Restart
            }
            if (range.total != null) s.serverTotal = range.total
            expectedBody = range.end - range.start + 1
        } else {
            // 200: the whole file follows (range ignored, or the file changed since the partial data was written).
            if (offset > 0) {
                s.dropPartial()
                offset = 0
            }
            s.serverTotal = r.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            expectedBody = s.serverTotal
        }
        if (offset == 0L) {
            val meta = ResumeMeta.fromHeaders(r.header("ETag"), r.header("Last-Modified"))
            s.validator = meta.validator
            try {
                if (meta.validator != null) meta.write(s.metaFile)
            } catch (e: IOException) {
                return Step.Fatal(storageError(e))
            }
        }

        var downloaded = offset
        var received = 0L
        val out = try {
            FileOutputStream(s.part, offset > 0)
        } catch (e: IOException) {
            return Step.Fatal(storageError(e))
        }
        try {
            val input = try {
                r.body
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                return stepFromIo(e)
            }
            s.meter.record(nowMs(), downloaded)
            emit(progress(downloaded, s))
            var lastEmit = nowMs()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val n = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    return stepFromIo(e)
                }
                if (n < 0) break
                if (n == 0) continue
                try {
                    out.write(buffer, 0, n)
                } catch (e: IOException) {
                    return Step.Fatal(storageError(e))
                }
                downloaded += n
                received += n
                val now = nowMs()
                s.meter.record(now, downloaded)
                if (now - lastEmit >= progressIntervalMs) {
                    lastEmit = now
                    emit(progress(downloaded, s))
                }
            }
        } finally {
            try {
                out.close()
            } catch (_: IOException) {
                // a failed close after a successful write has nothing more to tell us
            }
        }

        if (expectedBody != null && received < expectedBody) {
            return Step.Retry(DownloadError.connectionInterrupted("The connection ended after $received of $expectedBody bytes of this part"))
        }
        if (received <= 0L) {
            return if (code == 200) {
                Step.Fatal(DownloadError.resourceUnavailable("The server returned an empty file"))
            } else {
                Step.Retry(DownloadError.connectionInterrupted("The server returned an empty part"))
            }
        }
        emit(progress(downloaded, s))

        val total = s.serverTotal
        return when {
            code == 200 -> finish(s) // the whole body was read to its end
            total != null && downloaded >= total -> finish(s)
            else -> Step.More
        }
    }

    private fun finish(s: State): Step {
        val length = s.partLength()
        val total = s.serverTotal
        if (length <= 0L) return Step.Fatal(DownloadError.resourceUnavailable("The server returned an empty file"))
        if (total != null && length != total) {
            s.dropPartial()
            return Step.Restart
        }
        val target = File(s.dir, s.source.fileName)
        if (target.exists() && !target.delete()) {
            return Step.Fatal(DownloadError.invalidDestination("An old file with the same name could not be replaced"))
        }
        if (!s.part.renameTo(target)) {
            return Step.Fatal(DownloadError.invalidDestination("The finished file could not be moved into place"))
        }
        s.metaFile.delete()
        return Step.Done(target)
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /**
     * Runs [block] and cancels [call] as soon as the surrounding coroutine is cancelled, so a blocked connect or read ends at once.
     * When [block] finishes by itself the call is left alone (its response has already been closed by then).
     */
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

    private fun progress(done: Long, s: State) =
        DownloadEvent.Progress(done, s.shownTotal(), s.meter.bytesPerSecond(), s.meter.etaSeconds(done, s.shownTotal()))

    private fun headersFor(s: State, offset: Long, lastByte: Long): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["User-Agent"] = userAgent
        out["Accept"] = "*/*"
        for ((name, value) in s.source.headers) {
            if (name.lowercase() in managedHeaders) continue
            out.keys.removeAll { it.equals(name, ignoreCase = true) }
            out[name] = value
        }
        out["Accept-Encoding"] = "identity" // exact bytes, so Content-Length and Range mean what they say
        out["Range"] = "bytes=$offset-$lastByte"
        if (offset > 0 && s.validator != null) out["If-Range"] = s.validator!!
        return out
    }

    private fun httpError(code: Int): DownloadError = DownloadError.serverRejected(code, "HTTP $code")

    private fun retryAfterMs(r: HttpResponse): Long? =
        r.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }

    private fun stepFromIo(e: IOException): Step = when (e) {
        is MalformedRequestException -> Step.Fatal(DownloadError.unsupportedResource(null, e.message))
        else -> {
            val error = errorFromIo(e)
            if (isTlsFailure(e) || isCleartextBlocked(e)) Step.Fatal(error) else Step.Retry(error)
        }
    }

    private data class ContentRange(val start: Long, val end: Long, val total: Long?)

    companion object {
        const val DEFAULT_CHUNK_BYTES: Long = 10L * 1024 * 1024

        /**
         * A browser-like agent, as the extractor itself needs: several sites answer unknown agents with a different page or with
         * a refusal. This is the same convention NewPipe's own downloader follows.
         */
        const val NEWPIPE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

        internal const val PART_NAME = "download.part"
        internal const val META_NAME = "download.part.meta"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_RESTARTS = 2

        private val managedHeaders = setOf("range", "if-range", "accept-encoding", "host", "content-length", "connection", "transfer-encoding")

        private val contentRange = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)

        private fun parseContentRange(header: String?): ContentRange? {
            val m = contentRange.matchEntire(header?.trim() ?: return null) ?: return null
            val start = m.groupValues[1].toLongOrNull() ?: return null
            val end = m.groupValues[2].toLongOrNull() ?: return null
            if (end < start) return null
            val total = m.groupValues[3].let { if (it == "*") null else it.toLongOrNull() }
            return ContentRange(start, end, total)
        }

        internal fun errorFromIo(e: IOException): DownloadError {
            val detail = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            return when {
                isTlsFailure(e) -> DownloadError.insecureConnection(detail)
                isCleartextBlocked(e) -> DownloadError.cleartextBlocked(detail)
                e is UnknownHostException || e is ConnectException || e is NoRouteToHostException -> DownloadError.networkUnavailable(detail)
                else -> DownloadError.connectionInterrupted(detail)
            }
        }

        /** OkHttp reports a policy refusal of plain http as UnknownServiceException("CLEARTEXT communication ... not permitted"). */
        internal fun isCleartextBlocked(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.any { it is UnknownServiceException && it.message?.contains("cleartext", ignoreCase = true) == true }

        internal fun isTlsFailure(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.any { it is SSLHandshakeException || it is SSLPeerUnverifiedException || it is CertificateException }

        internal fun storageError(e: IOException): DownloadError {
            val detail = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            val m = (e.message ?: "").lowercase()
            return when {
                "no space left" in m || "enospc" in m || "quota" in m -> DownloadError.insufficientStorage(detail)
                "permission denied" in m || "eacces" in m || "read-only" in m || "erofs" in m -> DownloadError.permissionDenied(detail)
                else -> DownloadError.invalidDestination(detail)
            }
        }
    }
}
