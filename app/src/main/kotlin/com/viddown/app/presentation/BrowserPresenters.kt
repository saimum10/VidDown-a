package com.viddown.app.presentation

import com.viddown.browser.DetectedMedia
import com.viddown.browser.DetectionMethod
import com.viddown.browser.DetectionStatus
import com.viddown.browser.DetectionStatusResolver
import com.viddown.browser.PageLimitation
import com.viddown.browser.Protection
import com.viddown.browser.TabDetectionState
import com.viddown.browser.UrlPolicy
import com.viddown.core.model.Formatters
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineCapabilities
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineMode
import com.viddown.engine.api.EngineRouting
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaMimeTypes
import com.viddown.engine.api.MediaType
import com.viddown.engine.api.StreamKind
import com.viddown.engine.api.sortedForDisplay

/** The selected engine as the browser needs to know it: can it be used, and what does it handle. */
data class EngineInfo(
    /** Null when no engine is selected at all (the saved choice is not recognised). */
    val id: EngineId?,
    val label: String,
    val availability: EngineAvailability,
    /** Null when the engine cannot be created. */
    val capabilities: EngineCapabilities?,
)

/**
 * The engine the browser judges a link against. Fixed mode: always the same engine. Auto: the engine [EngineRouting] picks for that
 * link (YouTube -> NewPipe, everything else -> yt-dlp). [label] is what the screen calls the choice ("Auto", "NewPipe"...).
 */
class EngineRoute(val label: String, private val pick: (String?) -> EngineInfo) {
    fun forUrl(url: String?): EngineInfo = pick(url)

    companion object {
        fun fixed(info: EngineInfo) = EngineRoute(info.label) { info }

        fun auto(newPipe: EngineInfo, ytDlp: EngineInfo) =
            EngineRoute(EngineMode.AUTO.label) { url -> if (EngineRouting.engineFor(url) == EngineId.NEWPIPE) newPipe else ytDlp }
    }
}

/** One detected resource ready to show: the facts, what the selected engine can do with it, and the words for it. */
data class DetectionItemUi(
    val media: DetectedMedia,
    val status: DetectionStatus,
    val title: String,
    /** Type, size, quality, duration and caveats joined with dots. Unknown size is said to be unknown. */
    val details: String,
)

data class DetectionsUi(
    val items: List<DetectionItemUi> = emptyList(),
    val checking: Boolean = false,
    val limitation: PageLimitation? = null,
    val engineLabel: String = "",
    val dropped: Int = 0,
    /** The selected engine can read the page's own address (a video-site extractor): the page link can be downloaded directly. */
    val pageDownload: Boolean = false,
) {
    val count: Int get() = items.size
    val downloadableCount: Int get() = items.count { it.status.canDownload }

    /** What to say when nothing can be downloaded (null when something can). */
    val emptyExplanation: String?
        get() = when {
            downloadableCount > 0 -> null
            items.isNotEmpty() -> null // the items carry their own explanation
            checking -> "Checking what this page loads…"
            limitation != null -> limitation.message
            else -> "No downloadable media was found on this page yet. Start playing the video, then check again."
        }
}

object DetectionPresenter {
    fun typeLabel(m: DetectedMedia): String {
        val ext = m.extension?.uppercase()
        val audio = MediaMimeTypes.isAudioExtension(m.extension) || m.mime?.startsWith("audio/") == true
        return when (m.type) {
            MediaType.HLS -> "HLS stream"
            MediaType.DASH -> "DASH stream"
            MediaType.DIRECT_MP4 -> if (audio) "M4A audio" else "MP4 video"
            MediaType.DIRECT_WEBM -> if (audio) "WebM audio" else "WebM video"
            MediaType.DIRECT_OTHER -> when {
                ext != null && audio -> "$ext audio"
                ext != null -> "$ext video"
                else -> "Media file"
            }
            MediaType.PAGE -> "Web page"
        }
    }

    fun title(m: DetectedMedia): String =
        m.title?.takeIf { it.isNotBlank() } ?: UrlPolicy.displayHost(m.url)?.let { "Media from $it" } ?: "Media"

    fun details(m: DetectedMedia): String {
        val parts = ArrayList<String>()
        parts += typeLabel(m)
        if (!m.isStreamingManifest) parts += Formatters.bytes(m.sizeBytes) ?: "Size unknown"
        m.qualityInfo?.let { parts += it }
        Formatters.duration(m.durationSeconds)?.let { parts += it }
        if (m.isLive == true) parts += "Live"
        if (m.protection == Protection.ENCRYPTED) parts += "Encrypted"
        if (m.protection == Protection.DRM) parts += "DRM-protected"
        if (m.method == DetectionMethod.URL_EXTENSION) parts += "Not verified"
        return parts.joinToString(" \u00B7 ")
    }

    fun item(media: DetectedMedia, status: DetectionStatus) = DetectionItemUi(media, status, title(media), details(media))

    /** Downloadable items first (in the order they were found), then the ones the engine cannot take. */
    fun build(state: TabDetectionState, engine: EngineInfo): DetectionsUi = build(state, EngineRoute.fixed(engine), null)

    /** Same, but each resource is judged by the engine that would download it ([pageUrl] is the address of the page being viewed). */
    fun build(state: TabDetectionState, route: EngineRoute, pageUrl: String?): DetectionsUi {
        val items = state.items.map { media ->
            val engine = route.forUrl(media.url)
            val id = engine.id
            item(media, if (id == null) DetectionStatusResolver.resolveWithoutEngine(media) else DetectionStatusResolver.resolve(media, id, engine.availability, engine.capabilities))
        }
        val pageEngine = route.forUrl(pageUrl)
        return DetectionsUi(
            items = items.filter { it.status.canDownload } + items.filterNot { it.status.canDownload },
            checking = state.checking,
            limitation = state.limitation,
            engineLabel = route.label,
            dropped = state.dropped,
            pageDownload = pageEngine.availability == EngineAvailability.Available &&
                pageEngine.capabilities?.handling(MediaType.PAGE) == EngineCapabilities.Handling.Supported,
        )
    }
}

// ----------------------------------------------------------------------------------------------------------- formats

enum class FormatGroup(val label: String) {
    VIDEO_AND_AUDIO("Video + audio"),
    ORIGINAL_FILE("Original file"),
    VIDEO_ONLY("Video only"),
    AUDIO_ONLY("Audio only"),
}

data class FormatRowUi(val id: String, val group: FormatGroup, val title: String, val details: String)

/** Turns what an engine reported into rows. Only what the engine said is shown: a missing value is left out, never guessed. */
object QualityPresenter {
    fun rows(formats: List<FormatOption>): List<FormatRowUi> =
        formats.sortedForDisplay().map { FormatRowUi(it.id, group(it), title(it), details(it)) }

    fun group(f: FormatOption): FormatGroup = when (f.kind) {
        StreamKind.VIDEO_AND_AUDIO -> FormatGroup.VIDEO_AND_AUDIO
        StreamKind.VIDEO_ONLY -> FormatGroup.VIDEO_ONLY
        StreamKind.AUDIO_ONLY -> FormatGroup.AUDIO_ONLY
        StreamKind.ORIGINAL_FILE -> FormatGroup.ORIGINAL_FILE
    }

    fun title(f: FormatOption): String = when (f.kind) {
        StreamKind.ORIGINAL_FILE -> "Original file"
        StreamKind.AUDIO_ONLY -> f.bitrateKbps?.let { "Audio \u00B7 $it kbps" } ?: "Audio"
        else -> (f.resolutionLabel ?: "Video") + (f.fps?.takeIf { it >= 30 }?.let { " ${it.toInt()} fps" } ?: "")
    }

    fun details(f: FormatOption): String {
        val parts = ArrayList<String>()
        f.container?.takeIf { it.isNotBlank() }?.let { parts += it.uppercase() }
        f.videoCodec?.takeIf { it.isNotBlank() && it != "none" }?.let { parts += it }
        f.audioCodec?.takeIf { it.isNotBlank() && it != "none" }?.let { parts += it }
        parts += when (f.kind) {
            StreamKind.VIDEO_AND_AUDIO -> "video + audio"
            StreamKind.VIDEO_ONLY -> "video only, no sound"
            StreamKind.AUDIO_ONLY -> "audio only"
            StreamKind.ORIGINAL_FILE -> "as served"
        }
        val size = Formatters.bytes(f.sizeBytes)
        parts += if (size == null) "Size unknown" else if (f.sizeIsEstimate) "about $size" else size
        if (f.kind != StreamKind.AUDIO_ONLY && f.bitrateKbps != null && f.kind != StreamKind.ORIGINAL_FILE) parts += "${f.bitrateKbps} kbps"
        if (f.requiresMerge) parts += "merged with audio afterwards"
        f.note?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(" \u00B7 ")
    }
}
