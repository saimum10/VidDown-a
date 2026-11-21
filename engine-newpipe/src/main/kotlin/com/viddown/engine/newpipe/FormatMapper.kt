package com.viddown.engine.newpipe

import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.StreamKind
import com.viddown.engine.api.sortedForDisplay

/**
 * Turns what the extractor found into the formats VidDown may offer, and finds a stream again from a format id.
 *
 * Rules (nothing is invented):
 *  * Only streams that arrive as one file over plain HTTP ([Delivery.PROGRESSIVE_HTTP]) with a real http(s) address are offered.
 *  * Video-only streams are never offered on their own (they have no sound). When a merger is available ([map] with canMerge), each
 *    sharp video-only stream is paired with the best audio stream that fits it and offered as ONE choice that is merged after both
 *    streams arrived ([FormatOption.requiresMerge]); this is how the qualities above the site's single-file ones (360p on YouTube)
 *    are reached. Without a merger they are only counted in [Mapping.skippedVideoOnly], so the engine can explain why a site's best
 *    qualities are missing. A video-only stream without a usable audio partner is counted there too.
 *  * DASH/HLS/other delivery is not offered either ([Mapping.skippedManifest]).
 *  * A size is exact only when the site reported it; otherwise it is an estimate from bitrate x duration (flagged) or unknown.
 */
object FormatMapper {

    data class Mapping(
        val formats: List<FormatOption>,
        val skippedVideoOnly: Int,
        val skippedManifest: Int,
        val skippedNoAddress: Int,
    )

    /** What a format id stands for: one file, or a video file and an audio file that are merged. */
    sealed interface Selection {
        data class Single(val stream: ExtractedStream) : Selection
        data class Merged(val video: ExtractedStream, val audio: ExtractedStream, val container: String) : Selection
    }

    /** Ids of merged choices start with this; the rest is the video stream's id, a plus sign and the audio stream's id. */
    internal const val MERGED_PREFIX = "mrg:"

    /**
     * One stable id per stream, the same on every extraction of the same media (the extractor lists streams in a fixed order), so a
     * download recovered after the app was killed can find the stream the user chose. Ids are unique inside one media.
     */
    internal fun assignIds(streams: List<ExtractedStream>): List<Pair<String, ExtractedStream>> {
        val seen = HashMap<String, Int>()
        return streams.mapIndexed { index, s ->
            val native = s.nativeId?.trim()?.takeIf { it.isNotEmpty() } ?: "n$index"
            val base = "${prefix(s.role)}:$native"
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            (if (count == 1) base else "$base~$count") to s
        }
    }

    private fun prefix(role: StreamRole) = when (role) {
        StreamRole.VIDEO_AND_AUDIO -> "av"
        StreamRole.VIDEO_ONLY -> "v"
        StreamRole.AUDIO_ONLY -> "a"
    }

    fun map(media: ExtractedMedia, canMerge: Boolean = false): Mapping {
        var videoOnly = 0
        var manifest = 0
        var noAddress = 0
        val options = ArrayList<FormatOption>()
        val seenShapes = HashSet<List<Any?>>()
        val videoCandidates = ArrayList<Pair<String, ExtractedStream>>()
        val audioCandidates = ArrayList<Pair<String, ExtractedStream>>()
        for ((id, s) in assignIds(media.streams)) {
            if (s.role == StreamRole.VIDEO_ONLY) {
                if (canMerge && isMergeable(s)) videoCandidates += id to s else videoOnly++
                continue
            }
            if (s.delivery != Delivery.PROGRESSIVE_HTTP) {
                manifest++
                continue
            }
            if (!isHttpAddress(s.url)) {
                noAddress++
                continue
            }
            // The same quality listed twice (some services repeat a stream) is shown once.
            val shape = listOf(s.role, s.container, s.height, s.width, s.bitrateKbps, s.videoCodec, s.audioCodec, s.label, s.sizeBytes)
            if (!seenShapes.add(shape)) continue
            if (s.role == StreamRole.AUDIO_ONLY) audioCandidates += id to s
            options += toOption(id, s, media.durationSeconds, media.isLive)
        }
        if (canMerge) {
            for ((videoId, v) in bestVideoPerGroup(videoCandidates)) {
                val partner = partnerFor(v, audioCandidates)
                if (partner == null) {
                    videoOnly++
                    continue
                }
                options += merged(videoId, v, partner.first, partner.second, media.durationSeconds, media.isLive)
            }
            // Streams that lost against a better one of the same resolution, codec and container are not "left out": the better one is offered.
        }
        return Mapping(options.sortedForDisplay(), videoOnly, manifest, noAddress)
    }

    /**
     * The one-file or two-file recipe a format id stands for, or null when the site no longer offers it. Never picks "something
     * similar". A merged id names both of its streams, so it can only be found again while both are still offered.
     */
    fun select(media: ExtractedMedia, formatId: String): Selection? {
        if (!formatId.startsWith(MERGED_PREFIX)) return find(media, formatId)?.let { Selection.Single(it) }
        val ids = assignIds(media.streams)
        val body = formatId.removePrefix(MERGED_PREFIX)
        var plus = body.indexOf('+')
        while (plus >= 0) {
            val video = ids.firstOrNull { it.first == body.substring(0, plus) }?.second
            val audio = ids.firstOrNull { it.first == body.substring(plus + 1) }?.second
            if (video != null && audio != null && video.role == StreamRole.VIDEO_ONLY && audio.role == StreamRole.AUDIO_ONLY &&
                isMergeable(video) && isMergeable(audio)
            ) {
                return Selection.Merged(video, audio, containerFor(video, audio))
            }
            plus = body.indexOf('+', plus + 1)
        }
        return null
    }

    private fun isMergeable(s: ExtractedStream): Boolean = s.delivery == Delivery.PROGRESSIVE_HTTP && isHttpAddress(s.url)

    /** One video-only stream per resolution, codec family and container: the one with the highest bitrate (ties: the larger size). */
    private fun bestVideoPerGroup(streams: List<Pair<String, ExtractedStream>>): List<Pair<String, ExtractedStream>> {
        val best = LinkedHashMap<List<Any?>, Pair<String, ExtractedStream>>()
        for (entry in streams) {
            val s = entry.second
            val key = listOf(s.height, codecFamily(s.videoCodec), s.container?.lowercase())
            val current = best[key]
            if (current == null || rank(s) > rank(current.second)) best[key] = entry
        }
        return best.values.toList()
    }

    private fun rank(s: ExtractedStream): Long = (s.bitrateKbps ?: 0).toLong() * 1_000_000L + (s.sizeBytes ?: 0L) / 1_000

    /**
     * The audio stream to pair with [video]: a stream of the same container family first (m4a with mp4, webm audio with webm), then the
     * site's default track rather than a dubbed or described one, then the highest bitrate.
     */
    private fun partnerFor(video: ExtractedStream, audios: List<Pair<String, ExtractedStream>>): Pair<String, ExtractedStream>? {
        fun family(a: ExtractedStream): Int {
            val v = video.container?.lowercase()
            val c = a.container?.lowercase()
            return when {
                v in MP4_CONTAINERS && c in MP4_AUDIO_CONTAINERS -> 2
                v == "webm" && c in WEBM_AUDIO_CONTAINERS -> 2
                else -> 1
            }
        }
        fun isDefaultTrack(a: ExtractedStream): Boolean {
            val label = a.label?.trim().orEmpty()
            return label.isEmpty() || label.contains("original", ignoreCase = true)
        }
        return audios.maxWithOrNull(
            compareBy<Pair<String, ExtractedStream>> { family(it.second) }
                .thenBy { if (isDefaultTrack(it.second)) 1 else 0 }
                .thenBy { it.second.bitrateKbps ?: 0 },
        )
    }

    /**
     * mp4 for H.264/H.265 with AAC/MP3, webm for VP8/VP9/AV1 with Opus/Vorbis, Matroska for every other pair (it takes every codec).
     * The streams are copied, not re-encoded, so the container has to be one that can hold both. An unknown codec goes to Matroska.
     */
    internal fun containerFor(video: ExtractedStream, audio: ExtractedStream): String {
        val v = codecFamily(video.videoCodec)
        val a = codecFamily(audio.audioCodec)
        return when {
            v == null || a == null -> "mkv"
            v in MP4_VIDEO_CODECS && a in MP4_AUDIO_CODECS -> "mp4"
            v in WEBM_VIDEO_CODECS && a in WEBM_AUDIO_CODECS -> "webm"
            else -> "mkv"
        }
    }

    /** "avc", "hevc", "vp9", "vp8", "av1", "aac", "mp3", "opus", "vorbis"; null for anything else (never guessed). */
    internal fun codecFamily(raw: String?): String? {
        val c = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "none" && it != "unknown" } ?: return null
        return when {
            c.startsWith("avc") || c.startsWith("h264") -> "avc"
            c.startsWith("hev") || c.startsWith("hvc") || c.startsWith("h265") -> "hevc"
            c.startsWith("vp09") || c.startsWith("vp9") -> "vp9"
            c.startsWith("vp08") || c.startsWith("vp8") -> "vp8"
            c.startsWith("av01") || c.startsWith("av1") -> "av1"
            c.startsWith("mp4a.6b") || c.startsWith("mp4a.69") || c.startsWith("mp3") -> "mp3"
            c.startsWith("mp4a") || c.startsWith("aac") -> "aac"
            c.startsWith("opus") -> "opus"
            c.startsWith("vorbis") -> "vorbis"
            else -> null
        }
    }

    private fun merged(
        videoId: String, v: ExtractedStream, audioId: String, a: ExtractedStream, durationSeconds: Long?, isLive: Boolean,
    ): FormatOption {
        val exactV = v.sizeBytes?.takeIf { it > 0 }
        val exactA = a.sizeBytes?.takeIf { it > 0 }
        val sizeV = exactV ?: if (isLive) null else estimateSize(v.bitrateKbps, durationSeconds)
        val sizeA = exactA ?: if (isLive) null else estimateSize(a.bitrateKbps, durationSeconds)
        val total = if (sizeV != null && sizeA != null) sizeV + sizeA else null
        val bitrate = if (v.bitrateKbps != null && a.bitrateKbps != null) v.bitrateKbps + a.bitrateKbps else v.bitrateKbps
        return FormatOption(
            id = "$MERGED_PREFIX$videoId+$audioId",
            kind = StreamKind.VIDEO_AND_AUDIO,
            container = containerFor(v, a),
            width = v.width,
            height = v.height,
            videoCodec = v.videoCodec,
            audioCodec = a.audioCodec,
            fps = v.fps,
            bitrateKbps = bitrate,
            sizeBytes = total,
            sizeIsEstimate = total != null && (exactV == null || exactA == null),
            requiresMerge = true,
            note = "Video and audio are downloaded separately and merged on this device.",
        )
    }

    /** The stream the user's chosen format id stands for, or null when the site no longer offers it. Never picks "something similar". */
    fun find(media: ExtractedMedia, formatId: String): ExtractedStream? {
        val found = assignIds(media.streams).firstOrNull { it.first == formatId }?.second ?: return null
        return found.takeIf { it.role != StreamRole.VIDEO_ONLY && it.delivery == Delivery.PROGRESSIVE_HTTP && isHttpAddress(it.url) }
    }

    private fun toOption(id: String, s: ExtractedStream, durationSeconds: Long?, isLive: Boolean): FormatOption {
        val exact = s.sizeBytes?.takeIf { it > 0 }
        val estimate = if (exact == null && !isLive) estimateSize(s.bitrateKbps, durationSeconds) else null
        return FormatOption(
            id = id,
            kind = if (s.role == StreamRole.AUDIO_ONLY) StreamKind.AUDIO_ONLY else StreamKind.VIDEO_AND_AUDIO,
            container = s.container,
            width = s.width,
            height = s.height,
            videoCodec = s.videoCodec,
            audioCodec = s.audioCodec,
            fps = s.fps,
            bitrateKbps = s.bitrateKbps,
            sizeBytes = exact ?: estimate,
            sizeIsEstimate = exact == null && estimate != null,
            requiresMerge = false,
            note = s.label,
        )
    }

    private fun estimateSize(bitrateKbps: Int?, durationSeconds: Long?): Long? {
        if (bitrateKbps == null || bitrateKbps <= 0 || durationSeconds == null || durationSeconds <= 0) return null
        return bitrateKbps * 1000L / 8L * durationSeconds
    }

    private val MP4_CONTAINERS = setOf("mp4", "m4v")
    private val MP4_AUDIO_CONTAINERS = setOf("m4a", "mp4", "aac")
    private val WEBM_AUDIO_CONTAINERS = setOf("webm", "opus", "ogg", "weba")
    private val MP4_VIDEO_CODECS = setOf("avc", "hevc")
    private val MP4_AUDIO_CODECS = setOf("aac", "mp3")
    private val WEBM_VIDEO_CODECS = setOf("vp8", "vp9", "av1")
    private val WEBM_AUDIO_CODECS = setOf("opus", "vorbis")

    internal fun isHttpAddress(url: String?): Boolean =
        url != null && (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))
}
