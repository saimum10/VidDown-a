package com.viddown.app.engines

import android.content.Context
import com.viddown.app.Abi
import com.viddown.app.NativeManifest
import com.viddown.app.ProcessRunner
import com.viddown.app.Sha256
import com.viddown.app.engines.AndroidNativeTools.AndroidProcess
import com.viddown.engine.ytdlp.NativeTools
import com.viddown.engine.ytdlp.NativeToolsException
import com.viddown.engine.ytdlp.RunningProcess
import com.viddown.engine.ytdlp.Tool
import java.io.File
import java.io.IOException

/**
 * Only FFmpeg of the verified native bundle, for engines that merge streams but are not the yt-dlp engine (NewPipe).
 *
 * Unlike [AndroidNativeTools] this never extracts the Python standard library or the yt-dlp packages: [prepare] only checks the
 * executables' pinned SHA-256 and creates the small working folders, and Python/yt-dlp can never be started through it.
 * Creating the object touches nothing.
 */
class AndroidFfmpegTools(private val context: Context) : NativeTools {
    private var ready = false
    private val nativeLibDir get() = File(context.applicationInfo.nativeLibraryDir)
    private val homeDir get() = File(context.filesDir, "home")
    private val tmpDir get() = File(context.cacheDir, "runtime-tmp")
    private val workDir get() = File(context.filesDir, "work")

    @Synchronized
    override fun prepare() {
        if (ready) return
        val abi = Abi.select() ?: throw NativeToolsException("This build has no FFmpeg for this device's processor")
        val hashes = try {
            NativeManifest.load(context).hashesFor(abi)
        } catch (e: IOException) {
            throw NativeToolsException("The native manifest could not be read: ${e.message}", e)
        } catch (e: RuntimeException) {
            throw NativeToolsException("The native manifest could not be read: ${e.message}", e)
        }
        val expected = hashes.entries.firstOrNull { it.key.startsWith("jniLibs/") && File(it.key).name == FFMPEG }?.value
            ?: throw NativeToolsException("The native manifest has no entry for $FFMPEG")
        val file = File(nativeLibDir, FFMPEG)
        if (!file.isFile) throw NativeToolsException("$FFMPEG is missing from this app")
        // Never run an unverified executable.
        if (Sha256.hex(file) != expected) throw NativeToolsException("$FFMPEG failed its SHA-256 check")
        homeDir.mkdirs()
        tmpDir.mkdirs()
        workDir.mkdirs()
        ready = true
    }

    @Synchronized
    override fun start(tool: Tool, args: List<String>): RunningProcess {
        if (!ready) throw NativeToolsException("FFmpeg has not been prepared")
        val executable = when (tool) {
            Tool.FFMPEG -> FFMPEG
            // Only FFmpeg's hash is checked in prepare(), so nothing else may be started from here.
            Tool.FFPROBE, Tool.YT_DLP -> throw NativeToolsException("${tool.name} is not available here: only FFmpeg is")
        }
        val environment = mapOf(
            "LD_LIBRARY_PATH" to nativeLibDir.absolutePath,
            "HOME" to homeDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
        )
        val process = try {
            ProcessRunner.start(listOf(File(nativeLibDir, executable).path) + args, environment, workDir)
        } catch (e: IOException) {
            throw NativeToolsException("${tool.name} could not be started: ${e.message}", e)
        }
        return AndroidProcess(process)
    }

    private companion object {
        const val FFMPEG = "libffmpeg.so"
    }
}
