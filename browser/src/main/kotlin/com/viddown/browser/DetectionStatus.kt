package com.viddown.browser

import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineCapabilities
import com.viddown.engine.api.EngineId

/** Whether the selected engine can download one detection. The download button only exists for [Downloadable]. */
sealed interface DetectionStatus {
    data object Downloadable : DetectionStatus

    /** The selected engine does not handle this kind of resource (for example HLS with Direct HTTP). */
    data class UnsupportedByEngine(val engineLabel: String, val explanation: String) : DetectionStatus

    data class EngineUnavailable(val engineLabel: String, val explanation: String) : DetectionStatus

    /** DRM: not downloadable by any engine, never bypassed. */
    data class Protected(val explanation: String) : DetectionStatus

    val canDownload: Boolean get() = this is Downloadable
}

object DetectionStatusResolver {
    /** No engine is selected (the saved choice is not recognised): nothing can be downloaded, and no engine is guessed. */
    fun resolveWithoutEngine(media: DetectedMedia): DetectionStatus =
        if (media.protection == Protection.DRM) {
            DetectionStatus.Protected("This media is protected by DRM. It cannot be downloaded, and VidDown never bypasses DRM.")
        } else {
            DetectionStatus.EngineUnavailable("No engine", "No download engine is selected. Choose one in Settings; VidDown never picks an engine for you.")
        }

    /** [capabilities] is null when the selected engine cannot be created (it is then [EngineUnavailable]). */
    fun resolve(
        media: DetectedMedia,
        engineId: EngineId,
        availability: EngineAvailability,
        capabilities: EngineCapabilities?,
    ): DetectionStatus {
        if (media.protection == Protection.DRM) {
            return DetectionStatus.Protected("This media is protected by DRM. It cannot be downloaded, and VidDown never bypasses DRM.")
        }
        if (availability is EngineAvailability.NotAvailable || capabilities == null) {
            val reason = (availability as? EngineAvailability.NotAvailable)?.reason ?: "The engine could not be started."
            return DetectionStatus.EngineUnavailable(engineId.label, "${engineId.label} is not available: $reason")
        }
        return when (val h = capabilities.handling(media.type)) {
            EngineCapabilities.Handling.Supported -> DetectionStatus.Downloadable
            is EngineCapabilities.Handling.Unsupported -> DetectionStatus.UnsupportedByEngine(engineId.label, explain(media, engineId))
        }
    }

    private fun explain(media: DetectedMedia, engine: EngineId): String {
        val what = when {
            media.isStreamingManifest -> "streaming playlists (HLS/DASH)"
            else -> "this kind of file"
        }
        return "${engine.label} cannot download $what. Choose another engine in Settings if one that can is available."
    }
}
