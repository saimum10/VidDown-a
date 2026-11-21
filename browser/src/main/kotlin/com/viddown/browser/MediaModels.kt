package com.viddown.browser

import com.viddown.engine.api.MediaType

/** What the WebView told us about one request. Nothing here is a verdict yet. */
data class ResourceEvidence(
    val url: String,
    val method: String = "GET",
    val requestHeaders: Map<String, String> = emptyMap(),
    /** Where the evidence came from. */
    val source: EvidenceSource = EvidenceSource.REQUEST,
)

enum class EvidenceSource {
    /** WebViewClient.shouldInterceptRequest. */
    REQUEST,

    /** The address of a page the user opened that is itself a media file. */
    PAGE_ADDRESS,

    /** A <video>/<audio> element reported by a one-way script run in the page (untrusted, validated data). */
    PLAYER_ELEMENT,

    /** A link the user tapped that the WebView treats as a download. */
    DOWNLOAD_LINK,
}

/** How a detection was established. Only the first two are proof; URL_EXTENSION is a guess the engine still has to confirm. */
enum class DetectionMethod(val verified: Boolean, val label: String) {
    /** The server's own Content-Type said it is video or audio. */
    CONTENT_TYPE(true, "Content type"),

    /** The first bytes of a streaming manifest were read and are HLS or DASH. */
    MANIFEST(true, "Manifest"),

    /** Only the file extension in the address says so; the server could not be asked. */
    URL_EXTENSION(false, "File extension"),
}

enum class Protection {
    NONE,

    /** The streams are encrypted with a key the playlist hands out (for example HLS AES-128): not DRM, but not a plain file. */
    ENCRYPTED,

    /** DRM (Widevine, PlayReady, FairPlay...). Never downloadable; VidDown never tries to get around it. */
    DRM,
}

/** One media resource found on a page. Everything the page or server did not tell us is null, never guessed. */
data class DetectedMedia(
    /** Identity for de-duplication: the address without its fragment. */
    val key: String,
    val tabId: String,
    val url: String,
    val type: MediaType,
    /** A file name taken from Content-Disposition or the address, or null. */
    val title: String? = null,
    val mime: String? = null,
    val extension: String? = null,
    val sizeBytes: Long? = null,
    /** For streaming manifests: for example \"up to 1080p, 4 variants\". Null when the manifest says nothing. */
    val qualityInfo: String? = null,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val pageUrl: String? = null,
    val method: DetectionMethod,
    val protection: Protection = Protection.NONE,
    val isLive: Boolean? = null,
    val detectedAtMillis: Long = 0,
) {
    val isStreamingManifest: Boolean get() = type == MediaType.HLS || type == MediaType.DASH
}

/** Why a page that plays media may still offer nothing to download. */
enum class PageLimitation(val message: String) {
    BLOB_OR_MSE_PLAYER(
        "This page plays its video through a streaming player (blob:/MSE) that does not expose a file address. " +
            "VidDown cannot read it with the selected engine.",
    ),
    DRM_PLAYER("This page plays protected (DRM) video. It cannot be downloaded, and VidDown never bypasses DRM."),
}

/** One request VidDown may send to ask a server what a URL is. */
data class ProbeRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** Also read the first bytes of the body (HLS/DASH manifests are small text files). */
    val readManifestBody: Boolean = false,
)

sealed interface ProbeResult {
    data class Reply(
        val code: Int,
        val finalUrl: String,
        val contentType: String?,
        /** Total size from Content-Length or Content-Range; null when the server did not say. */
        val contentLength: Long?,
        val contentDisposition: String?,
        /** Only set when [ProbeRequest.readManifestBody] was true and the body was readable text. */
        val bodyPrefix: String? = null,
        /** True when the body was longer than [bodyPrefix]. */
        val bodyTruncated: Boolean = false,
    ) : ProbeResult

    /** The server could not be asked (network error, timeout, blocked cleartext...). [reason] is for diagnostics only. */
    data class Failed(val reason: String) : ProbeResult
}

/** Asks a server about a URL without downloading it. Production: [HttpUrlMediaProber]; tests use fakes. */
interface MediaProber {
    suspend fun probe(request: ProbeRequest): ProbeResult
}
