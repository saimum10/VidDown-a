package com.viddown.app.presentation

import com.viddown.core.model.AppSettings
import com.viddown.download.DownloadManager
import com.viddown.download.EnqueueResult
import com.viddown.engine.api.DownloadEngine
import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.EngineCapabilities
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineResult
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaInfo
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.MediaType
import com.viddown.engine.api.SuggestedAction
import com.viddown.engine.selector.EngineSelector

/** What the user asked to download from the browser: a detected resource or a link the WebView handed over. */
data class DownloadTarget(
    val url: String,
    val pageUrl: String?,
    /** A display name when known (a detected file's name); the engine's own answer wins once it has inspected the resource. */
    val name: String?,
    /** Null for a link whose type is not known yet. */
    val type: MediaType?,
)

/** The state of the quality bottom sheet. */
sealed interface QualityState {
    data object Hidden : QualityState

    /** The selected engine is retrieving the real formats. */
    data class Loading(val target: DownloadTarget) : QualityState

    data class Ready(
        val target: DownloadTarget,
        val request: MediaRequest,
        val info: MediaInfo,
        val rows: List<FormatRowUi>,
        val selectedId: String?,
        val engineLabel: String,
        /** Said when the engine returned no formats at all. */
        val notice: String?,
        /** True only when the engine genuinely handles this resource as a plain file, so it can be saved without choosing a format. */
        val originalFileAllowed: Boolean,
        /** The engine that listed [rows]; the choice is only queued while it is still the selected one. */
        val engineId: EngineId? = null,
        /** The advanced options (subtitle, codec, container) as chosen so far. */
        val advanced: AdvancedOptions = AdvancedOptions(),
        /** The format to queue at once when "Ask before downloading" is off (the same one that is selected); null when the user chooses. */
        val autoFormatId: String? = null,
    ) : QualityState {
        /** The panel as the user sees it: one row per resolution, audio and video+audio only, following the advanced options. */
        val panel: QualityPanel get() = QualityPanelBuilder.build(info.formats, advanced, info.subtitles)

        val canConfirm: Boolean get() = selectedId != null || (rows.isEmpty() && originalFileAllowed)

        /** The user picked another row. An id this panel does not know changes nothing. */
        fun withSelected(id: String): Ready = if (rows.any { it.id == id }) copy(selectedId = id) else this

        /** The user changed subtitle, codec or container: the panel is rebuilt, and the row that was selected stays selected. */
        fun withAdvanced(options: AdvancedOptions): Ready {
            val key = panel.keyOf(selectedId)
            val rebuilt = copy(advanced = options)
            val sameRow = rebuilt.panel.all.firstOrNull { it.key == key }?.formatId
            return rebuilt.copy(selectedId = sameRow ?: selectedId)
        }
    }

    data class Failed(
        val target: DownloadTarget,
        val message: String,
        val detail: String?,
        val action: SuggestedAction?,
        /** The engine found out that the address is a playlist: the caller lists its videos instead of showing this failure. */
        val isPlaylist: Boolean = false,
    ) : QualityState

    /** [automatic]: it was queued without asking, because \"Ask before downloading\" is off. */
    data class Queued(val target: DownloadTarget, val automatic: Boolean) : QualityState
}

/**
 * Browser download flow: inspect with the selected engine, show only the formats it returned, then queue the chosen one. Nothing
 * is queued before the engine has really looked at the resource, and the engine and format are stored with the download.
 */
class MediaDownloader(
    private val engines: EngineSelector,
    private val manager: DownloadManager,
    private val settings: suspend () -> AppSettings,
    /** Cookie, Referer and User-Agent for [url] as the page would send them: (pageUrl, url) -> headers. */
    private val headers: (pageUrl: String?, url: String) -> Map<String, String>,
) {
    suspend fun inspect(target: DownloadTarget): QualityState {
        val s = settings()
        val engine = when (val e = engines.engineForUrl(target.url)) {
            is EngineResult.Failure -> return failed(target, e.error)
            is EngineResult.Success -> e.value
        }
        val type = target.type
        if (type != null) {
            val handling = engine.capabilities.handling(type)
            if (handling is EngineCapabilities.Handling.Unsupported) {
                return failed(target, DownloadError.unsupportedResource(engine.id, handling.reason))
            }
        }
        val request = MediaRequest(url = target.url, pageUrl = target.pageUrl, headers = headers(target.pageUrl, target.url))
        val info = when (val r = engine.inspect(request)) {
            is EngineResult.Failure -> return failed(target, r.error)
            is EngineResult.Success -> r.value
        }
        val ready = QualityOpening.ready(target, request, info, engine, s)
        // "Ask before downloading" off: queue the default choice at once, but only when it really exists.
        return ready.autoFormatId?.let { confirm(ready, it, automatic = true) } ?: ready
    }

    suspend fun confirm(ready: QualityState.Ready, selectedId: String?, automatic: Boolean = false): QualityState {
        val format = formatToQueue(ready, selectedId)
        if (format == null && !(ready.rows.isEmpty() && ready.originalFileAllowed)) {
            return QualityState.Failed(ready.target, "Choose one of the options first.", null, SuggestedAction.NONE)
        }
        return when (val r = manager.enqueue(ready.request, format, ready.info.title ?: ready.target.name, ready.info.durationSeconds, listedWith = ready.engineId, subtitleLang = ready.advanced.subtitleLang)) {
            is EnqueueResult.Queued -> QualityState.Queued(ready.target, automatic)
            is EnqueueResult.Rejected -> failed(ready.target, r.error)
        }
    }

    private fun failed(target: DownloadTarget, e: DownloadError) =
        QualityState.Failed(target, e.userMessage, e.technicalDetail.takeIf { it.isNotBlank() }, e.suggestedAction, isPlaylist = e.isPlaylist)
}

/** The format that is queued for [selectedId]: the stream the row stands for, put into the container chosen in the advanced options. */
internal fun formatToQueue(ready: QualityState.Ready, selectedId: String?): FormatOption? =
    ready.info.formats.firstOrNull { it.id == selectedId }?.let { ready.advanced.applyTo(it) }

/**
 * What the quality panel opens with. The browser and "Download from link" both use this, so the two always show the same panel:
 * the same rows, the same sizes, the same advanced options and the same selected row.
 */
internal object QualityOpening {
    fun ready(target: DownloadTarget, request: MediaRequest, info: MediaInfo, engine: DownloadEngine, settings: AppSettings): QualityState.Ready {
        val type = target.type
        val rows = QualityPresenter.rows(info.formats)
        val picked = settings.defaultQuality.pick(info.formats, canMerge = engine.capabilities.mayNeedFfmpeg)
        val advanced = AdvancedOptions(codec = settings.preferredCodec, container = settings.preferredContainer)
        val panel = QualityPanelBuilder.build(info.formats, advanced, info.subtitles)
        // The default quality as a row of the panel: a video-only stream (or a stream hidden by the codec chosen in the advanced options)
        // is never downloaded by itself, the row of the same resolution stands for it. What is queued is what the row shows.
        val defaultRowId = picked?.let { panel.choiceMatching(it)?.formatId ?: it.id }
        val autoFormatId = if (!settings.askBeforeDownloading) defaultRowId else null
        // What is selected when the panel opens: the default when it is queued without asking, else the quality of the last download,
        // else the saved default.
        val opening = autoFormatId
            ?: panel.choiceFor(settings.lastQuality)?.formatId
            ?: defaultRowId
            ?: info.formats.firstOrNull()?.let { panel.choiceMatching(it)?.formatId ?: it.id }
        val directFile = type == null || type == MediaType.DIRECT_MP4 || type == MediaType.DIRECT_WEBM || type == MediaType.DIRECT_OTHER
        return QualityState.Ready(
            target = target,
            request = request,
            info = info,
            rows = rows,
            selectedId = opening,
            engineLabel = engine.displayName,
            notice = if (rows.isEmpty()) {
                "${engine.displayName} did not report any quality options for this resource." +
                    if (directFile && engine.capabilities.handling(MediaType.DIRECT_OTHER) == EngineCapabilities.Handling.Supported) {
                        " The file can still be saved exactly as the server provides it."
                    } else {
                        ""
                    }
            } else if (info.formats.size == 1 && !engine.capabilities.listsFormats) {
                "${engine.displayName} offers the file as the server provides it: there are no resolutions to choose from."
            } else {
                null
            },
            originalFileAllowed = directFile && engine.capabilities.handling(MediaType.DIRECT_OTHER) == EngineCapabilities.Handling.Supported,
            engineId = engine.id,
            advanced = advanced,
            autoFormatId = autoFormatId,
        )
    }
}
