package com.viddown.engine.api

enum class ErrorKind {
    UNSUPPORTED_RESOURCE,
    NO_MEDIA_DETECTED,
    NETWORK_UNAVAILABLE,
    CONNECTION_INTERRUPTED,
    SERVER_REJECTED,
    RESOURCE_UNAVAILABLE,
    ENGINE_UNAVAILABLE,
    NATIVE_DEPENDENCY_FAILED,
    FFMPEG_FAILURE,
    INSUFFICIENT_STORAGE,
    PERMISSION_DENIED,
    INVALID_DESTINATION,
    UNSUPPORTED_FORMAT,
    EXTRACTION_FAILED,
    CANCELLED,
}

enum class SuggestedAction {
    NONE,
    RETRY,
    CHECK_NETWORK,
    CHANGE_ENGINE,
    TRY_ANOTHER_FORMAT,
    FREE_UP_STORAGE,
    CHOOSE_ANOTHER_DESTINATION,
    GRANT_PERMISSION,
    OPEN_SOURCE_PAGE,
}

/**
 * The single error model for every engine and for the download manager.
 * [userMessage] is a short explanation, [technicalDetail] preserves what actually happened (always redacted),
 * [suggestedAction] tells the UI what to offer next.
 */
data class DownloadError(
    val kind: ErrorKind,
    val userMessage: String,
    val technicalDetail: String,
    val suggestedAction: SuggestedAction,
    val engineId: EngineId? = null,
    val httpStatus: Int? = null,
    /** True when the link turned out to be a playlist: the caller can list its videos with `DownloadEngine.inspectPlaylist`. */
    val isPlaylist: Boolean = false,
) {
    companion object {
        private fun make(
            kind: ErrorKind,
            user: String,
            detail: String?,
            action: SuggestedAction,
            engine: EngineId? = null,
            status: Int? = null,
        ) = DownloadError(kind, user, Redactor.redact(detail), action, engine, status)

        private fun name(engine: EngineId?) = engine?.label ?: "The selected engine"

        fun unsupportedResource(engine: EngineId?, detail: String? = null) = make(
            ErrorKind.UNSUPPORTED_RESOURCE,
            "${name(engine)} cannot handle this resource. Another engine may support it: change the engine in Settings.",
            detail, SuggestedAction.CHANGE_ENGINE, engine,
        )

        /** The link is a playlist, not one video. It is not a failure of the engine: the playlist can be listed and its videos picked. */
        fun playlistLink(engine: EngineId?, detail: String? = null) = DownloadError(
            kind = ErrorKind.UNSUPPORTED_RESOURCE,
            userMessage = "This link is a playlist. Open it with \"Download from a link\" on Home to choose which videos to save.",
            technicalDetail = Redactor.redact(detail ?: "The link points to a playlist"),
            suggestedAction = SuggestedAction.NONE,
            engineId = engine,
            isPlaylist = true,
        )

        fun noMediaDetected(detail: String? = null) = make(
            ErrorKind.NO_MEDIA_DETECTED, "No downloadable media was found on this page.", detail, SuggestedAction.NONE,
        )

        fun networkUnavailable(detail: String? = null) = make(
            ErrorKind.NETWORK_UNAVAILABLE, "There is no network connection.", detail, SuggestedAction.CHECK_NETWORK,
        )

        fun connectionInterrupted(detail: String? = null) = make(
            ErrorKind.CONNECTION_INTERRUPTED, "The connection was interrupted.", detail, SuggestedAction.RETRY,
        )

        fun serverRejected(status: Int, detail: String? = null) = make(
            ErrorKind.SERVER_REJECTED,
            when (status) {
                401, 403 -> "The server refused access to this file (HTTP $status). It may need a login or may not allow downloads."
                404, 410 -> "The file was not found on the server (HTTP $status)."
                429 -> "The server is limiting requests (HTTP 429). Try again later."
                in 500..599 -> "The server had a problem (HTTP $status). Try again later."
                else -> "The server rejected the request (HTTP $status)."
            },
            detail, if (status in 500..599 || status == 429) SuggestedAction.RETRY else SuggestedAction.OPEN_SOURCE_PAGE, null, status,
        )

        /** The TLS handshake or certificate check failed. VidDown never bypasses certificate validation. */
        fun insecureConnection(detail: String? = null) = make(
            ErrorKind.SERVER_REJECTED,
            "A secure connection to the server could not be established. VidDown does not bypass certificate checks.",
            detail, SuggestedAction.OPEN_SOURCE_PAGE,
        )

        /** Android's network security policy refuses plain http:// traffic. Nothing is weakened to get around it. */
        fun cleartextBlocked(detail: String? = null) = make(
            ErrorKind.SERVER_REJECTED,
            "This link uses plain http://, which Android blocks for security. Use the https:// version of the link if the site offers it.",
            detail, SuggestedAction.OPEN_SOURCE_PAGE,
        )

        fun resourceUnavailable(detail: String? = null) = make(
            ErrorKind.RESOURCE_UNAVAILABLE, "This media is no longer available.", detail, SuggestedAction.OPEN_SOURCE_PAGE,
        )

        fun engineUnavailable(engine: EngineId, reason: String) = make(
            ErrorKind.ENGINE_UNAVAILABLE,
            "${engine.label} is not available: $reason",
            reason, SuggestedAction.CHANGE_ENGINE, engine,
        )

        /**
         * Nothing is selected: the saved choice is not one this version knows (for example it was written by another version).
         * VidDown never picks an engine for the user, so downloads wait until one is chosen.
         */
        fun noEngineSelected(detail: String? = null) = make(
            ErrorKind.ENGINE_UNAVAILABLE,
            "No download engine is selected. Choose one in Settings; VidDown never picks an engine for you.",
            detail, SuggestedAction.CHANGE_ENGINE,
        )

        /**
         * The engine was changed in Settings after the options on screen were read by [listedWith]. Those options belong to that
         * engine, so they are not sent to [now]: the user must look again.
         */
        fun engineChanged(listedWith: EngineId, now: EngineId?) = make(
            ErrorKind.ENGINE_UNAVAILABLE,
            "The engine was changed to ${now?.label ?: "none"} after ${listedWith.label} listed these options. Open the download again so the selected engine can read it.",
            "listedWith=${listedWith.storageKey} selectedNow=${now?.storageKey ?: "none"}", SuggestedAction.RETRY, listedWith,
        )

        /**
         * A download that already exists cannot start because ITS engine (the one it was created with) is not usable. Changing the
         * selected engine does not help this record: it keeps its engine for its whole life, so the advice is to start a new download.
         */
        fun engineUnavailableForDownload(engine: EngineId, reason: String) = make(
            ErrorKind.ENGINE_UNAVAILABLE,
            "${engine.label} started this download and is not available now: ${reason.trim().trimEnd('.')}. Downloads keep their engine. " +
                "To use another one, select it in Settings and start the download again.",
            reason, SuggestedAction.CHANGE_ENGINE, engine,
        )

        fun nativeDependencyFailed(engine: EngineId, detail: String? = null) = make(
            ErrorKind.NATIVE_DEPENDENCY_FAILED,
            "${engine.label} could not start its native components on this device.",
            detail, SuggestedAction.CHANGE_ENGINE, engine,
        )

        fun ffmpegFailure(detail: String? = null) = make(
            ErrorKind.FFMPEG_FAILURE, "Processing the downloaded streams failed.", detail, SuggestedAction.TRY_ANOTHER_FORMAT,
        )

        fun insufficientStorage(detail: String? = null) = make(
            ErrorKind.INSUFFICIENT_STORAGE, "There is not enough free storage for this download.", detail, SuggestedAction.FREE_UP_STORAGE,
        )

        fun permissionDenied(detail: String? = null) = make(
            ErrorKind.PERMISSION_DENIED, "VidDown does not have permission to save to this location.", detail, SuggestedAction.GRANT_PERMISSION,
        )

        fun invalidDestination(detail: String? = null) = make(
            ErrorKind.INVALID_DESTINATION, "The download location is not valid.", detail, SuggestedAction.CHOOSE_ANOTHER_DESTINATION,
        )

        fun unsupportedFormat(engine: EngineId?, detail: String? = null) = make(
            ErrorKind.UNSUPPORTED_FORMAT, "This format cannot be downloaded or processed by ${name(engine)}.", detail,
            SuggestedAction.TRY_ANOTHER_FORMAT, engine,
        )

        fun extractionFailed(engine: EngineId?, detail: String? = null) = make(
            ErrorKind.EXTRACTION_FAILED, "${name(engine)} could not read this page or link.", detail, SuggestedAction.CHANGE_ENGINE, engine,
        )

        /**
         * The site understood the request but will not hand the media out without a login, payment, age check, a different region
         * or a human check. VidDown never signs in for the user and never bypasses such a restriction. [restriction] is one
         * plain sentence naming it, for example "This video is age-restricted."
         */
        fun accessRestricted(engine: EngineId?, restriction: String, detail: String? = null) = make(
            ErrorKind.RESOURCE_UNAVAILABLE,
            "$restriction VidDown does not sign in or bypass access restrictions.",
            detail, SuggestedAction.OPEN_SOURCE_PAGE, engine,
        )

        /**
         * The engine found the media but none of its formats can be downloaded by this engine in this build (for example every
         * format needs a merge step that is not available). [reason] is one plain sentence.
         */
        fun noUsableFormat(engine: EngineId?, reason: String, detail: String? = null) = make(
            ErrorKind.UNSUPPORTED_FORMAT,
            "${name(engine)} found this media, but none of its formats can be downloaded here. $reason",
            detail, SuggestedAction.CHANGE_ENGINE, engine,
        )

        fun cancelled() = make(ErrorKind.CANCELLED, "The download was cancelled.", null, SuggestedAction.NONE)
    }
}
