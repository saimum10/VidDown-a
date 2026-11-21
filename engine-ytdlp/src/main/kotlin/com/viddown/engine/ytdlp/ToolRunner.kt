package com.viddown.engine.ytdlp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What a finished tool run left behind. [stdout] and [stderr] hold what was read before the run ended (possibly cut, see [truncated]). */
internal data class ToolResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    /** No output at all for longer than the idle limit: the process was killed. */
    val stalled: Boolean,
    /** [stdout] reached its size limit and the rest was dropped. */
    val truncated: Boolean,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut && !stalled
}

/**
 * Runs the native tools as child processes and takes care of everything around them:
 *  * output is read on helper threads that can never throw (an uncaught exception in a helper thread would kill the app),
 *  * the process is killed when the caller is cancelled, on timeout and when it stops printing for too long,
 *  * [killAll] ends every process that is still running (used when the engine is closed).
 * Nothing here logs: command lines contain page addresses that may carry tokens.
 */
internal class ToolRunner(
    private val tools: NativeTools,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pollMs: Long = 25,
) {
    private val running = LinkedHashSet<RunningProcess>()

    suspend fun run(
        tool: Tool,
        args: List<String>,
        timeoutMs: Long,
        idleTimeoutMs: Long = 0,
        maxStdoutBytes: Int = DEFAULT_MAX_STDOUT_BYTES,
        onStdoutLine: ((String) -> Unit)? = null,
        onStderrLine: ((String) -> Unit)? = null,
    ): ToolResult = withContext(ioDispatcher) {
        val process = try {
            tools.start(tool, args)
        } catch (e: NativeToolsException) {
            throw e
        } catch (e: IOException) {
            throw NativeToolsException("Could not start ${tool.name}: ${e.message}", e)
        }
        synchronized(running) { running.add(process) }
        val activity = AtomicLong(clock())
        val out = StreamCollector(process.stdout, maxStdoutBytes, onStdoutLine, activity, clock)
        val err = StreamCollector(process.stderr, MAX_STDERR_BYTES, onStderrLine, activity, clock)
        out.start()
        err.start()

        var timedOut = false
        var stalled = false
        val started = clock()
        try {
            while (process.exitCodeOrNull() == null) {
                val now = clock()
                if (timeoutMs > 0 && now - started > timeoutMs) {
                    timedOut = true
                    break
                }
                if (idleTimeoutMs > 0 && now - activity.get() > idleTimeoutMs) {
                    stalled = true
                    break
                }
                delay(pollMs)
            }
        } finally {
            // Also runs when the caller was cancelled: a cancelled download must not leave a process behind.
            withContext(NonCancellable) {
                if (process.exitCodeOrNull() == null) {
                    process.kill()
                    val deadline = clock() + KILL_WAIT_MS
                    while (process.exitCodeOrNull() == null && clock() < deadline) delay(pollMs)
                }
                // A grandchild that inherited a pipe can keep it open after the main process died: never wait long for it.
                out.join(READER_JOIN_MS)
                err.join(READER_JOIN_MS)
                synchronized(running) { running.remove(process) }
            }
        }
        ToolResult(
            exitCode = process.exitCodeOrNull() ?: -1,
            stdout = out.text(),
            stderr = err.text(),
            timedOut = timedOut,
            stalled = stalled,
            truncated = out.truncated,
        )
    }

    /** Kills every process that is still running. */
    fun killAll() {
        val all = synchronized(running) { running.toList() }
        for (p in all) {
            try {
                p.kill()
            } catch (e: RuntimeException) {
                // already gone
            }
        }
    }

    private class StreamCollector(
        private val stream: InputStream,
        private val maxBytes: Int,
        private val onLine: ((String) -> Unit)?,
        private val activity: AtomicLong,
        private val clock: () -> Long,
    ) {
        private val all = ByteArrayOutputStream()

        @Volatile
        var truncated = false
            private set

        private val thread = Thread({ pump() }, "viddown-tool-reader").apply { isDaemon = true }

        fun start() = thread.start()

        fun join(ms: Long) {
            try {
                thread.join(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        fun text(): String = synchronized(all) { String(all.toByteArray(), Charsets.UTF_8) }

        private fun pump() {
            try {
                val buffer = ByteArray(8 * 1024)
                val line = ByteArrayOutputStream()
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    activity.set(clock())
                    synchronized(all) {
                        val room = (maxBytes - all.size()).coerceAtLeast(0)
                        if (room > 0) all.write(buffer, 0, minOf(room, n))
                        if (n > room) truncated = true
                    }
                    if (onLine != null) {
                        for (i in 0 until n) {
                            val b = buffer[i]
                            if (b == NEWLINE) {
                                emitLine(line)
                            } else if (line.size() < MAX_LINE_BYTES) {
                                line.write(b.toInt())
                            }
                        }
                    }
                }
                if (onLine != null && line.size() > 0) emitLine(line)
            } catch (e: IOException) {
                // The stream was closed because the process was killed or ended: expected, not an error.
            } catch (e: RuntimeException) {
                // Nothing may escape a helper thread.
            }
        }

        private fun emitLine(line: ByteArrayOutputStream) {
            val text = String(line.toByteArray(), Charsets.UTF_8).trimEnd('\r')
            line.reset()
            try {
                onLine?.invoke(text)
            } catch (e: RuntimeException) {
                // a faulty callback must not stop the reader
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_STDOUT_BYTES = 48 * 1024 * 1024
        private const val MAX_STDERR_BYTES = 256 * 1024
        private const val MAX_LINE_BYTES = 16 * 1024
        private const val KILL_WAIT_MS = 5_000L
        private const val READER_JOIN_MS = 2_000L
        private val NEWLINE: Byte = 10
    }
}
