package com.viddown.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.StreamKind
import com.viddown.engine.api.SuggestedAction

/**
 * One row per download. The engine and the chosen format are stored with the record and never rewritten, so the UI can always
 * say which engine produced a file. Request headers are deliberately NOT stored: they may contain cookies or tokens.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val pageUrl: String?,
    val title: String?,
    val durationSeconds: Long?,
    val engine: String,
    val state: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val outputPath: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    // chosen format (all null when no format was chosen)
    val formatId: String?,
    val formatKind: String?,
    val formatContainer: String?,
    val formatWidth: Int?,
    val formatHeight: Int?,
    val formatVideoCodec: String?,
    val formatAudioCodec: String?,
    val formatSizeBytes: Long?,
    val formatSizeIsEstimate: Boolean,
    val formatRequiresMerge: Boolean,
    // last error (all null when there is none)
    val errorKind: String?,
    val errorUserMessage: String?,
    val errorTechnicalDetail: String?,
    val errorAction: String?,
    val errorHttpStatus: Int?,
    // subtitle language asked for (null when none); added in database version 2
    val subtitleLang: String? = null,
)

fun DownloadTask.toEntity(nowMillis: Long): DownloadEntity = DownloadEntity(
    id = id,
    url = request.url,
    pageUrl = request.pageUrl,
    title = title,
    durationSeconds = durationSeconds,
    engine = engineId.storageKey,
    state = state.name,
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    outputPath = outputPath,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = nowMillis,
    formatId = format?.id,
    formatKind = format?.kind?.name,
    formatContainer = format?.container,
    formatWidth = format?.width,
    formatHeight = format?.height,
    formatVideoCodec = format?.videoCodec,
    formatAudioCodec = format?.audioCodec,
    formatSizeBytes = format?.sizeBytes,
    formatSizeIsEstimate = format?.sizeIsEstimate ?: false,
    formatRequiresMerge = format?.requiresMerge ?: false,
    errorKind = error?.kind?.name,
    errorUserMessage = error?.userMessage,
    errorTechnicalDetail = error?.technicalDetail,
    errorAction = error?.suggestedAction?.name,
    errorHttpStatus = error?.httpStatus,
    subtitleLang = subtitleLang,
)

/** Returns null when the row cannot be understood (for example an engine id from a newer app version). */
fun DownloadEntity.toTask(): DownloadTask? {
    val engineId = EngineId.fromStorageKey(engine) ?: return null
    val taskState = DownloadState.entries.firstOrNull { it.name == state } ?: return null
    val format = formatId?.let { id ->
        FormatOption(
            id = id,
            kind = StreamKind.entries.firstOrNull { it.name == formatKind } ?: StreamKind.ORIGINAL_FILE,
            container = formatContainer,
            width = formatWidth,
            height = formatHeight,
            videoCodec = formatVideoCodec,
            audioCodec = formatAudioCodec,
            sizeBytes = formatSizeBytes,
            sizeIsEstimate = formatSizeIsEstimate,
            requiresMerge = formatRequiresMerge,
        )
    }
    val error = errorKind?.let { kindName ->
        val kind = ErrorKind.entries.firstOrNull { it.name == kindName } ?: return@let null
        DownloadError(
            kind = kind,
            userMessage = errorUserMessage.orEmpty(),
            technicalDetail = errorTechnicalDetail.orEmpty(),
            suggestedAction = SuggestedAction.entries.firstOrNull { it.name == errorAction } ?: SuggestedAction.NONE,
            engineId = engineId,
            httpStatus = errorHttpStatus,
        )
    }
    return DownloadTask(
        id = id,
        engineId = engineId,
        request = MediaRequest(url = url, pageUrl = pageUrl),
        format = format,
        title = title,
        durationSeconds = durationSeconds,
        state = taskState,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        outputPath = outputPath,
        error = error,
        createdAtMillis = createdAtMillis,
        subtitleLang = subtitleLang,
    )
}
