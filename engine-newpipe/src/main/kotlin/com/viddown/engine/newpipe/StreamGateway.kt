package com.viddown.engine.newpipe

import com.viddown.engine.api.DownloadError

/**
 * The only door to the extractor library. The real implementation lives in the `adapter` package; tests use a fake.
 * Blocking: callers run it off the main thread. Failures are thrown as [GatewayException] carrying an already-translated error.
 */
interface StreamGateway {
    /** True when one of the extractor's services recognises this address as a page it can read. Cheap and offline. */
    fun supportsPage(url: String): Boolean

    /** The name of the service that recognises [url] ("YouTube"...), or null. Cheap and offline. */
    fun serviceNameFor(url: String): String?

    /** Reads [url] with the extractor. Must finish quickly once [cancel] is cancelled (its network calls are aborted). */
    @Throws(GatewayException::class)
    fun extract(url: String, cancel: CancelToken): ExtractedMedia

    /** True when one of the extractor's services recognises this address as a playlist (of videos). Cheap and offline. */
    fun supportsPlaylist(url: String): Boolean = false

    /**
     * Lists the videos of the playlist at [url], at most [maxEntries], following the site's pages until that many are found.
     * Same contract as [extract]: blocking, quick to stop once [cancel] is cancelled, failures as [GatewayException].
     */
    @Throws(GatewayException::class)
    fun extractPlaylist(url: String, maxEntries: Int, cancel: CancelToken): ExtractedPlaylist =
        throw GatewayException(DownloadError.unsupportedResource(com.viddown.engine.api.EngineId.NEWPIPE, "This gateway cannot read playlists"))

    /** Releases what the extractor holds (connection pools, threads). Safe to call more than once. */
    fun close()
}

/** An extraction problem, already translated to VidDown's error model (never a raw library exception). */
class GatewayException(val error: DownloadError, cause: Throwable? = null) : Exception(error.technicalDetail, cause)
