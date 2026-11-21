package com.viddown.engine.ytdlp

import java.io.InputStream

/** The three executables of the verified native bundle that this engine uses. */
enum class Tool { YT_DLP, FFMPEG, FFPROBE }

/** A started child process. The implementation must close the process' standard input, so that no tool ever waits for it. */
interface RunningProcess {
    val stdout: InputStream
    val stderr: InputStream

    /** The exit code, or null while the process is still running. */
    fun exitCodeOrNull(): Int?

    /** Kills the process (SIGKILL on Android). Safe to call more than once and after the process ended. */
    fun kill()
}

/** The native runtime cannot be used: missing for this device, failed verification, could not be extracted or could not start. */
class NativeToolsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The seam between this module and the Android runtime of the native bundle. :app implements it with the verified, extracted
 * CPython + yt-dlp + FFmpeg; tests implement it with scripted fake processes.
 */
interface NativeTools {
    /**
     * Verifies every packaged native file against its pinned SHA-256 and extracts what must be extracted (idempotent). Blocking:
     * call it off the main thread. It is the first thing that touches the native runtime, so an engine that is never used never
     * calls it. Throws [NativeToolsException] when the runtime cannot be used.
     */
    fun prepare()

    /**
     * Starts [tool] with [args] (for [Tool.YT_DLP] the arguments after `-m yt_dlp`). No shell is involved: every element is
     * one argument. Throws [NativeToolsException] when the process cannot be started. [prepare] has succeeded before.
     */
    fun start(tool: Tool, args: List<String>): RunningProcess
}
