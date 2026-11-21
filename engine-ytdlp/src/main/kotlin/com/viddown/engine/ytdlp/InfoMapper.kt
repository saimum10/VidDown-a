package com.viddown.engine.ytdlp

import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.StreamKind
import com.viddown.engine.api.SubtitleOption
import com.viddown.engine.api.sortedForDisplay
import kotlin.math.roundToInt

/**
 * Turns the JSON of `yt-dlp --dump-single-json` into VidDown formats. Only what yt-dlp reported is used; a value it did not
 * report stays null. Sizes are yt-dlp's own (exact `filesize` or its `filesize_approx`, flagged as an estimate): never computed here.
 *
 * What is offered (docs/ytdlp.md, decisions D70-D73):
 *  * complete files (video+audio in one stream, or a single file as served),
 *  * video+audio pairs that VidDown merges itself with FFmpeg (one per distinct height / codec family / container),
 *  * video-only and audio-only streams (one per distinct combination, best bitrate).
 * Streams that this build cannot download are left out and counted, so the reason can be given when nothing is left.
 */
internal object InfoMapper {
    /** Protocols yt-dlp downloads on its own, without FFmpeg. "m3u8" (FFmpeg downloader), rtmp, rtsp, mms... are not among them. */
    private val downloadableProtocols = setOf("http", "https", "m3u8_native", "http_dash_segments", "ism")

    data class Mapping(
        val title: String?,
        val durationSeconds: Long?,
        val thumbnailUrl: String?,
        val uploader: String?,
        val isLive: Boolean,
        val isUpcoming: Boolean,
        val extractor: String?,
        val formats: List<FormatOption>,
        /** Media formats in the JSON (storyboards and thumbnails are not counted), including the ones that were left out. */
        val totalFormats: Int,
        val drmFormats: Int,
        val needsFfmpegNetwork: Int,
        val subtitles: List<SubtitleOption> = emptyList(),
    )

    private data class Stream(
        val id: String,
        val ext: String?,
        val protocol: String?,
        val videoCodec: String?,
        val audioCodec: String?,
        val hasVideoStream: Boolean,
        val hasAudioStream: Boolean,
        val width: Int?,
        val height: Int?,
        val fps: Double?,
        val totalBitrate: Double?,
        val videoBitrate: Double?,
        val audioBitrate: Double?,
        val size: Long?,
        val sizeIsEstimate: Boolean,
        val note: String?,
        val language: String?,
        val languagePreference: Int,
    ) {
        val kind: StreamKind
            get() = when {
                !hasVideoStream -> StreamKind.AUDIO_ONLY
                !hasAudioStream -> StreamKind.VIDEO_ONLY
                videoCodec != null && audioCodec != null -> StreamKind.VIDEO_AND_AUDIO
                else -> StreamKind.ORIGINAL_FILE // the streams of this file are not described: it is offered as the file it is
            }
        val bitrateKbps: Int? get() = (if (kind == StreamKind.AUDIO_ONLY) (audioBitrate ?: totalBitrate) else (totalBitrate ?: videoBitrate))?.roundToInt()
    }

    fun map(root: Map<String, Any?>): Mapping {
        val raw = root["formats"].asList().orEmpty().mapNotNull { it.asMap() }
        val usable = ArrayList<Stream>()
        var drm = 0
        var ffmpegNetwork = 0
        var candidates = 0
        for (f in raw) {
            val id = f["format_id"].asString() ?: continue
            val protocol = f["protocol"].asString()?.lowercase()
            val ext = f["ext"].asString()?.lowercase()
            val vcodecRaw = f["vcodec"].asString()
            val acodecRaw = f["acodec"].asString()
            val noVideo = vcodecRaw.equals("none", true)
            val noAudio = acodecRaw.equals("none", true)
            if (noVideo && noAudio) continue // storyboards, thumbnails: not media
            if (ext == "mhtml" || protocol == "mhtml") continue
            candidates++
            if (f["has_drm"].asBoolean() == true) {
                drm++
                continue
            }
            if (!YtDlpArgs.isSafeFormatId(id)) continue
            if (protocol != null && protocol !in downloadableProtocols) {
                ffmpegNetwork++
                continue
            }
            val exact = f["filesize"].asLong()?.takeIf { it > 0 }
            val approx = f["filesize_approx"].asLong()?.takeIf { it > 0 }
            usable += Stream(
                id = id,
                ext = ext,
                protocol = protocol,
                videoCodec = Codecs.known(vcodecRaw),
                audioCodec = Codecs.known(acodecRaw),
                hasVideoStream = !noVideo,
                hasAudioStream = !noAudio,
                width = f["width"].asInt()?.takeIf { it > 0 },
                height = f["height"].asInt()?.takeIf { it > 0 },
                fps = f["fps"].asDouble()?.takeIf { it > 0 },
                totalBitrate = f["tbr"].asDouble()?.takeIf { it > 0 },
                videoBitrate = f["vbr"].asDouble()?.takeIf { it > 0 },
                audioBitrate = f["abr"].asDouble()?.takeIf { it > 0 },
                size = exact ?: approx,
                sizeIsEstimate = exact == null && approx != null,
                note = f["format_note"].asString()?.takeIf { it.isNotBlank() },
                language = f["language"].asString()?.takeIf { it.isNotBlank() },
                languagePreference = f["language_preference"].asInt() ?: 0,
            )
        }

        val options = ArrayList<FormatOption>()
        val complete = usable.filter { it.kind == StreamKind.VIDEO_AND_AUDIO || it.kind == StreamKind.ORIGINAL_FILE }
        val videoOnly = usable.filter { it.kind == StreamKind.VIDEO_ONLY }
        val audioOnly = usable.filter { it.kind == StreamKind.AUDIO_ONLY }

        complete.mapTo(options) { single(it) }

        val videoChoices = bestPerGroup(videoOnly) { Triple(it.height, Codecs.family(it.videoCodec), it.ext) }
        val audioChoices = bestPerGroup(audioOnly) { Triple(Codecs.family(it.audioCodec), it.ext, it.language) }

        for (v in videoChoices) {
            options += single(v)
            val a = partnerFor(v, audioOnly)
            val container = a?.let { MergePlanner.containerFor(v.videoCodec, it.audioCodec) }
            if (a != null && container != null) options += merged(v, a, container)
        }
        audioChoices.mapTo(options) { single(it) }

        return Mapping(
            title = root["title"].asString()?.takeIf { it.isNotBlank() },
            durationSeconds = root["duration"].asDouble()?.takeIf { it > 0 }?.toLong(),
            thumbnailUrl = root["thumbnail"].asString()?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            uploader = (root["uploader"].asString() ?: root["channel"].asString())?.takeIf { it.isNotBlank() },
            isLive = root["is_live"].asBoolean() == true || root["live_status"].asString() == "is_live",
            isUpcoming = root["live_status"].asString() == "is_upcoming",
            extractor = root["extractor_key"].asString() ?: root["extractor"].asString(),
            formats = options.sortedForDisplay(),
            totalFormats = candidates,
            drmFormats = drm,
            needsFfmpegNetwork = ffmpegNetwork,
            subtitles = subtitlesOf(root),
        )
    }

    private val safeLang = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$")

    /**
     * The subtitle tracks the site made by hand, plus the machine-made captions of the language the video is spoken in (the site
     * otherwise lists a machine translation into every language, which nobody wants in a picker). Only plain language codes are
     * kept, so a code can never be read as an option or a pattern by yt-dlp.
     */
    private fun subtitlesOf(root: Map<String, Any?>): List<SubtitleOption> {
        val out = LinkedHashMap<String, SubtitleOption>()
        for ((lang, tracks) in root["subtitles"].asMap().orEmpty()) {
            if (!safeLang.matches(lang) || lang == "live_chat") continue
            val name = tracks.asList()?.firstNotNullOfOrNull { it.asMap()?.get("name").asString()?.takeIf { n -> n.isNotBlank() } }
            out[lang] = SubtitleOption(lang, name ?: lang)
        }
        val spoken = root["language"].asString()?.takeIf { safeLang.matches(it) }
        if (spoken != null && out.keys.none { it == spoken || it.startsWith("$spoken-") }) {
            val auto = root["automatic_captions"].asMap().orEmpty()
            val key = listOf("$spoken-orig", spoken).firstOrNull { it in auto && safeLang.matches(it) }
            if (key != null) out[key] = SubtitleOption(key, "$spoken (auto)", automatic = true)
        }
        return out.values.toList()
    }

    /** Keeps the stream with the highest bitrate of each group (ties: the larger known size). Order of first appearance is kept. */
    private fun bestPerGroup(streams: List<Stream>, key: (Stream) -> Any): List<Stream> {
        val best = LinkedHashMap<Any, Stream>()
        for (s in streams) {
            val k = key(s)
            val current = best[k]
            if (current == null || rank(s) > rank(current)) best[k] = s
        }
        return best.values.toList()
    }

    private fun rank(s: Stream): Double = (s.totalBitrate ?: s.audioBitrate ?: s.videoBitrate ?: 0.0) * 1000.0 + ((s.size ?: 0L) / 1_000_000.0).coerceAtMost(999.0)

    /** The audio stream to pair with [video]: same container family first, then the track yt-dlp prefers, then the best bitrate. */
    private fun partnerFor(video: Stream, audios: List<Stream>): Stream? {
        fun sameFamily(a: Stream): Int = when {
            video.ext in setOf("mp4", "m4v") && a.ext in setOf("m4a", "mp4", "aac") -> 2
            video.ext == "webm" && a.ext in setOf("webm", "opus", "ogg") -> 2
            else -> 1
        }
        return audios
            .filter { MergePlanner.containerFor(video.videoCodec, it.audioCodec) != null }
            .maxWithOrNull(
                compareBy<Stream> { sameFamily(it) }
                    .thenBy { it.languagePreference }
                    .thenBy { it.audioBitrate ?: it.totalBitrate ?: 0.0 },
            )
    }

    private fun single(s: Stream): FormatOption = FormatOption(
        id = s.id,
        kind = s.kind,
        container = s.ext,
        width = s.width,
        height = s.height,
        videoCodec = s.videoCodec,
        audioCodec = s.audioCodec,
        fps = s.fps,
        bitrateKbps = s.bitrateKbps,
        sizeBytes = s.size,
        sizeIsEstimate = s.sizeIsEstimate,
        requiresMerge = false,
        note = noteFor(s),
    )

    private fun merged(v: Stream, a: Stream, container: String): FormatOption {
        val bothSized = v.size != null && a.size != null
        val audioRate = a.audioBitrate ?: a.totalBitrate
        val videoRate = v.totalBitrate
        val bitrate = if (videoRate != null && audioRate != null) (videoRate + audioRate).roundToInt() else v.bitrateKbps
        return FormatOption(
            id = "${v.id}+${a.id}",
            kind = StreamKind.VIDEO_AND_AUDIO,
            container = container,
            width = v.width,
            height = v.height,
            videoCodec = v.videoCodec,
            audioCodec = a.audioCodec,
            fps = v.fps,
            bitrateKbps = bitrate,
            sizeBytes = if (bothSized) v.size!! + a.size!! else null,
            sizeIsEstimate = bothSized && (v.sizeIsEstimate || a.sizeIsEstimate),
            requiresMerge = true,
            note = "Video and audio are downloaded separately and merged on this device.",
        )
    }

    private fun noteFor(s: Stream): String? {
        val parts = ArrayList<String>(3)
        when (s.protocol) {
            "m3u8_native" -> parts += "HLS stream, downloaded in segments"
            "http_dash_segments" -> parts += "DASH stream, downloaded in segments"
            "ism" -> parts += "Smooth Streaming, downloaded in segments"
        }
        s.language?.let { parts += "Language: $it" }
        s.note?.takeIf { s.kind == StreamKind.AUDIO_ONLY || s.height == null }?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(". ")
    }

    /** One plain sentence for why nothing can be offered; used in the error shown to the user. */
    fun whyNothing(m: Mapping): String = when {
        m.totalFormats == 0 -> "yt-dlp found the page but no media formats on it."
        m.drmFormats > 0 && m.drmFormats + m.needsFfmpegNetwork >= m.totalFormats -> "Every format is protected by DRM, which VidDown never bypasses."
        m.needsFfmpegNetwork > 0 -> "The site only offers streams that need FFmpeg to download them from the network, which this build's FFmpeg cannot do."
        else -> "None of the formats yt-dlp found can be saved by this build."
    }
}
