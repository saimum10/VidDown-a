package com.viddown.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** What the browser shows for one tab: the detections, whether the server is still being asked, and why there may be none. */
data class TabDetectionState(
    val items: List<DetectedMedia> = emptyList(),
    /** True while at least one address is still being checked. */
    val checking: Boolean = false,
    val limitation: PageLimitation? = null,
    /** Detections that were dropped because the per-page limit was reached. */
    val dropped: Int = 0,
)

/**
 * Collects the media a page loads, per tab:
 *  * only evidence counts (see [MediaClassifier]): an address is asked about once, and what the server answers decides;
 *  * the same file is listed once (same address without fragment, or same host + path + size + type behind a changing token);
 *  * every detection belongs to the tab and the page it was found on: a new page clears that tab's list, and an answer that arrives
 *    for an older page is thrown away;
 *  * work is bounded (3 requests at a time, 32 waiting per tab, 30 detections per page) and a closed tab cancels its requests.
 * Nothing in here touches a WebView, so it can live as long as the process.
 */
class MediaDetectionManager(
    private val prober: MediaProber,
    private val scope: CoroutineScope,
    /** Headers for the probe of one address (Cookie, Referer, User-Agent of the tab). Called on a background thread. */
    private val probeHeaders: (tabId: String, url: String) -> Map<String, String> = { _, _ -> emptyMap() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPerPage: Int = 30,
    private val maxPendingPerTab: Int = 32,
    probeConcurrency: Int = 3,
) {
    private data class Tab(
        val generation: Int = 0,
        val pageUrl: String? = null,
        val items: List<DetectedMedia> = emptyList(),
        val pending: Set<String> = emptySet(),
        val handled: Set<String> = emptySet(),
        val players: Map<String, PlayerElement> = emptyMap(),
        val limitation: PageLimitation? = null,
        val dropped: Int = 0,
    )

    private val tabs = MutableStateFlow<Map<String, Tab>>(emptyMap())
    private val jobs = HashMap<String, Job>()
    private val permits = Semaphore(probeConcurrency)

    // --------------------------------------------------------------------------------------------------------- queries

    fun state(tabId: String): Flow<TabDetectionState> = tabs.map { it[tabId].toState() }.distinctUntilChanged()

    fun current(tabId: String): TabDetectionState = tabs.value[tabId].toState()

    private fun Tab?.toState() =
        if (this == null) TabDetectionState() else TabDetectionState(items, pending.isNotEmpty(), limitation, dropped)

    // -------------------------------------------------------------------------------------------------------- lifecycle

    /** A new page starts in [tabId]: what was found on the old page no longer applies. */
    fun beginPage(tabId: String, pageUrl: String) {
        cancelJobs(tabId)
        tabs.update { map ->
            val old = map[tabId]
            map + (tabId to Tab(generation = (old?.generation ?: 0) + 1, pageUrl = UrlPolicy.withoutFragment(pageUrl)))
        }
        // A page that is itself a media file (the user opened a direct link) is evidence about itself.
        if (UrlPolicy.isHttp(pageUrl)) onRequest(tabId, ResourceEvidence(pageUrl, source = EvidenceSource.PAGE_ADDRESS))
    }

    fun closeTab(tabId: String) {
        cancelJobs(tabId)
        tabs.update { it - tabId }
    }

    fun clearAll() {
        val ids = synchronized(jobs) { jobs.keys.toList() }
        ids.forEach { cancelJobs(it) }
        tabs.value = emptyMap()
    }

    // ------------------------------------------------------------------------------------------------------------ input

    /** Called for every request of a page (any thread). Cheap for everything that cannot be media. */
    fun onRequest(tabId: String, evidence: ResourceEvidence) {
        val kind = MediaClassifier.candidate(evidence) ?: return
        val key = UrlPolicy.withoutFragment(evidence.url)
        var generation = -1
        tabs.update { map ->
            generation = -1
            val tab = map[tabId] ?: Tab()
            if (key in tab.handled || key in tab.pending || tab.pending.size >= maxPendingPerTab) return@update map
            generation = tab.generation
            map + (tabId to tab.copy(pending = tab.pending + key))
        }
        if (generation < 0) return
        val gen = generation
        scope.launch(jobFor(tabId)) {
            val verdict = try {
                val headers = probeHeaders(tabId, evidence.url)
                val probe = permits.withPermit {
                    prober.probe(ProbeRequest(evidence.url, headers, readManifestBody = kind != CandidateKind.MEDIA_EXTENSION))
                }
                MediaClassifier.classify(evidence, kind, probe) to probe
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Verdict.Rejected("probe crashed: ${t.javaClass.simpleName}") to null
            }
            record(tabId, gen, key, evidence, verdict.first, verdict.second)
        }
    }

    /** What the page's own <video>/<audio> elements say (see [PagePlayer]). */
    fun onPlayerReport(tabId: String, report: PagePlayerReport) {
        val toProbe = ArrayList<String>()
        tabs.update { map ->
            toProbe.clear()
            val tab = map[tabId] ?: return@update map
            val players = LinkedHashMap<String, PlayerElement>()
            var blob = false
            var drm = false
            for (e in report.elements) {
                if (e.isBlob) blob = true
                if (e.usesDrm) drm = true
                val src = e.httpSource ?: continue
                val key = UrlPolicy.withoutFragment(src)
                players[key] = e
                toProbe += src
            }
            val limitation = when {
                drm -> PageLimitation.DRM_PLAYER
                blob -> PageLimitation.BLOB_OR_MSE_PLAYER
                else -> null
            }
            map + (tabId to tab.copy(players = players, limitation = limitation, items = tab.items.map { enrich(it, players[it.key]) }))
        }
        toProbe.distinct().forEach { onRequest(tabId, ResourceEvidence(it, source = EvidenceSource.PLAYER_ELEMENT)) }
    }

    /** A link the WebView handed to its download listener (a file the user tapped): asked about like any other candidate. */
    fun onDownloadLink(tabId: String, url: String) {
        onRequest(tabId, ResourceEvidence(url, source = EvidenceSource.DOWNLOAD_LINK))
    }

    // ----------------------------------------------------------------------------------------------------------- internals

    private fun record(tabId: String, generation: Int, key: String, evidence: ResourceEvidence, verdict: Verdict, probe: ProbeResult?) {
        tabs.update { map ->
            val tab = map[tabId] ?: return@update map
            if (tab.generation != generation) return@update map // the page changed while the server was being asked
            val base = tab.copy(pending = tab.pending - key, handled = tab.handled + key)
            if (verdict !is Verdict.Media) return@update map + (tabId to base)
            val finalUrl = (probe as? ProbeResult.Reply)?.finalUrl ?: evidence.url
            val candidate = enrich(
                DetectedMedia(
                    key = key, tabId = tabId, url = evidence.url, type = verdict.type, title = verdict.title, mime = verdict.mime,
                    extension = verdict.extension, sizeBytes = verdict.sizeBytes, qualityInfo = verdict.qualityInfo,
                    durationSeconds = verdict.durationSeconds, pageUrl = tab.pageUrl, method = verdict.method,
                    protection = verdict.protection, isLive = verdict.isLive, detectedAtMillis = clock(),
                ),
                tab.players[key],
            )
            val sameFile = tab.items.any { it.key == key || sameResource(it, candidate, finalUrl) }
            when {
                sameFile -> map + (tabId to base)
                tab.items.size >= maxPerPage -> map + (tabId to base.copy(dropped = tab.dropped + 1))
                else -> map + (tabId to base.copy(items = tab.items + candidate))
            }
        }
    }

    /** The same file behind a changing token: same host, path, size and type (the size must be known: unknown never matches). */
    private fun sameResource(existing: DetectedMedia, other: DetectedMedia, otherFinalUrl: String): Boolean {
        val size = other.sizeBytes ?: return false
        if (existing.sizeBytes != size || existing.type != other.type) return false
        return UrlPolicy.hostOf(existing.url) == UrlPolicy.hostOf(otherFinalUrl) &&
            UrlPolicy.pathOf(existing.url) == UrlPolicy.pathOf(otherFinalUrl)
    }

    private fun enrich(media: DetectedMedia, element: PlayerElement?): DetectedMedia {
        if (element == null) return media
        val resolution = if (element.width != null && element.height != null && !element.isAudio) "${element.width}\u00D7${element.height}" else null
        return media.copy(
            durationSeconds = media.durationSeconds ?: element.durationSeconds,
            thumbnailUrl = media.thumbnailUrl ?: element.posterUrl,
            qualityInfo = media.qualityInfo ?: resolution,
        )
    }

    private fun jobFor(tabId: String): Job = synchronized(jobs) {
        jobs.getOrPut(tabId) { SupervisorJob(scope.coroutineContext[Job]) }
    }

    private fun cancelJobs(tabId: String) {
        val job = synchronized(jobs) { jobs.remove(tabId) }
        job?.cancel()
    }
}
