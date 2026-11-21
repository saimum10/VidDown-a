package com.viddown.engine.ytdlp

import com.viddown.engine.api.StreamKind
import kotlin.math.abs
import kotlin.math.max

internal data class ProbedStream(val kind: String, val codec: String?)

/** What ffprobe says about a finished file. */
internal data class MediaProbe(
    val formatNames: Set<String>,
    val durationSeconds: Double?,
    val streams: List<ProbedStream>,
) {
    val hasVideo: Boolean get() = streams.any { it.kind == "video" }
    val hasAudio: Boolean get() = streams.any { it.kind == "audio" }

    /** An MPEG transport stream: what HLS segments are, whatever the file is called. */
    val isMpegTs: Boolean get() = "mpegts" in formatNames
}

internal object ProbeParser {
    /** Arguments for ffprobe: JSON about the container and every stream. */
    fun args(path: String): List<String> =
        listOf("-v", "error", "-print_format", "json", "-show_format", "-show_streams", path)

    fun parse(json: String): MediaProbe? {
        val root = try {
            Json.parse(json).asMap()
        } catch (e: Json.ParseException) {
            null
        } ?: return null
        val format = root["format"].asMap() ?: return null
        val names = format["format_name"].asString()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val streams = root["streams"].asList().orEmpty().mapNotNull { it.asMap() }.map { s ->
            val attachedPicture = s["disposition"].asMap()?.get("attached_pic").asInt() == 1
            val type = s["codec_type"].asString().orEmpty()
            // Cover art in an audio file is a "video" stream to ffprobe, but it is not video.
            ProbedStream(if (type == "video" && attachedPicture) "picture" else type, s["codec_name"].asString())
        }
        return MediaProbe(names, format["duration"].asDouble()?.takeIf { it > 0 }, streams)
    }
}

/** Checks a finished file against what was asked for. Returns the problem as one plain sentence, or null when the file is fine. */
internal object OutputValidator {
    fun problem(probe: MediaProbe, kind: StreamKind, fileSizeBytes: Long, expectedDurationSeconds: Long?): String? {
        if (fileSizeBytes <= 0) return "The file is empty."
        when (kind) {
            StreamKind.VIDEO_AND_AUDIO -> {
                if (!probe.hasVideo) return "The file has no video stream."
                if (!probe.hasAudio) return "The file has no audio stream."
            }
            StreamKind.VIDEO_ONLY -> if (!probe.hasVideo) return "The file has no video stream."
            StreamKind.AUDIO_ONLY -> if (!probe.hasAudio) return "The file has no audio stream."
            StreamKind.ORIGINAL_FILE -> if (!probe.hasVideo && !probe.hasAudio) return "The file has no audio or video stream."
        }
        val actual = probe.durationSeconds
        // A container without a duration is not a defect (some WebM files have none): only a duration that is there is compared.
        if (actual != null && expectedDurationSeconds != null && expectedDurationSeconds > 0) {
            val allowed = max(5.0, expectedDurationSeconds * 0.1)
            if (abs(actual - expectedDurationSeconds) > allowed) {
                return "The file is ${actual.toLong()} seconds long, but the site announced $expectedDurationSeconds seconds."
            }
        }
        return null
    }
}
