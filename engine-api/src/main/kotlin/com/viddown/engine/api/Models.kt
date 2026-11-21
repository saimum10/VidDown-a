package com.viddown.engine.api

/** What the user (or the browser's media detection) asks an engine to look at. */
data class MediaRequest(
    val url: String,
    /** The page the media was found on, when known. */
    val pageUrl: String? = null,
    /** Request headers the engine must send (for example Referer). May contain secrets: never log them unredacted. */
    val headers: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "MediaRequest(url=${Redactor.redact(url)}, pageUrl=${pageUrl?.let { Redactor.redact(it) }}, headers=${Redactor.redactHeaders(headers)})"
}

/** What kind of resource was detected. Used to ask an engine whether it can handle it. */
enum class MediaType {
    DIRECT_MP4,
    DIRECT_WEBM,
    DIRECT_OTHER,
    HLS,
    DASH,

    /** A web page that a site extractor may understand (for example a video page URL). */
    PAGE,
}

enum class StreamKind {
    VIDEO_AND_AUDIO,
    VIDEO_ONLY,
    AUDIO_ONLY,

    /** The file as served, without choosing a stream (direct downloads). */
    ORIGINAL_FILE,
}

/**
 * One downloadable choice as reported by an engine. Anything the engine did not report is null: never guessed.
 */
data class FormatOption(
    /** Engine-specific identifier, passed back to the engine when downloading. */
    val id: String,
    val kind: StreamKind,
    val container: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val fps: Double? = null,
    val bitrateKbps: Int? = null,
    val sizeBytes: Long? = null,
    /** True when [sizeBytes] is an estimate (for example computed from bitrate and duration). */
    val sizeIsEstimate: Boolean = false,
    /** True when separate video and audio streams must be merged afterwards (needs FFmpeg). */
    val requiresMerge: Boolean = false,
    val note: String? = null,
) {
    /** "1080p", "720p"... or null when the height is unknown. */
    val resolutionLabel: String? get() = height?.let { "${it}p" }

    /** Larger is "better" for sorting: height first, then bitrate, then size. Unknown values sort last. */
    internal val sortKey: Long
        get() = (height ?: 0).toLong() * 1_000_000_000L + (bitrateKbps ?: 0).toLong() * 10_000L + ((sizeBytes ?: 0L) / 1_000_000L).coerceAtMost(9_999L)
}

/** Formats in the order a quality picker should list them: video+audio first, best quality first within each group. */
fun List<FormatOption>.sortedForDisplay(): List<FormatOption> {
    val groupOrder = mapOf(
        StreamKind.VIDEO_AND_AUDIO to 0,
        StreamKind.ORIGINAL_FILE to 1,
        StreamKind.VIDEO_ONLY to 2,
        StreamKind.AUDIO_ONLY to 3,
    )
    return sortedWith(compareBy<FormatOption> { groupOrder.getValue(it.kind) }.thenByDescending { it.sortKey })
}

/** A subtitle track the site offers. [lang] is the language code as the site reports it ("en", "bn"...). */
data class SubtitleOption(
    val lang: String,
    /** The language's own name when the site gave one, else the code. */
    val label: String,
    /** True for machine-made captions. */
    val automatic: Boolean = false,
)

data class MediaInfo(
    val engineId: EngineId,
    val sourceUrl: String,
    val title: String? = null,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val uploader: String? = null,
    val formats: List<FormatOption> = emptyList(),
    /** Subtitle tracks the site offers; empty when it has none (or the engine cannot list them). */
    val subtitles: List<SubtitleOption> = emptyList(),
)
