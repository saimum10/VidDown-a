package com.viddown.engine.api

enum class DownloadState {
    QUEUED,
    PREPARING,
    DOWNLOADING,

    /** Merging, remuxing or validating after the transfer. A download is never COMPLETED before this succeeds. */
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,

    /** Only used by engines that can really resume. */
    PAUSED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
    val isActive: Boolean get() = this == PREPARING || this == DOWNLOADING || this == PROCESSING

    /**
     * The allowed state machine. A terminal state can only be left through an explicit retry (back to QUEUED).
     * An active download may also go back to QUEUED when it was interrupted without failing (the network was lost, or the app
     * process died and the download is recovered): it then runs again, resuming where the engine can.
     */
    fun canTransitionTo(next: DownloadState): Boolean = when (this) {
        QUEUED -> next in setOf(PREPARING, CANCELLED, FAILED)
        PREPARING -> next in setOf(DOWNLOADING, PROCESSING, FAILED, CANCELLED, QUEUED)
        DOWNLOADING -> next in setOf(PROCESSING, COMPLETED, FAILED, CANCELLED, PAUSED, QUEUED)
        PROCESSING -> next in setOf(COMPLETED, FAILED, CANCELLED, QUEUED)
        PAUSED -> next in setOf(DOWNLOADING, QUEUED, FAILED, CANCELLED)
        COMPLETED -> false
        FAILED, CANCELLED -> next == QUEUED
    }
}

/** What a download needs to start. The engine and the chosen format are recorded here and never changed afterwards. */
data class DownloadTask(
    val id: String,
    val engineId: EngineId,
    val request: MediaRequest,
    val format: FormatOption?,
    val title: String? = null,
    /** Known from the engine's inspection; null when the engine did not report it. */
    val durationSeconds: Long? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val outputPath: String? = null,
    val error: DownloadError? = null,
    val createdAtMillis: Long,
    /** Language code of the subtitle file to save next to the video; null when no subtitle was asked for. */
    val subtitleLang: String? = null,
) {
    /** 0f..1f, or null when the total is unknown (never faked). */
    val progressFraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }

    /** Returns a copy in [next], or throws if the transition is not allowed by the state machine. */
    fun transitionTo(next: DownloadState): DownloadTask {
        check(state.canTransitionTo(next)) { "Illegal download state change: $state -> $next" }
        return copy(state = next)
    }
}

/** Events an engine emits while downloading. The flow ends after [Completed] or [Failed]. */
sealed interface DownloadEvent {
    data object Started : DownloadEvent

    /**
     * Real measurements only. [speedBytesPerSecond] and [etaSeconds] are null unless reliably measurable.
     */
    data class Progress(
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val speedBytesPerSecond: Long? = null,
        val etaSeconds: Long? = null,
    ) : DownloadEvent

    /** Post-processing, for example "Merging video and audio". */
    data class Processing(val stage: String) : DownloadEvent

    /**
     * [extraFiles]: other finished files inside the same folder that belong to the result (a subtitle file). They are saved next to
     * it under their own names; a problem with one of them never fails the download.
     */
    data class Completed(val outputPath: String, val sizeBytes: Long, val extraFiles: List<String> = emptyList()) : DownloadEvent

    data class Failed(val error: DownloadError) : DownloadEvent
}
