package com.viddown.engine.newpipe.adapter

import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.EngineId
import com.viddown.engine.newpipe.CancelToken
import com.viddown.engine.newpipe.Delivery
import com.viddown.engine.api.PlaylistLinks
import com.viddown.engine.newpipe.ExtractedMedia
import com.viddown.engine.newpipe.ExtractedPlaylist
import com.viddown.engine.newpipe.ExtractedPlaylistEntry
import com.viddown.engine.newpipe.ExtractedStream
import com.viddown.engine.newpipe.GatewayException
import com.viddown.engine.newpipe.ResolutionLabel
import com.viddown.engine.newpipe.StreamGateway
import com.viddown.engine.newpipe.StreamRole
import java.util.Locale
import java.util.concurrent.CancellationException
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * The ONLY class that talks to NewPipeExtractor's stream API. It converts what the library returns into [ExtractedMedia] and every
 * library exception into a [GatewayException]. Replacing or updating the extractor means touching this package, nothing else.
 *
 * NewPipeExtractor keeps its downloader and localisation in static state ([NewPipe.init]). That state is set lazily on the first
 * extraction, so merely creating this object (or listing engines in Settings) never starts the extractor.
 */
class ExtractorGateway(
    private val downloader: OkHttpDownloader,
    private val localization: Localization = Localization.fromLocale(Locale.getDefault()),
) : StreamGateway {

    private val initLock = Any()

    @Volatile
    private var initialised = false

    private fun ensureInitialised() {
        if (initialised) return
        synchronized(initLock) {
            if (!initialised) {
                NewPipe.init(downloader, localization)
                initialised = true
            }
        }
    }

    /** The service that reads this address as one video/audio page. Channels and playlists are recognised but not handled. */
    private fun serviceFor(url: String): StreamingService? = try {
        NewPipe.getServiceByUrl(url).takeIf { it.getLinkTypeByUrl(url) == StreamingService.LinkType.STREAM }
    } catch (_: Exception) {
        null
    }

    override fun supportsPage(url: String): Boolean = serviceFor(url) != null

    /**
     * The service and the address that read [url] as a playlist. A YouTube video address that carries a playlist
     * (watch?v=...&list=...) is a STREAM link for the extractor, so the playlist is asked for under its own address first.
     */
    private fun playlistTarget(url: String): Pair<StreamingService, String>? {
        val candidates = listOfNotNull(PlaylistLinks.canonicalYoutubePlaylistUrl(url), url).distinct()
        for (candidate in candidates) {
            try {
                val service = NewPipe.getServiceByUrl(candidate)
                if (service.getLinkTypeByUrl(candidate) == StreamingService.LinkType.PLAYLIST) return service to candidate
            } catch (_: Exception) {
                // not recognised under this address: try the next one
            }
        }
        return null
    }

    override fun supportsPlaylist(url: String): Boolean = playlistTarget(url) != null

    override fun extractPlaylist(url: String, maxEntries: Int, cancel: CancelToken): ExtractedPlaylist {
        val (service, target) = playlistTarget(url)
            ?: throw GatewayException(DownloadError.unsupportedResource(EngineId.NEWPIPE, "No NewPipe service recognises this address as a playlist"))
        try {
            ensureInitialised()
            val first = downloader.withCancel(cancel) { PlaylistInfo.getInfo(service, target) }
            val items = ArrayList<StreamInfoItem>(first.relatedItems.orEmpty())
            var more = first.hasNextPage()
            var next = first.nextPage
            // The site lists a playlist page by page (YouTube: 100 videos a page): read pages until enough videos are known.
            while (items.size < maxEntries && more) {
                if (cancel.isCancelled) throw CancellationException("The extraction was cancelled")
                val current = next ?: break
                val page = downloader.withCancel(cancel) { PlaylistInfo.getMoreItems(service, target, current) }
                items += page.items.orEmpty()
                more = page.hasNextPage()
                next = page.nextPage
            }
            val seen = HashSet<String>()
            val entries = ArrayList<ExtractedPlaylistEntry>()
            for (item in items) {
                if (entries.size >= maxEntries) break
                val address = item.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
                if (!seen.add(address)) continue
                entries += ExtractedPlaylistEntry(
                    url = address,
                    title = item.name?.takeIf { it.isNotBlank() },
                    durationSeconds = item.duration.takeIf { it > 0 },
                    thumbnailUrl = item.thumbnails.orEmpty().maxByOrNull { it.height }?.url,
                    uploader = item.uploaderName?.takeIf { it.isNotBlank() },
                )
            }
            return ExtractedPlaylist(
                serviceName = service.serviceInfo.name,
                title = first.name?.takeIf { it.isNotBlank() },
                uploader = first.uploaderName?.takeIf { it.isNotBlank() },
                entries = entries,
                hasMore = items.size > entries.size || (entries.size >= maxEntries && more),
            )
        } catch (e: LinkageError) {
            throw GatewayException(
                DownloadError.engineUnavailable(EngineId.NEWPIPE, "The extractor library could not be loaded on this device"),
                e,
            )
        } catch (e: Exception) {
            if (cancel.isCancelled) throw CancellationException("The extraction was cancelled")
            throw GatewayException(ExtractionErrors.translate(e), e)
        }
    }

    override fun serviceNameFor(url: String): String? = serviceFor(url)?.serviceInfo?.name

    override fun extract(url: String, cancel: CancelToken): ExtractedMedia {
        val service = serviceFor(url)
            ?: throw GatewayException(DownloadError.unsupportedResource(EngineId.NEWPIPE, "No NewPipe service recognises this address"))
        try {
            ensureInitialised()
            val info = downloader.withCancel(cancel) { StreamInfo.getInfo(service, url) }
            return convert(service, info)
        } catch (e: LinkageError) {
            // For example a missing JDK class on an old Android release when desugaring is not set up: never crash the app for it.
            throw GatewayException(
                DownloadError.engineUnavailable(EngineId.NEWPIPE, "The extractor library could not be loaded on this device"),
                e,
            )
        } catch (e: Exception) {
            if (cancel.isCancelled) throw CancellationException("The extraction was cancelled")
            throw GatewayException(ExtractionErrors.translate(e), e)
        }
    }

    override fun close() {
        // Nothing of its own: the HTTP client is shared with, and closed by, the engine's transport. NewPipe's static downloader
        // reference stays until the next engine initialises it again (the library has no way to clear it).
    }

    // ------------------------------------------------------------------------------------------------ conversion

    internal fun convert(service: StreamingService, info: StreamInfo): ExtractedMedia {
        val streams = ArrayList<ExtractedStream>()
        info.videoStreams.orEmpty().mapTo(streams) { video(it) }
        info.videoOnlyStreams.orEmpty().mapTo(streams) { video(it, forceVideoOnly = true) }
        info.audioStreams.orEmpty().mapTo(streams) { audio(it) }
        return ExtractedMedia(
            serviceName = service.serviceInfo.name,
            title = info.name?.takeIf { it.isNotBlank() },
            durationSeconds = info.duration.takeIf { it > 0 },
            thumbnailUrl = info.thumbnails.orEmpty().maxByOrNull { it.height }?.url,
            uploader = info.uploaderName?.takeIf { it.isNotBlank() },
            isLive = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM,
            streams = streams,
            problems = info.errors.orEmpty().map { "${it.javaClass.simpleName}: ${it.message ?: "no message"}" },
        )
    }

    private fun delivery(s: Stream): Delivery = when (s.deliveryMethod) {
        DeliveryMethod.PROGRESSIVE_HTTP -> Delivery.PROGRESSIVE_HTTP
        DeliveryMethod.DASH -> Delivery.DASH
        DeliveryMethod.HLS -> Delivery.HLS
        else -> Delivery.OTHER
    }

    private fun video(v: VideoStream, forceVideoOnly: Boolean = false): ExtractedStream {
        // The label ("720p60") is the fallback when the numeric fields are unknown (0 or -1).
        val label = ResolutionLabel.parse(v.getResolution())
        return ExtractedStream(
            nativeId = v.id,
            role = if (forceVideoOnly || v.isVideoOnly()) StreamRole.VIDEO_ONLY else StreamRole.VIDEO_AND_AUDIO,
            delivery = delivery(v),
            url = if (v.isUrl) v.content else null,
            container = v.format?.suffix,
            mimeType = v.format?.mimeType,
            width = v.width.takeIf { it > 0 },
            height = v.height.takeIf { it > 0 } ?: label.height,
            fps = v.fps.takeIf { it > 0 }?.toDouble() ?: label.fps,
            videoCodec = v.codec?.takeIf { it.isNotBlank() },
            audioCodec = null,
            // NewPipe reports a video bitrate in bits per second.
            bitrateKbps = v.bitrate.takeIf { it > 0 }?.let { it / 1000 }?.takeIf { it > 0 },
            sizeBytes = v.itagItem?.contentLength?.takeIf { it > 0 },
        )
    }

    private fun audio(a: AudioStream): ExtractedStream {
        val track = a.audioTrackName?.takeIf { it.isNotBlank() } ?: a.audioLocale?.toLanguageTag()
        return ExtractedStream(
            nativeId = a.id,
            role = StreamRole.AUDIO_ONLY,
            delivery = delivery(a),
            url = if (a.isUrl) a.content else null,
            container = a.format?.suffix,
            mimeType = a.format?.mimeType,
            audioCodec = a.codec?.takeIf { it.isNotBlank() },
            // The average bitrate is in kbps (NewPipe's own UI shows it so); a plain bitrate is in bits per second.
            bitrateKbps = a.averageBitrate.takeIf { it > 0 } ?: a.bitrate.takeIf { it > 0 }?.let { it / 1000 }?.takeIf { it > 0 },
            sizeBytes = a.itagItem?.contentLength?.takeIf { it > 0 },
            label = track,
        )
    }
}
