package com.viddown.core.model

import com.viddown.engine.api.EngineMode
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.StreamKind
import java.net.URI
import java.net.URLEncoder

enum class ThemeMode(val storageKey: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorageKey(key: String?): ThemeMode? = entries.firstOrNull { it.storageKey == key }
    }
}

/** Default quality used when the user turned "Ask before downloading" off. */
enum class QualityPreference(val storageKey: String, val maxHeight: Int?) {
    BEST("best", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360),
    AUDIO_ONLY("audio", null);

    companion object {
        fun fromStorageKey(key: String?): QualityPreference? = entries.firstOrNull { it.storageKey == key }
    }

    /**
     * Picks one of the formats an engine really returned, or null when none fits. Never invents a format.
     * Prefers a single stream that already has audio and video; separate streams are only chosen when merging is possible.
     */
    fun pick(formats: List<FormatOption>, canMerge: Boolean): FormatOption? {
        if (formats.isEmpty()) return null
        if (this == AUDIO_ONLY) {
            return formats.filter { it.kind == StreamKind.AUDIO_ONLY }.maxByOrNull { it.bitrateKbps ?: 0 }
        }
        fun fits(f: FormatOption): Boolean {
            val limit = maxHeight ?: return true
            val h = f.height ?: return false // unknown resolution cannot be shown to respect a limit
            return h <= limit
        }
        val muxed = formats.filter { (it.kind == StreamKind.VIDEO_AND_AUDIO || it.kind == StreamKind.ORIGINAL_FILE) && fits(it) }
        val bestMuxed = muxed.maxWithOrNull(compareBy<FormatOption> { it.height ?: -1 }.thenBy { it.bitrateKbps ?: 0 })
        if (!canMerge) return bestMuxed ?: formats.firstOrNull { it.kind == StreamKind.ORIGINAL_FILE }
        val bestVideo = formats.filter { it.kind == StreamKind.VIDEO_ONLY && fits(it) }.maxByOrNull { it.height ?: -1 }
        val hasAudio = formats.any { it.kind == StreamKind.AUDIO_ONLY }
        // Take the separate streams only when they are strictly sharper than the best single stream.
        if (bestVideo != null && hasAudio && (bestMuxed == null || (bestVideo.height ?: -1) > (bestMuxed.height ?: -1))) return bestVideo
        return bestMuxed ?: formats.firstOrNull { it.kind == StreamKind.ORIGINAL_FILE }
    }
}

enum class SearchEngine(val storageKey: String, val displayName: String, private val template: String) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s"),
    BING("bing", "Bing", "https://www.bing.com/search?q=%s"),
    STARTPAGE("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s");

    fun searchUrl(query: String): String = template.format(URLEncoder.encode(query.trim(), "UTF-8"))

    companion object {
        fun fromStorageKey(key: String?): SearchEngine? = entries.firstOrNull { it.storageKey == key }
    }
}

/** Everything the user can configure. Each field is persisted by the data module and read by the feature that needs it. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Auto (YouTube -> NewPipe, other links -> yt-dlp) or one fixed engine. Auto is the default. */
    val engineMode: EngineMode = EngineMode.AUTO,
    /**
     * Set when the saved engine choice is not one this version knows (written by another version, or damaged). The saved value is kept
     * as it is and [engineMode] is only the default shown by the UI: while this is set NO engine is active (see EngineSelector),
     * nothing is downloaded, and Settings asks the user to choose. Choosing an engine clears it. Null in the normal case.
     */
    val unrecognizedEngineKey: String? = null,
    val askBeforeDownloading: Boolean = true,
    val wifiOnly: Boolean = false,
    val maxSimultaneousDownloads: Int = 2,
    val defaultQuality: QualityPreference = QualityPreference.BEST,
    val notificationsEnabled: Boolean = true,
    /** Storage Access Framework tree URI chosen by the user, or null for the default VidDown folder in the main storage. */
    val downloadTreeUri: String? = null,
    val homepage: String = SettingsValidator.DEFAULT_HOMEPAGE,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val javascriptEnabled: Boolean = true,
    /**
     * The quality of the last download the user started from the quality panel, as a key ("v720", "a:fast"...; see QualityPanel).
     * The panel opens with it selected. Null before the first download.
     */
    val lastQuality: String? = null,
    /** Video codec the user prefers in the panel's advanced options ("avc", "vp9", "av1", "hevc"); null = automatic. */
    val preferredCodec: String? = null,
    /** Container the user prefers in the advanced options ("mp4", "webm", "mkv"); null = automatic. */
    val preferredContainer: String? = null,
)

object SettingsValidator {
    const val DEFAULT_HOMEPAGE = "https://duckduckgo.com"
    const val MIN_DOWNLOADS = 1
    const val MAX_DOWNLOADS = 10

    fun coerceMaxDownloads(value: Int): Int = value.coerceIn(MIN_DOWNLOADS, MAX_DOWNLOADS)

    /** Returns a normalised http(s) URL, or null when [input] is not a usable homepage. A bare "example.com" becomes https. */
    fun normalizeHomepage(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return try {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) withScheme else null
        } catch (e: java.net.URISyntaxException) {
            null
        }
    }

    fun sanitize(s: AppSettings): AppSettings = s.copy(
        maxSimultaneousDownloads = coerceMaxDownloads(s.maxSimultaneousDownloads),
        homepage = normalizeHomepage(s.homepage) ?: DEFAULT_HOMEPAGE,
        downloadTreeUri = s.downloadTreeUri?.takeIf { it.isNotBlank() },
    )
}
