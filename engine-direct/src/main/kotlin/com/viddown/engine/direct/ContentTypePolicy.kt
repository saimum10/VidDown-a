package com.viddown.engine.direct

import com.viddown.engine.api.FileNames
import com.viddown.engine.api.MediaMimeTypes

sealed interface Classification {
    /** [extension] is the extension the saved file should carry, when it can be told. */
    data class Accept(val mime: String?, val extension: String?) : Classification

    data class Reject(val kind: RejectKind, val detail: String) : Classification
}

enum class RejectKind {
    /** An HTML page or other document: the link is a web page, not a file. */
    WEB_PAGE,

    /** HLS or DASH manifest: a list of segments, which this engine does not stitch together. */
    STREAMING_PLAYLIST,

    /** Something that is not video or audio (an archive, an image, a program...). */
    NOT_MEDIA,
}

/**
 * Decides from the Content-Type header and the file name whether a response is a video or audio file the Direct HTTP engine
 * should save. A page that merely answers with HTML must never be saved as "video.mp4".
 */
object ContentTypePolicy {
    private val streamingMimes = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl",
        "application/dash+xml", "application/vnd.ms-sstr+xml",
    )
    private val streamingExtensions = setOf("m3u8", "m3u", "mpd", "ism")
    private val htmlMimes = setOf("text/html", "application/xhtml+xml")
    private val genericBinaryMimes = setOf(
        "application/octet-stream", "binary/octet-stream", "application/force-download", "application/x-download",
        "application/download", "application/unknown",
    )

    fun classify(contentTypeHeader: String?, fileName: String?): Classification {
        val mime = MediaMimeTypes.normalize(contentTypeHeader)
        val nameExt = fileName?.let { FileNames.extensionOf(it) }
        val knownNameExt = nameExt?.takeIf { MediaMimeTypes.isKnownMediaExtension(it) }

        if (mime in streamingMimes || nameExt in streamingExtensions) {
            return Classification.Reject(RejectKind.STREAMING_PLAYLIST, "Streaming playlist (${mime ?: nameExt})")
        }
        if (mime != null && mime in htmlMimes) {
            return Classification.Reject(RejectKind.WEB_PAGE, "The server returned a web page ($mime)")
        }
        if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/") || MediaMimeTypes.extensionFor(mime) != null)) {
            return Classification.Accept(mime, knownNameExt ?: MediaMimeTypes.extensionFor(mime))
        }
        if (mime == null || mime in genericBinaryMimes) {
            // Generic or missing type: only trust it when the name says it is media, or says nothing at all.
            if (knownNameExt != null) return Classification.Accept(MediaMimeTypes.forExtension(knownNameExt), knownNameExt)
            if (nameExt == null && mime != null) return Classification.Accept(null, null)
            val shown = mime ?: "no content type"
            return Classification.Reject(RejectKind.NOT_MEDIA, "Not a video or audio file ($shown, name ${fileName ?: "unknown"})")
        }
        val kind = if (mime.startsWith("text/")) RejectKind.WEB_PAGE else RejectKind.NOT_MEDIA
        return Classification.Reject(kind, "Not a video or audio file ($mime)")
    }
}
