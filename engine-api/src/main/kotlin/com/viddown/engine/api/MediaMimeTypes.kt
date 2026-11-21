package com.viddown.engine.api

/** Small, fixed table for the media types VidDown deals with. Platform lookups (MimeTypeMap) are only a second opinion. */
object MediaMimeTypes {
    private val byExtension: Map<String, String> = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "wmv" to "video/x-ms-wmv",
        "3gp" to "video/3gpp",
        "3g2" to "video/3gpp2",
        "ts" to "video/mp2t",
        "flv" to "video/x-flv",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "ogv" to "video/ogg",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "m4b" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "weba" to "audio/webm",
        "wma" to "audio/x-ms-wma",
        "mka" to "audio/x-matroska",
    )

    /** MIME types and the extension a file of that type should carry (first match wins when several map to one). */
    private val extensionByMime: Map<String, String> = mapOf(
        "video/mp4" to "mp4",
        "application/mp4" to "mp4",
        "application/x-matroska" to "mkv",
        "video/x-m4v" to "m4v",
        "video/webm" to "webm",
        "video/x-matroska" to "mkv",
        "video/quicktime" to "mov",
        "video/x-msvideo" to "avi",
        "video/x-ms-wmv" to "wmv",
        "video/3gpp" to "3gp",
        "video/3gpp2" to "3g2",
        "video/mp2t" to "ts",
        "video/x-flv" to "flv",
        "video/mpeg" to "mpg",
        "video/ogg" to "ogv",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/aac" to "aac",
        "audio/ogg" to "ogg",
        "application/ogg" to "ogg",
        "audio/opus" to "opus",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/webm" to "weba",
        "audio/x-ms-wma" to "wma",
        "audio/x-matroska" to "mka",
    )

    private val audioExtensions = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "weba", "wma", "mka")

    /** The MIME type for a file extension ("mp4", case-insensitive, with or without the dot), or null when it is not a known media type. */
    fun forExtension(extension: String?): String? = byExtension[extension?.trim('.')?.lowercase()]

    /** The extension for a Content-Type value (parameters such as "; codecs=" are ignored), or null when unknown. */
    fun extensionFor(mimeType: String?): String? = extensionByMime[normalize(mimeType)]

    fun isKnownMediaExtension(extension: String?): Boolean = forExtension(extension) != null

    fun isAudioExtension(extension: String?): Boolean = extension?.trim('.')?.lowercase() in audioExtensions

    /** "Video/MP4; codecs=avc1" -> "video/mp4"; blank -> null. */
    fun normalize(contentType: String?): String? =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
