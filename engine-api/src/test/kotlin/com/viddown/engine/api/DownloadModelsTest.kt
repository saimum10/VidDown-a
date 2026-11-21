package com.viddown.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DownloadModelsTest {
    private fun task(state: DownloadState = DownloadState.QUEUED, done: Long = 0, total: Long? = null) = DownloadTask(
        id = "t1", engineId = EngineId.NEWPIPE, request = MediaRequest("https://x.test/a.mp4"), format = null,
        state = state, bytesDownloaded = done, totalBytes = total, createdAtMillis = 1L,
    )

    @Test
    fun happyPathIsAllowed() {
        var t = task()
        for (next in listOf(DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.PROCESSING, DownloadState.COMPLETED)) {
            t = t.transitionTo(next)
        }
        assertEquals(DownloadState.COMPLETED, t.state)
    }

    @Test
    fun completedCannotBeLeft() {
        for (next in DownloadState.entries) assertFalse("COMPLETED -> $next", DownloadState.COMPLETED.canTransitionTo(next))
    }

    @Test
    fun failedAndCancelledCanOnlyBeRetriedBackToQueued() {
        for (from in listOf(DownloadState.FAILED, DownloadState.CANCELLED)) {
            for (next in DownloadState.entries) assertEquals("$from -> $next", next == DownloadState.QUEUED, from.canTransitionTo(next))
        }
    }

    @Test
    fun cannotSkipPreparingOrCompleteFromQueued() {
        assertFalse(DownloadState.QUEUED.canTransitionTo(DownloadState.DOWNLOADING))
        assertFalse(DownloadState.QUEUED.canTransitionTo(DownloadState.COMPLETED))
    }

    @Test
    fun processingMustFinishBeforeCompleted() {
        assertFalse(DownloadState.PREPARING.canTransitionTo(DownloadState.COMPLETED))
        assertTrue(DownloadState.PROCESSING.canTransitionTo(DownloadState.COMPLETED))
    }

    @Test
    fun illegalTransitionThrows() {
        try {
            task(DownloadState.COMPLETED).transitionTo(DownloadState.DOWNLOADING)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("COMPLETED -> DOWNLOADING"))
        }
    }

    @Test
    fun anInterruptedActiveDownloadCanGoBackToQueued() {
        for (from in listOf(DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.PROCESSING)) {
            assertTrue("$from -> QUEUED", from.canTransitionTo(DownloadState.QUEUED))
        }
        assertFalse(DownloadState.QUEUED.canTransitionTo(DownloadState.QUEUED))
        assertFalse(DownloadState.COMPLETED.canTransitionTo(DownloadState.QUEUED))
    }

    @Test
    fun terminalAndActiveFlags() {
        assertTrue(DownloadState.FAILED.isTerminal)
        assertTrue(DownloadState.DOWNLOADING.isActive)
        assertFalse(DownloadState.QUEUED.isActive)
        assertFalse(DownloadState.PAUSED.isTerminal)
    }

    @Test
    fun progressFractionIsNullWhenTotalUnknownAndClamped() {
        assertNull(task(done = 500, total = null).progressFraction)
        assertNull(task(done = 500, total = 0).progressFraction)
        assertEquals(0.5f, task(done = 500, total = 1000).progressFraction!!, 0.0001f)
        assertEquals(1.0f, task(done = 2000, total = 1000).progressFraction!!, 0.0001f)
    }
}
