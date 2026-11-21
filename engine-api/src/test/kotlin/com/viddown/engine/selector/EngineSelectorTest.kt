package com.viddown.engine.selector

import com.viddown.engine.api.DownloadEngine
import com.viddown.engine.api.DownloadEvent
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineCapabilities
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineMode
import com.viddown.engine.api.EngineResult
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaInfo
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class CountingEngine(override val id: EngineId) : DownloadEngine {
    override val displayName = id.label
    override val description = "test"
    override val capabilities = EngineCapabilities(setOf(MediaType.DIRECT_MP4), supportsResume = false, listsFormats = false, mayNeedFfmpeg = false)
    var closed = 0
    var failOnClose = false
    override fun availability() = EngineAvailability.Available
    override suspend fun inspect(request: MediaRequest): EngineResult<MediaInfo> = EngineResult.Success(MediaInfo(id, request.url))
    override suspend fun listFormats(request: MediaRequest): EngineResult<List<FormatOption>> = EngineResult.Success(emptyList())
    override fun download(task: DownloadTask, destinationDir: String): Flow<DownloadEvent> = emptyFlow()
    override suspend fun close() {
        closed++
        if (failOnClose) throw IllegalStateException("close failed")
    }
}

private class FakeRegistry(private val availability: Map<EngineId, EngineAvailability>) : EngineRegistry {
    val creations = mutableListOf<EngineId>()
    val engines = mutableMapOf<EngineId, CountingEngine>()
    override val descriptors = EngineId.entries.map { id -> EngineDescriptor(id, "desc ${id.label}") { availability.getValue(id) } }
    override fun create(id: EngineId): DownloadEngine { creations += id; return CountingEngine(id).also { engines[id] = it } }
}

class EngineSelectorTest {
    private val unavailable = EngineAvailability.NotAvailable("Not available in this build")
    private fun registry(vararg available: EngineId) =
        FakeRegistry(EngineId.entries.associateWith { if (it in available) EngineAvailability.Available else unavailable })

    @Test
    fun statusesShowAvailabilitySelectionAndSelectability() = runBlocking {
        val sel = EngineSelector(registry(EngineId.NEWPIPE), MutableStateFlow(EngineMode.NEWPIPE))
        val list = sel.statuses.first()
        assertEquals(2, list.size)
        val direct = list.first { it.id == EngineId.NEWPIPE }
        assertTrue(direct.selected && direct.selectable)
        val ytdlp = list.first { it.id == EngineId.YTDLP }
        assertFalse(ytdlp.selected)
        assertFalse(ytdlp.selectable)
        assertEquals(unavailable, ytdlp.availability)
    }

    @Test
    fun anEngineThatIsNotAvailableCannotBeSelected() {
        val sel = EngineSelector(registry(EngineId.NEWPIPE), MutableStateFlow(EngineMode.NEWPIPE))
        assertEquals(SelectOutcome.Allowed, sel.checkSelectable(EngineId.NEWPIPE))
        assertEquals(SelectOutcome.Refused("Not available in this build"), sel.checkSelectable(EngineId.YTDLP))
    }

    @Test
    fun selectedEngineUnavailableFailsAndNeverFallsBackToAnotherEngine() = runBlocking {
        val reg = registry(EngineId.YTDLP) // another engine IS available, the selected one is not
        val sel = EngineSelector(reg, MutableStateFlow(EngineMode.NEWPIPE))
        val result = sel.activeEngine()
        assertTrue(result is EngineResult.Failure)
        val error = (result as EngineResult.Failure).error
        assertEquals(ErrorKind.ENGINE_UNAVAILABLE, error.kind)
        assertEquals(EngineId.NEWPIPE, error.engineId)
        assertTrue(error.userMessage, error.userMessage.contains("NewPipe"))
        assertTrue("no engine may be created", reg.creations.isEmpty())
    }

    @Test
    fun onlyTheSelectedEngineIsEverCreated() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val sel = EngineSelector(reg, MutableStateFlow(EngineMode.NEWPIPE))
        val first = (sel.activeEngine() as EngineResult.Success).value
        val second = (sel.activeEngine() as EngineResult.Success).value
        assertTrue("same instance on repeated calls", first === second)
        assertEquals(listOf(EngineId.NEWPIPE), reg.creations)
        assertEquals(setOf(EngineId.NEWPIPE), sel.createdEngineIds())
    }

    @Test
    fun changingTheSelectionKeepsTheOldEngineUntilItIsReleased() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val selected = MutableStateFlow(EngineMode.NEWPIPE)
        val sel = EngineSelector(reg, selected)
        sel.activeEngine()
        selected.value = EngineMode.YTDLP
        val now = (sel.activeEngine() as EngineResult.Success).value
        assertEquals(EngineId.YTDLP, now.id)
        // a running download still uses NewPipe: it must not be closed
        sel.releaseUnused(inUse = setOf(EngineId.NEWPIPE))
        assertEquals(0, reg.engines.getValue(EngineId.NEWPIPE).closed)
        assertEquals(setOf(EngineId.NEWPIPE, EngineId.YTDLP), sel.createdEngineIds())
        // the download finished: now it can go
        sel.releaseUnused(inUse = emptySet())
        assertEquals(1, reg.engines.getValue(EngineId.NEWPIPE).closed)
        assertEquals(0, reg.engines.getValue(EngineId.YTDLP).closed)
        assertEquals(setOf(EngineId.YTDLP), sel.createdEngineIds())
    }

    @Test
    fun aRunningDownloadCanStillGetItsOwnEngineAfterTheSelectionChanged() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val selected = MutableStateFlow(EngineMode.NEWPIPE)
        val sel = EngineSelector(reg, selected)
        val started = (sel.engineFor(EngineId.NEWPIPE) as EngineResult.Success).value
        selected.value = EngineMode.YTDLP
        val again = (sel.engineFor(EngineId.NEWPIPE) as EngineResult.Success).value
        assertTrue(started === again)
    }

    @Test
    fun closeAllClosesEveryCreatedEngineOnce() = runBlocking {
        val reg = registry(EngineId.NEWPIPE)
        val sel = EngineSelector(reg, MutableStateFlow(EngineMode.NEWPIPE))
        sel.activeEngine()
        sel.closeAll()
        sel.closeAll()
        assertEquals(1, reg.engines.getValue(EngineId.NEWPIPE).closed)
        assertTrue(sel.createdEngineIds().isEmpty())
    }

    // ------------------------------------------------------------------------------------------ Phase 8 hardening

    private fun noChoice() = MutableStateFlow<EngineMode?>(null)

    @Test
    fun withNoSavedChoiceNoEngineIsCreatedAndTheErrorSaysSo() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP) // every engine is available: none may be picked
        val sel = EngineSelector(reg, noChoice())
        val result = sel.activeEngine()
        val error = (result as EngineResult.Failure).error
        assertEquals(ErrorKind.ENGINE_UNAVAILABLE, error.kind)
        assertNull(error.engineId)
        assertTrue(error.userMessage, error.userMessage.contains("No download engine is selected"))
        assertTrue("nothing may be created", reg.creations.isEmpty())
        assertTrue(sel.createdEngineIds().isEmpty())
        assertTrue("no status is marked as selected", sel.statuses.first().none { it.selected })
    }

    @Test
    fun theSelectionFlowReportsTheChoiceWithoutCreatingAnything() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val choice = MutableStateFlow<EngineMode?>(EngineMode.YTDLP)
        val sel = EngineSelector(reg, choice)
        assertEquals(EngineMode.YTDLP, sel.selection.first())
        choice.value = null
        assertNull(sel.selection.first())
        assertTrue(reg.creations.isEmpty())
    }

    @Test
    fun releasingAfterASwitchClosesTheEngineThatWasSelectedBefore() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val choice = MutableStateFlow<EngineMode?>(EngineMode.YTDLP)
        val sel = EngineSelector(reg, choice)
        sel.activeEngine()
        choice.value = EngineMode.NEWPIPE
        val closed = sel.releaseUnusedBy { emptySet() }
        assertEquals(setOf(EngineId.YTDLP), closed)
        assertEquals(1, reg.engines.getValue(EngineId.YTDLP).closed)
        assertTrue("the newly selected engine was never created by a release", EngineId.NEWPIPE !in reg.creations)
        assertTrue(sel.createdEngineIds().isEmpty())
    }

    @Test
    fun aReleaseNeverClosesTheSelectedEngineNorOneThatIsInUse() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val choice = MutableStateFlow<EngineMode?>(EngineMode.NEWPIPE)
        val sel = EngineSelector(reg, choice)
        sel.activeEngine()
        choice.value = EngineMode.YTDLP
        sel.activeEngine()
        val kept = sel.releaseUnusedBy { setOf(EngineId.NEWPIPE) }
        assertTrue("the selected engine and the one in use both stay open", kept.isEmpty())
        assertEquals(setOf(EngineId.NEWPIPE, EngineId.YTDLP), sel.createdEngineIds())
        val closed = sel.releaseUnusedBy { emptySet() }
        assertEquals("only the engine that is neither selected nor in use", setOf(EngineId.NEWPIPE), closed)
        assertEquals(setOf(EngineId.YTDLP), sel.createdEngineIds())
    }

    @Test
    fun aReleaseWithNoSelectionClosesEverythingThatIsNotInUse() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val choice = MutableStateFlow<EngineMode?>(EngineMode.NEWPIPE)
        val sel = EngineSelector(reg, choice)
        sel.activeEngine()
        sel.engineFor(EngineId.YTDLP)
        choice.value = null
        sel.releaseUnused(inUse = setOf(EngineId.YTDLP))
        assertEquals(setOf(EngineId.YTDLP), sel.createdEngineIds())
        assertEquals(1, reg.engines.getValue(EngineId.NEWPIPE).closed)
    }

    @Test
    fun anEngineThatFailsToCloseDoesNotKeepTheOthersOpenAndIsReported() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val failures = mutableListOf<EngineId>()
        val sel = EngineSelector(reg, MutableStateFlow<EngineMode?>(null)) { id, _ -> failures += id }
        sel.engineFor(EngineId.NEWPIPE)
        sel.engineFor(EngineId.YTDLP)
        reg.engines.getValue(EngineId.NEWPIPE).failOnClose = true
        sel.releaseUnused(emptySet())
        assertEquals(1, reg.engines.getValue(EngineId.NEWPIPE).closed)
        assertEquals(1, reg.engines.getValue(EngineId.YTDLP).closed)
        assertEquals(listOf(EngineId.NEWPIPE), failures)
        assertTrue(sel.createdEngineIds().isEmpty())
    }

    @Test
    fun aSwitchBackCreatesAFreshEngineAfterTheOldOneWasClosed() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val choice = MutableStateFlow<EngineMode?>(EngineMode.YTDLP)
        val sel = EngineSelector(reg, choice)
        val first = (sel.activeEngine() as EngineResult.Success).value
        choice.value = EngineMode.NEWPIPE
        sel.releaseUnused(emptySet())
        choice.value = EngineMode.YTDLP
        val second = (sel.activeEngine() as EngineResult.Success).value
        assertFalse("a closed engine is never handed out again", first === second)
        assertEquals(listOf(EngineId.YTDLP, EngineId.YTDLP), reg.creations)
    }

    // ---- Auto mode: YouTube -> NewPipe, everything else -> yt-dlp ----------------------------------------------------

    private suspend fun EngineSelector.idFor(url: String?): EngineId = (engineForUrl(url) as EngineResult.Success).value.id

    @Test
    fun autoSendsYouTubeLinksToNewPipeAndEveryOtherLinkToYtDlp() = runBlocking {
        val sel = EngineSelector(registry(EngineId.NEWPIPE, EngineId.YTDLP), MutableStateFlow(EngineMode.AUTO))
        assertEquals(EngineId.NEWPIPE, sel.idFor("https://www.youtube.com/watch?v=abc"))
        assertEquals(EngineId.NEWPIPE, sel.idFor("https://youtu.be/abc"))
        assertEquals(EngineId.YTDLP, sel.idFor("https://vimeo.com/123"))
        assertEquals(EngineId.YTDLP, sel.idFor("https://example.com/video.mp4"))
        assertEquals(EngineId.YTDLP, sel.idFor(null))
    }

    @Test
    fun autoCreatesOnlyTheEngineThatALinkNeeds() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val sel = EngineSelector(reg, MutableStateFlow(EngineMode.AUTO))
        sel.idFor("https://youtu.be/abc")
        assertEquals("yt-dlp is never created for a YouTube link", setOf(EngineId.NEWPIPE), sel.createdEngineIds())
        sel.idFor("https://vimeo.com/123")
        assertEquals(setOf(EngineId.NEWPIPE, EngineId.YTDLP), sel.createdEngineIds())
        assertEquals("each engine is created once", listOf(EngineId.NEWPIPE, EngineId.YTDLP), reg.creations)
    }

    @Test
    fun autoNeverFallsBackWhenTheEngineALinkNeedsIsUnavailable() = runBlocking {
        val reg = registry(EngineId.NEWPIPE) // yt-dlp is not available
        val sel = EngineSelector(reg, MutableStateFlow(EngineMode.AUTO))
        val result = sel.engineForUrl("https://vimeo.com/123")
        assertTrue(result is EngineResult.Failure)
        val error = (result as EngineResult.Failure).error
        assertEquals(ErrorKind.ENGINE_UNAVAILABLE, error.kind)
        assertEquals(EngineId.YTDLP, error.engineId)
        assertTrue("NewPipe must not be substituted", reg.creations.isEmpty())
        assertEquals(EngineId.NEWPIPE, sel.idFor("https://youtu.be/abc"))
    }

    @Test
    fun aFixedModeUsesItsEngineForEveryLink() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val choice = MutableStateFlow<EngineMode?>(EngineMode.YTDLP)
        val sel = EngineSelector(reg, choice)
        assertEquals(EngineId.YTDLP, sel.idFor("https://www.youtube.com/watch?v=abc"))
        choice.value = EngineMode.NEWPIPE
        assertEquals(EngineId.NEWPIPE, sel.idFor("https://vimeo.com/123"))
    }

    @Test
    fun noChoiceMeansNoEngineEvenForALink() = runBlocking {
        val sel = EngineSelector(registry(EngineId.NEWPIPE, EngineId.YTDLP), MutableStateFlow<EngineMode?>(null))
        val result = sel.engineForUrl("https://youtu.be/abc")
        assertTrue(result is EngineResult.Failure)
        assertEquals(ErrorKind.ENGINE_UNAVAILABLE, (result as EngineResult.Failure).error.kind)
    }

    @Test
    fun activeEngineAloneCannotChooseInAuto() = runBlocking {
        val sel = EngineSelector(registry(EngineId.NEWPIPE, EngineId.YTDLP), MutableStateFlow(EngineMode.AUTO))
        assertTrue(sel.activeEngine() is EngineResult.Failure)
    }

    @Test
    fun autoKeepsTheEnginesItCreatedAndLeavingAutoClosesTheOneThatIsNotNeeded() = runBlocking {
        val reg = registry(EngineId.NEWPIPE, EngineId.YTDLP)
        val choice = MutableStateFlow<EngineMode?>(EngineMode.AUTO)
        val sel = EngineSelector(reg, choice)
        sel.idFor("https://youtu.be/abc")
        sel.idFor("https://vimeo.com/123")
        assertTrue("Auto may need either engine at any moment", sel.releaseUnusedBy { emptySet() }.isEmpty())
        choice.value = EngineMode.NEWPIPE
        assertEquals(setOf(EngineId.YTDLP), sel.releaseUnusedBy { emptySet() })
        assertEquals(setOf(EngineId.NEWPIPE), sel.createdEngineIds())
    }

    @Test
    fun modesListAutoFirstAndAutoWorksWithOneEngine() = runBlocking {
        val sel = EngineSelector(registry(EngineId.NEWPIPE), MutableStateFlow(EngineMode.AUTO))
        val list = sel.modes.first()
        assertEquals(listOf(EngineMode.AUTO, EngineMode.NEWPIPE, EngineMode.YTDLP), list.map { it.mode })
        assertTrue(list[0].selected && list[0].selectable)
        assertFalse(list[1].selected)
        assertFalse(list[2].selectable)
        assertEquals(SelectOutcome.Allowed, sel.checkSelectable(EngineMode.AUTO))
        assertTrue(EngineSelector(registry(), MutableStateFlow(EngineMode.AUTO)).checkSelectable(EngineMode.AUTO) is SelectOutcome.Refused)
    }
}
