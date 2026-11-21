package com.viddown.engine.api

/** One video of a playlist, as the engine listed it. Only what the engine reported is set; nothing is guessed. */
data class PlaylistEntry(
    /** The address of the single video. It is read and downloaded like any other video link. */
    val url: String,
    val title: String? = null,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val uploader: String? = null,
)

/**
 * The videos of a playlist (at most [MAX_ENTRIES]), in the order the site lists them.
 * [hasMore] is true when the playlist has more videos than were listed.
 */
data class PlaylistInfo(
    val engineId: EngineId,
    val sourceUrl: String,
    val title: String? = null,
    val uploader: String? = null,
    val entries: List<PlaylistEntry> = emptyList(),
    val hasMore: Boolean = false,
) {
    companion object {
        /** The most videos a playlist shows (and lets the user pick from). */
        const val MAX_ENTRIES = 100
    }
}

/**
 * Host and query checks only (no network, no regex) that say whether a link is a playlist link. A link that is not recognised here
 * can still turn out to be a playlist when an engine reads it: that case is reported with [DownloadError.isPlaylist].
 */
object PlaylistLinks {
    /**
     * True for a YouTube playlist address (`/playlist?list=...`) and for a YouTube video address that carries a playlist
     * (`watch?v=...&list=...`, `youtu.be/ID?list=...`). Every other site: false (the engine decides after reading the link).
     */
    fun looksLikePlaylist(url: String): Boolean {
        if (!EngineRouting.isYouTube(url)) return false
        return youtubeListId(url) != null
    }

    /** The playlist id of a YouTube link (`list=` value), or null. Only letters, digits, "_" and "-" are accepted. */
    fun youtubeListId(url: String): String? {
        if (!EngineRouting.isYouTube(url)) return null
        val q = url.indexOf('?')
        if (q < 0) return null
        val end = url.indexOf('#', q).let { if (it < 0) url.length else it }
        for (pair in url.substring(q + 1, end).split('&')) {
            if (!pair.startsWith("list=")) continue
            val id = pair.substring(5)
            if (id.length in 2..128 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) return id
        }
        return null
    }

    /**
     * The same YouTube video address without its playlist (`list=`, `index=`, `start_radio=` removed), or null when the link is not
     * the address of one video (a `/playlist?list=...` page names no video), carries no playlist (nothing to drop) or is not a
     * YouTube link. It is what "download only this video" reads when a watch page belongs to a playlist.
     */
    fun singleVideoUrl(url: String): String? {
        if (!EngineRouting.isYouTube(url)) return null
        if (youtubeListId(url) == null) return null
        val q = url.indexOf('?')
        if (q < 0) return null
        val hash = url.indexOf('#', q)
        val base = url.substring(0, q)
        val params = url.substring(q + 1, if (hash < 0) url.length else hash).split('&').filter { it.isNotEmpty() }
        val shortLinkId = base.substringAfter("youtu.be/", "").trim('/')
        val hasVideo = params.any { it.startsWith("v=") && it.length > 2 } || shortLinkId.isNotEmpty()
        if (!hasVideo) return null
        val kept = params.filter { p -> DROPPED_WITH_PLAYLIST.none { p.startsWith(it) } }
        return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
    }

    private val DROPPED_WITH_PLAYLIST = listOf("list=", "index=", "start_radio=")

    /** `https://www.youtube.com/playlist?list=ID` for a YouTube link that carries a playlist id, else null. */
    fun canonicalYoutubePlaylistUrl(url: String): String? =
        youtubeListId(url)?.let { "https://www.youtube.com/playlist?list=$it" }
}
