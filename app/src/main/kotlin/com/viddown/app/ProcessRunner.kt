package com.viddown.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

data class ProcResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/** Starts child processes. Only API 24 compatible calls are used on purpose (no waitFor(timeout), isAlive, destroyForcibly). */
object ProcessRunner {
    fun start(command: List<String>, env: Map<String, String>, workDir: File): Process {
        val builder = ProcessBuilder(command)
        builder.directory(workDir)
        builder.environment().putAll(env)
        return builder.start()
    }

    fun isAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (e: IllegalThreadStateException) {
        true
    }

    /** Waits until the process exits or [timeoutMs] passes. Returns true if it exited. */
    fun waitUntilExit(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (isAlive(process)) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(25)
        }
        return true
    }

    /** Kills the process (Android's Process.destroy() sends SIGKILL) and waits for it to disappear. */
    fun kill(process: Process, waitMs: Long = 5_000): Boolean {
        process.destroy()
        return waitUntilExit(process, waitMs)
    }

    fun run(command: List<String>, env: Map<String, String>, workDir: File, timeoutMs: Long = 120_000): ProcResult =
        collect(start(command, env, workDir), timeoutMs)

    /**
     * Reads [stream] into [sink] until end-of-file. When the process is killed, Android closes its streams under a blocked
     * read and the read fails with an IOException (InterruptedIOException: "read interrupted"). That is expected, not an
     * error: it ends the loop and everything read so far stays in [sink]. Nothing may escape this thread, because an
     * uncaught exception in any thread kills the whole app (or instrumentation) process.
     */
    private fun drain(stream: InputStream, sink: ByteArrayOutputStream): Thread = thread(isDaemon = true) {
        try {
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                sink.write(buffer, 0, n)
            }
        } catch (e: IOException) {
            // stream closed because the process was killed or destroyed
        }
    }

    /** Collects output of an already started process, enforcing [timeoutMs]. Separate from [run] so it can be tested with a fake Process. */
    fun collect(process: Process, timeoutMs: Long): ProcResult {
        try {
            process.outputStream.close()
        } catch (e: IOException) {
            // stdin already closed
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val outThread = drain(process.inputStream, out)
        val errThread = drain(process.errorStream, err)
        val exited = waitUntilExit(process, timeoutMs)
        if (!exited) kill(process)
        // A grandchild that inherited the pipe can keep it open after the main process died; never wait for it for long.
        outThread.join(5_000)
        errThread.join(5_000)
        val code = if (exited) process.exitValue() else -1
        return ProcResult(code, String(out.toByteArray(), Charsets.UTF_8), String(err.toByteArray(), Charsets.UTF_8), !exited)
    }
}
