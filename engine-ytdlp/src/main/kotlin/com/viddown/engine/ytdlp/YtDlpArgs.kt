package com.viddown.engine.ytdlp

import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.PlaylistInfo

/**
 * Builds yt-dlp command lines. There is no shell: every element is one argument, and the page address always comes after "--",
 * so it can never be read as an option. The command lines hold addresses that may carry tokens: they are never logged.
 */
internal object YtDlpArgs {
    private val safeFormatId = Regex("^[A-Za-z0-9_.=:@][A-Za-z0-9_.=:@-]{0,99}$")

    /**
     * Request headers that are forwarded on the command line. Cookies and credentials are NOT, ever: a cookie reaches yt-dlp only
     * through [cookieFile] (--cookies), a private temporary file that the engine deletes after the run (docs/ytdlp.md, D68).
     */
    private val forwardedHeaders = setOf("referer", "user-agent", "origin", "accept-language")

    /** A yt-dlp format id that cannot be mistaken for a selector expression or an option ("+", "/", ",", brackets, spaces and a leading "-" are not allowed). */
    fun isSafeFormatId(id: String): Boolean = safeFormatId.matches(id)

    /**
     * [noPlaylist] is true everywhere a single video is read or downloaded: a link that carries a playlist (watch?v=...&list=...)
     * then means that one video. Only [playlist] turns it off, to list the videos of a playlist.
     */
    private fun common(headers: Map<String, String>, noPlaylist: Boolean = true, cookieFile: String? = null): List<String> = buildList {
        add("--ignore-config")
        add("--no-cache-dir")
        // The path of a file the engine wrote itself; the cookies are never on the command line.
        if (cookieFile != null) { add("--cookies"); add(cookieFile) }
        if (noPlaylist) add("--no-playlist")
        add("--no-color")
        add("--socket-timeout"); add("30")
        add("--retries"); add("5")
        add("--fragment-retries"); add("5")
        // TLS is never weakened: there is no --no-check-certificate anywhere in this module.
        for ((name, value) in headers) {
            if (name.lowercase() !in forwardedHeaders) continue
            if (value.isBlank() || value.length > 2048 || value.any { it == '\r' || it == '\n' || it.code < 0x20 }) continue
            add("--add-header"); add("$name:$value")
        }
    }

    /** One JSON document describing the media and all its formats. Playlists are listed flat (fast) so they can be refused. */
    fun inspect(request: MediaRequest, cookieFile: String? = null): List<String> =
        common(request.headers, cookieFile = cookieFile) + listOf("--dump-single-json", "--flat-playlist", "--", request.url)

    /**
     * Lists the videos of a playlist as one JSON document: flat (no video is opened, so 100 entries take seconds) and cut at
     * [maxEntries]. Nothing is downloaded.
     */
    fun playlist(request: MediaRequest, maxEntries: Int, cookieFile: String? = null): List<String> =
        common(request.headers, noPlaylist = false, cookieFile = cookieFile) + listOf(
            "--dump-single-json",
            "--flat-playlist",
            "--playlist-end", maxEntries.coerceIn(1, PlaylistInfo.MAX_ENTRIES).toString(),
            "--", request.url,
        )

    /**
     * Downloads exactly one format into [directory] under [nameTemplate] (a bare file name ending in ".%(ext)s"). The folder is
     * given with -P and not inside the template, so a "%" in a path can never be read as a template field. yt-dlp keeps ".part"
     * files and resumes them on the next attempt. Fixups are off: they would call FFmpeg; VidDown does its own checked remuxing.
     */
    fun download(request: MediaRequest, formatId: String, directory: String, nameTemplate: String, cookieFile: String? = null): List<String> =
        common(request.headers, cookieFile = cookieFile) + listOf(
            "-f", formatId,
            "-P", directory,
            "-o", nameTemplate,
            "--fixup", "never",
            "--newline",
            "--quiet",
            "--progress",
            "--progress-template", ProgressParser.PROGRESS_TEMPLATE,
            "--no-simulate",
            "--print", ProgressParser.FILE_TEMPLATE,
            "--", request.url,
        )

    /** True for a plain language code ("en", "pt-BR", "en-orig"): the only kind that is passed on to --sub-langs. */
    fun isSafeLanguage(lang: String): Boolean = safeLanguage.matches(lang)

    private val safeLanguage = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$")

    /**
     * Saves only the subtitle file of [lang] (manual, else machine-made) into [directory] as "<nameStem>.<lang>.<ext>". Nothing of the
     * video is downloaded. Only formats VidDown can hand over as they are (srt, vtt, ass) are asked for: no conversion, so no FFmpeg.
     */
    fun subtitle(request: MediaRequest, lang: String, directory: String, nameStem: String, cookieFile: String? = null): List<String> =
        common(request.headers, cookieFile = cookieFile) + listOf(
            "--skip-download",
            "--write-subs",
            "--write-auto-subs",
            "--sub-langs", lang,
            "--sub-format", "srt/vtt/ass",
            "-P", directory,
            "-o", "$nameStem.%(ext)s",
            "--quiet",
            "--no-warnings",
            "--", request.url,
        )
}
