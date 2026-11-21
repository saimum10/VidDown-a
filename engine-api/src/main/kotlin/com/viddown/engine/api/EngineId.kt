package com.viddown.engine.api

/** The real download engines. A download is always created with exactly one of them and keeps it; there is no silent fallback. */
enum class EngineId(val storageKey: String, val label: String) {
    NEWPIPE("newpipe", "NewPipe"),
    YTDLP("ytdlp", "yt-dlp");

    companion object {
        fun fromStorageKey(key: String?): EngineId? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * What the user picks in Settings: one fixed engine, or [AUTO], which chooses the engine per link (see [EngineRouting]).
 * The storage keys of the two fixed modes are the same as the keys of [EngineId], so a value saved by an older version still reads.
 */
enum class EngineMode(val storageKey: String, val label: String, val fixed: EngineId?) {
    AUTO("auto", "Auto", null),
    NEWPIPE("newpipe", "NewPipe", EngineId.NEWPIPE),
    YTDLP("ytdlp", "yt-dlp", EngineId.YTDLP);

    companion object {
        fun fromStorageKey(key: String?): EngineMode? = entries.firstOrNull { it.storageKey == key }

        fun of(id: EngineId): EngineMode = when (id) {
            EngineId.NEWPIPE -> NEWPIPE
            EngineId.YTDLP -> YTDLP
        }
    }
}

/**
 * Which engine Auto mode uses for a link: YouTube links go to NewPipe, every other link goes to yt-dlp.
 * Only the host of the text is looked at (no network, no regex, no allocation): the decision takes microseconds.
 */
object EngineRouting {
    fun engineFor(url: String?): EngineId = if (url != null && isYouTube(url)) EngineId.NEWPIPE else EngineId.YTDLP

    /** youtube.com and its subdomains (www, m, music, ...), youtu.be and youtube-nocookie.com. Anything else, or no host, is false. */
    fun isYouTube(url: String): Boolean {
        val schemeEnd = url.indexOf("://")
        var start = if (schemeEnd in 1..10) schemeEnd + 3 else 0
        while (start < url.length && url[start] == ' ') start++
        var end = start
        while (end < url.length) {
            val c = url[end]
            if (c == '/' || c == '?' || c == '#' || c == '\\') break
            end++
        }
        // authority = [userinfo@]host[:port]
        val at = url.lastIndexOf('@', end - 1)
        if (at >= start) start = at + 1
        val colon = url.indexOf(':', start)
        if (colon in start until end) end = colon
        while (end > start && url[end - 1] == '.') end--
        val length = end - start
        if (length <= 0) return false
        return hostIs(url, start, length, "youtu.be") ||
            hostIs(url, start, length, "youtube.com") || hostEndsWith(url, start, length, ".youtube.com") ||
            hostIs(url, start, length, "youtube-nocookie.com") || hostEndsWith(url, start, length, ".youtube-nocookie.com")
    }

    private fun hostIs(url: String, start: Int, length: Int, host: String) =
        length == host.length && url.regionMatches(start, host, 0, host.length, ignoreCase = true)

    private fun hostEndsWith(url: String, start: Int, length: Int, suffix: String) =
        length > suffix.length && url.regionMatches(start + length - suffix.length, suffix, 0, suffix.length, ignoreCase = true)
}

/** Whether an engine can be selected and used in this build and on this device. */
sealed interface EngineAvailability {
    data object Available : EngineAvailability

    /** [reason] is shown to the user as the engine's status, for example "Not available in this build". */
    data class NotAvailable(val reason: String) : EngineAvailability

    val isAvailable: Boolean get() = this is Available
}
