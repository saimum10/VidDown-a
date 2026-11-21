package com.viddown.engine.ytdlp

/** Codec names as yt-dlp reports them ("avc1.640028", "vp09.00.40.08", "mp4a.40.2", "opus"...). Unknown stays null: nothing is guessed. */
internal object Codecs {
    /** The codec string, or null when yt-dlp reported nothing, "none" or "unknown". */
    fun known(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", true) && !it.equals("unknown", true) }

    /** A short family name used to group and to plan merges: "avc", "hevc", "vp9", "vp8", "av1", "aac", "opus", "vorbis"... */
    fun family(raw: String?): String? {
        val c = known(raw)?.lowercase() ?: return null
        return when {
            c.startsWith("avc") || c.startsWith("h264") -> "avc"
            c.startsWith("hev") || c.startsWith("hvc") || c.startsWith("h265") -> "hevc"
            c.startsWith("vp09") || c.startsWith("vp9") -> "vp9"
            c.startsWith("vp08") || c.startsWith("vp8") -> "vp8"
            c.startsWith("av01") || c.startsWith("av1") -> "av1"
            c.startsWith("mp4a.6b") || c.startsWith("mp4a.69") -> "mp3"
            c.startsWith("mp4a") || c.startsWith("aac") -> "aac"
            c.startsWith("opus") -> "opus"
            c.startsWith("vorbis") -> "vorbis"
            c.startsWith("flac") -> "flac"
            c.startsWith("mp3") -> "mp3"
            c.startsWith("ac-3") || c.startsWith("ac3") -> "ac3"
            c.startsWith("ec-3") || c.startsWith("eac3") -> "eac3"
            else -> c.substringBefore('.')
        }
    }
}

/**
 * Chooses the container for putting a separate video stream and a separate audio stream together by stream copy (no encoding:
 * this build's FFmpeg has no video encoder). Only combinations that are known to belong together get mp4 or webm; anything else
 * goes into Matroska, which accepts every codec involved.
 */
internal object MergePlanner {
    private val mp4Video = setOf("avc", "hevc")
    private val mp4Audio = setOf("aac", "mp3")
    private val webmVideo = setOf("vp8", "vp9", "av1")
    private val webmAudio = setOf("opus", "vorbis")

    /** "mp4", "webm" or "mkv"; null when a codec is not known (a stream that cannot be described cannot be planned for). */
    fun containerFor(videoCodec: String?, audioCodec: String?): String? {
        val v = Codecs.family(videoCodec) ?: return null
        val a = Codecs.family(audioCodec) ?: return null
        return when {
            v in mp4Video && a in mp4Audio -> "mp4"
            v in webmVideo && a in webmAudio -> "webm"
            else -> "mkv"
        }
    }

    /** The containers to try in order: the planned one, then Matroska as the universal fallback. */
    fun attempts(planned: String): List<String> = listOf(planned, "mkv").distinct()
}
