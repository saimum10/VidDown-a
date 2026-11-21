package com.viddown.core.model

import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.StreamKind

enum class LibrarySort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name"),
    SIZE("Largest first"),
}

enum class LibraryFilter(val label: String) {
    ALL("All"),
    VIDEO("Video"),
    AUDIO("Audio"),
}

/** Search, filter and sort over the completed downloads. Pure functions: the screen only displays the result. */
object LibraryQuery {
    private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "oga", "wma")

    fun isAudio(task: DownloadTask): Boolean {
        val format = task.format
        if (format?.kind == StreamKind.AUDIO_ONLY) return true
        if (format != null && format.kind != StreamKind.ORIGINAL_FILE) return false
        val ext = (format?.container ?: Formatters.fileNameFromPath(task.outputPath)?.substringAfterLast('.', "") ?: "").lowercase()
        return ext in audioExtensions
    }

    fun apply(tasks: List<DownloadTask>, search: String, sort: LibrarySort, filter: LibraryFilter): List<DownloadTask> {
        val needle = search.trim().lowercase()
        val matching = tasks.filter { it.state == DownloadState.COMPLETED }
            .filter { t ->
                when (filter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.AUDIO -> isAudio(t)
                    LibraryFilter.VIDEO -> !isAudio(t)
                }
            }
            .filter { needle.isEmpty() || DownloadUi.title(it).lowercase().contains(needle) }
        return when (sort) {
            LibrarySort.NEWEST -> matching.sortedByDescending { it.createdAtMillis }
            LibrarySort.OLDEST -> matching.sortedBy { it.createdAtMillis }
            LibrarySort.NAME -> matching.sortedBy { DownloadUi.title(it).lowercase() }
            LibrarySort.SIZE -> matching.sortedByDescending { it.bytesDownloaded }
        }
    }
}
