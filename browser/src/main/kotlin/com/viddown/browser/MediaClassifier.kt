package com.viddown.browser

import com.viddown.engine.api.FileNames
import com.viddown.engine.api.MediaMimeTypes
import com.viddown.engine.api.MediaType

/** Why a request is worth asking the server about. Never a verdict. */
enum class CandidateKind {
    /** The path ends in a media file extension. */
    MEDIA_EXTENSION,

    /** The path ends in .m3u8 or .mpd. */
    MANIFEST_EXTENSION,

    /** The request's own headers say a media element asked for it (Sec-Fetch-Dest: video/audio). */
    MEDIA_ELEMENT_HEADER,

    /** The page's own <video>/<audio> element reported this address as its source. */
    PLAYER_SOURCE,

    /** The WebView handed the link to the download listener. */
    DOWNLOAD_LINK,
}

sealed interface Verdict {
    data class Media(
        val type: MediaType,
        val method: DetectionMethod,
        val mime: String?,
        val extension: String?,
        val title: String?,
        val sizeBytes: Long?,
        val qualityInfo: String?,
        val durationSeconds: Long?,
        val isLive: Boolean?,
        val protection: Protection,
    ) : Verdict

    /** [reason] is for tests and diagnostics; the user never sees a rejected resource. */
    data class Rejected(val reason: String) : Verdict
}

/**
 * Decides from evidence, never from guesses, whether a request is media:
 *  1. [candidate] is a cheap filter on the request (extension, request headers). The word \"video\" in a URL means nothing.
 *  2. [classify] reads what the server really answered (Content-Type, size, manifest text). Without an answer it can only say
 *     \"the extension suggests media\", and marks that as not verified.
 */
object MediaClassifier {
    /** Files smaller than this are treated as interface sounds and tracking clips, not downloads. */
    const val MIN_MEDIA_BYTES = 64L * 1024

    private val segmentExtensions = setOf("m4s", "fmp4", "cmfv", "cmfa", "cmft", "ts", "mts", "aacp", "init")
    private val manifestExtensions = setOf("m3u8", "mpd")
    private val hlsMimes = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl", "application/mpegurl",
    )
    private val dashMimes = setOf("application/dash+xml")
    private val genericBinary = setOf(
        "application/octet-stream", "binary/octet-stream", "application/force-download", "application/x-download",
        "application/download", "application/unknown",
    )

    /**
     * Null when the request cannot be media. Segments of a stream (.ts, .m4s ...) are never listed on their own: they are parts of
     * a playlist that is detected separately.
     */
    fun candidate(evidence: ResourceEvidence): CandidateKind? {
        if (!evidence.method.equals("GET", ignoreCase = true)) return null
        if (!UrlPolicy.isHttp(evidence.url)) return null
        val ext = UrlPolicy.pathExtension(evidence.url)
        if (evidence.source == EvidenceSource.PLAYER_ELEMENT) return CandidateKind.PLAYER_SOURCE
        if (evidence.source == EvidenceSource.DOWNLOAD_LINK) return CandidateKind.DOWNLOAD_LINK
        if (ext != null) {
            if (ext in manifestExtensions) return CandidateKind.MANIFEST_EXTENSION
            if (ext in segmentExtensions && evidence.source != EvidenceSource.PAGE_ADDRESS) return null
            if (MediaMimeTypes.isKnownMediaExtension(ext)) return CandidateKind.MEDIA_EXTENSION
        }
        val dest = evidence.requestHeaders.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }?.value?.trim()?.lowercase()
        if (dest == "video" || dest == "audio") return CandidateKind.MEDIA_ELEMENT_HEADER
        return null
    }

    /**
     * [probe] is what the server answered, or null when it could not be asked (then only a media extension can still make this a
     * non-verified detection).
     */
    fun classify(evidence: ResourceEvidence, kind: CandidateKind, probe: ProbeResult?): Verdict {
        val ext = UrlPolicy.pathExtension(evidence.url)
        return when (probe) {
            is ProbeResult.Reply -> classifyReply(evidence, kind, probe)
            is ProbeResult.Failed, null -> {
                // The server could not be asked. A media extension is a hint, not proof: keep it, marked as not verified.
                if (ext != null && ext in manifestExtensions) {
                    Verdict.Media(
                        type = if (ext == "m3u8") MediaType.HLS else MediaType.DASH,
                        method = DetectionMethod.URL_EXTENSION, mime = null, extension = ext,
                        title = FileNames.fromUrl(evidence.url), sizeBytes = null, qualityInfo = null, durationSeconds = null,
                        isLive = null, protection = Protection.NONE,
                    )
                } else if (ext != null && MediaMimeTypes.isKnownMediaExtension(ext) && ext !in segmentExtensions) {
                    Verdict.Media(
                        type = typeForExtension(ext), method = DetectionMethod.URL_EXTENSION,
                        mime = MediaMimeTypes.forExtension(ext), extension = ext, title = FileNames.fromUrl(evidence.url),
                        sizeBytes = null, qualityInfo = null, durationSeconds = null, isLive = null, protection = Protection.NONE,
                    )
                } else {
                    Verdict.Rejected("the server could not be asked and the address has no media extension")
                }
            }
        }
    }

    private fun classifyReply(evidence: ResourceEvidence, kind: CandidateKind, r: ProbeResult.Reply): Verdict {
        if (r.code !in 200..299) return Verdict.Rejected("HTTP ${r.code}")
        val mime = MediaMimeTypes.normalize(r.contentType)
        val name = FileNames.fromContentDisposition(r.contentDisposition) ?: FileNames.fromUrl(r.finalUrl) ?: FileNames.fromUrl(evidence.url)
        val nameExt = name?.let { FileNames.extensionOf(it) }?.lowercase()
        val urlExt = UrlPolicy.pathExtension(r.finalUrl) ?: UrlPolicy.pathExtension(evidence.url)

        // ---- streaming manifests: the type or the extension asks for a look at the text; the text decides.
        val manifestByType = mime in hlsMimes || mime in dashMimes
        val manifestByExtension = nameExt in manifestExtensions || urlExt in manifestExtensions
        if (manifestByType || manifestByExtension) {
            val body = r.bodyPrefix
            if (body != null) {
                return when {
                    ManifestInspector.looksLikeHls(body) -> manifestVerdict(ManifestInspector.inspectHls(body, r.bodyTruncated), mime, name, r)
                    ManifestInspector.looksLikeDash(body) -> manifestVerdict(ManifestInspector.inspectDash(body, r.bodyTruncated), mime, name, r)
                    else -> Verdict.Rejected("the text is not an HLS or DASH manifest")
                }
            }
            if (manifestByType) {
                // The server declared a manifest type but the text was not read: the declared type is still server evidence.
                val type = if (mime in dashMimes) MediaType.DASH else MediaType.HLS
                return Verdict.Media(
                    type, DetectionMethod.CONTENT_TYPE, mime, if (type == MediaType.DASH) "mpd" else "m3u8", name,
                    null, null, null, null, Protection.NONE,
                )
            }
            return Verdict.Rejected("manifest extension but the text was not available")
        }

        // ---- plain files
        if (mime != null && (mime.startsWith("text/") || mime == "application/xhtml+xml" || mime == "application/json" ||
                mime.startsWith("image/") || mime == "application/javascript")
        ) {
            return Verdict.Rejected("not media ($mime)")
        }
        val knownExt = (nameExt ?: urlExt)?.takeIf { MediaMimeTypes.isKnownMediaExtension(it) }
        val accepted: Pair<String?, String?>? = when {
            mime != null && (mime.startsWith("video/") || mime.startsWith("audio/") || MediaMimeTypes.extensionFor(mime) != null) ->
                mime to (MediaMimeTypes.extensionFor(mime) ?: knownExt)
            (mime == null || mime in genericBinary) && knownExt != null -> MediaMimeTypes.forExtension(knownExt) to knownExt
            else -> null
        }
        if (accepted == null) return Verdict.Rejected("not media (${mime ?: "no content type"})")
        val (acceptedMime, acceptedExt) = accepted
        if (acceptedExt in segmentExtensions && evidence.source != EvidenceSource.PAGE_ADDRESS && kind != CandidateKind.PLAYER_SOURCE &&
            kind != CandidateKind.DOWNLOAD_LINK
        ) {
            return Verdict.Rejected("a stream segment")
        }
        val size = r.contentLength?.takeIf { it >= 0 }
        if (size != null && size < MIN_MEDIA_BYTES) return Verdict.Rejected("too small to be a download ($size bytes)")
        val type = when {
            acceptedMime == "video/mp4" || acceptedExt == "mp4" || acceptedExt == "m4v" -> MediaType.DIRECT_MP4
            acceptedMime == "video/webm" || acceptedMime == "audio/webm" || acceptedExt == "webm" || acceptedExt == "weba" -> MediaType.DIRECT_WEBM
            else -> MediaType.DIRECT_OTHER
        }
        val method = if (mime != null && mime !in genericBinary) DetectionMethod.CONTENT_TYPE else DetectionMethod.URL_EXTENSION
        return Verdict.Media(
            type = type, method = method, mime = acceptedMime, extension = acceptedExt, title = name, sizeBytes = size,
            qualityInfo = null, durationSeconds = null, isLive = null, protection = Protection.NONE,
        )
    }

    private fun manifestVerdict(info: ManifestInfo, mime: String?, name: String?, r: ProbeResult.Reply): Verdict.Media =
        Verdict.Media(
            type = info.type, method = DetectionMethod.MANIFEST, mime = mime,
            extension = if (info.type == MediaType.DASH) "mpd" else "m3u8", title = name, sizeBytes = null,
            qualityInfo = info.qualityInfo, durationSeconds = info.durationSeconds, isLive = info.isLive, protection = info.protection,
        )

    private fun typeForExtension(ext: String): MediaType = when (ext) {
        "mp4", "m4v" -> MediaType.DIRECT_MP4
        "webm", "weba" -> MediaType.DIRECT_WEBM
        else -> MediaType.DIRECT_OTHER
    }
}
