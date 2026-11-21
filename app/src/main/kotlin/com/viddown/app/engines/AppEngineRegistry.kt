package com.viddown.app.engines

import android.content.Context
import com.viddown.app.Abi
import com.viddown.app.BuildConfig
import com.viddown.engine.api.DownloadEngine
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineId
import com.viddown.engine.newpipe.NewPipeEngine
import com.viddown.engine.newpipe.adapter.NewPipeEngines
import com.viddown.engine.selector.EngineDescriptor
import com.viddown.engine.selector.EngineRegistry
import com.viddown.engine.ytdlp.FfmpegStreamMerger
import com.viddown.engine.ytdlp.YtDlpEngine

/**
 * The engines this build knows. An engine is only "Available" when its adapter is really integrated and tested.
 * NewPipe and yt-dlp are integrated; yt-dlp is only available when this APK packages a
 * native runtime for the device's ABI (YtDlpSupport).
 */
class AppEngineRegistry(private val context: Context) : EngineRegistry {

    /**
     * NewPipe merges separate video and audio streams (its qualities above the site's single-file ones) with the bundle's FFmpeg, which
     * only exists when this APK packages a native runtime for the device. Without it NewPipe offers single-file qualities only.
     * Checking this starts nothing.
     */
    private val canMerge: Boolean get() = Abi.select() != null

    override val descriptors: List<EngineDescriptor> = listOf(
        EngineDescriptor(EngineId.NEWPIPE, NewPipeEngine.describe(canMerge)) { EngineAvailability.Available },
        EngineDescriptor(EngineId.YTDLP, YtDlpSupport.description()) { YtDlpSupport.availability() },
    )

    override fun create(id: EngineId): DownloadEngine = when (id) {
        // Only FFmpeg is ever started for NewPipe (AndroidFfmpegTools): no Python, no yt-dlp, and nothing runs until a merge is needed.
        EngineId.NEWPIPE -> NewPipeEngines.create(
            merger = if (canMerge) FfmpegStreamMerger(AndroidFfmpegTools(context.applicationContext)) else null,
        )
        // Creating the engine starts nothing: the native runtime is verified and extracted on the engine's first use.
        EngineId.YTDLP -> YtDlpEngine(
            tools = AndroidNativeTools(context.applicationContext),
            availabilityProvider = { YtDlpSupport.availability() },
            allowLoopbackHttp = BuildConfig.DEBUG,
        )
    }
}
