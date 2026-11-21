package com.viddown.core.model

import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.StreamKind

data class DownloadItemUi(
    val id: String,
    val title: String,
    val state: DownloadState,
    val stateLabel: String,
    /** 0..1, or null when the total size is unknown: the UI then shows no percentage (never a fake one). */
    val progress: Float?,
    val progressText: String?,
    val engineLabel: String,
    val qualityLabel: String?,
    val errorMessage: String?,
    val errorDetail: String?,
    val canDeleteRecord: Boolean,
    /** Queued or running: the download can be stopped. */
    val canCancel: Boolean = false,
    /** Failed or cancelled: the download can be queued again. */
    val canRetry: Boolean = false,
    /** Measured speed such as "1.2 MB/s"; null unless the engine measured it. */
    val speedText: String? = null,
    /** Estimated time left; null unless speed and total size are both known. */
    val etaText: String? = null,
    /** Extra line such as "Waiting for Wi-Fi" or "Saving", or null. */
    val note: String? = null,
)

data class ActiveSummary(val active: Int, val queued: Int, val progress: Float?) {
    val hasWork: Boolean get() = active > 0 || queued > 0
}

object DownloadUi {
    fun stateLabel(state: DownloadState): String = when (state) {
        DownloadState.QUEUED -> "Queued"
        DownloadState.PREPARING -> "Preparing"
        DownloadState.DOWNLOADING -> "Downloading"
        DownloadState.PROCESSING -> "Processing"
        DownloadState.COMPLETED -> "Completed"
        DownloadState.FAILED -> "Failed"
        DownloadState.CANCELLED -> "Cancelled"
        DownloadState.PAUSED -> "Paused"
    }

    fun title(task: DownloadTask): String =
        task.title?.takeIf { it.isNotBlank() }
            ?: Formatters.fileNameFromPath(task.outputPath)
            ?: Formatters.fileNameFromUrl(task.request.url)
            ?: task.request.url

    /** "1080p · MP4", "Original file", "Audio only · M4A"... or null when no format was chosen. */
    fun qualityLabel(task: DownloadTask): String? {
        val f = task.format ?: return null
        val kind = when (f.kind) {
            StreamKind.ORIGINAL_FILE -> "Original file"
            StreamKind.AUDIO_ONLY -> "Audio only"
            StreamKind.VIDEO_ONLY -> "Video only"
            StreamKind.VIDEO_AND_AUDIO -> null
        }
        return listOfNotNull(kind, f.resolutionLabel, f.container?.uppercase()).joinToString(" · ").ifEmpty { null }
    }

    fun progressText(task: DownloadTask): String? {
        val done = Formatters.bytes(task.bytesDownloaded.takeIf { it > 0 || task.totalBytes != null })
        val total = Formatters.bytes(task.totalBytes?.takeIf { it > 0 })
        return when {
            done != null && total != null -> "$done of $total"
            done != null -> "$done downloaded"
            else -> null
        }
    }

    fun item(task: DownloadTask): DownloadItemUi = DownloadItemUi(
        id = task.id,
        title = title(task),
        state = task.state,
        stateLabel = stateLabel(task.state),
        progress = if (task.state == DownloadState.COMPLETED) 1f else task.progressFraction,
        progressText = progressText(task),
        engineLabel = task.engineId.label,
        qualityLabel = qualityLabel(task),
        errorMessage = task.error?.userMessage,
        errorDetail = task.error?.technicalDetail?.takeIf { it.isNotBlank() },
        canDeleteRecord = task.state.isTerminal,
        canCancel = task.state == DownloadState.QUEUED || task.state.isActive,
        canRetry = task.state == DownloadState.FAILED || task.state == DownloadState.CANCELLED,
    )

    /**
     * Adds what is only known while the app runs. Speed and time left are shown only for a downloading item that has them;
     * [waitingNote] ("Waiting for Wi-Fi") is shown only for queued items.
     */
    fun withLive(item: DownloadItemUi, speedBytesPerSecond: Long?, etaSeconds: Long?, stage: String?, waitingNote: String?): DownloadItemUi = when (item.state) {
        DownloadState.DOWNLOADING -> item.copy(speedText = Formatters.speed(speedBytesPerSecond), etaText = Formatters.eta(etaSeconds))
        DownloadState.PROCESSING, DownloadState.PREPARING -> item.copy(note = stage)
        DownloadState.QUEUED -> item.copy(note = waitingNote)
        else -> item
    }

    /** Overall progress is only reported when every running download has a known total. */
    fun summary(tasks: List<DownloadTask>): ActiveSummary {
        val running = tasks.filter { it.state.isActive || it.state == DownloadState.PAUSED }
        val queued = tasks.count { it.state == DownloadState.QUEUED }
        val totals = running.map { it.totalBytes?.takeIf { t -> t > 0 } }
        val progress = if (running.isNotEmpty() && totals.all { it != null }) {
            val total = totals.sumOf { it!! }
            (running.sumOf { it.bytesDownloaded }.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        } else {
            null
        }
        return ActiveSummary(active = running.size, queued = queued, progress = progress)
    }
}
