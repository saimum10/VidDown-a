package com.viddown.engine.newpipe

/**
 * What the extractor library found, in VidDown's own terms. Nothing in this file (or anywhere outside the `adapter` package)
 * knows a NewPipe class: this is the seam that keeps the engine testable offline and the extractor updatable.
 * Values the site did not report are null, never guessed.
 */

/** How a stream reaches the device. Only [PROGRESSIVE_HTTP] (one file over plain HTTP range requests) is downloadable here. */
enum class Delivery { PROGRESSIVE_HTTP, DASH, HLS, OTHER }

enum class StreamRole { VIDEO_AND_AUDIO, VIDEO_ONLY, AUDIO_ONLY }

data class ExtractedStream(
    /** The extractor's own identifier (for YouTube the itag). May be null or blank. */
    val nativeId: String?,
    val role: StreamRole,
    val delivery: Delivery,
    /** The address to fetch; null when the extractor's content is not an address. */
    val url: String?,
    /** File suffix without the dot, for example "mp4", "webm", "m4a". */
    val container: String?,
    val mimeType: String?,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val bitrateKbps: Int? = null,
    /** Exact size when the site reported it. */
    val sizeBytes: Long? = null,
    /** Audio language or track name when the site reported one. */
    val label: String? = null,
)

data class ExtractedMedia(
    /** Display name of the site service, for example "YouTube". Reported by the extractor, never hard-coded here. */
    val serviceName: String,
    val title: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val uploader: String?,
    /** A live stream (or one that was live): it has no fixed end, so a file download is not possible. */
    val isLive: Boolean,
    val streams: List<ExtractedStream>,
    /** The extractor could not read some parts (for example "Couldn't get video streams"). Kept for diagnostics, already redacted. */
    val problems: List<String> = emptyList(),
)

/** One video of a playlist as the extractor listed it. */
data class ExtractedPlaylistEntry(
    val url: String,
    val title: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val uploader: String?,
)

/** The videos of a playlist (at most what was asked for). [hasMore] is true when the site has more pages than were read. */
data class ExtractedPlaylist(
    val serviceName: String,
    val title: String?,
    val uploader: String?,
    val entries: List<ExtractedPlaylistEntry>,
    val hasMore: Boolean,
)
