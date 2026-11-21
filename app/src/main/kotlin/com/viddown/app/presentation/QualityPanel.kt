package com.viddown.app.presentation

import com.viddown.core.model.Formatters
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.StreamKind
import com.viddown.engine.api.SubtitleOption

/**
 * What the user picked in the panel's advanced options. Null means "automatic" (or "no subtitle").
 *
 *  * [codec]: "avc" (H.264), "hevc" (H.265), "vp9" or "av1".
 *  * [container]: "mp4", "webm" or "mkv".
 *  * [subtitleLang]: language code of a subtitle file to save next to the video.
 */
data class AdvancedOptions(
    val codec: String? = null,
    val container: String? = null,
    val subtitleLang: String? = null,
) {
    /**
     * The format as it is to be downloaded. Two streams that VidDown merges itself are copied (not re-encoded) into whatever container
     * is asked for, and Matroska takes every codec; any other choice was already made by picking the matching stream.
     */
    fun applyTo(format: FormatOption): FormatOption =
        if (container == "mkv" && format.requiresMerge) format.copy(container = "mkv") else format
}

/** One selectable row of the panel: what the user reads, and the format behind it. */
data class QualityChoice(
    /** Stable across videos: "v720", "a:fast", "a:high", "orig". Remembered as the last quality. */
    val key: String,
    /** The id of the [FormatOption] that is downloaded when this row is selected. */
    val formatId: String,
    val title: String,
    /** One short line; shown in "More formats" only. */
    val description: String,
    /** "22.3 MB": the real size only. Null when it is not known exactly (nothing is shown then). */
    val sizeText: String?,
    val isAudio: Boolean,
    val height: Int?,
)

/** What the advanced options can offer for this video: only what the formats (and the site) really have. */
data class AdvancedAvailability(
    val codecs: List<String>,
    val containers: List<String>,
    val subtitles: List<SubtitleOption>,
) {
    val any: Boolean get() = codecs.size > 1 || containers.size > 1 || subtitles.isNotEmpty()

    companion object {
        val NONE = AdvancedAvailability(emptyList(), emptyList(), emptyList())

        fun codecLabel(codec: String): String = when (codec) {
            "avc" -> "H.264"
            "hevc" -> "H.265"
            "vp9" -> "VP9"
            "av1" -> "AV1"
            else -> codec.uppercase()
        }

        fun containerLabel(container: String): String = when (container) {
            "mp4" -> "MP4"
            "webm" -> "WebM"
            "mkv" -> "MKV"
            else -> container.uppercase()
        }
    }
}

/**
 * The quality panel: at most one row per resolution and a few audio rows, built from the formats the engine really listed.
 * Video-only streams never appear (the merged video+audio row of the same resolution stands for them), and the same resolution is
 * never listed twice for another container or codec: those are chosen in the advanced options instead.
 */
data class QualityPanel(
    val audio: List<QualityChoice>,
    /** Video + audio, smallest resolution first. */
    val video: List<QualityChoice>,
    val availability: AdvancedAvailability,
) {
    val all: List<QualityChoice> get() = audio + video

    fun find(formatId: String?): QualityChoice? = formatId?.let { id -> all.firstOrNull { it.formatId == id } }

    fun keyOf(formatId: String?): String? = find(formatId)?.key

    /**
     * The few rows shown before "More formats": a fast low-resolution one, a high-quality one, and the audio rows. [selectedId] is
     * always included, so what is selected is always visible.
     */
    fun mainVideo(selectedId: String?): List<QualityChoice> {
        if (video.size <= 2) return video
        val fast = video.firstOrNull { it.height == 240 } ?: video.filter { (it.height ?: 0) <= 360 }.maxByOrNull { it.height ?: 0 } ?: video.first()
        val high = video.firstOrNull { it.height == 720 } ?: video.filter { (it.height ?: 0) <= 720 }.maxByOrNull { it.height ?: 0 }
        val picks = listOfNotNull(fast, high, find(selectedId)?.takeIf { !it.isAudio }).distinctBy { it.key }
        return picks.sortedBy { it.height ?: Int.MAX_VALUE }
    }

    /** The row remembered as [key], or the closest one when this video does not have exactly it. Null when [key] is null or unknown. */
    fun choiceFor(key: String?): QualityChoice? {
        if (key == null) return null
        all.firstOrNull { it.key == key }?.let { return it }
        return when {
            // "Best available": the sharpest resolution this video has (what a playlist's "Best available" row asks of every video).
            key == "best" -> video.lastOrNull { it.height != null } ?: video.lastOrNull()
            key.startsWith("v") -> {
                val wanted = key.drop(1).toIntOrNull() ?: return null
                video.filter { (it.height ?: Int.MAX_VALUE) <= wanted }.maxByOrNull { it.height ?: 0 }
                    ?: video.minByOrNull { it.height ?: Int.MAX_VALUE }
            }
            key.startsWith("a:") -> audio.lastOrNull()
            key == "orig" -> video.firstOrNull { it.key == "orig" }
            else -> null
        }
    }

    /** The row for a format that may not be shown itself (a video-only stream): the row of the same resolution, else the sharpest below it. */
    fun choiceMatching(format: FormatOption?): QualityChoice? {
        if (format == null) return null
        find(format.id)?.let { return it }
        return when (format.kind) {
            StreamKind.AUDIO_ONLY -> audio.lastOrNull()
            else -> {
                val h = format.height
                if (h == null) video.lastOrNull() else choiceFor("v$h")
            }
        }
    }
}

object QualityPanelBuilder {
    fun build(formats: List<FormatOption>, advanced: AdvancedOptions = AdvancedOptions(), subtitles: List<SubtitleOption> = emptyList()): QualityPanel {
        val complete = formats.filter { it.kind == StreamKind.VIDEO_AND_AUDIO || it.kind == StreamKind.ORIGINAL_FILE }
        val video = complete
            .groupBy { it.height }
            .mapNotNull { (height, group) -> group.maxWithOrNull(candidateOrder(advanced))?.let { videoChoice(it, height) } }
            .sortedBy { it.height ?: Int.MAX_VALUE }
        return QualityPanel(
            audio = audioChoices(formats.filter { it.kind == StreamKind.AUDIO_ONLY }),
            video = video,
            availability = AdvancedAvailability(
                codecs = complete.mapNotNull { codecFamily(it.videoCodec) }.distinct().sortedBy { CODEC_ORDER.indexOf(it).let { i -> if (i < 0) CODEC_ORDER.size else i } },
                containers = containersOf(complete),
                subtitles = subtitles,
            ),
        )
    }

    // ------------------------------------------------------------------------------------------------ video

    /** Which of several streams of one resolution is the row: the one that fits the advanced options, then the most compatible, then the best. */
    private fun candidateOrder(adv: AdvancedOptions): Comparator<FormatOption> =
        compareBy<FormatOption> { score(it, adv) }
            .thenBy { it.bitrateKbps ?: 0 }
            .thenBy { it.sizeBytes ?: 0L }

    private fun score(f: FormatOption, adv: AdvancedOptions): Int {
        val family = codecFamily(f.videoCodec)
        val container = f.container?.lowercase()
        var s = 0
        if (adv.codec != null && family == adv.codec) s += 8
        if (adv.container != null && (container == adv.container || (adv.container == "mkv" && f.requiresMerge))) s += 8
        if (family == "avc") s += 2 // plays everywhere
        if (container == "mp4") s += 1
        return s
    }

    private fun videoChoice(f: FormatOption, height: Int?): QualityChoice {
        if (height == null) {
            return QualityChoice("orig", f.id, "Original file", "The file as it is", sizeText(f), isAudio = false, height = null)
        }
        return QualityChoice("v$height", f.id, videoTitle(height), videoDescription(height), sizeText(f), isAudio = false, height = height)
    }

    fun videoTitle(h: Int): String = when {
        h <= 480 -> "Fast (${h}p)"
        h <= 1080 -> "High quality (${h}p)"
        h <= 1600 -> "2K (${h}p)"
        h <= 3000 -> "4K (${h}p)"
        else -> "8K (${h}p)"
    }

    fun videoDescription(h: Int): String = when {
        h <= 144 -> "Poor video quality"
        h <= 240 -> "Low quality for quick play"
        h <= 480 -> "Normal quality for quick play"
        h <= 720 -> "Clear view and quick play"
        h <= 1080 -> "High details for full screen play"
        h <= 1600 -> "Sharper details for big screens"
        else -> "Ultra sharp, large file"
    }

    private fun containersOf(complete: List<FormatOption>): List<String> {
        val found = complete.mapNotNull { it.container?.lowercase() }.filter { it in CONTAINER_ORDER }.toMutableSet()
        if (complete.any { it.requiresMerge }) found += "mkv" // two streams can always be put into Matroska
        return CONTAINER_ORDER.filter { it in found }
    }

    // ------------------------------------------------------------------------------------------------ audio

    /**
     * Up to two rows. "Fast": the best AAC stream (M4A), which plays on every phone, speaker and car. "High quality": the stream with
     * the highest bitrate, when that is another one. Nothing is converted, so no row promises a format this build cannot make.
     */
    private fun audioChoices(audio: List<FormatOption>): List<QualityChoice> {
        if (audio.isEmpty()) return emptyList()
        val byRate = compareBy<FormatOption> { it.bitrateKbps ?: 0 }.thenBy { it.sizeBytes ?: 0L }
        val compatible = audio.filter { codecFamily(it.audioCodec) == "aac" || it.container?.lowercase() in setOf("m4a", "mp4") }.maxWithOrNull(byRate)
        val best = audio.maxWithOrNull(byRate)!!
        val out = ArrayList<QualityChoice>(2)
        if (compatible != null) out += audioChoice("a:fast", "Fast", "Plays on any phone, speaker or car", compatible)
        if (best.id != compatible?.id) out += audioChoice("a:high", "High quality", "Best sound for the size", best)
        return out.sortedBy { choice -> audio.first { it.id == choice.formatId }.bitrateKbps ?: 0 }
    }

    private fun audioChoice(key: String, title: String, description: String, f: FormatOption) =
        QualityChoice(key, f.id, title, description, sizeText(f), isAudio = true, height = null)

    // ------------------------------------------------------------------------------------------------ helpers

    /**
     * Only a real size is shown: the exact one the site reported for exactly the stream that is downloaded when this row is chosen
     * (so it follows the codec and container picked in the advanced options). An estimate, or no size at all, shows nothing.
     */
    private fun sizeText(f: FormatOption): String? {
        if (f.sizeIsEstimate) return null
        return Formatters.bytes(f.sizeBytes?.takeIf { it > 0 })
    }

    /** "avc", "hevc", "vp9", "av1" for the codec names sites report ("avc1.640028", "vp09.00.40.08"...); null for anything else. */
    fun codecFamily(raw: String?): String? {
        val c = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "none" && it != "unknown" } ?: return null
        return when {
            c.startsWith("avc") || c.startsWith("h264") -> "avc"
            c.startsWith("hev") || c.startsWith("hvc") || c.startsWith("h265") -> "hevc"
            c.startsWith("vp09") || c.startsWith("vp9") -> "vp9"
            c.startsWith("av01") || c.startsWith("av1") -> "av1"
            c.startsWith("mp4a") || c.startsWith("aac") -> "aac"
            else -> null
        }
    }

    private val CODEC_ORDER = listOf("avc", "hevc", "vp9", "av1")
    private val CONTAINER_ORDER = listOf("mp4", "webm", "mkv")
}
