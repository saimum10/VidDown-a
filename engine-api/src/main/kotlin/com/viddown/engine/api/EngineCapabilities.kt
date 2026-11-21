package com.viddown.engine.api

/** What one engine can really do. Each engine reports its own; nothing is assumed to be shared between engines. */
data class EngineCapabilities(
    val mediaTypes: Set<MediaType>,
    /** Can resume an interrupted transfer. */
    val supportsResume: Boolean,
    /** Can return a list of formats (resolutions, codecs) for a resource. */
    val listsFormats: Boolean,
    /** May need FFmpeg after downloading (merging or remuxing). */
    val mayNeedFfmpeg: Boolean,
    val notes: String? = null,
) {
    fun handling(type: MediaType): Handling =
        if (type in mediaTypes) Handling.Supported else Handling.Unsupported("This engine does not handle $type resources.")

    sealed interface Handling {
        data object Supported : Handling
        data class Unsupported(val reason: String) : Handling
    }
}
