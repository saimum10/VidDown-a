package com.viddown.engine.newpipe

import com.viddown.engine.api.DownloadEngine
import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.DownloadEvent
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineCapabilities
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineResult
import com.viddown.engine.api.FileNames
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaInfo
import com.viddown.engine.api.MediaMimeTypes
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.MediaType
import com.viddown.engine.api.MergeResult
import com.viddown.engine.api.PlaylistEntry
import com.viddown.engine.api.PlaylistInfo
import com.viddown.engine.api.StreamKind
import com.viddown.engine.api.StreamMerger
import com.viddown.engine.direct.HttpTransport
import com.viddown.engine.direct.RetryPolicy
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The NewPipe engine: reads a page of a supported video site with the NewPipe extractor (through [StreamGateway]) and downloads
 * the stream the user chose. It also saves a plain video or audio file that a page serves directly (a file the browser detected, or a
 * link to one), because it is the engine the user selected and nothing else may take the job.
 *
 * What it does and does not do:
 *  * Single files: video+audio files and audio-only files, as one transfer each.
 *  * Higher qualities: many sites (YouTube above 360p) only offer video and audio as separate streams. When a [StreamMerger] is
 *    given, each such video is offered together with its best-fitting audio as ONE choice. Both streams are downloaded by this
 *    engine, then the merger (FFmpeg, stream copy, no re-encoding) puts them into one file. Without a merger these qualities are
 *    not listed, so nothing offered is a promise the build cannot keep.
 *  * Plain files: an address no extractor service recognises is probed ([DirectFileProbe]); a video or audio file is offered as
 *    "the file as served" and saved with the browser's own headers. Web pages and streaming playlists (HLS/DASH) are refused.
 *  * Playlists of videos: the list is read ([inspectPlaylist], at most 100 videos) and every chosen video is then read and downloaded as
 *    a single video of its own. Nothing of a playlist is downloaded as a whole.
 *  * Streaming playlists (HLS/DASH), live streams, DRM and anything behind a login, payment, age check or region lock are reported
 *    as such. Nothing is bypassed, nobody is signed in, and no browser cookies are sent to the extractor or to a site's stream host.
 *  * It never starts Python, never touches yt-dlp and never retries with a different engine. FFmpeg is only a helper that runs
 *    after both streams have arrived.
 *  * The chosen format is re-extracted when the download starts (stream addresses expire), and found again by its id. If the site
 *    no longer offers it, the download fails and says so: a different quality is never substituted.
 */
class NewPipeEngine(
    private val gateway: StreamGateway,
    private val transport: HttpTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    private val chunkBytes: Long = StreamTransfer.DEFAULT_CHUNK_BYTES,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Puts a video-only and an audio-only file together. Null: separate streams are not offered. */
    private val merger: StreamMerger? = null,
) : DownloadEngine {

    override val id: EngineId = EngineId.NEWPIPE
    override val displayName: String = "NewPipe"
    override val description: String = describe(canMerge = merger != null)
    override val capabilities: EngineCapabilities = EngineCapabilities(
        mediaTypes = setOf(MediaType.PAGE, MediaType.DIRECT_MP4, MediaType.DIRECT_WEBM, MediaType.DIRECT_OTHER),
        supportsResume = true,
        listsFormats = true,
        mayNeedFfmpeg = merger != null,
        notes = "Pages of sites the extractor recognises, and plain video or audio files. " +
            (if (merger != null) {
                "Qualities that come as separate video and audio streams are downloaded as two files and merged on this device (no re-encoding). "
            } else {
                "Complete video+audio files and audio-only files only: separate video and audio streams need FFmpeg, which is not available here. "
            }) +
            "Playlists of videos are listed (up to 100) and each chosen video is downloaded on its own. " +
            "No streaming playlists (HLS/DASH), live streams, DRM or login-only media.",
    )

    private val directProbe = DirectFileProbe(transport)

    override fun availability(): EngineAvailability = EngineAvailability.Available

    /** Cheap and offline: does one of the extractor's services recognise this page address? */
    fun supportsPage(url: String): Boolean = FormatMapper.isHttpAddress(url) && gateway.supportsPage(url)

    // ------------------------------------------------------------------------------------------------ inspection

    override suspend fun inspect(request: MediaRequest): EngineResult<MediaInfo> {
        if (!FormatMapper.isHttpAddress(request.url)) {
            return EngineResult.Failure(DownloadError.unsupportedResource(id, "Only http and https links can be read"))
        }
        if (!gateway.supportsPage(request.url)) {
            // A playlist is not a file: say so, so the caller can list it with inspectPlaylist instead of probing it as a file.
            if (gateway.supportsPlaylist(request.url)) return EngineResult.Failure(DownloadError.playlistLink(id))
            return inspectFile(request)
        }
        val media = when (val r = extract(request.url)) {
            is Extraction.Failed -> return EngineResult.Failure(r.error)
            is Extraction.Found -> r.media
        }
        if (media.isLive) {
            return EngineResult.Failure(
                DownloadError.noUsableFormat(id, "It is a live stream, which has no fixed end to save as a file.", detail(media)),
            )
        }
        val mapping = FormatMapper.map(media, canMerge = merger != null)
        if (mapping.formats.isEmpty()) {
            return EngineResult.Failure(DownloadError.noUsableFormat(id, whyNothing(mapping), detail(media, mapping)))
        }
        return EngineResult.Success(
            MediaInfo(
                engineId = id,
                sourceUrl = request.url,
                title = media.title?.takeIf { it.isNotBlank() },
                durationSeconds = media.durationSeconds?.takeIf { it > 0 },
                thumbnailUrl = media.thumbnailUrl,
                uploader = media.uploader?.takeIf { it.isNotBlank() },
                formats = mapping.formats,
            ),
        )
    }

    override suspend fun listFormats(request: MediaRequest): EngineResult<List<FormatOption>> =
        when (val r = inspect(request)) {
            is EngineResult.Success -> EngineResult.Success(r.value.formats)
            is EngineResult.Failure -> r
        }

    /** Lists the videos of a playlist (at most [maxEntries]). The videos themselves are not read here. */
    override suspend fun inspectPlaylist(request: MediaRequest, maxEntries: Int): EngineResult<PlaylistInfo> {
        if (!FormatMapper.isHttpAddress(request.url)) {
            return EngineResult.Failure(DownloadError.unsupportedResource(id, "Only http and https links can be read"))
        }
        if (!gateway.supportsPlaylist(request.url)) {
            return EngineResult.Failure(DownloadError.unsupportedResource(id, "No NewPipe service recognises this address as a playlist"))
        }
        val limit = maxEntries.coerceIn(1, PlaylistInfo.MAX_ENTRIES)
        return when (val r = guarded { token -> gateway.extractPlaylist(request.url, limit, token) }) {
            is Guarded.Failed -> EngineResult.Failure(r.error)
            is Guarded.Done -> EngineResult.Success(
                PlaylistInfo(
                    engineId = id,
                    sourceUrl = request.url,
                    title = r.value.title,
                    uploader = r.value.uploader,
                    entries = r.value.entries.map { PlaylistEntry(it.url, it.title, it.durationSeconds, it.thumbnailUrl, it.uploader) },
                    hasMore = r.value.hasMore,
                ),
            )
        }
    }

    /** An address no extractor service recognises: it can still be one plain video or audio file. */
    private suspend fun inspectFile(request: MediaRequest): EngineResult<MediaInfo> =
        when (val r = withContext(ioDispatcher) { directProbe.probe(request) }) {
            is DirectFileProbe.Result.Refused -> EngineResult.Failure(r.error)
            is DirectFileProbe.Result.Media -> EngineResult.Success(
                MediaInfo(
                    engineId = id,
                    sourceUrl = request.url,
                    title = r.name,
                    formats = listOf(
                        FormatOption(
                            id = ORIGINAL_FORMAT_ID,
                            kind = StreamKind.ORIGINAL_FILE,
                            container = FileNames.extensionOf(r.name),
                            sizeBytes = r.sizeBytes,
                            sizeIsEstimate = false,
                        ),
                    ),
                ),
            )
        }

    // ------------------------------------------------------------------------------------------------ download

    override fun download(task: DownloadTask, destinationDir: String): Flow<DownloadEvent> = flow {
        emit(DownloadEvent.Started)
        emit(run(task, File(destinationDir)) { emit(it) })
    }.flowOn(ioDispatcher)

    private suspend fun run(task: DownloadTask, dir: File, emit: suspend (DownloadEvent) -> Unit): DownloadEvent {
        val format = task.format
            ?: return DownloadEvent.Failed(DownloadError.unsupportedFormat(id, "No format was chosen for this download"))
        if (!FormatMapper.isHttpAddress(task.request.url)) {
            return DownloadEvent.Failed(DownloadError.unsupportedResource(id, "Only http and https links can be downloaded"))
        }
        if (!gateway.supportsPage(task.request.url)) {
            // Not a page of a site: only "the file as served" can be saved.
            if (format.kind == StreamKind.ORIGINAL_FILE) return runFile(task, format, dir, emit)
            return DownloadEvent.Failed(DownloadError.unsupportedResource(id, "No NewPipe service recognises this address"))
        }
        // Stream addresses expire, so the page is read again now. The same extraction order gives the same format ids.
        val media = when (val r = extract(task.request.url)) {
            is Extraction.Failed -> return DownloadEvent.Failed(r.error)
            is Extraction.Found -> r.media
        }
        if (media.isLive) {
            return DownloadEvent.Failed(DownloadError.noUsableFormat(id, "It is a live stream, which has no fixed end to save as a file."))
        }
        val selection = FormatMapper.select(media, format.id)
            ?: return DownloadEvent.Failed(
                DownloadError.unsupportedFormat(id, "The site no longer offers the format that was chosen (${format.id}). Choose the quality again."),
            )
        val stream = when (selection) {
            is FormatMapper.Selection.Merged -> return runMerged(task, format, media, selection, dir, emit)
            is FormatMapper.Selection.Single -> selection.stream
        }
        val url = stream.url ?: return DownloadEvent.Failed(DownloadError.unsupportedFormat(id, "The chosen stream has no address"))

        val fileName = outputName(task.title ?: media.title, stream)
        val source = StreamTransfer.Source(url = url, fileName = fileName, sizeHint = stream.sizeBytes)
        // Cookies and other browser headers of the task are NOT forwarded: the stream host is a third party and the extractor
        // works anonymously, so none of them are needed or wanted there.
        return StreamTransfer(transport, retryPolicy, chunkBytes, nowMs = nowMs).run(source, dir, emit)
    }

    override suspend fun close() {
        merger?.close()
        gateway.close()
        transport.close()
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private sealed interface Extraction {
        data class Found(val media: ExtractedMedia) : Extraction
        data class Failed(val error: DownloadError) : Extraction
    }

    /** Runs the blocking extractor off the caller's thread and aborts its network calls when the caller is cancelled. */
    private suspend fun extract(url: String): Extraction = coroutineScope {
        val token = CancelToken()
        val finished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) token.cancel()
            }
        }
        try {
            withContext(ioDispatcher) {
                try {
                    Extraction.Found(gateway.extract(url, token))
                } catch (e: GatewayException) {
                    Extraction.Failed(e.error)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The gateway translates everything it knows; this is the net for what it does not.
                    Extraction.Failed(DownloadError.extractionFailed(id, "${e.javaClass.simpleName}: ${e.message ?: "no message"}"))
                }
            }
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    private sealed interface Guarded<out T> {
        data class Done<T>(val value: T) : Guarded<T>
        data class Failed(val error: DownloadError) : Guarded<Nothing>
    }

    /** Like [extract] for any blocking extractor call: runs it off the caller's thread and aborts its network calls on cancellation. */
    private suspend fun <T> guarded(work: (CancelToken) -> T): Guarded<T> = coroutineScope {
        val token = CancelToken()
        val finished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) token.cancel()
            }
        }
        try {
            withContext(ioDispatcher) {
                try {
                    Guarded.Done(work(token))
                } catch (e: GatewayException) {
                    Guarded.Failed(e.error)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Guarded.Failed(DownloadError.extractionFailed(id, "${e.javaClass.simpleName}: ${e.message ?: "no message"}"))
                }
            }
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    private fun whyNothing(m: FormatMapper.Mapping): String = when {
        m.skippedVideoOnly > 0 && merger != null ->
            "Its video and audio come as separate streams, but none of them could be paired with an audio stream that can be merged."
        m.skippedVideoOnly > 0 ->
            "Its video and audio come as separate streams, which need FFmpeg to be merged. FFmpeg is not available to this engine here."
        m.skippedManifest > 0 ->
            "They are served as streaming playlists (HLS or DASH), which this engine does not download."
        else -> "The site offered no downloadable stream for this page."
    }

    private fun detail(media: ExtractedMedia, m: FormatMapper.Mapping? = null): String = buildString {
        append("service=").append(media.serviceName)
        append(", streams=").append(media.streams.size)
        if (m != null) {
            append(", videoOnly=").append(m.skippedVideoOnly)
            append(", manifest=").append(m.skippedManifest)
            append(", noAddress=").append(m.skippedNoAddress)
        }
        if (media.isLive) append(", live")
        if (media.problems.isNotEmpty()) append(", problems=").append(media.problems.joinToString("; "))
    }

    // ------------------------------------------------------------------------------------------------ plain files

    /** Saves "the file as served" (a media file the browser found, or a link to one) with the headers the browser used for it. */
    private suspend fun runFile(task: DownloadTask, format: FormatOption, dir: File, emit: suspend (DownloadEvent) -> Unit): DownloadEvent {
        val url = task.request.url
        val named = FileNames.sanitize(task.title ?: FileNames.fromUrl(url), "download")
        val fileName = if (MediaMimeTypes.isKnownMediaExtension(FileNames.extensionOf(named))) {
            named
        } else {
            FileNames.withExtension(named, format.container ?: FileNames.fromUrl(url)?.let { FileNames.extensionOf(it) })
        }
        val source = StreamTransfer.Source(url = url, fileName = fileName, headers = task.request.headers, sizeHint = format.sizeBytes)
        return StreamTransfer(transport, retryPolicy, chunkBytes, nowMs = nowMs).run(source, dir, emit)
    }

    // ------------------------------------------------------------------------------------------------ merged qualities

    /**
     * Downloads the video stream, then the audio stream (each into its own folder, each resumable and retried like any transfer),
     * then merges them without re-encoding. The result is only reported after the merge produced a file. A stream that already
     * arrived in an earlier attempt of this download is not fetched again.
     */
    private suspend fun runMerged(
        task: DownloadTask,
        format: FormatOption,
        media: ExtractedMedia,
        selection: FormatMapper.Selection.Merged,
        dir: File,
        emit: suspend (DownloadEvent) -> Unit,
    ): DownloadEvent {
        val merge = merger ?: return DownloadEvent.Failed(
            DownloadError.unsupportedFormat(id, "Merging video and audio is not available on this device, so this quality cannot be saved."),
        )
        val videoUrl = selection.video.url ?: return DownloadEvent.Failed(DownloadError.unsupportedFormat(id, "The chosen video stream has no address"))
        val audioUrl = selection.audio.url ?: return DownloadEvent.Failed(DownloadError.unsupportedFormat(id, "The chosen audio stream has no address"))
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            return DownloadEvent.Failed(DownloadError.invalidDestination("The temporary folder could not be created"))
        }

        val videoHint = selection.video.sizeBytes?.takeIf { it > 0 }
        val audioHint = selection.audio.sizeBytes?.takeIf { it > 0 }

        // 1. video
        val videoFile = when (val r = stage(dir, "video", videoUrl, selection.video, "video", videoHint) { e ->
            emit(shifted(e, offset = 0, total = { own -> if (own != null && audioHint != null) own + audioHint else null }, keepEta = false))
        }) {
            is Stage.Failed -> return r.event
            is Stage.Done -> r.file
        }
        // 2. audio: the bytes of the video are already behind us
        val videoBytes = videoFile.length()
        val audioFile = when (val r = stage(dir, "audio", audioUrl, selection.audio, "audio", audioHint) { e ->
            emit(shifted(e, offset = videoBytes, total = { own -> own?.let { videoBytes + it } }, keepEta = true))
        }) {
            is Stage.Failed -> return r.event
            is Stage.Done -> r.file
        }

        // 3. merge: the planned container first, Matroska (takes every codec) when that fails
        emit(DownloadEvent.Processing("Merging video and audio"))
        val wanted = format.container?.lowercase()?.takeIf { it in MERGE_CONTAINERS } ?: selection.container
        val stem = FileNames.sanitize(task.title ?: media.title, "video")
        var lastProblem = "no output"
        for (container in listOf(wanted, "mkv").distinct()) {
            val out = File(dir, FileNames.withExtension(stem, container))
            if (out.exists() && !out.delete()) {
                return DownloadEvent.Failed(DownloadError.invalidDestination("An old file with the same name could not be replaced"))
            }
            when (val m = merge.merge(videoFile, audioFile, out)) {
                MergeResult.Done -> {
                    if (out.isFile && out.length() > 0) {
                        File(dir, "video").deleteRecursively()
                        File(dir, "audio").deleteRecursively()
                        File(dir, "video.done").delete()
                        File(dir, "audio.done").delete()
                        return DownloadEvent.Completed(out.absolutePath, out.length())
                    }
                    lastProblem = "The merge reported success but wrote no file"
                    out.delete()
                }
                is MergeResult.Failed -> {
                    lastProblem = m.detail
                    out.delete()
                }
            }
        }
        return DownloadEvent.Failed(DownloadError.ffmpegFailure(lastProblem))
    }

    private sealed interface Stage {
        data class Done(val file: File) : Stage
        data class Failed(val event: DownloadEvent) : Stage
    }

    /** One stream into `<dir>/<folder>`. A `<folder>.done` note next to it says the stream finished, so an interrupted merge does not download it again. */
    private suspend fun stage(
        dir: File,
        folder: String,
        url: String,
        stream: ExtractedStream,
        stem: String,
        sizeHint: Long?,
        emit: suspend (DownloadEvent) -> Unit,
    ): Stage {
        val subDir = File(dir, folder)
        val note = File(dir, "$folder.done")
        val noted = try {
            if (note.isFile) File(subDir, note.readText().trim()).takeIf { it.isFile && it.length() > 0 } else null
        } catch (_: IOException) {
            null
        }
        if (noted != null) return Stage.Done(noted)

        val extension = stream.container?.takeIf { it.isNotBlank() }
            ?: MediaMimeTypes.extensionFor(stream.mimeType)
            ?: if (stream.role == StreamRole.AUDIO_ONLY) "m4a" else "mp4"
        // Cookies and other browser headers are NOT forwarded: the stream host is a third party and the extractor works anonymously.
        val source = StreamTransfer.Source(url = url, fileName = FileNames.withExtension(stem, extension), sizeHint = sizeHint)
        return when (val r = StreamTransfer(transport, retryPolicy, chunkBytes, nowMs = nowMs).run(source, subDir, emit)) {
            is DownloadEvent.Completed -> {
                val file = File(r.outputPath)
                try {
                    note.writeText(file.name)
                } catch (_: IOException) {
                    // only a shortcut for a later attempt
                }
                Stage.Done(file)
            }
            else -> Stage.Failed(r)
        }
    }

    /** One stream's progress as part of the whole download. The speed stays; the remaining time only when it covers all that is left. */
    private fun shifted(e: DownloadEvent, offset: Long, total: (Long?) -> Long?, keepEta: Boolean): DownloadEvent =
        if (e is DownloadEvent.Progress) {
            e.copy(
                bytesDownloaded = offset + e.bytesDownloaded,
                totalBytes = total(e.totalBytes),
                etaSeconds = if (keepEta) e.etaSeconds else null,
            )
        } else {
            e
        }

    private fun outputName(title: String?, stream: ExtractedStream): String {
        val stem = FileNames.sanitize(title, "video")
        val extension = stream.container?.takeIf { it.isNotBlank() } ?: MediaMimeTypes.extensionFor(stream.mimeType)
        return FileNames.withExtension(stem, extension)
    }

    companion object {
        const val ORIGINAL_FORMAT_ID = "original"
        private val MERGE_CONTAINERS = setOf("mp4", "webm", "mkv")

        /** The text under the engine's name in Settings. */
        fun describe(canMerge: Boolean): String =
            if (canMerge) {
                "Reads supported video sites with the NewPipe extractor, also saves plain video files. Higher qualities are saved by merging video and audio on this device."
            } else {
                "Reads supported video sites with the NewPipe extractor, also saves plain video files. Higher qualities that need merging are not offered."
            }
    }
}
