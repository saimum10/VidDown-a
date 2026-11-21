package com.viddown.engine.ytdlp

import java.io.File
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal sealed interface StepResult {
    data object Ok : StepResult
    data class Failed(val detail: String) : StepResult
}

/**
 * FFmpeg as a helper (never an engine): merging separate video and audio streams, remuxing, and ffprobe checks of local files.
 * This build's FFmpeg has no network, no video encoder and no MP3/Opus encoder (docs/native-bundle.md section 6), so everything
 * here is stream copy between containers it can write: mp4, webm and Matroska. Only one FFmpeg run happens at a time.
 */
internal class FfmpegProcessor(private val runner: ToolRunner) {

    suspend fun probe(file: File): MediaProbe? {
        val result = limited { runner.run(Tool.FFPROBE, ProbeParser.args(file.absolutePath), timeoutMs = PROBE_TIMEOUT_MS) }
        if (!result.ok) return null
        return ProbeParser.parse(result.stdout)
    }

    /** Puts one video file and one audio file into [out] (the container follows out's extension) without re-encoding. */
    suspend fun merge(video: File, audio: File, out: File): StepResult {
        val args = base() + listOf(
            "-i", video.absolutePath,
            "-i", audio.absolutePath,
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-c", "copy",
        ) + containerFlags(out) + out.absolutePath
        return finish(limited { runner.run(Tool.FFMPEG, args, timeoutMs = PROCESS_TIMEOUT_MS) }, out)
    }

    /** Copies the audio and video of [input] into [out] without re-encoding (data and subtitle streams are left out). */
    suspend fun remux(input: File, out: File): StepResult {
        val args = base() + listOf(
            "-i", input.absolutePath,
            "-map", "0:v?",
            "-map", "0:a?",
            "-c", "copy",
        ) + containerFlags(out) + out.absolutePath
        return finish(limited { runner.run(Tool.FFMPEG, args, timeoutMs = PROCESS_TIMEOUT_MS) }, out)
    }

    private fun base(): List<String> = listOf("-hide_banner", "-nostdin", "-y", "-loglevel", "error")

    private fun containerFlags(out: File): List<String> =
        if (out.name.lowercase().endsWith(".mp4")) listOf("-movflags", "+faststart") else emptyList()

    private fun finish(result: ToolResult, out: File): StepResult {
        if (result.ok && out.isFile && out.length() > 0) return StepResult.Ok
        out.delete() // a half-written output must never be mistaken for a result
        val why = when {
            result.timedOut -> "FFmpeg did not finish in time and was stopped."
            result.ok -> "FFmpeg finished but wrote no output file."
            else -> "FFmpeg exited with code ${result.exitCode}."
        }
        val said = result.stderr.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(4).joinToString(" | ")
        return StepResult.Failed(if (said.isEmpty()) why else "$why $said")
    }

    private suspend fun <T> limited(block: suspend () -> T): T = gate.withPermit { block() }

    private companion object {
        const val PROBE_TIMEOUT_MS = 60_000L
        const val PROCESS_TIMEOUT_MS = 30 * 60_000L

        /** FFmpeg and ffprobe never run in parallel, within the process: the helper must not compete with downloads for memory. */
        val gate = Semaphore(1)
    }
}
