package com.viddown.app.engines

import android.content.Context
import com.viddown.app.ProcessRunner
import com.viddown.app.RuntimeInstaller
import com.viddown.engine.ytdlp.NativeTools
import com.viddown.engine.ytdlp.NativeToolsException
import com.viddown.engine.ytdlp.RunningProcess
import com.viddown.engine.ytdlp.Tool
import java.io.IOException
import java.io.InputStream

/**
 * The verified native bundle as child processes for the yt-dlp engine. Creating this object touches nothing: the first call of
 * [prepare] verifies every packaged file against its pinned SHA-256 and extracts the Python stdlib and the yt-dlp packages
 * (RuntimeInstaller); only then can a tool be started. Only API-24-safe process calls are used (ProcessRunner).
 */
class AndroidNativeTools(
    private val context: Context,
    /** Verifies and extracts the runtime. Replaceable so tests can simulate a runtime that fails its check. */
    private val install: (Context) -> RuntimeInstaller = { RuntimeInstaller(it).also { ready -> ready.install() } },
) : NativeTools {
    private var installer: RuntimeInstaller? = null

    @Synchronized
    override fun prepare() {
        if (installer != null) return
        val created = try {
            install(context)
        } catch (e: IOException) {
            throw failed(e)
        } catch (e: RuntimeException) {
            throw failed(e)
        }
        installer = created
        YtDlpSupport.reportRuntimeOk()
    }

    /** A runtime that cannot be verified, extracted or started makes the engine unavailable (with the reason) instead of failing again and again. */
    private fun failed(e: Exception): NativeToolsException {
        val message = e.message ?: "The native runtime could not be prepared"
        YtDlpSupport.reportRuntimeProblem(message)
        return NativeToolsException(message, e)
    }

    @Synchronized
    override fun start(tool: Tool, args: List<String>): RunningProcess {
        val ready = installer ?: throw NativeToolsException("The native runtime has not been prepared")
        val command = when (tool) {
            Tool.YT_DLP -> listOf(ready.nativeFile("libviddown_python.so").path, "-m", "yt_dlp")
            Tool.FFMPEG -> listOf(ready.nativeFile("libffmpeg.so").path)
            Tool.FFPROBE -> listOf(ready.nativeFile("libffprobe.so").path)
        } + args
        // Unbuffered UTF-8 text on the pipes: progress lines arrive at once and titles in any script never break printing.
        val environment = ready.environment() + mapOf(
            "PYTHONUNBUFFERED" to "1",
            "PYTHONIOENCODING" to "utf-8",
            "PYTHONUTF8" to "1",
        )
        val process = try {
            ProcessRunner.start(command, environment, ready.workDir)
        } catch (e: IOException) {
            throw NativeToolsException("${tool.name} could not be started: ${e.message}", e)
        }
        return AndroidProcess(process)
    }

    internal class AndroidProcess(private val process: Process) : RunningProcess {
        init {
            try {
                process.outputStream.close() // no tool ever waits for input
            } catch (e: IOException) {
                // already closed
            }
        }

        override val stdout: InputStream get() = process.inputStream
        override val stderr: InputStream get() = process.errorStream

        override fun exitCodeOrNull(): Int? = try {
            process.exitValue()
        } catch (e: IllegalThreadStateException) {
            null
        }

        override fun kill() {
            process.destroy() // Android's Process.destroy() sends SIGKILL
        }
    }
}
