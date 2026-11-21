package com.viddown.engine.ytdlp

import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.Redactor
import com.viddown.engine.api.SuggestedAction

/**
 * Maps what yt-dlp printed on stderr (and its exit code) to VidDown's single error model. The patterns are yt-dlp's own English
 * messages; when nothing matches the error is a generic extraction failure that still carries the redacted yt-dlp message, so
 * nothing is hidden. Restrictions (login, age, region, bot check, DRM) are reported as such and never bypassed.
 */
internal object YtDlpErrors {
    private val engine = EngineId.YTDLP
    private val httpStatus = Regex("HTTP Error (\\d{3})")
    private const val MAX_DETAIL = 700

    fun fromRun(result: ToolResult): DownloadError {
        if (result.timedOut) {
            return DownloadError.connectionInterrupted("yt-dlp did not finish in time and was stopped")
        }
        if (result.stalled) {
            return DownloadError.connectionInterrupted("yt-dlp printed nothing for a long time and was stopped")
        }
        return fromMessage(result.stderr, result.exitCode)
    }

    fun fromMessage(stderr: String, exitCode: Int): DownloadError {
        val message = significant(stderr)
        val lower = message.lowercase()
        val detail = (message + " (exit code $exitCode)").take(MAX_DETAIL)

        fun has(vararg needles: String) = needles.any { it in lower }

        return when {
            has("no module named", "importerror", "failed to import encodings", "fatal python error", "cannot open shared object", "dlopen failed") ->
                DownloadError.nativeDependencyFailed(engine, detail)

            has("certificate verify failed", "ssl: certificate", "sslcertverificationerror", "self-signed certificate", "certificate has expired") ->
                DownloadError.insecureConnection(detail)

            has("no space left on device", "disk quota exceeded") -> DownloadError.insufficientStorage(detail)
            has("read-only file system", "[errno 13]", "permission denied") -> DownloadError.invalidDestination(detail)

            has("name or service not known", "temporary failure in name resolution", "unable to resolve", "network is unreachable", "no address associated", "getaddrinfo failed", "nodename nor servname") ->
                DownloadError.networkUnavailable(detail)

            has("unsupported url") -> DownloadError.unsupportedResource(engine, detail)

            has("confirm you’re not a bot", "confirm you're not a bot", "not a bot") ->
                DownloadError.accessRestricted(engine, "The site asked to confirm that the visitor is not a bot.", detail)
            has("drm protected", "drm-protected", "has drm", "widevine", "playready", "fairplay") ->
                DownloadError.accessRestricted(engine, "This media is protected by DRM.", detail)
            has("age-restricted", "confirm your age", "age verification", "inappropriate for some users") ->
                DownloadError.accessRestricted(engine, "This media is age-restricted.", detail)
            has("private video", "this video is private", "members-only", "members only", "join this channel", "sign in to", "please sign in", "log in to", "please log in", "login required", "authentication required", "requires authentication", "premium", "subscribers only", "paywall") ->
                DownloadError.accessRestricted(engine, "This media needs a login, a membership or a payment.", detail)
            has("not available in your country", "geo restricted", "geo-restricted", "not made this video available in your country", "blocked it in your country", "available in your region") ->
                DownloadError.accessRestricted(engine, "This media is not available in this region.", detail)

            has("javascript runtime", "js runtime", "no supported javascript", "n challenge", "nsig extraction failed", "signature extraction failed", "yt-dlp-ejs", "challenge solving failed") ->
                DownloadError(
                    kind = ErrorKind.EXTRACTION_FAILED,
                    userMessage = "yt-dlp could not read this site: it needs a JavaScript runtime, which this build of VidDown does not include. " +
                        "Another engine may support it: change the engine in Settings.",
                    technicalDetail = Redactor.redact(detail),
                    suggestedAction = SuggestedAction.CHANGE_ENGINE,
                    engineId = engine,
                )

            has("requested format is not available", "requested format not available") ->
                DownloadError.unsupportedFormat(engine, "The site no longer offers the format that was chosen. Choose the quality again. $detail")

            has("ffmpeg is not installed", "ffmpeg not found", "ffprobe and ffmpeg not found", "you have requested merging of multiple formats") ->
                DownloadError.unsupportedFormat(engine, "This format needs FFmpeg features that this build does not have. $detail")

            httpStatus.containsMatchIn(message) -> {
                val status = httpStatus.find(message)!!.groupValues[1].toInt()
                DownloadError.serverRejected(status, detail)
            }

            has("video unavailable", "has been removed", "no longer available", "does not exist", "this video is unavailable", "is not available", "been terminated", "deleted") ->
                DownloadError.resourceUnavailable(detail)

            has("timed out", "timeout", "connection reset", "connection aborted", "remote end closed", "incomplete read", "connection refused", "unable to download webpage", "unable to connect", "broken pipe", "eof occurred") ->
                DownloadError.connectionInterrupted(detail)

            has("traceback (most recent call last)") && !has("error:") -> DownloadError.nativeDependencyFailed(engine, detail)

            else -> DownloadError.extractionFailed(engine, detail)
        }
    }

    /** The ERROR lines when there are any, else the last lines: that is where yt-dlp says what went wrong. */
    private fun significant(stderr: String): String {
        val lines = stderr.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val errors = lines.filter { it.startsWith("ERROR:") }
        val chosen = if (errors.isNotEmpty()) errors.takeLast(3) else lines.takeLast(6)
        return chosen.joinToString("\n")
    }
}
