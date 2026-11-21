package com.viddown.app

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs real host processes (sh, sleep): ProcessRunner has no Android dependency. */
class ProcessRunnerTest {
    private val uncaught = Collections.synchronizedList(mutableListOf<String>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val work = File(System.getProperty("java.io.tmpdir"))

    @Before
    fun recordUncaughtExceptions() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        // On Android an uncaught exception in ANY thread kills the whole instrumentation process, so none may happen.
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught += "${t.name}: $e" }
    }

    @After
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test
    fun capturesOutputAndExitCode() {
        val r = ProcessRunner.run(listOf("sh", "-c", "echo out; echo err 1>&2; exit 3"), emptyMap(), work, timeoutMs = 20_000)
        assertEquals(3, r.exitCode)
        assertFalse(r.timedOut)
        assertEquals("out", r.stdout.trim())
        assertEquals("err", r.stderr.trim())
        assertTrue(uncaught.toString(), uncaught.isEmpty())
    }

    @Test
    fun timeoutKillsHungProcessKeepsPartialOutputAndDoesNotThrow() {
        val started = System.currentTimeMillis()
        val r = ProcessRunner.run(listOf("sh", "-c", "echo partial; exec sleep 300"), emptyMap(), work, timeoutMs = 1_500)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("timedOut expected: $r", r.timedOut)
        assertEquals(-1, r.exitCode)
        assertTrue("partial output must be kept: '${r.stdout}'", r.stdout.contains("partial"))
        assertTrue("run() took too long: $elapsed ms", elapsed < 15_000)
        Thread.sleep(300) // give reader threads a moment to fail, if they are going to
        assertTrue("uncaught exceptions in background threads: $uncaught", uncaught.isEmpty())
    }

    @Test
    fun killStopsAProcessThatIsBlockedOnOutput() {
        val p = ProcessRunner.start(listOf("sleep", "300"), emptyMap(), work)
        assertTrue(ProcessRunner.isAlive(p))
        assertTrue(ProcessRunner.kill(p))
        assertFalse(ProcessRunner.isAlive(p))
    }

    /**
     * Behaves like Android's process: reading stdout blocks until the process is destroyed, and then the blocked read
     * fails with InterruptedIOException("read interrupted") instead of returning end-of-file.
     */
    private class AndroidLikeProcess : Process() {
        private val destroyed = CountDownLatch(1)

        @Volatile
        private var exited = false

        private val blockedStream = object : InputStream() {
            private var sent = false
            override fun read(): Int {
                if (!sent) {
                    sent = true
                    return 'x'.code // one byte of partial output
                }
                destroyed.await()
                throw InterruptedIOException("read interrupted")
            }
        }

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) {}
        }

        override fun getInputStream(): InputStream = blockedStream
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            destroyed.await()
            return 137
        }

        override fun exitValue(): Int {
            if (!exited) throw IllegalThreadStateException("still running")
            return 137
        }

        override fun destroy() {
            exited = true
            destroyed.countDown()
        }
    }

    @Test
    fun timeoutWithReadersInterruptedByDestroyDoesNotCrashAndKeepsPartialOutput() {
        val r = ProcessRunner.collect(AndroidLikeProcess(), timeoutMs = 600)
        assertTrue("timedOut expected: $r", r.timedOut)
        assertEquals(-1, r.exitCode)
        assertTrue("partial output must be kept: '${r.stdout}'", r.stdout.contains("x"))
        Thread.sleep(300)
        assertTrue("uncaught exceptions in background threads: $uncaught", uncaught.isEmpty())
    }
}
