package com.viddown.engine.ytdlp

import com.viddown.engine.api.MergeResult
import com.viddown.engine.api.StreamMerger
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bundle's FFmpeg as a [StreamMerger] for engines other than yt-dlp (the NewPipe engine merges its separate video and audio
 * streams with it). It starts FFmpeg only: no Python and no yt-dlp. [tools] may refuse every other tool.
 *
 * Creating this object starts nothing; [NativeTools.prepare] runs on the first merge.
 */
class FfmpegStreamMerger(
    private val tools: NativeTools,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StreamMerger {
    private val runner = ToolRunner(tools, ioDispatcher)
    private val ffmpeg = FfmpegProcessor(runner)

    @Volatile
    private var prepared = false

    override suspend fun merge(video: File, audio: File, output: File): MergeResult {
        try {
            if (!prepared) {
                withContext(ioDispatcher) { tools.prepare() }
                prepared = true
            }
            return when (val r = ffmpeg.merge(video, audio, output)) {
                StepResult.Ok -> MergeResult.Done
                is StepResult.Failed -> MergeResult.Failed(r.detail)
            }
        } catch (e: NativeToolsException) {
            output.delete()
            return MergeResult.Failed(e.message ?: "FFmpeg could not be started")
        }
    }

    override fun close() {
        runner.killAll()
    }
}
