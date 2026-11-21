package com.viddown.engine.ytdlp

import com.viddown.engine.api.EngineId
import com.viddown.engine.api.PlaylistEntry
import com.viddown.engine.api.PlaylistInfo

/**
 * Turns the JSON of `yt-dlp --dump-single-json --flat-playlist` into a [PlaylistInfo]. Only what yt-dlp reported is used. Entries
 * without a usable web address, placeholders of private or deleted videos and repeated addresses are left out; the list never
 * has more than [maxEntries] videos.
 */
internal object PlaylistMapper {
    private val unavailableTitles = setOf("[private video]", "[deleted video]", "[unavailable video]")
    private const val MAX_URL_LENGTH = 8192

    /** Null when the JSON is not a playlist (a single video). */
    fun map(root: Map<String, Any?>, sourceUrl: String, maxEntries: Int): PlaylistInfo? {
        val type = root["_type"].asString()
        if (type != "playlist" && type != "multi_video") return null
        val limit = maxEntries.coerceIn(1, PlaylistInfo.MAX_ENTRIES)
        val seen = HashSet<String>()
        val entries = ArrayList<PlaylistEntry>()
        for (raw in root["entries"].asList().orEmpty()) {
            if (entries.size >= limit) break
            val e = raw.asMap() ?: continue
            val url = addressOf(e) ?: continue
            val title = e["title"].asString()?.trim()?.takeIf { it.isNotEmpty() }
            if (title != null && title.lowercase() in unavailableTitles) continue
            if (!seen.add(url)) continue
            entries += PlaylistEntry(
                url = url,
                title = title,
                durationSeconds = e["duration"].asDouble()?.takeIf { it > 0 }?.toLong(),
                thumbnailUrl = thumbnailOf(e),
                uploader = (e["uploader"].asString() ?: e["channel"].asString())?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        val total = root["playlist_count"].asInt() ?: root["n_entries"].asInt()
        return PlaylistInfo(
            engineId = EngineId.YTDLP,
            sourceUrl = sourceUrl,
            title = root["title"].asString()?.trim()?.takeIf { it.isNotEmpty() },
            uploader = (root["uploader"].asString() ?: root["channel"].asString())?.trim()?.takeIf { it.isNotEmpty() },
            entries = entries,
            hasMore = entries.size >= limit && (total == null || total > entries.size),
        )
    }

    /** The address of one entry: its `url` or `webpage_url` when that is a web address, else a YouTube address built from its id. */
    private fun addressOf(e: Map<String, Any?>): String? {
        for (key in listOf("url", "webpage_url")) {
            e[key].asString()?.trim()?.takeIf { isWebAddress(it) }?.let { return it }
        }
        val id = e["id"].asString()
        val key = e["ie_key"].asString()
        if (id != null && key.equals("Youtube", ignoreCase = true) && id.length in 5..32 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) {
            return "https://www.youtube.com/watch?v=$id"
        }
        return null
    }

    private fun isWebAddress(s: String): Boolean {
        if (s.length > MAX_URL_LENGTH || s.any { it.isWhitespace() || it.code < 0x20 }) return false
        val lower = s.lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://")
    }

    private fun thumbnailOf(e: Map<String, Any?>): String? {
        e["thumbnail"].asString()?.takeIf { isWebAddress(it) }?.let { return it }
        // The last listed thumbnail is the largest one in yt-dlp's own ordering.
        return e["thumbnails"].asList().orEmpty().asReversed().firstNotNullOfOrNull { it.asMap()?.get("url").asString()?.takeIf { u -> isWebAddress(u) } }
    }
}
