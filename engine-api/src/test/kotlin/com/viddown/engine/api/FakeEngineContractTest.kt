package com.viddown.engine.api

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tiny engine that follows the DownloadEngine contract. It documents the contract and is the template for real engine tests. */
private class FakeEngine : DownloadEngine {
    override val id = EngineId.NEWPIPE
    override val displayName = "Fake"
    override val description = "Test engine"
    override val capabilities = EngineCapabilities(setOf(MediaType.DIRECT_MP4), supportsResume = false, listsFormats = false, mayNeedFfmpeg = false)
    var cleaned = false
    var closed = 0

    override fun availability(): EngineAvailability = EngineAvailability.Available

    override suspend fun inspect(request: MediaRequest): EngineResult<MediaInfo> = EngineResult.Success(MediaInfo(id, request.url))

    override suspend fun listFormats(request: MediaRequest): EngineResult<List<FormatOption>> = EngineResult.Success(emptyList())

    override fun download(task: DownloadTask, destinationDir: String): Flow<DownloadEvent> = flow {
        try {
            emit(DownloadEvent.Started)
            emit(DownloadEvent.Progress(100, 1000))
            delay(60_000) // pretend to transfer for a long time
            emit(DownloadEvent.Completed("$destinationDir/out.mp4", 1000))
        } finally {
            cleaned = true // temp files removed, processes killed
        }
    }

    override suspend fun close() {
        closed++
    }
}

class FakeEngineContractTest {
    private val task = DownloadTask("t", EngineId.NEWPIPE, MediaRequest("https://x.test/a.mp4"), null, createdAtMillis = 0)

    @Test
    fun cancellingTheCollectorStopsTheDownloadAndCleansUp() = runBlocking {
        val engine = FakeEngine()
        val seen = mutableListOf<DownloadEvent>()
        val job = launch { engine.download(task, "/tmp").collect { seen += it } }
        withTimeout(5_000) { while (seen.size < 2) delay(10) }
        job.cancelAndJoin()
        assertTrue("cleanup must run when the collector is cancelled", engine.cleaned)
        assertFalse(seen.any { it is DownloadEvent.Completed })
    }

    @Test
    fun downloadIsColdUntilCollected() = runBlocking {
        val engine = FakeEngine()
        engine.download(task, "/tmp") // never collected
        assertFalse(engine.cleaned)
        assertEquals(EngineResult.Success(MediaInfo(EngineId.NEWPIPE, "https://x.test/a.mp4")), engine.inspect(task.request))
    }

    @Test
    fun firstEventIsStartedAndCloseIsIdempotent() = runBlocking {
        val engine = FakeEngine()
        assertEquals(DownloadEvent.Started, engine.download(task, "/tmp").first())
        engine.close()
        engine.close()
        assertEquals(2, engine.closed)
        assertTrue(engine.availability().isAvailable)
    }
}
