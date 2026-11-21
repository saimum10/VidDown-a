package com.viddown.engine.api

import java.io.File

/** What a [StreamMerger] run left behind. */
sealed interface MergeResult {
    /** The output file exists and is not empty. */
    data object Done : MergeResult

    /** [detail] is for the technical details of the error, never shown as the main message. */
    data class Failed(val detail: String) : MergeResult
}

/**
 * Puts one finished video-only file and one finished audio-only file into one file WITHOUT re-encoding (stream copy).
 *
 * It is a helper of an engine, never an engine: an engine that is handed one only calls it after both streams have arrived. The
 * container (mp4, webm, mkv) follows the extension of the output file. Implementations must stop their work when the calling
 * coroutine is cancelled and must delete a half-written output.
 */
interface StreamMerger {
    suspend fun merge(video: File, audio: File, output: File): MergeResult

    /** Stops anything still running. Safe to call more than once. */
    fun close() {}
}
