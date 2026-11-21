package com.viddown.engine.ytdlp

import com.viddown.engine.api.BrowserCookies
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
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.MediaType
import com.viddown.engine.api.PlaylistInfo
import com.viddown.engine.api.StreamKind
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The yt-dlp engine: yt-dlp (CPython, bundled) runs as a separate child process, FFmpeg only as a helper for local files.
 *
 *  * Nothing starts until the engine is used: creating it loads no Python, and [availability] never starts a process.
 *  * [inspect] runs `yt-dlp --dump-single-json`; only formats yt-dlp reported are offered (see [InfoMapper]).
 *  * [download] downloads each chosen stream with yt-dlp into the download folder. Two streams (video + audio) are merged with
 *    FFmpeg by stream copy; MPEG-TS from HLS is remuxed; the result is checked with ffprobe. [DownloadEvent.Completed] comes only
 *    after all of that succeeded.
 *  * Cancelling the collector kills the running process. There is no fallback to another engine and no re-encoding.
 *  * Cookies: when a request carries the Cookie header of the in-app browser, it is written to a private temporary Netscape cookie file
 *    for the duration of ONE yt-dlp run (--cookies), for the site's own domain only, and the file is deleted afterwards. Nothing else of
 *    that kind is passed on (no Authorization), nothing is logged, nothing signs in and no access restriction is bypassed.
 */
class YtDlpEngine(
    private val tools: NativeTools,
    private val availabilityProvider: () -> EngineAvailability = { EngineAvailability.Available },
    /** Debug builds only: lets tests use http://127.0.0.1. Release builds refuse every plain http:// link. */
    private val allowLoopbackHttp: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val inspectTimeoutMs: Long = 120_000,
    private val idleTimeoutMs: Long = 10 * 60_000,
    /** Where the temporary cookie file is written; null = the app's temporary directory (private to the app on Android). */
    private val cookieDir: File? = null,
) : DownloadEngine {

    override val id: EngineId = EngineId.YTDLP
    override val displayName: String = "yt-dlp"
    override val description: String =
        "Reads a wide range of sites with yt-dlp, using its bundled Python runtime. Separate video and audio streams are merged on this device with FFmpeg."
    override val capabilities: EngineCapabilities = EngineCapabilities(
        mediaTypes = setOf(MediaType.PAGE, MediaType.DIRECT_MP4, MediaType.DIRECT_WEBM, MediaType.DIRECT_OTHER, MediaType.HLS, MediaType.DASH),
        supportsResume = true,
        listsFormats = true,
        mayNeedFfmpeg = true,
        notes = "Sites yt-dlp has extractors for, direct files and HLS/DASH streams that yt-dlp can download itself. No JavaScript runtime: " +
            "sites that need one fail with a message saying so. FFmpeg here only merges and remuxes local files (no network, no MP3/Opus " +
            "encoding). Playlists are listed (up to 100 videos) and each chosen video is downloaded on its own. No live streams or DRM. " +
            "Media behind a login works only when the in-app browser is signed in to that site (its cookies are used); VidDown never " +
            "signs in itself and bypasses no restriction.",
    )

    private val runner = ToolRunner(tools, ioDispatcher, clock)
    private val ffmpeg = FfmpegProcessor(runner)
    private val prepareLock = Mutex()

    @Volatile
    private var prepared = false

    private class Cached(val url: String, val atMs: Long, val mapping: InfoMapper.Mapping)

    @Volatile
    private var cached: Cached? = null

    override fun availability(): EngineAvailability = availabilityProvider()

    // ------------------------------------------------------------------------------------------------ inspection

    override suspend fun inspect(request: MediaRequest): EngineResult<MediaInfo> {
        urlProblem(request.url)?.let { return EngineResult.Failure(it) }
        val mapping = when (val r = extract(request)) {
            is Extraction.Failed -> return EngineResult.Failure(r.error)
            is Extraction.Found -> r.mapping
        }
        if (mapping.isUpcoming) {
            return EngineResult.Failure(DownloadError.noUsableFormat(id, "It is a live event that has not started yet.", extractorDetail(mapping)))
        }
        if (mapping.isLive) {
            return EngineResult.Failure(DownloadError.noUsableFormat(id, "It is a live stream, which has no fixed end to save as a file.", extractorDetail(mapping)))
        }
        if (mapping.formats.isEmpty()) {
            return EngineResult.Failure(DownloadError.noUsableFormat(id, InfoMapper.whyNothing(mapping), extractorDetail(mapping)))
        }
        return EngineResult.Success(
            MediaInfo(
                engineId = id,
                sourceUrl = request.url,
                title = mapping.title,
                durationSeconds = mapping.durationSeconds,
                thumbnailUrl = mapping.thumbnailUrl,
                uploader = mapping.uploader,
                formats = mapping.formats,
                subtitles = mapping.subtitles,
            ),
        )
    }

    override suspend fun listFormats(request: MediaRequest): EngineResult<List<FormatOption>> =
        when (val r = inspect(request)) {
            is EngineResult.Success -> EngineResult.Success(r.value.formats)
            is EngineResult.Failure -> r
        }

    /** Lists the videos of a playlist link (flat, so it is fast) and returns at most [maxEntries] of them. Nothing is downloaded. */
    override suspend fun inspectPlaylist(request: MediaRequest, maxEntries: Int): EngineResult<PlaylistInfo> {
        urlProblem(request.url)?.let { return EngineResult.Failure(it) }
        prepareProblem()?.let { return EngineResult.Failure(it) }
        val limit = maxEntries.coerceIn(1, PlaylistInfo.MAX_ENTRIES)
        val result = try {
            withCookieFile(request) { cookies -> runner.run(Tool.YT_DLP, YtDlpArgs.playlist(request, limit, cookies), timeoutMs = inspectTimeoutMs) }
        } catch (e: NativeToolsException) {
            return EngineResult.Failure(DownloadError.nativeDependencyFailed(id, e.message))
        }
        if (!result.ok) return EngineResult.Failure(YtDlpErrors.fromRun(result))
        if (result.truncated) {
            return EngineResult.Failure(DownloadError.extractionFailed(id, "The playlist answer of yt-dlp was larger than the size limit and was not read"))
        }
        val root = try {
            Json.parse(result.stdout).asMap()
        } catch (e: Json.ParseException) {
            null
        } ?: return EngineResult.Failure(DownloadError.extractionFailed(id, "yt-dlp returned a playlist answer that could not be read"))
        val playlist = PlaylistMapper.map(root, request.url, limit)
            ?: return EngineResult.Failure(DownloadError.unsupportedResource(id, "The link is not a playlist"))
        return EngineResult.Success(playlist)
    }

    @Volatile
    private var sweptStaleCookieFiles = false

    /**
     * Runs [block] with the path of a private temporary cookie file holding the cookies of [request] (see [BrowserCookies]), or with
     * null when the request has none. The file is owner-only and is deleted when [block] ends, however it ends. If it cannot be written
     * the run goes on without cookies: nothing is ever put on a command line instead.
     */
    private suspend fun <T> withCookieFile(request: MediaRequest, block: suspend (cookieFile: String?) -> T): T {
        val header = BrowserCookies.headerOf(request.headers) ?: return block(null)
        val content = BrowserCookies.toNetscapeFile(header, request.url, System.currentTimeMillis() / 1000 + COOKIE_LIFETIME_SECONDS)
            ?: return block(null)
        sweepStaleCookieFiles()
        val file = try {
            File.createTempFile(COOKIE_FILE_PREFIX, ".txt", cookieDir)
        } catch (e: IOException) {
            return block(null)
        }
        val written = try {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.writeText(content)
            true
        } catch (e: IOException) {
            false
        }
        return try {
            block(if (written) file.absolutePath else null)
        } finally {
            file.delete()
        }
    }

    /** A killed app can leave a cookie file behind: the first time cookies are used, files of an earlier run (over an hour old) are removed. */
    private fun sweepStaleCookieFiles() {
        if (sweptStaleCookieFiles) return
        sweptStaleCookieFiles = true
        val folder = cookieDir ?: File(System.getProperty("java.io.tmpdir") ?: return)
        val limit = System.currentTimeMillis() - STALE_COOKIE_FILE_MS
        folder.listFiles { f -> f.isFile && f.name.startsWith(COOKIE_FILE_PREFIX) && f.lastModified() < limit }?.forEach { it.delete() }
    }

    private sealed interface Extraction {
        data class Found(val mapping: InfoMapper.Mapping) : Extraction
        data class Failed(val error: DownloadError) : Extraction
    }

    private suspend fun extract(request: MediaRequest): Extraction {
        cached?.let { if (it.url == request.url && clock() - it.atMs < CACHE_MS) return Extraction.Found(it.mapping) }
        prepareProblem()?.let { return Extraction.Failed(it) }
        val result = try {
            withCookieFile(request) { cookies -> runner.run(Tool.YT_DLP, YtDlpArgs.inspect(request, cookies), timeoutMs = inspectTimeoutMs) }
        } catch (e: NativeToolsException) {
            return Extraction.Failed(DownloadError.nativeDependencyFailed(id, e.message))
        }
        if (!result.ok) return Extraction.Failed(YtDlpErrors.fromRun(result))
        if (result.truncated) {
            return Extraction.Failed(DownloadError.extractionFailed(id, "The answer of yt-dlp was larger than the size limit and was not read"))
        }
        val root = try {
            Json.parse(result.stdout).asMap()
        } catch (e: Json.ParseException) {
            null
        } ?: return Extraction.Failed(DownloadError.extractionFailed(id, "yt-dlp returned an answer that could not be read"))
        val type = root["_type"].asString()
        if (type == "playlist" || type == "multi_video") {
            // Not a failure of the engine: the caller can list the playlist with inspectPlaylist and let the user pick videos.
            return Extraction.Failed(DownloadError.playlistLink(id, "yt-dlp reported _type=$type"))
        }
        val mapping = InfoMapper.map(root)
        cached = Cached(request.url, clock(), mapping)
        return Extraction.Found(mapping)
    }

    private fun extractorDetail(m: InfoMapper.Mapping): String =
        "extractor=${m.extractor}, formats=${m.totalFormats}, drm=${m.drmFormats}, needs FFmpeg network=${m.needsFfmpegNetwork}"

    // ------------------------------------------------------------------------------------------------ download

    override fun download(task: DownloadTask, destinationDir: String): Flow<DownloadEvent> = channelFlow<DownloadEvent> {
        send(DownloadEvent.Started)
        val last: DownloadEvent = try {
            perform(task, File(destinationDir)) { event -> trySend(event) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DownloadEvent.Failed(DownloadError.extractionFailed(id, "Unexpected error: ${e.javaClass.simpleName}: ${e.message}"))
        }
        send(last)
    }.buffer(Channel.UNLIMITED).flowOn(ioDispatcher)

    private class DownloadPlan(val streamIds: List<String>, val merge: Boolean, val container: String?) {
        companion object {
            fun from(format: FormatOption): DownloadPlan? {
                if (!format.requiresMerge) {
                    return if (YtDlpArgs.isSafeFormatId(format.id)) DownloadPlan(listOf(format.id), false, null) else null
                }
                val ids = format.id.split('+')
                if (ids.size != 2 || ids.any { !YtDlpArgs.isSafeFormatId(it) }) return null
                val container = format.container?.lowercase()?.takeIf { it == "mp4" || it == "webm" || it == "mkv" } ?: return null
                return DownloadPlan(ids, true, container)
            }
        }
    }

    private sealed interface StageResult {
        data class Done(val file: File) : StageResult
        data class Failed(val error: DownloadError) : StageResult
    }

    /** Converts yt-dlp's byte counts into events: stage offsets for merged downloads, and no more than four events a second. */
    private class ProgressReporter(
        private val emit: (DownloadEvent) -> Unit,
        private val knownTotal: Long?,
        private val merge: Boolean,
        private val clock: () -> Long,
    ) {
        private var offset = 0L
        private var lastMs: Long? = null

        @Synchronized
        fun onProgress(raw: RawProgress) {
            val got = raw.downloadedBytes ?: return
            val now = clock()
            val previous = lastMs
            if (previous != null && now - previous < PROGRESS_INTERVAL_MS) return
            lastMs = now
            // The total is only ever a real number: the size yt-dlp listed, or (one stream) the exact size the server announced.
            val total = if (merge) knownTotal else (knownTotal ?: raw.totalBytes)
            emit(DownloadEvent.Progress(offset + got, total, raw.speedBytesPerSecond, if (merge) null else raw.etaSeconds))
        }

        @Synchronized
        fun stageFinished(sizeBytes: Long) {
            offset += sizeBytes
            lastMs = null
            emit(DownloadEvent.Progress(offset, if (merge) knownTotal else (knownTotal ?: offset)))
        }

        @Synchronized
        fun start() {
            emit(DownloadEvent.Progress(0, knownTotal))
        }
    }

    private suspend fun perform(task: DownloadTask, dir: File, emit: (DownloadEvent) -> Unit): DownloadEvent {
        val format = task.format ?: return failed(DownloadError.unsupportedFormat(id, "No format was chosen for this download"))
        urlProblem(task.request.url)?.let { return failed(it) }
        val plan = DownloadPlan.from(format)
            ?: return failed(DownloadError.unsupportedFormat(id, "The chosen format (${format.id}) cannot be used with yt-dlp"))
        prepareProblem()?.let { return failed(it) }
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            return failed(DownloadError.invalidDestination("The temporary folder could not be created"))
        }

        val knownTotal = format.sizeBytes?.takeIf { !format.sizeIsEstimate && it > 0 }
        val progress = ProgressReporter(emit, knownTotal, plan.merge, clock)
        progress.start()

        val files = ArrayList<File>(2)
        for ((index, streamId) in plan.streamIds.withIndex()) {
            when (val stage = runStage(task, dir, index, streamId, progress)) {
                is StageResult.Failed -> return failed(stage.error)
                is StageResult.Done -> {
                    files += stage.file
                    progress.stageFinished(stage.file.length())
                }
            }
        }
        val result = if (plan.merge) {
            finishMerge(task, dir, files[0], files[1], format, plan.container ?: "mkv", emit)
        } else {
            finishSingle(task, dir, files[0], format, emit)
        }
        return withSubtitle(task, dir, format, result, emit)
    }

    /**
     * Saves the subtitle the user asked for next to the finished video, named like it ("Title.en.srt"). It is an extra: when the
     * site does not deliver it in a format VidDown can keep, the video is still delivered as it is.
     */
    private suspend fun withSubtitle(task: DownloadTask, dir: File, format: FormatOption, result: DownloadEvent, emit: (DownloadEvent) -> Unit): DownloadEvent {
        val lang = task.subtitleLang ?: return result
        if (result !is DownloadEvent.Completed || format.kind == StreamKind.AUDIO_ONLY || !YtDlpArgs.isSafeLanguage(lang)) return result
        emit(DownloadEvent.Processing("Saving the subtitle"))
        val video = File(result.outputPath)
        val run = try {
            withCookieFile(task.request) { cookies ->
                runner.run(Tool.YT_DLP, YtDlpArgs.subtitle(task.request, lang, dir.absolutePath, SUBTITLE_PREFIX.removeSuffix("."), cookies), timeoutMs = SUBTITLE_TIMEOUT_MS)
            }
        } catch (e: NativeToolsException) {
            return result
        }
        if (!run.ok) return result
        val found = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(SUBTITLE_PREFIX) && it.length() > 0 && FileNames.extensionOf(it.name) in SUBTITLE_EXTENSIONS }
            ?.maxByOrNull { it.lastModified() }
            ?: return result
        val stem = video.name.substringBeforeLast('.', video.name)
        val wanted = FileNames.withExtension("$stem.$lang", FileNames.extensionOf(found.name))
        val name = FileNames.uniqueName(wanted) { candidate -> File(dir, candidate).let { it.exists() && it != found } }
        val target = File(dir, name)
        if (target != found && !found.renameTo(target)) return result
        dir.listFiles()?.filter { it.name.startsWith(SUBTITLE_PREFIX) }?.forEach { it.delete() }
        return result.copy(extraFiles = result.extraFiles + target.absolutePath)
    }

    private suspend fun runStage(task: DownloadTask, dir: File, index: Int, streamId: String, progress: ProgressReporter): StageResult {
        val prefix = "$STAGE_PREFIX${index + 1}"
        val marker = File(dir, "$prefix.done")
        readMarker(dir, marker)?.let { return StageResult.Done(it) } // finished in an earlier attempt: not downloaded again

        val reported = AtomicReference<String?>(null)
        val result = try {
            withCookieFile(task.request) { cookies ->
                runner.run(
                    Tool.YT_DLP,
                    YtDlpArgs.download(task.request, streamId, dir.absolutePath, "$prefix.%(ext)s", cookies),
                    timeoutMs = 0,
                    idleTimeoutMs = idleTimeoutMs,
                    onStdoutLine = { line ->
                        ProgressParser.parseFile(line)?.let { reported.set(it) }
                        ProgressParser.parseProgress(line)?.let { progress.onProgress(it) }
                    },
                )
            }
        } catch (e: NativeToolsException) {
            return StageResult.Failed(DownloadError.nativeDependencyFailed(id, e.message))
        }
        if (!result.ok) return StageResult.Failed(YtDlpErrors.fromRun(result))
        val file = locate(dir, prefix, reported.get())
            ?: return StageResult.Failed(DownloadError.extractionFailed(id, "yt-dlp reported success, but the downloaded file was not found"))
        try {
            marker.writeText(file.name)
        } catch (e: IOException) {
            // only an optimisation for a later attempt
        }
        return StageResult.Done(file)
    }

    private suspend fun finishSingle(task: DownloadTask, dir: File, downloaded: File, format: FormatOption, emit: (DownloadEvent) -> Unit): DownloadEvent {
        emit(DownloadEvent.Processing("Checking the downloaded file"))
        val first = ffmpeg.probe(downloaded)
            ?: return failed(DownloadError.ffmpegFailure("The downloaded file could not be read as media (ffprobe failed)"))

        var file = downloaded
        var probe = first
        var ext = FileNames.extensionOf(downloaded.name) ?: "bin"
        if (first.isMpegTs) {
            // HLS segments are an MPEG transport stream whatever the file is called: put them into a regular container.
            emit(DownloadEvent.Processing("Converting the stream to a regular media file"))
            val containers = if (first.hasVideo) MergePlanner.attempts("mp4") else listOf("m4a", "mka")
            var converted: File? = null
            var convertedExt = ""
            for (container in containers) {
                val out = File(dir, "$REMUX_PREFIX$container")
                if (ffmpeg.remux(downloaded, out) is StepResult.Ok) {
                    converted = out
                    convertedExt = container
                    break
                }
            }
            val convertedProbe = converted?.let { ffmpeg.probe(it) }
            if (converted != null && convertedProbe != null) {
                downloaded.delete()
                file = converted
                probe = convertedProbe
                ext = convertedExt
            } else {
                converted?.delete()
                ext = "ts" // the transport stream itself is valid media: it is delivered under its true name
            }
        }
        val problem = problemOf(probe, format, file, task)
        if (problem != null) return failed(DownloadError.ffmpegFailure("The result did not pass the check: $problem"))
        deleteMarkers(dir)
        return rename(task, dir, file, ext)
    }

    private suspend fun finishMerge(
        task: DownloadTask,
        dir: File,
        video: File,
        audio: File,
        format: FormatOption,
        container: String,
        emit: (DownloadEvent) -> Unit,
    ): DownloadEvent {
        emit(DownloadEvent.Processing("Merging video and audio"))
        var merged: File? = null
        var mergedExt = container
        var detail = "FFmpeg did not run"
        for (attempt in MergePlanner.attempts(container)) {
            val out = File(dir, "$MERGE_PREFIX$attempt")
            when (val r = ffmpeg.merge(video, audio, out)) {
                StepResult.Ok -> {
                    merged = out
                    mergedExt = attempt
                    break
                }
                is StepResult.Failed -> detail = r.detail
            }
        }
        val result: File = merged ?: return failed(DownloadError.ffmpegFailure(detail))
        emit(DownloadEvent.Processing("Checking the merged file"))
        val probe = ffmpeg.probe(result)
        if (probe == null) {
            result.delete()
            return failed(DownloadError.ffmpegFailure("The merged file could not be read as media (ffprobe failed)"))
        }
        val problem = problemOf(probe, format, result, task)
        if (problem != null) {
            result.delete()
            return failed(DownloadError.ffmpegFailure("The merged file did not pass the check: $problem"))
        }
        video.delete()
        audio.delete()
        deleteMarkers(dir)
        return rename(task, dir, result, mergedExt)
    }

    private fun problemOf(probe: MediaProbe, format: FormatOption, file: File, task: DownloadTask): String? =
        OutputValidator.problem(probe, format.kind, file.length(), task.durationSeconds)

    /** Gives the verified file its final name (the title + the real extension) and reports it. */
    private fun rename(task: DownloadTask, dir: File, file: File, ext: String): DownloadEvent {
        var stem = FileNames.sanitize(task.title?.takeIf { it.isNotBlank() }, "video")
        if (stem.startsWith("vd-")) stem = "video - $stem" // never collide with the working files of this engine
        val wanted = FileNames.withExtension(stem, ext)
        val name = FileNames.uniqueName(wanted) { candidate -> File(dir, candidate).let { it.exists() && it != file } }
        val target = File(dir, name)
        if (target != file && !file.renameTo(target)) {
            return failed(DownloadError.invalidDestination("The finished file could not be renamed inside the temporary folder"))
        }
        return DownloadEvent.Completed(target.absolutePath, target.length())
    }

    private fun failed(error: DownloadError): DownloadEvent = DownloadEvent.Failed(error)

    private fun deleteMarkers(dir: File) {
        dir.listFiles()?.filter { it.name.startsWith(STAGE_PREFIX) && it.name.endsWith(".done") }?.forEach { it.delete() }
    }

    private fun readMarker(dir: File, marker: File): File? = try {
        if (!marker.isFile) {
            null
        } else {
            val file = File(dir, marker.readText().trim())
            if (file.isFile && file.length() > 0 && file.parentFile?.canonicalFile == dir.canonicalFile) file else null
        }
    } catch (e: IOException) {
        null
    }

    /** The file yt-dlp wrote: the path it printed when that is inside [dir], else the newest finished file of this stage. */
    private fun locate(dir: File, prefix: String, reported: String?): File? {
        val canonicalDir = try {
            dir.canonicalFile
        } catch (e: IOException) {
            return null
        }
        if (reported != null) {
            val file = File(reported)
            val inside = try {
                file.isFile && file.length() > 0 && file.canonicalFile.parentFile == canonicalDir
            } catch (e: IOException) {
                false
            }
            if (inside) return file
        }
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$prefix.") && it.length() > 0 && !isScratch(it.name) }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun isScratch(name: String): Boolean =
        name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".done") || name.endsWith(".temp") || name.contains(".part-Frag")

    // ------------------------------------------------------------------------------------------------ helpers

    private suspend fun prepareProblem(): DownloadError? {
        val availability = availabilityProvider()
        if (availability is EngineAvailability.NotAvailable) return DownloadError.engineUnavailable(id, availability.reason)
        return prepareLock.withLock {
            if (prepared) {
                null
            } else {
                try {
                    withContext(ioDispatcher) { tools.prepare() }
                    prepared = true
                    null
                } catch (e: NativeToolsException) {
                    DownloadError.nativeDependencyFailed(id, e.message)
                }
            }
        }
    }

    /**
     * Only https:// links. Python does not follow Android's network security policy, so the app applies the same rule itself:
     * plain http:// is refused (debug builds may allow loopback for tests). The address must be one line without spaces.
     */
    private fun urlProblem(url: String): DownloadError? {
        if (url.length > MAX_URL_LENGTH || url.any { it.isWhitespace() || it.code < 0x20 }) {
            return DownloadError.unsupportedResource(id, "The address is not a valid link")
        }
        val lower = url.lowercase()
        return when {
            lower.startsWith("https://") -> null
            lower.startsWith("http://") ->
                if (allowLoopbackHttp && isLoopback(lower)) null else DownloadError.cleartextBlocked("yt-dlp refused a plain http:// link")
            else -> DownloadError.unsupportedResource(id, "Only http and https links can be read")
        }
    }

    private fun isLoopback(lowerUrl: String): Boolean {
        val authority = lowerUrl.removePrefix("http://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return false
        val host = authority.substringBefore(':')
        return host == "127.0.0.1" || host == "localhost"
    }

    override suspend fun close() {
        runner.killAll()
        cached = null
    }

    private companion object {
        const val CACHE_MS = 60_000L
        const val MAX_URL_LENGTH = 8192
        const val STAGE_PREFIX = "vd-stage-"
        const val MERGE_PREFIX = "vd-merged."
        const val REMUX_PREFIX = "vd-remux."
        const val PROGRESS_INTERVAL_MS = 250L
        const val SUBTITLE_PREFIX = "vd-sub."
        const val SUBTITLE_TIMEOUT_MS = 120_000L
        const val COOKIE_FILE_PREFIX = "vd-cookies-"
        const val COOKIE_LIFETIME_SECONDS = 365L * 24 * 60 * 60
        const val STALE_COOKIE_FILE_MS = 60L * 60_000
        val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass")
    }
}
