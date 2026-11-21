package com.viddown.browser

import com.viddown.engine.api.MediaType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProber : MediaProber {
    val answers = ConcurrentHashMap<String, ProbeResult>()
    val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    val calls = AtomicInteger(0)
    val seen = ConcurrentHashMap<String, ProbeRequest>()

    override suspend fun probe(request: ProbeRequest): ProbeResult {
        calls.incrementAndGet()
        seen[request.url] = request
        gates[request.url]?.await()
        return answers[request.url] ?: ProbeResult.Failed("no answer")
    }
}

class MediaDetectionManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prober = FakeProber()
    private val manager = MediaDetectionManager(prober, scope, probeHeaders = { _, url -> mapOf("Referer" to "https://page.test/", "X-For" to url) }, clock = { 1000 })

    @After
    fun tearDown() = scope.cancel()

    private fun mp4(url: String, size: Long = 2_000_000, type: String = "video/mp4") =
        ProbeResult.Reply(200, url, type, size, null)

    private fun waitFor(tab: String, what: String = "state", check: (TabDetectionState) -> Boolean): TabDetectionState = runBlocking {
        withTimeout(5_000) { manager.state(tab).first(check) }
    }

    private fun idle(tab: String) = waitFor(tab) { !it.checking }

    @Test
    fun aDetectionIsAssociatedWithItsTabAndPage() {
        prober.answers["https://x.test/a.mp4"] = mp4("https://x.test/a.mp4")
        manager.beginPage("t1", "https://page.test/#top")
        manager.onRequest("t1", ResourceEvidence("https://x.test/a.mp4"))
        val s = waitFor("t1") { it.items.isNotEmpty() }
        val m = s.items.single()
        assertEquals("t1", m.tabId)
        assertEquals("https://page.test/", m.pageUrl)
        assertEquals(MediaType.DIRECT_MP4, m.type)
        assertEquals(2_000_000L, m.sizeBytes)
        assertEquals("a.mp4", m.title)
        assertEquals(1000L, m.detectedAtMillis)
        assertTrue(manager.current("t2").items.isEmpty())
    }

    @Test
    fun theProbeGetsTheTabsHeaders() {
        prober.answers["https://x.test/a.mp4"] = mp4("https://x.test/a.mp4")
        manager.beginPage("t1", "https://page.test/")
        manager.onRequest("t1", ResourceEvidence("https://x.test/a.mp4"))
        waitFor("t1") { it.items.isNotEmpty() }
        assertEquals("https://page.test/", prober.seen.getValue("https://x.test/a.mp4").headers["Referer"])
    }

    @Test
    fun theSameAddressIsAskedAboutAndListedOnce() {
        prober.answers["https://x.test/a.mp4"] = mp4("https://x.test/a.mp4")
        manager.beginPage("t1", "https://page.test/")
        repeat(5) { manager.onRequest("t1", ResourceEvidence("https://x.test/a.mp4#t=${it}")) }
        waitFor("t1") { it.items.isNotEmpty() }
        idle("t1")
        manager.onRequest("t1", ResourceEvidence("https://x.test/a.mp4"))
        idle("t1")
        assertEquals(1, manager.current("t1").items.size)
        assertEquals(1, prober.calls.get())
    }

    @Test
    fun theSameFileBehindAChangingTokenIsListedOnceWhenTheSizeMatches() {
        prober.answers["https://x.test/v.mp4?token=1"] = mp4("https://x.test/v.mp4?token=1", 3_000_000)
        prober.answers["https://x.test/v.mp4?token=2"] = mp4("https://x.test/v.mp4?token=2", 3_000_000)
        prober.answers["https://x.test/v.mp4?token=3"] = mp4("https://x.test/v.mp4?token=3", 3_000_001)
        manager.beginPage("t1", "https://page.test/")
        manager.onRequest("t1", ResourceEvidence("https://x.test/v.mp4?token=1"))
        waitFor("t1") { it.items.size == 1 }
        manager.onRequest("t1", ResourceEvidence("https://x.test/v.mp4?token=2"))
        idle("t1")
        assertEquals(1, manager.current("t1").items.size)
        manager.onRequest("t1", ResourceEvidence("https://x.test/v.mp4?token=3"))
        waitFor("t1") { it.items.size == 2 }
    }

    @Test
    fun unknownSizesNeverMergeDifferentAddresses() {
        prober.answers["https://x.test/a/v.mp4"] = mp4("https://x.test/a/v.mp4", 0).copy(contentLength = null)
        prober.answers["https://x.test/b/v.mp4"] = mp4("https://x.test/b/v.mp4", 0).copy(contentLength = null)
        manager.beginPage("t1", "https://page.test/")
        manager.onRequest("t1", ResourceEvidence("https://x.test/a/v.mp4"))
        manager.onRequest("t1", ResourceEvidence("https://x.test/b/v.mp4"))
        waitFor("t1") { it.items.size == 2 }
    }

    @Test
    fun aNewPageClearsTheTabsListAndDropsLateAnswersForTheOldPage() {
        val gate = CompletableDeferred<Unit>()
        prober.gates["https://x.test/slow.mp4"] = gate
        prober.answers["https://x.test/slow.mp4"] = mp4("https://x.test/slow.mp4")
        manager.beginPage("t1", "https://page.test/one")
        manager.onRequest("t1", ResourceEvidence("https://x.test/slow.mp4"))
        waitFor("t1") { it.checking }
        manager.beginPage("t1", "https://page.test/two")
        gate.complete(Unit)
        Thread.sleep(200)
        assertTrue(manager.current("t1").items.isEmpty())
        assertEquals(false, manager.current("t1").checking)
    }

    @Test
    fun closingATabForgetsItsDetections() {
        prober.answers["https://x.test/a.mp4"] = mp4("https://x.test/a.mp4")
        manager.beginPage("t1", "https://page.test/")
        manager.onRequest("t1", ResourceEvidence("https://x.test/a.mp4"))
        waitFor("t1") { it.items.isNotEmpty() }
        manager.closeTab("t1")
        assertTrue(manager.current("t1").items.isEmpty())
    }

    @Test
    fun rejectedResourcesAreNotListedAndNotAskedAgain() {
        prober.answers["https://x.test/fake.mp4"] = ProbeResult.Reply(200, "https://x.test/fake.mp4", "text/html", 90_000, null)
        manager.beginPage("t1", "https://page.test/")
        repeat(3) { manager.onRequest("t1", ResourceEvidence("https://x.test/fake.mp4")) }
        Thread.sleep(150)
        idle("t1")
        assertTrue(manager.current("t1").items.isEmpty())
        manager.onRequest("t1", ResourceEvidence("https://x.test/fake.mp4"))
        Thread.sleep(100)
        assertEquals(1, prober.calls.get())
    }

    @Test
    fun irrelevantRequestsNeverReachTheProber() {
        manager.beginPage("t1", "https://page.test/")
        for (u in listOf("https://x.test/app.js", "https://x.test/s-1.ts", "https://x.test/video/page", "https://x.test/logo.png")) {
            manager.onRequest("t1", ResourceEvidence(u))
        }
        manager.onRequest("t1", ResourceEvidence("https://x.test/a.mp4", method = "POST"))
        Thread.sleep(100)
        assertEquals(0, prober.calls.get())
    }

    @Test
    fun aPageThatIsAMediaFileIsDetectedOnItsOwn() {
        prober.answers["https://x.test/direct.mp4"] = mp4("https://x.test/direct.mp4")
        manager.beginPage("t1", "https://x.test/direct.mp4")
        waitFor("t1") { it.items.isNotEmpty() }
    }

    @Test
    fun limitsAreEnforced() {
        val small = MediaDetectionManager(prober, scope, maxPerPage = 2)
        small.beginPage("t1", "https://page.test/")
        for (i in 1..4) {
            prober.answers["https://x.test/$i.mp4"] = mp4("https://x.test/$i.mp4", 1_000_000L + i)
            small.onRequest("t1", ResourceEvidence("https://x.test/$i.mp4"))
        }
        runBlocking { withTimeout(5_000) { small.state("t1").first { it.items.size == 2 && !it.checking && it.dropped == 2 } } }
    }

    @Test
    fun playerReportsEnrichDetectionsAndExplainLimitations() {
        prober.answers["https://x.test/clip"] = mp4("https://x.test/clip", 4_000_000)
        manager.beginPage("t1", "https://page.test/")
        manager.onPlayerReport(
            "t1",
            PagePlayerReport(
                listOf(
                    PlayerElement(false, "https://x.test/clip", false, "https://x.test/p.jpg", 61, 1280, 720, false),
                    PlayerElement(false, null, true, null, null, null, null, false),
                ),
            ),
        )
        val s = waitFor("t1") { it.items.isNotEmpty() }
        val m = s.items.single()
        assertEquals(61L, m.durationSeconds)
        assertEquals("https://x.test/p.jpg", m.thumbnailUrl)
        assertEquals("1280\u00D7720", m.qualityInfo)
        assertEquals(PageLimitation.BLOB_OR_MSE_PLAYER, s.limitation)
        manager.onPlayerReport("t1", PagePlayerReport(listOf(PlayerElement(false, null, true, null, null, null, null, true))))
        assertEquals(PageLimitation.DRM_PLAYER, manager.current("t1").limitation)
    }

    @Test
    fun downloadLinksAreCheckedLikeAnyCandidate() {
        prober.answers["https://x.test/get?id=1"] = ProbeResult.Reply(200, "https://x.test/get?id=1", "video/mp4", 2_000_000, "attachment; filename=\"a.mp4\"")
        manager.beginPage("t1", "https://page.test/")
        manager.onDownloadLink("t1", "https://x.test/get?id=1")
        assertEquals("a.mp4", waitFor("t1") { it.items.isNotEmpty() }.items.single().title)
        assertNull(manager.current("other").limitation)
    }
}
