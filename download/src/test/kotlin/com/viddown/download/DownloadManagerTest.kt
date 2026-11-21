package com.viddown.download

import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.DownloadEvent
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineMode
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.StreamKind
import com.viddown.engine.selector.EngineSelector
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadManagerTest {
    private lateinit var tempRoot: File
    private lateinit var scope: CoroutineScope
    private val store = FakeStore()
    private val output = FakeOutput()
    private val settings = MutableStateFlow(ManagerSettings(maxConcurrent = 2, wifiOnly = false))
    private val network = MutableStateFlow(NetworkState.WIFI)
    private val selected = MutableStateFlow<EngineMode?>(EngineMode.NEWPIPE)
    private val unexpected = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private var clockValue = 1_000L
    private var idCounter = 0

    @Before fun setUp() {
        tempRoot = java.nio.file.Files.createTempDirectory("viddown-mgr").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        // Cancelling is not enough: the manager's own cleanup of a download's temp folder runs while it is being cancelled.
        // Wait for it to finish (bounded, so a stuck test cannot hang the build) before the folder is deleted here.
        runBlocking { withTimeoutOrNull(10_000) { scope.coroutineContext[Job]?.cancelAndJoin() } }
        tempRoot.deleteRecursively()
    }

    private fun manager(registry: FakeRegistry, persistMs: Long = 0): DownloadManager = DownloadManager(
        store = store, engines = EngineSelector(registry, selected), output = output, tempRoot = File(tempRoot, "dl"),
        settings = settings, network = network, scope = scope,
        clock = { ++clockValue }, newId = { "id${++idCounter}" }, persistIntervalMs = persistMs,
        onUnexpectedError = { unexpected += it },
    )

    private fun registryOf(engine: FakeEngine) = FakeRegistry(mapOf(engine.id to engine))
    private fun req(n: Int = 1) = MediaRequest("https://x.test/$n.mp4")
    private fun stateOf(id: String) = store.tasks[id]?.state

    private fun assertLegalHistory() {
        for ((id, states) in store.history) {
            val s = states.toList()
            for (i in 1 until s.size) {
                assertTrue("$id: ${s[i - 1]} -> ${s[i]} in $s", s[i - 1] == s[i] || s[i - 1].canTransitionTo(s[i]))
            }
        }
    }

    private fun blockingEngine(gate: CompletableDeferred<Unit>, size: Int = 100) = FakeEngine { task, dir ->
        flow {
            emit(DownloadEvent.Started)
            emit(DownloadEvent.Progress(10, size.toLong()))
            gate.await()
            dir.mkdirs()
            File(dir, "f.mp4").writeBytes(ByteArray(size))
            emit(DownloadEvent.Completed(File(dir, "f.mp4").absolutePath, size.toLong()))
        }
    }

    // ------------------------------------------------------------------------------------------ happy path

    @Test fun aDownloadGoesThroughTheStatesAndEndsCompletedOnlyAfterSaving() = runBlocking<Unit> {
        val engine = FakeEngine.succeeding(size = 2048, name = "movie.mp4")
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(req(), null, title = "My movie") as EnqueueResult.Queued).id

        eventually(message = "completed") { stateOf(id) == DownloadState.COMPLETED }
        val t = store.tasks.getValue(id)
        assertEquals(DownloadState.COMPLETED, t.state)
        assertEquals(output.published.single().first, t.outputPath)
        assertEquals("movie.mp4", output.published.single().second)
        assertEquals("video/mp4", output.published.single().third)
        assertEquals(2048L, t.bytesDownloaded)
        assertEquals(2048L, t.totalBytes)
        assertEquals("the title the user gave is kept", "My movie", t.title)
        assertEquals(EngineId.NEWPIPE, t.engineId)
        assertNull(t.error)
        assertEquals(
            listOf(DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.PROCESSING, DownloadState.COMPLETED),
            store.history.getValue(id).toList().fold(mutableListOf<DownloadState>()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc },
        )
        eventually(message = "temporary data removed") { !File(tempRoot, "dl/$id").exists() }
        eventually(message = "live stats cleared") { m.live.value.isEmpty() }
        assertLegalHistory()
        eventually { !m.work.value.hasWork }
    }

    @Test fun aTitleIsTakenFromTheSavedFileWhenNoneWasGiven() = runBlocking<Unit> {
        val m = manager(registryOf(FakeEngine.succeeding(name = "clip.mp4"))).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.COMPLETED }
        assertEquals("clip.mp4", store.tasks.getValue(id).title)
    }

    @Test fun theDownloadIsNotCompletedWhileSavingIsStillRunning() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        output.gate = gate
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.PROCESSING }
        delay(150)
        assertEquals("still saving: never COMPLETED early", DownloadState.PROCESSING, stateOf(id))
        assertNull(store.tasks.getValue(id).outputPath)
        gate.complete(Unit)
        eventually { stateOf(id) == DownloadState.COMPLETED }
    }

    @Test fun theEngineReceivesRequestHeadersButTheyAreNotPartOfTheRecordAtEnqueue() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val engine = blockingEngine(gate)
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(MediaRequest("https://x.test/a.mp4", headers = mapOf("Referer" to "https://p.test")), null) as EnqueueResult.Queued).id
        eventually { engine.runs.isNotEmpty() }
        assertEquals(mapOf("Referer" to "https://p.test"), engine.receivedHeaders[id])
        gate.complete(Unit)
        eventually { stateOf(id) == DownloadState.COMPLETED }
    }

    @Test fun aKnownExactSizeIsUsedAsTheInitialTotalButAnEstimateIsNot() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val m = manager(registryOf(blockingEngine(gate))).also { network.value = NetworkState.OFFLINE; it.start() }
        val exact = FormatOption("a", StreamKind.ORIGINAL_FILE, sizeBytes = 5000)
        val estimate = FormatOption("b", StreamKind.ORIGINAL_FILE, sizeBytes = 7000, sizeIsEstimate = true)
        val a = (m.enqueue(req(1), exact) as EnqueueResult.Queued).id
        val b = (m.enqueue(req(2), estimate) as EnqueueResult.Queued).id
        assertEquals(5000L, store.tasks.getValue(a).totalBytes)
        assertNull(store.tasks.getValue(b).totalBytes)
        gate.complete(Unit)
    }

    // ------------------------------------------------------------------------------------------ concurrency

    @Test fun neverRunsMoreThanTheConfiguredNumberAndStartsOldestFirst() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val engine = blockingEngine(gate)
        val m = manager(registryOf(engine)).also { it.start() }
        val ids = (1..5).map { (m.enqueue(req(it), null) as EnqueueResult.Queued).id }
        eventually { engine.concurrent.get() == 2 }
        delay(100)
        assertEquals(2, engine.concurrent.get())
        assertEquals("the two oldest run first", setOf(ids[0], ids[1]), engine.runs.toSet())
        assertEquals(3, m.work.value.queued)
        gate.complete(Unit)
        eventually { ids.all { stateOf(it) == DownloadState.COMPLETED } }
        assertEquals(2, engine.maxConcurrent.get())
        assertLegalHistory()
    }

    @Test fun withOneSlotDownloadsRunStrictlyInTheOrderTheyWereQueued() = runBlocking<Unit> {
        settings.value = ManagerSettings(maxConcurrent = 1)
        val engine = FakeEngine.succeeding()
        val m = manager(registryOf(engine)).also { network.value = NetworkState.OFFLINE; it.start() }
        val ids = (1..6).map { (m.enqueue(req(it), null) as EnqueueResult.Queued).id }
        network.value = NetworkState.WIFI
        eventually { ids.all { stateOf(it) == DownloadState.COMPLETED } }
        assertEquals(ids, engine.runs.toList())
        assertEquals(1, engine.maxConcurrent.get())
    }

    @Test fun raisingTheLimitStartsMoreAtOnce() = runBlocking<Unit> {
        settings.value = ManagerSettings(maxConcurrent = 1)
        val gate = CompletableDeferred<Unit>()
        val engine = blockingEngine(gate)
        val m = manager(registryOf(engine)).also { it.start() }
        val ids = (0 until 3).map { (m.enqueue(req(it), null) as EnqueueResult.Queued).id }
        eventually { engine.concurrent.get() == 1 }
        settings.value = ManagerSettings(maxConcurrent = 3)
        eventually { engine.concurrent.get() == 3 }
        gate.complete(Unit)
        // Do not end the test while the downloads still write and clean up their files.
        eventually(message = "all three completed") { ids.all { stateOf(it) == DownloadState.COMPLETED } }
    }

    @Test fun aNonsenseLimitStillRunsOneAtATime() = runBlocking<Unit> {
        settings.value = ManagerSettings(maxConcurrent = 0)
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.COMPLETED }
    }

    // ------------------------------------------------------------------------------------------ cancel and retry

    @Test fun cancellingARunningDownloadStopsTheEngineAndDeletesItsData() = runBlocking<Unit> {
        val engine = FakeEngine { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                dir.mkdirs(); File(dir, "download.part").writeBytes(ByteArray(500))
                emit(DownloadEvent.Progress(500, 10_000))
                awaitCancellation()
            }
        }
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.DOWNLOADING && File(tempRoot, "dl/$id/download.part").exists() }
        m.cancel(id)
        eventually { stateOf(id) == DownloadState.CANCELLED }
        assertEquals(listOf(id), engine.cancelled.toList())
        assertFalse(File(tempRoot, "dl/$id").exists())
        assertEquals(0L, store.tasks.getValue(id).bytesDownloaded)
        assertTrue(output.published.isEmpty())
        eventually { !m.work.value.hasWork }
        assertLegalHistory()
    }

    @Test fun cancellingAQueuedDownloadMeansItNeverStarts() = runBlocking<Unit> {
        settings.value = ManagerSettings(maxConcurrent = 1)
        val gate = CompletableDeferred<Unit>()
        val engine = blockingEngine(gate)
        val m = manager(registryOf(engine)).also { it.start() }
        val first = (m.enqueue(req(1), null) as EnqueueResult.Queued).id
        val second = (m.enqueue(req(2), null) as EnqueueResult.Queued).id
        eventually { engine.runs.contains(first) }
        m.cancel(second)
        assertEquals(DownloadState.CANCELLED, stateOf(second))
        gate.complete(Unit)
        eventually { stateOf(first) == DownloadState.COMPLETED }
        assertFalse(engine.runs.contains(second))
    }

    @Test fun cancellingAFinishedDownloadChangesNothing() = runBlocking<Unit> {
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.COMPLETED }
        m.cancel(id)
        assertEquals(DownloadState.COMPLETED, stateOf(id))
        m.cancel("does-not-exist") // must not throw
    }

    @Test fun aFailedDownloadCanBeRetriedAndKeepsItsEngine() = runBlocking<Unit> {
        var attempt = 0
        val engine = FakeEngine { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                if (++attempt == 1) {
                    emit(DownloadEvent.Failed(DownloadError.serverRejected(404)))
                } else {
                    dir.mkdirs(); File(dir, "ok.mp4").writeBytes(ByteArray(10))
                    emit(DownloadEvent.Completed(File(dir, "ok.mp4").absolutePath, 10))
                }
            }
        }
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.FAILED }
        assertEquals(404, store.tasks.getValue(id).error?.httpStatus)
        assertTrue(m.retry(id))
        eventually { stateOf(id) == DownloadState.COMPLETED }
        assertNull("the old error is cleared", store.tasks.getValue(id).error)
        assertFalse("a running or completed download cannot be retried", m.retry(id))
        assertLegalHistory()
    }

    @Test fun aCancelledDownloadCanBeRetried() = runBlocking<Unit> {
        val m = manager(registryOf(FakeEngine.succeeding())).also { network.value = NetworkState.OFFLINE; it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        m.cancel(id)
        assertEquals(DownloadState.CANCELLED, stateOf(id))
        network.value = NetworkState.WIFI
        assertTrue(m.retry(id))
        eventually { stateOf(id) == DownloadState.COMPLETED }
    }

    // ------------------------------------------------------------------------------------------ failures

    @Test fun aTemporaryServerFailureKeepsPartialDataButAPermanentOneDeletesIt() = runBlocking<Unit> {
        fun engineFailing(error: DownloadError) = FakeEngine { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                dir.mkdirs(); File(dir, "download.part").writeBytes(ByteArray(300))
                emit(DownloadEvent.Progress(300, 1000))
                emit(DownloadEvent.Failed(error))
            }
        }
        val transient = manager(registryOf(engineFailing(DownloadError.serverRejected(503)))).also { it.start() }
        val a = (transient.enqueue(req(1), null) as EnqueueResult.Queued).id
        eventually { stateOf(a) == DownloadState.FAILED }
        assertTrue(File(tempRoot, "dl/$a/download.part").exists())
        assertEquals(300L, store.tasks.getValue(a).bytesDownloaded)
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val permanent = manager(registryOf(engineFailing(DownloadError.serverRejected(404)))).also { it.start() }
        val b = (permanent.enqueue(req(2), null) as EnqueueResult.Queued).id
        eventually { stateOf(b) == DownloadState.FAILED }
        assertFalse(File(tempRoot, "dl/$b").exists())
        assertEquals(0L, store.tasks.getValue(b).bytesDownloaded)
    }

    @Test fun anEngineThatEndsWithoutAResultIsAFailure() = runBlocking<Unit> {
        val engine = FakeEngine { _, _ -> flow { emit(DownloadEvent.Started) } }
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.FAILED }
        assertTrue(store.tasks.getValue(id).error!!.technicalDetail.contains("without"))
    }

    @Test fun anEngineThatThrowsBecomesAFailedRecordAndDoesNotKillTheManager() = runBlocking<Unit> {
        var first = true
        val engine = FakeEngine { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                if (first) { first = false; throw IllegalStateException("boom token=SECRET123") }
                dir.mkdirs(); File(dir, "ok.mp4").writeBytes(ByteArray(5))
                emit(DownloadEvent.Completed(File(dir, "ok.mp4").absolutePath, 5))
            }
        }
        val m = manager(registryOf(engine)).also { it.start() }
        val bad = (m.enqueue(req(1), null) as EnqueueResult.Queued).id
        eventually { stateOf(bad) == DownloadState.FAILED }
        val err = store.tasks.getValue(bad).error!!
        assertEquals(ErrorKind.EXTRACTION_FAILED, err.kind)
        assertFalse("secrets are redacted", err.technicalDetail.contains("SECRET123"))
        val good = (m.enqueue(req(2), null) as EnqueueResult.Queued).id
        eventually { stateOf(good) == DownloadState.COMPLETED }
        assertEquals(1, unexpected.size)
    }

    @Test fun aResultFileWithTheWrongSizeIsNeverPublished() = runBlocking<Unit> {
        val engine = FakeEngine { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                dir.mkdirs(); File(dir, "x.mp4").writeBytes(ByteArray(10))
                emit(DownloadEvent.Completed(File(dir, "x.mp4").absolutePath, 999))
            }
        }
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.FAILED }
        assertTrue(output.published.isEmpty())
        assertFalse(File(tempRoot, "dl/$id").exists())
    }

    @Test fun aResultFileOutsideTheEnginesFolderIsRefused() = runBlocking<Unit> {
        val outside = File(tempRoot, "secret.txt").also { it.writeText("private") }
        val engine = FakeEngine { _, _ -> flow { emit(DownloadEvent.Started); emit(DownloadEvent.Completed(outside.absolutePath, 7)) } }
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.FAILED }
        assertEquals(ErrorKind.INVALID_DESTINATION, store.tasks.getValue(id).error!!.kind)
        assertTrue(output.published.isEmpty())
        assertTrue("the outside file must never be deleted", outside.exists())
    }

    @Test fun aSaveFailureIsReportedAndNothingIsHalfPublished() = runBlocking<Unit> {
        output.failWith = DownloadError.insufficientStorage("disk full")
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.FAILED }
        val t = store.tasks.getValue(id)
        assertEquals(ErrorKind.INSUFFICIENT_STORAGE, t.error!!.kind)
        assertNull(t.outputPath)
        assertFalse(File(tempRoot, "dl/$id").exists())
        assertFalse(store.history.getValue(id).contains(DownloadState.COMPLETED))
    }

    @Test fun notEnoughSpaceIsDetectedAsSoonAsTheSizeIsKnown() = runBlocking<Unit> {
        val engine = FakeEngine { _, _ ->
            flow {
                emit(DownloadEvent.Started)
                emit(DownloadEvent.Progress(0, Long.MAX_VALUE / 4))
                awaitCancellation()
            }
        }
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.FAILED }
        assertEquals(ErrorKind.INSUFFICIENT_STORAGE, store.tasks.getValue(id).error!!.kind)
        assertEquals("the engine was stopped", listOf(id), engine.stopped.toList())
    }

    @Test fun anUnavailableEngineRejectsTheEnqueueAndNoRecordIsCreated() = runBlocking<Unit> {
        val registry = FakeRegistry(emptyMap(), unavailable = mapOf(EngineId.NEWPIPE to "Not available in this build"))
        val m = manager(registry).also { it.start() }
        val r = m.enqueue(req(), null)
        assertTrue(r is EnqueueResult.Rejected)
        assertEquals(ErrorKind.ENGINE_UNAVAILABLE, (r as EnqueueResult.Rejected).error.kind)
        assertTrue(store.tasks.isEmpty())
        assertTrue(registry.created.isEmpty())
    }

    // ------------------------------------------------------------------------------------------ engines

    @Test fun aQueuedDownloadKeepsItsEngineWhenTheSelectionChanges() = runBlocking<Unit> {
        val direct = FakeEngine.succeeding(id = EngineId.NEWPIPE)
        val newpipe = FakeEngine.succeeding(id = EngineId.YTDLP)
        val registry = FakeRegistry(mapOf(EngineId.NEWPIPE to direct, EngineId.YTDLP to newpipe))
        network.value = NetworkState.OFFLINE
        val m = manager(registry).also { it.start() }
        val old = (m.enqueue(req(1), null) as EnqueueResult.Queued).id
        selected.value = EngineMode.YTDLP
        val fresh = (m.enqueue(req(2), null) as EnqueueResult.Queued).id
        assertEquals(EngineId.NEWPIPE, store.tasks.getValue(old).engineId)
        assertEquals(EngineId.YTDLP, store.tasks.getValue(fresh).engineId)
        network.value = NetworkState.WIFI
        eventually { stateOf(old) == DownloadState.COMPLETED && stateOf(fresh) == DownloadState.COMPLETED }
        assertEquals(listOf(old), direct.runs.toList())
        assertEquals(listOf(fresh), newpipe.runs.toList())
    }

    @Test fun enginesThatAreNeitherSelectedNorInUseAreClosedAfterwards() = runBlocking<Unit> {
        val direct = FakeEngine.succeeding(id = EngineId.NEWPIPE)
        val newpipe = FakeEngine.succeeding(id = EngineId.YTDLP)
        val m = manager(FakeRegistry(mapOf(EngineId.NEWPIPE to direct, EngineId.YTDLP to newpipe))).also { it.start() }
        val old = (m.enqueue(req(1), null) as EnqueueResult.Queued).id
        eventually { stateOf(old) == DownloadState.COMPLETED }
        selected.value = EngineMode.YTDLP
        val fresh = (m.enqueue(req(2), null) as EnqueueResult.Queued).id
        eventually { stateOf(fresh) == DownloadState.COMPLETED }
        eventually { direct.closed >= 1 }
        assertEquals("the selected engine stays open", 0, newpipe.closed)
    }

    // ---- Phase 8: engine selection hardening

    private fun twoEngines(direct: FakeEngine = FakeEngine.succeeding(id = EngineId.NEWPIPE), newpipe: FakeEngine = FakeEngine.succeeding(id = EngineId.YTDLP)) =
        FakeRegistry(mapOf(EngineId.NEWPIPE to direct, EngineId.YTDLP to newpipe))

    @Test fun switchingTheEngineClosesThePreviousOneAtOnceWithoutCreatingTheNewOne() = runBlocking<Unit> {
        val direct = FakeEngine.succeeding(id = EngineId.NEWPIPE)
        val registry = twoEngines(direct = direct)
        val m = manager(registry).also { it.start() }
        val id = (m.enqueue(req(1), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.COMPLETED }
        assertEquals("the selected engine stays open after its download", 0, direct.closed)
        selected.value = EngineMode.YTDLP
        eventually(message = "the previous engine is closed after the switch") { direct.closed >= 1 }
        assertEquals("nobody asked for the new engine, so it was never created", listOf(EngineId.NEWPIPE), registry.created.toList())
    }

    @Test fun aRunningDownloadKeepsItsEngineOpenAfterTheSwitchAndItIsClosedWhenTheDownloadEnds() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val direct = blockingEngine(gate)
        val m = manager(twoEngines(direct = direct)).also { it.start() }
        val id = (m.enqueue(req(1), null) as EnqueueResult.Queued).id
        eventually { direct.concurrent.get() == 1 }
        selected.value = EngineMode.YTDLP
        delay(150)
        assertEquals("a running download must never lose its engine", 0, direct.closed)
        assertEquals(setOf(EngineId.NEWPIPE), m.engineCommitments.value)
        gate.complete(Unit)
        eventually { stateOf(id) == DownloadState.COMPLETED }
        eventually(message = "closed once nothing uses it") { direct.closed >= 1 }
        eventually { m.engineCommitments.value.isEmpty() }
    }

    @Test fun theOptionsOfOneEngineAreNeverSentToAnother() = runBlocking<Unit> {
        val registry = twoEngines()
        val m = manager(registry).also { it.start() }
        selected.value = EngineMode.YTDLP
        val r = m.enqueue(req(1), null, listedWith = EngineId.NEWPIPE)
        val error = (r as EnqueueResult.Rejected).error
        assertEquals(ErrorKind.ENGINE_UNAVAILABLE, error.kind)
        assertTrue(error.userMessage, error.userMessage.contains("NewPipe") && error.userMessage.contains("yt-dlp"))
        assertTrue("no record is created", store.tasks.isEmpty())
    }

    @Test fun anEnqueueFromTheEngineThatListedTheOptionsIsAccepted() = runBlocking<Unit> {
        val m = manager(twoEngines()).also { it.start() }
        val r = m.enqueue(req(1), null, listedWith = EngineId.NEWPIPE)
        assertTrue(r is EnqueueResult.Queued)
    }

    @Test fun withNoEngineSelectedNothingIsQueuedAndNoEngineIsCreated() = runBlocking<Unit> {
        val registry = twoEngines() // both engines are available: neither may be picked
        val m = manager(registry).also { it.start() }
        selected.value = null
        val error = (m.enqueue(req(1), null) as EnqueueResult.Rejected).error
        assertEquals(ErrorKind.ENGINE_UNAVAILABLE, error.kind)
        assertTrue(error.userMessage, error.userMessage.contains("No download engine is selected"))
        assertTrue(store.tasks.isEmpty())
        assertTrue(registry.created.isEmpty())
    }

    @Test fun aDownloadWhoseOwnEngineIsGoneFailsAndNamesThatEngineNotTheSelectedOne() = runBlocking<Unit> {
        val direct = FakeEngine.succeeding(id = EngineId.NEWPIPE)
        // yt-dlp is not available any more; NewPipe (the selected one) is, and must NOT be used instead.
        val registry = FakeRegistry(mapOf(EngineId.NEWPIPE to direct), unavailable = mapOf(EngineId.YTDLP to "Not available in this build"))
        store.seed(seededTask("old", DownloadState.QUEUED, created = 1).copy(engineId = EngineId.YTDLP))
        manager(registry).also { it.start() }
        eventually { stateOf("old") == DownloadState.FAILED }
        val error = store.tasks.getValue("old").error!!
        assertEquals(ErrorKind.ENGINE_UNAVAILABLE, error.kind)
        assertEquals(EngineId.YTDLP, error.engineId)
        assertTrue(error.userMessage, error.userMessage.contains("yt-dlp") && error.userMessage.contains("Downloads keep their engine"))
        assertTrue("the selected engine never ran it", direct.runs.isEmpty())
        assertEquals(EngineId.YTDLP, store.tasks.getValue("old").engineId)
    }

    @Test fun theCommitmentsListTheEnginesOfQueuedAndRunningDownloads() = runBlocking<Unit> {
        network.value = NetworkState.OFFLINE
        val m = manager(twoEngines()).also { it.start() }
        m.enqueue(req(1), null)
        selected.value = EngineMode.YTDLP
        m.enqueue(req(2), null)
        assertEquals(setOf(EngineId.NEWPIPE, EngineId.YTDLP), m.engineCommitments.value)
        network.value = NetworkState.WIFI
        eventually { store.tasks.values.all { it.state == DownloadState.COMPLETED } && store.tasks.size == 2 }
        eventually { m.engineCommitments.value.isEmpty() }
    }

    // ------------------------------------------------------------------------------------------ network

    @Test fun nothingStartsWhileOfflineAndItRunsWhenTheNetworkReturns() = runBlocking<Unit> {
        network.value = NetworkState.OFFLINE
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        delay(150)
        assertEquals(DownloadState.QUEUED, stateOf(id))
        assertEquals(BlockReason.NO_NETWORK, m.work.value.blockedBy)
        assertFalse(m.work.value.hasWork)
        network.value = NetworkState.MOBILE_OR_OTHER
        eventually { stateOf(id) == DownloadState.COMPLETED }
    }

    @Test fun wifiOnlyHoldsDownloadsOnMobileData() = runBlocking<Unit> {
        settings.value = ManagerSettings(maxConcurrent = 2, wifiOnly = true)
        network.value = NetworkState.MOBILE_OR_OTHER
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        delay(150)
        assertEquals(DownloadState.QUEUED, stateOf(id))
        assertEquals(BlockReason.WIFI_REQUIRED, m.work.value.blockedBy)
        network.value = NetworkState.WIFI
        eventually { stateOf(id) == DownloadState.COMPLETED }
    }

    @Test fun turningWifiOnlyOffReleasesWaitingDownloads() = runBlocking<Unit> {
        settings.value = ManagerSettings(wifiOnly = true)
        network.value = NetworkState.MOBILE_OR_OTHER
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        delay(100)
        settings.value = ManagerSettings(wifiOnly = false)
        eventually { stateOf(id) == DownloadState.COMPLETED }
    }

    @Test fun losingTheNetworkMidDownloadRequeuesItAndKeepsPartialData() = runBlocking<Unit> {
        val resumeSeen = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val engine = FakeEngine { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                dir.mkdirs()
                val part = File(dir, "download.part")
                resumeSeen += part.exists()
                if (!part.exists()) {
                    part.writeBytes(ByteArray(400))
                    emit(DownloadEvent.Progress(400, 1000))
                    awaitCancellation() // transfer in flight until the network goes away
                } else {
                    part.delete()
                    File(dir, "full.mp4").writeBytes(ByteArray(1000))
                    emit(DownloadEvent.Completed(File(dir, "full.mp4").absolutePath, 1000))
                }
            }
        }
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.DOWNLOADING }
        network.value = NetworkState.OFFLINE
        eventually { stateOf(id) == DownloadState.QUEUED && !m.work.value.hasWork }
        assertTrue("partial data kept", File(tempRoot, "dl/$id/download.part").exists())
        assertNull("not an error: just waiting", store.tasks.getValue(id).error)
        network.value = NetworkState.WIFI
        eventually { stateOf(id) == DownloadState.COMPLETED }
        assertEquals(listOf(false, true), resumeSeen.toList())
        assertLegalHistory()
    }

    @Test fun aDownloadThatIsAlreadySavingIsNotInterruptedByNetworkLoss() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        output.gate = gate
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.PROCESSING }
        network.value = NetworkState.OFFLINE
        delay(100)
        gate.complete(Unit)
        eventually { stateOf(id) == DownloadState.COMPLETED }
    }

    // ------------------------------------------------------------------------------------------ recovery

    private fun seededTask(id: String, state: DownloadState, created: Long) = DownloadTask(
        id = id, engineId = EngineId.NEWPIPE, request = MediaRequest("https://x.test/$id.mp4"), format = null,
        state = state, bytesDownloaded = 100, totalBytes = 1000, createdAtMillis = created,
    )

    @Test fun downloadsThatWereRunningWhenTheProcessDiedAreRecoveredAndRunAgain() = runBlocking<Unit> {
        listOf(DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.PROCESSING).forEachIndexed { i, s ->
            store.seed(seededTask("dead$i", s, created = i.toLong()))
        }
        store.seed(seededTask("done", DownloadState.COMPLETED, 10))
        store.seed(seededTask("failed", DownloadState.FAILED, 11))
        val partial = File(tempRoot, "dl/dead1").also { it.mkdirs() }
        File(partial, "download.part").writeBytes(ByteArray(100))
        val engine = FakeEngine.succeeding()
        val m = manager(registryOf(engine)).also { it.start() }
        eventually { (0..2).all { stateOf("dead$it") == DownloadState.COMPLETED } }
        assertEquals(setOf("dead0", "dead1", "dead2"), engine.runs.toSet())
        assertEquals("finished and failed records are untouched", DownloadState.FAILED, stateOf("failed"))
        assertEquals(DownloadState.COMPLETED, stateOf("done"))
        assertLegalHistory()
    }

    @Test fun partialDataSurvivesRecoveryForTheEngineToResume() = runBlocking<Unit> {
        store.seed(seededTask("x", DownloadState.DOWNLOADING, 1))
        File(tempRoot, "dl/x").also { it.mkdirs() }.resolve("download.part").writeBytes(ByteArray(100))
        val sawPartial = CompletableDeferred<Boolean>()
        val engine = FakeEngine { task, dir ->
            flow {
                sawPartial.complete(File(dir, "download.part").exists())
                emit(DownloadEvent.Started)
                emit(DownloadEvent.Failed(DownloadError.serverRejected(404)))
            }
        }
        manager(registryOf(engine)).also { it.start() }
        assertTrue(sawPartial.await())
    }

    @Test fun leftoverTemporaryFoldersWithoutARecordAreRemovedAtStart() = runBlocking<Unit> {
        val orphan = File(tempRoot, "dl/orphan").also { it.mkdirs() }
        File(orphan, "junk.part").writeBytes(ByteArray(10))
        val ofDone = File(tempRoot, "dl/done").also { it.mkdirs() }
        store.seed(seededTask("done", DownloadState.COMPLETED, 1))
        val ofFailed = File(tempRoot, "dl/failed").also { it.mkdirs() }
        store.seed(seededTask("failed", DownloadState.FAILED, 2))
        manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        assertFalse(orphan.exists())
        assertFalse(ofDone.exists())
        assertTrue("a failed download may still be resumed", ofFailed.exists())
    }

    @Test fun startingTwiceDoesNotRunDownloadsTwice() = runBlocking<Unit> {
        store.seed(seededTask("a", DownloadState.DOWNLOADING, 1))
        val engine = FakeEngine.succeeding()
        val m = manager(registryOf(engine))
        m.start(); m.start()
        eventually { stateOf("a") == DownloadState.COMPLETED }
        assertEquals(listOf("a"), engine.runs.toList())
    }

    // ------------------------------------------------------------------------------------------ records and files

    @Test fun aRunningDownloadsRecordCannotBeDeleted() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val m = manager(registryOf(blockingEngine(gate))).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.DOWNLOADING }
        assertFalse(m.deleteRecord(id))
        assertNotNull(store.tasks[id])
        gate.complete(Unit)
        eventually { stateOf(id) == DownloadState.COMPLETED }
        assertTrue(m.deleteRecord(id))
        assertNull(store.tasks[id])
        assertTrue("the saved file is untouched by deleting the record", output.deleted.isEmpty())
    }

    @Test fun deleteFileRemovesTheFileThenTheRecord() = runBlocking<Unit> {
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.COMPLETED }
        val location = store.tasks.getValue(id).outputPath!!
        assertTrue(m.fileExists(id))
        assertEquals(DeleteFileResult.Deleted, m.deleteFile(id))
        assertEquals(listOf(location), output.deleted.toList())
        assertNull(store.tasks[id])
        assertEquals(DeleteFileResult.NoRecord, m.deleteFile(id))
    }

    @Test fun aFileThatCouldNotBeDeletedKeepsItsRecord() = runBlocking<Unit> {
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.COMPLETED }
        output.deleteResult = DeleteResult.Failed("permission denied")
        assertEquals(DeleteFileResult.Failed("permission denied"), m.deleteFile(id))
        assertNotNull(store.tasks[id])
    }

    @Test fun aFileDeletedByAnotherAppIsDetectedAndItsRecordCanBeCleared() = runBlocking<Unit> {
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.COMPLETED }
        output.existing.clear() // the user deleted it in a file manager
        assertFalse(m.fileExists(id))
        output.deleteResult = DeleteResult.NotFound
        assertEquals(DeleteFileResult.AlreadyGone, m.deleteFile(id))
        assertNull(store.tasks[id])
    }

    @Test fun clearUnsuccessfulKeepsTheLibraryButClearHistoryDoesNot() = runBlocking<Unit> {
        store.seed(seededTask("ok", DownloadState.COMPLETED, 1))
        store.seed(seededTask("bad", DownloadState.FAILED, 2))
        store.seed(seededTask("stop", DownloadState.CANCELLED, 3))
        File(tempRoot, "dl/bad").mkdirs()
        val m = manager(registryOf(FakeEngine.succeeding())).also { network.value = NetworkState.OFFLINE; it.start() }
        store.seed(seededTask("waiting", DownloadState.QUEUED, 4))
        assertEquals(2, m.clearUnsuccessful())
        assertEquals(setOf("ok", "waiting"), store.tasks.keys.toSet())
        assertFalse(File(tempRoot, "dl/bad").exists())
        assertEquals(1, m.clearHistory())
        assertEquals(setOf("waiting"), store.tasks.keys.toSet())
        assertTrue("files were never deleted", output.deleted.isEmpty())
    }

    @Test fun cleanupDeletesOnlyDataThatNothingNeeds() = runBlocking<Unit> {
        network.value = NetworkState.OFFLINE
        val m = manager(registryOf(FakeEngine.succeeding())).also { it.start() }
        store.seed(seededTask("failed", DownloadState.FAILED, 1))
        store.seed(seededTask("queued", DownloadState.QUEUED, 2))
        for (n in listOf("failed", "queued", "stray")) File(tempRoot, "dl/$n").also { it.mkdirs() }.resolve("download.part").writeBytes(ByteArray(100))
        assertEquals(300L, m.temporaryBytes())
        assertEquals(200L, m.cleanupTemporaryFiles())
        assertTrue(File(tempRoot, "dl/queued").exists())
        assertFalse(File(tempRoot, "dl/failed").exists())
        assertFalse(File(tempRoot, "dl/stray").exists())
        assertEquals("a failed download whose data is gone shows no stale progress", 0L, store.tasks.getValue("failed").bytesDownloaded)
    }

    // ------------------------------------------------------------------------------------------ persistence behaviour

    @Test fun progressIsPersistedWithAThrottleNotOnEveryEvent() = runBlocking<Unit> {
        val engine = FakeEngine { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                for (i in 1..2000) emit(DownloadEvent.Progress(i.toLong(), 2000))
                dir.mkdirs(); File(dir, "f.mp4").writeBytes(ByteArray(2000))
                emit(DownloadEvent.Completed(File(dir, "f.mp4").absolutePath, 2000))
            }
        }
        val m = manager(registryOf(engine), persistMs = 60_000).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.COMPLETED }
        assertTrue("saves: ${store.history.getValue(id).size}", store.history.getValue(id).size < 15)
    }

    @Test fun anUnknownTotalStaysUnknown() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                emit(DownloadEvent.Progress(777, null))
                gate.await()
                awaitCancellation()
            }
        }
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { store.tasks[id]?.bytesDownloaded == 777L }
        assertNull(store.tasks.getValue(id).totalBytes)
        assertNull(store.tasks.getValue(id).progressFraction)
        m.cancel(id)
    }

    @Test fun speedAndEtaAreExposedOnlyWhenTheEngineMeasuredThem() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                emit(DownloadEvent.Progress(10, 100, speedBytesPerSecond = null, etaSeconds = null))
                gate.await()
                emit(DownloadEvent.Progress(50, 100, speedBytesPerSecond = 1234, etaSeconds = 4))
                awaitCancellation()
            }
        }
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { m.live.value[id] != null }
        assertNull(m.live.value.getValue(id).speedBytesPerSecond)
        gate.complete(Unit)
        eventually { m.live.value[id]?.speedBytesPerSecond == 1234L }
        assertEquals(4L, m.live.value.getValue(id).etaSeconds)
        m.cancel(id)
        eventually { m.live.value[id] == null }
    }

    @Test fun processingEventsShowTheirStage() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                emit(DownloadEvent.Processing("Merging video and audio"))
                gate.await()
                awaitCancellation()
            }
        }
        val m = manager(registryOf(engine)).also { it.start() }
        val id = (m.enqueue(req(), null) as EnqueueResult.Queued).id
        eventually { stateOf(id) == DownloadState.PROCESSING }
        assertEquals("Merging video and audio", m.live.value[id]?.stage)
        m.cancel(id)
        eventually { stateOf(id) == DownloadState.CANCELLED }
    }

    @Test fun workStateTracksRunningAndQueued() = runBlocking<Unit> {
        settings.value = ManagerSettings(maxConcurrent = 1)
        val gate = CompletableDeferred<Unit>()
        val m = manager(registryOf(blockingEngine(gate))).also { it.start() }
        m.enqueue(req(1), null); m.enqueue(req(2), null)
        eventually { m.work.value.running == 1 && m.work.value.queued == 1 }
        assertTrue(m.work.value.hasWork)
        gate.complete(Unit)
        eventually { m.work.value == WorkState(0, 0, null) }
    }

    @Test fun outcomesAreAnnouncedOnceForCompletedAndFailedButNotForCancelled() = runBlocking<Unit> {
        val seen = java.util.concurrent.CopyOnWriteArrayList<DownloadOutcome>()
        var n = 0
        val engine = FakeEngine { task, dir ->
            flow {
                emit(DownloadEvent.Started)
                when (++n) {
                    1 -> { dir.mkdirs(); File(dir, "a.mp4").writeBytes(ByteArray(4)); emit(DownloadEvent.Completed(File(dir, "a.mp4").absolutePath, 4)) }
                    2 -> emit(DownloadEvent.Failed(DownloadError.serverRejected(404)))
                    else -> awaitCancellation()
                }
            }
        }
        val m = manager(registryOf(engine))
        val ready = CompletableDeferred<Unit>()
        scope.launch { m.outcomes.onSubscription { ready.complete(Unit) }.collect { seen += it } }
        ready.await()
        m.start()
        settings.value = ManagerSettings(maxConcurrent = 1)
        val a = (m.enqueue(req(1), null) as EnqueueResult.Queued).id
        eventually { stateOf(a) == DownloadState.COMPLETED }
        val b = (m.enqueue(req(2), null) as EnqueueResult.Queued).id
        eventually { stateOf(b) == DownloadState.FAILED }
        val c = (m.enqueue(req(3), null) as EnqueueResult.Queued).id
        eventually { stateOf(c) == DownloadState.DOWNLOADING }
        m.cancel(c)
        eventually { stateOf(c) == DownloadState.CANCELLED }
        eventually { seen.size >= 2 }
        delay(100)
        assertEquals(2, seen.size)
        assertTrue(seen[0] is DownloadOutcome.Completed && seen[0].task.id == a)
        assertTrue(seen[1] is DownloadOutcome.Failed && seen[1].task.id == b)
    }

    @Test fun networkClassification() {
        assertEquals(NetworkState.OFFLINE, classifyNetwork(hasInternet = false, wifiOrEthernet = true))
        assertEquals(NetworkState.WIFI, classifyNetwork(true, true))
        assertEquals(NetworkState.MOBILE_OR_OTHER, classifyNetwork(true, false))
    }
}
