package com.viddown.browser

import com.viddown.engine.api.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClassifierTest {
    private fun ev(url: String, source: EvidenceSource = EvidenceSource.REQUEST, headers: Map<String, String> = emptyMap(), method: String = "GET") =
        ResourceEvidence(url, method, headers, source)

    private fun reply(type: String?, length: Long? = 5_000_000, code: Int = 200, url: String = "https://x.test/a", disposition: String? = null, body: String? = null, truncated: Boolean = false) =
        ProbeResult.Reply(code, url, type, length, disposition, body, truncated)

    private fun media(v: Verdict) = v as Verdict.Media

    // ------------------------------------------------------------------------------------------------ candidates

    @Test
    fun theWordVideoInAnAddressIsNotEvidence() {
        assertNull(MediaClassifier.candidate(ev("https://x.test/video/watch?v=1")))
        assertNull(MediaClassifier.candidate(ev("https://x.test/videos/index.html")))
        assertNull(MediaClassifier.candidate(ev("https://x.test/api?file=movie.mp4")))
    }

    @Test
    fun extensionsMakeCandidatesAndSegmentsAreIgnored() {
        assertEquals(CandidateKind.MEDIA_EXTENSION, MediaClassifier.candidate(ev("https://x.test/a/clip.mp4?t=1")))
        assertEquals(CandidateKind.MEDIA_EXTENSION, MediaClassifier.candidate(ev("https://x.test/a.WEBM")))
        assertEquals(CandidateKind.MANIFEST_EXTENSION, MediaClassifier.candidate(ev("https://x.test/a/master.m3u8")))
        assertEquals(CandidateKind.MANIFEST_EXTENSION, MediaClassifier.candidate(ev("https://x.test/a/manifest.mpd")))
        assertNull(MediaClassifier.candidate(ev("https://x.test/a/seg-1.ts")))
        assertNull(MediaClassifier.candidate(ev("https://x.test/a/chunk.m4s")))
        assertNull(MediaClassifier.candidate(ev("https://x.test/a/logo.png")))
        assertNull(MediaClassifier.candidate(ev("https://x.test/a/app.js")))
    }

    @Test
    fun aPageThatIsItselfATsFileIsAllowed() {
        assertEquals(CandidateKind.MEDIA_EXTENSION, MediaClassifier.candidate(ev("https://x.test/a.ts", EvidenceSource.PAGE_ADDRESS)))
    }

    @Test
    fun onlyGetOnHttpIsConsidered() {
        assertNull(MediaClassifier.candidate(ev("https://x.test/a.mp4", method = "POST")))
        assertNull(MediaClassifier.candidate(ev("blob:https://x.test/uuid")))
        assertNull(MediaClassifier.candidate(ev("data:video/mp4;base64,AAAA")))
    }

    @Test
    fun requestHeadersCanMarkAMediaElementRequest() {
        assertEquals(CandidateKind.MEDIA_ELEMENT_HEADER, MediaClassifier.candidate(ev("https://x.test/stream?id=1", headers = mapOf("sec-fetch-dest" to "video"))))
        assertNull(MediaClassifier.candidate(ev("https://x.test/stream?id=1", headers = mapOf("Sec-Fetch-Dest" to "image"))))
        assertNull(MediaClassifier.candidate(ev("https://x.test/stream?id=1", headers = mapOf("Range" to "bytes=0-"))))
    }

    @Test
    fun playerSourcesAndDownloadLinksAreCandidatesWhateverTheirAddressLooksLike() {
        assertEquals(CandidateKind.PLAYER_SOURCE, MediaClassifier.candidate(ev("https://x.test/stream?id=1", EvidenceSource.PLAYER_ELEMENT)))
        assertEquals(CandidateKind.DOWNLOAD_LINK, MediaClassifier.candidate(ev("https://x.test/get?id=1", EvidenceSource.DOWNLOAD_LINK)))
    }

    // -------------------------------------------------------------------------------------------------- classify

    @Test
    fun contentTypeDecidesDirectFiles() {
        val mp4 = media(MediaClassifier.classify(ev("https://x.test/a.mp4"), CandidateKind.MEDIA_EXTENSION, reply("video/mp4")))
        assertEquals(MediaType.DIRECT_MP4, mp4.type)
        assertEquals(DetectionMethod.CONTENT_TYPE, mp4.method)
        assertEquals(5_000_000L, mp4.sizeBytes)
        assertEquals("mp4", mp4.extension)
        val webm = media(MediaClassifier.classify(ev("https://x.test/a"), CandidateKind.PLAYER_SOURCE, reply("video/webm; codecs=vp9")))
        assertEquals(MediaType.DIRECT_WEBM, webm.type)
        val mkv = media(MediaClassifier.classify(ev("https://x.test/a.mkv"), CandidateKind.MEDIA_EXTENSION, reply("video/x-matroska")))
        assertEquals(MediaType.DIRECT_OTHER, mkv.type)
        val mp3 = media(MediaClassifier.classify(ev("https://x.test/a.mp3"), CandidateKind.MEDIA_EXTENSION, reply("audio/mpeg")))
        assertEquals(MediaType.DIRECT_OTHER, mp3.type)
    }

    @Test
    fun anExtensionWithAWebPageBehindItIsRejected() {
        val v = MediaClassifier.classify(ev("https://x.test/a.mp4"), CandidateKind.MEDIA_EXTENSION, reply("text/html; charset=utf-8", length = 90_000))
        assertTrue(v is Verdict.Rejected)
        assertTrue(MediaClassifier.classify(ev("https://x.test/a.mp4"), CandidateKind.MEDIA_EXTENSION, reply("image/png")) is Verdict.Rejected)
    }

    @Test
    fun errorsAndTinyFilesAreRejected() {
        assertTrue(MediaClassifier.classify(ev("https://x.test/a.mp4"), CandidateKind.MEDIA_EXTENSION, reply("video/mp4", code = 403)) is Verdict.Rejected)
        assertTrue(MediaClassifier.classify(ev("https://x.test/a.mp3"), CandidateKind.MEDIA_EXTENSION, reply("audio/mpeg", length = 4_000)) is Verdict.Rejected)
        // An unknown size is not a reason to reject.
        assertTrue(MediaClassifier.classify(ev("https://x.test/a.mp4"), CandidateKind.MEDIA_EXTENSION, reply("video/mp4", length = null)) is Verdict.Media)
    }

    @Test
    fun genericBinaryIsOnlyTrustedWithAMediaName() {
        val ok = media(MediaClassifier.classify(ev("https://x.test/dl?id=1"), CandidateKind.DOWNLOAD_LINK, reply("application/octet-stream", disposition = "attachment; filename=\"clip.mp4\"")))
        assertEquals(MediaType.DIRECT_MP4, ok.type)
        assertEquals(DetectionMethod.URL_EXTENSION, ok.method)
        assertEquals("clip.mp4", ok.title)
        assertTrue(MediaClassifier.classify(ev("https://x.test/dl?id=1"), CandidateKind.DOWNLOAD_LINK, reply("application/octet-stream")) is Verdict.Rejected)
        assertTrue(MediaClassifier.classify(ev("https://x.test/a.zip"), CandidateKind.DOWNLOAD_LINK, reply("application/zip")) is Verdict.Rejected)
    }

    @Test
    fun manifestsAreProvenByTheirText() {
        val hls = media(MediaClassifier.classify(ev("https://x.test/a.m3u8"), CandidateKind.MANIFEST_EXTENSION,
            reply("application/vnd.apple.mpegurl", body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720\nx.m3u8")))
        assertEquals(MediaType.HLS, hls.type)
        assertEquals(DetectionMethod.MANIFEST, hls.method)
        assertEquals("up to 720p, 1 variant", hls.qualityInfo)
        val dash = media(MediaClassifier.classify(ev("https://x.test/a.mpd"), CandidateKind.MANIFEST_EXTENSION,
            reply("application/dash+xml", body = "<MPD type=\"static\"></MPD>")))
        assertEquals(MediaType.DASH, dash.type)
        assertTrue(MediaClassifier.classify(ev("https://x.test/a.m3u8"), CandidateKind.MANIFEST_EXTENSION, reply("text/html", body = "<html>not found</html>")) is Verdict.Rejected)
        val drm = media(MediaClassifier.classify(ev("https://x.test/a.mpd"), CandidateKind.MANIFEST_EXTENSION,
            reply("application/dash+xml", body = "<MPD><ContentProtection schemeIdUri=\"urn:uuid:x\"/></MPD>")))
        assertEquals(Protection.DRM, drm.protection)
    }

    @Test
    fun aManifestTypeWithoutTextIsStillServerEvidence() {
        val v = media(MediaClassifier.classify(ev("https://x.test/live"), CandidateKind.PLAYER_SOURCE, reply("application/x-mpegURL", length = null)))
        assertEquals(MediaType.HLS, v.type)
        assertEquals(DetectionMethod.CONTENT_TYPE, v.method)
    }

    @Test
    fun whenTheServerCannotBeAskedOnlyAnExtensionSurvivesAndIsMarkedUnverified() {
        val v = media(MediaClassifier.classify(ev("https://x.test/a.mp4"), CandidateKind.MEDIA_EXTENSION, ProbeResult.Failed("timeout")))
        assertEquals(DetectionMethod.URL_EXTENSION, v.method)
        assertEquals(false, v.method.verified)
        assertNull(v.sizeBytes)
        assertTrue(MediaClassifier.classify(ev("https://x.test/stream?id=1"), CandidateKind.PLAYER_SOURCE, ProbeResult.Failed("timeout")) is Verdict.Rejected)
        assertTrue(MediaClassifier.classify(ev("https://x.test/seg.ts"), CandidateKind.MEDIA_EXTENSION, null) is Verdict.Rejected)
    }
}
