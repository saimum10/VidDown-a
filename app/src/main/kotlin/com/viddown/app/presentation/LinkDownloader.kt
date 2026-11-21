package com.viddown.app.presentation

import com.viddown.core.model.AppSettings
import com.viddown.core.model.Formatters
import com.viddown.core.model.UrlInput
import com.viddown.core.model.UrlInputResult
import com.viddown.download.DownloadManager
import com.viddown.download.EnqueueResult
import com.viddown.engine.api.DownloadEngine
import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineResult
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaInfo
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.PlaylistEntry
import com.viddown.engine.api.PlaylistInfo
import com.viddown.engine.api.PlaylistLinks
import com.viddown.engine.api.SuggestedAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

sealed interface LinkState {
    data object Idle : LinkState
    data object Checking : LinkState

    /** The link was inspected and the user may still decide ("Ask before downloading"). */
    data class Ready(
        val request: MediaRequest,
        val info: MediaInfo,
        val format: FormatOption?,
        val fileName: String,
        /** Null when the server did not say: the UI then shows "Size unknown", never a guess. */
        val sizeText: String?,
        val typeText: String?,
        val engineLabel: String,
        /** The engine that read the link; the download is only queued while it is still the selected one. */
        val engineId: EngineId? = null,
        /** Every choice the engine listed (resolution, container, codecs, size); the user picks one before downloading. */
        val rows: List<FormatRowUi> = emptyList(),
        /** True when "Ask before downloading" is off and a format was picked by the default quality: the download starts at once. */
        val autoDownload: Boolean = false,
        /**
         * The panel the user sees: exactly the one the browser shows (rows, sizes, advanced options, selected row). [format], [sizeText]
         * and [typeText] follow its selected row.
         */
        val quality: QualityState.Ready,
    ) : LinkState

    data class Failed(val message: String, val detail: String?, val action: SuggestedAction?) : LinkState
    data class Queued(val id: String) : LinkState

    /**
     * The link is a playlist and its videos (at most [PlaylistInfo.MAX_ENTRIES]) were listed. The user ticks the ones to save:
     * [selected] holds the addresses of the ticked videos. Nothing is queued until the user confirms.
     */
    data class Playlist(
        val request: MediaRequest,
        val info: PlaylistInfo,
        val engineLabel: String,
        val engineId: EngineId,
        val selected: Set<String> = emptySet(),
        /** True while the quality sheet of the playlist is open (after "Download selected", before anything is queued). */
        val choosingQuality: Boolean = false,
        /** The quality every ticked video is saved in, as a key of [PlaylistQuality] ("best", "v720", "a:fast"...); null until the sheet opens. */
        val qualityKey: String? = null,
    ) : LinkState {
        val selectedCount: Int get() = selected.size

        /** True when every listed video is ticked: the button then reads "Unselect all". */
        val allSelected: Boolean get() = info.entries.isNotEmpty() && selected.size == info.entries.size
    }

    /** The ticked videos of a playlist are being read and queued one after the other ([done] of [total] so far). */
    data class QueueingPlaylist(val done: Int, val total: Int, val queued: Int, val failed: Int) : LinkState

    /** All ticked videos were handled: [queued] are in Downloads, [failed] could not be read ([firstProblem] says why for the first). */
    data class PlaylistQueued(val queued: Int, val failed: Int, val firstProblem: String?) : LinkState
}

/**
 * "Download from link": inspect first, then ask (or not, per the setting), then queue. Used from Home until the in-app
 * browser can find media on a page. Nothing is queued unless the engine really inspected the link.
 */
class LinkDownloader(
    private val engines: com.viddown.engine.selector.EngineSelector,
    private val manager: DownloadManager,
    /** Headers to send for [url] (the Cookie of the in-app browser for that site). Nothing by default: a link is read anonymously. */
    private val requestHeaders: (url: String) -> Map<String, String> = { emptyMap() },
    private val settings: suspend () -> AppSettings,
) {
    /** Inspects the link and, when "Ask before downloading" is off, queues it at once. */
    suspend fun check(text: String): LinkState = proceed(inspect(text))

    /** Only reads the link: never queues anything, so it is safe to run in the background before the user asks. */
    suspend fun inspect(text: String): LinkState {
        val s = settings()
        val url = when (val parsed = UrlInput.parse(text, s.searchEngine)) {
            is UrlInputResult.Url -> parsed.url
            is UrlInputResult.Search -> return LinkState.Failed("Paste a complete link that starts with http:// or https://.", null, null)
            UrlInputResult.Empty -> return LinkState.Failed("Paste a link to download.", null, null)
            is UrlInputResult.UnsupportedScheme -> return LinkState.Failed("Only http and https links can be downloaded (not ${parsed.scheme}:).", null, null)
            is UrlInputResult.Invalid -> return LinkState.Failed(parsed.reason, null, null)
        }
        val engine = when (val e = engines.engineForUrl(url)) {
            is EngineResult.Failure -> return failed(e.error)
            is EngineResult.Success -> e.value
        }
        val request = MediaRequest(url, headers = requestHeaders(url))
        // A link that clearly names a playlist is listed first: reading it as one video would only give its first video.
        if (PlaylistLinks.looksLikePlaylist(url)) {
            when (val p = engine.inspectPlaylist(request)) {
                is EngineResult.Success -> return playlistState(request, p.value, engine)
                is EngineResult.Failure -> if (p.error.kind != ErrorKind.UNSUPPORTED_RESOURCE) return failed(p.error)
                // "not a playlist this engine can list": the link is read as one video below
            }
        }
        val info = when (val r = engine.inspect(request)) {
            is EngineResult.Failure -> {
                // The engine found out that the link is a playlist (any site): list it instead of failing.
                if (r.error.isPlaylist) {
                    return when (val p = engine.inspectPlaylist(request)) {
                        is EngineResult.Success -> playlistState(request, p.value, engine)
                        is EngineResult.Failure -> failed(p.error)
                    }
                }
                return failed(r.error)
            }
            is EngineResult.Success -> r.value
        }
        // The same panel as in the browser: one place decides the rows, the sizes and what is selected.
        val quality = QualityOpening.ready(DownloadTarget(url, null, null, null), request, info, engine, s)
        val first = info.formats.firstOrNull { it.id == quality.selectedId } ?: info.formats.firstOrNull()
        return LinkState.Ready(
            request = request,
            info = info,
            format = first,
            fileName = info.title?.takeIf { it.isNotBlank() } ?: Formatters.fileNameFromUrl(url) ?: url,
            sizeText = sizeText(first),
            typeText = first?.container?.uppercase(),
            engineLabel = engine.displayName,
            engineId = engine.id,
            rows = quality.rows,
            // Without a format the user must look at the result: never download "something" silently.
            autoDownload = quality.autoFormatId != null,
            quality = quality,
        )
    }

    /**
     * Lists the playlist at [url] for the in-app browser (the same list as "Download from link"). Returns the playlist, a failure that
     * says why it could not be listed, or null when the selected engine cannot list this address as a playlist: the caller then reads it
     * as one video. [pageUrl] is the page the address came from (the Referer of the request).
     */
    suspend fun listPlaylist(url: String, pageUrl: String? = null): LinkState? {
        val engine = when (val e = engines.engineForUrl(url)) {
            is EngineResult.Failure -> return failed(e.error)
            is EngineResult.Success -> e.value
        }
        val request = MediaRequest(url, pageUrl = pageUrl, headers = requestHeaders(url))
        return when (val p = engine.inspectPlaylist(request)) {
            is EngineResult.Success -> playlistState(request, p.value, engine)
            is EngineResult.Failure -> if (p.error.kind == ErrorKind.UNSUPPORTED_RESOURCE) null else failed(p.error)
        }
    }

    private fun playlistState(request: MediaRequest, info: PlaylistInfo, engine: DownloadEngine): LinkState =
        if (info.entries.isEmpty()) {
            LinkState.Failed("This playlist has no videos that can be downloaded.", null, null)
        } else {
            LinkState.Playlist(request = request, info = info, engineLabel = engine.displayName, engineId = engine.id)
        }

    /** The user ticked or unticked one video of the playlist. An address that is not in the list changes nothing. */
    fun togglePlaylistEntry(state: LinkState.Playlist, url: String): LinkState.Playlist {
        if (state.info.entries.none { it.url == url }) return state
        return state.copy(selected = if (url in state.selected) state.selected - url else state.selected + url)
    }

    /** "Select all" ticks every video; when every video is already ticked ("Unselect all") it unticks them all. */
    fun togglePlaylistAll(state: LinkState.Playlist): LinkState.Playlist =
        state.copy(selected = if (state.allSelected) emptySet() else state.info.entries.map { it.url }.toSet())

    /** "Download selected": opens the quality sheet. It starts on the quality of the last download, else the default quality of the settings. */
    suspend fun openPlaylistQuality(state: LinkState.Playlist): LinkState.Playlist {
        if (state.selectedCount == 0) return state
        return state.copy(choosingQuality = true, qualityKey = state.qualityKey ?: PlaylistQuality.initialKey(settings()))
    }

    /** The user picked another quality in the playlist's sheet. A key the sheet does not offer changes nothing. */
    fun selectPlaylistQuality(state: LinkState.Playlist, key: String): LinkState.Playlist =
        if (PlaylistQuality.isKnown(key)) state.copy(qualityKey = key) else state

    /** Back from the quality sheet to the list of videos; the ticks and the chosen quality stay. */
    fun closePlaylistQuality(state: LinkState.Playlist): LinkState.Playlist = state.copy(choosingQuality = false)

    /**
     * Queues the ticked videos, in the order of the playlist. Each one is read by the engine on its own (its address expires and
     * its formats are its own) and saved in the quality the user chose for the playlist ([LinkState.Playlist.qualityKey]; the default
     * quality of the settings when none was chosen), so nothing is asked per video: a video that lacks that quality takes the closest below. A video that cannot
     * be read is counted and skipped: it never stops the others. [onProgress] reports how far it is; cancelling stops reading the
     * rest, and what was already queued stays queued.
     */
    suspend fun confirmPlaylist(state: LinkState.Playlist, onProgress: (LinkState.QueueingPlaylist) -> Unit = {}): LinkState {
        val chosen = state.info.entries.filter { it.url in state.selected }
        if (chosen.isEmpty()) return state
        // One quality picks the format of every video; the "ask before downloading" question is for one video, not for a list.
        val s = settings().copy(askBeforeDownloading = false)
        val key = state.qualityKey ?: PlaylistQuality.initialKey(s)
        var queued = 0
        var failed = 0
        var firstProblem: String? = null
        var done = 0
        onProgress(LinkState.QueueingPlaylist(0, chosen.size, 0, 0))
        for (wave in chosen.chunked(PLAYLIST_PARALLEL_READS)) {
            currentCoroutineContext().ensureActive()
            val problems = coroutineScope { wave.map { entry -> async { queueOne(entry, s, key) } }.awaitAll() }
            for (problem in problems) {
                done++
                if (problem == null) {
                    queued++
                } else {
                    failed++
                    if (firstProblem == null) firstProblem = problem
                }
            }
            onProgress(LinkState.QueueingPlaylist(done, chosen.size, queued, failed))
        }
        return LinkState.PlaylistQueued(queued, failed, firstProblem)
    }

    /** Reads one video of a playlist and queues it in the quality [key]. Returns null when queued, else one sentence saying why not. */
    private suspend fun queueOne(entry: PlaylistEntry, s: AppSettings, key: String): String? = try {
        val engine = when (val e = engines.engineForUrl(entry.url)) {
            is EngineResult.Failure -> return e.error.userMessage
            is EngineResult.Success -> e.value
        }
        val request = MediaRequest(entry.url, headers = requestHeaders(entry.url))
        val info = when (val r = engine.inspect(request)) {
            is EngineResult.Failure -> return r.error.userMessage
            is EngineResult.Success -> r.value
        }
        val ready = QualityOpening.ready(DownloadTarget(entry.url, null, null, null), request, info, engine, s)
        // The row of this video's own panel that stands for the chosen quality (or the closest below it).
        val format = formatToQueue(ready, PlaylistQuality.resolve(ready.panel, key)?.formatId)
        if (format == null && !(ready.rows.isEmpty() && ready.originalFileAllowed)) {
            "No quality of this video fits the chosen quality."
        } else {
            when (val r = manager.enqueue(request, format, info.title ?: entry.title, info.durationSeconds, listedWith = engine.id)) {
                is EnqueueResult.Queued -> null
                is EnqueueResult.Rejected -> r.error.userMessage
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message?.takeIf { it.isNotBlank() } ?: "The video could not be read."
    }

    /** What happens after a successful [inspect]: queue it when the settings say so, otherwise let the user choose. */
    suspend fun proceed(state: LinkState): LinkState =
        if (state is LinkState.Ready && state.autoDownload) confirm(state) else state

    /** Whether [text] is a link that is worth reading in the background. With [requireScheme] only http(s):// text counts. */
    suspend fun isLink(text: String, requireScheme: Boolean = false): Boolean {
        val t = text.trim()
        if (requireScheme && !t.startsWith("http://", ignoreCase = true) && !t.startsWith("https://", ignoreCase = true)) return false
        return UrlInput.parse(t, settings().searchEngine) is UrlInputResult.Url
    }

    suspend fun confirm(ready: LinkState.Ready): LinkState {
        val quality = ready.quality
        // The chosen row, put into the container chosen in the advanced options; the subtitle is saved next to the video.
        val format = formatToQueue(quality, quality.selectedId)
        return when (val r = manager.enqueue(ready.request, format, ready.info.title, ready.info.durationSeconds, listedWith = ready.engineId, subtitleLang = quality.advanced.subtitleLang)) {
            is EnqueueResult.Queued -> LinkState.Queued(r.id)
            is EnqueueResult.Rejected -> failed(r.error)
        }
    }

    /** The user chose another option in the list: the size and type shown follow it. An unknown id changes nothing. */
    fun select(ready: LinkState.Ready, id: String): LinkState.Ready {
        val format = ready.info.formats.firstOrNull { it.id == id } ?: return ready
        return ready.copy(
            format = format,
            sizeText = sizeText(format),
            typeText = format.container?.uppercase(),
            quality = ready.quality.withSelected(id),
        )
    }

    /** The user changed subtitle, codec or container: the rows and their sizes follow, and the same quality stays selected. */
    fun setAdvanced(ready: LinkState.Ready, options: AdvancedOptions): LinkState.Ready {
        val quality = ready.quality.withAdvanced(options)
        val format = ready.info.formats.firstOrNull { it.id == quality.selectedId } ?: ready.format
        return ready.copy(format = format, sizeText = sizeText(format), typeText = format?.container?.uppercase(), quality = quality)
    }

    private fun sizeText(f: FormatOption?): String? {
        val size = Formatters.bytes(f?.sizeBytes) ?: return null
        return if (f?.sizeIsEstimate == true) "about $size" else size
    }

    private fun failed(e: DownloadError) = LinkState.Failed(e.userMessage, e.technicalDetail.takeIf { it.isNotBlank() }, e.suggestedAction)
}

/** How many videos of a playlist are read at the same time while they are queued: faster than one by one, gentle on the site. */
private const val PLAYLIST_PARALLEL_READS = 3

/**
 * The link dialog's background work. While the dialog is open the link on the clipboard is already being read, so "Try download"
 * can show the qualities at once. When the field holds another link, the old background read is dropped and only the new link's
 * result is ever shown. Results are matched to the link they belong to, never to "whatever finished last".
 */
class LinkSession(
    private val downloader: LinkDownloader,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
    private class Background(val key: String, val job: Deferred<LinkState>)

    private var background: Background? = null
    private var pending: Job? = null

    /** The link being read in the background right now (trimmed, as it is compared with the field), if any. */
    val backgroundLink: String? get() = background?.key

    /** The dialog opened with an empty field: start reading the clipboard link, if there is a real http(s) link on it. */
    fun prefetchClipboard(text: String) {
        val key = text.trim()
        if (key.isEmpty() || background?.key == key) return
        pending?.cancel()
        pending = scope.launch { if (downloader.isLink(key, requireScheme = true)) start(key) }
    }

    /**
     * The field changed (typed or pasted). The same link as the background one keeps it running; any other link drops it at once
     * and, once it looks like a link and stops changing, is read in the background instead.
     */
    fun fieldChanged(text: String) {
        val key = text.trim()
        pending?.cancel()
        if (key.isEmpty() || background?.key == key) return
        cancelBackground()
        pending = scope.launch {
            delay(debounceMillis)
            if (downloader.isLink(key)) start(key)
        }
    }

    /**
     * "Try download". An empty field or the background link uses the background result (waiting for it if it is not finished);
     * any other text is read fresh and the background read is dropped.
     */
    suspend fun tryDownload(text: String): LinkState {
        val typed = text.trim()
        pending?.cancel()
        val bg = background
        if (bg != null && (typed.isEmpty() || typed == bg.key)) return fromBackground(bg)
        cancelBackground()
        return downloader.check(typed)
    }

    /** The dialog was closed. */
    fun close() {
        pending?.cancel()
        pending = null
        cancelBackground()
    }

    /** Waits until a pending clipboard check or field debounce has finished (used by tests). */
    internal suspend fun settle() {
        pending?.join()
    }

    private suspend fun fromBackground(bg: Background): LinkState {
        val result = try {
            bg.job.await()
        } catch (e: CancellationException) {
            // The background read was dropped while we waited: read the link again. If we ourselves were cancelled, stop.
            currentCoroutineContext().ensureActive()
            if (background === bg) background = null
            return downloader.check(bg.key)
        }
        // A failed background read is shown once; the next tap reads the link again instead of repeating the failure.
        if (result is LinkState.Failed && background === bg) background = null
        return downloader.proceed(result)
    }

    private fun start(key: String) {
        background?.job?.cancel()
        background = Background(key, scope.async { safeInspect(key) })
    }

    private fun cancelBackground() {
        background?.job?.cancel()
        background = null
    }

    private suspend fun safeInspect(key: String): LinkState = try {
        downloader.inspect(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LinkState.Failed("Could not read the link.", e.message, null)
    }

    companion object {
        /** A pasted link starts reading almost at once; this only stops every typed letter from starting a read. */
        const val DEFAULT_DEBOUNCE_MILLIS = 700L
    }
}
