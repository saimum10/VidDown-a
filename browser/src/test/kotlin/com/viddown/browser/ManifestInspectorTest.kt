package com.viddown.browser

import com.viddown.engine.api.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestInspectorTest {
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        low/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
        mid/index.m3u8
    """.trimIndent()

    private val vod = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXTINF:10.0,
        a.ts
        #EXTINF:9.5,
        b.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun recognisesHlsAndDashByTheirText() {
        assertTrue(ManifestInspector.looksLikeHls("\uFEFF\n#EXTM3U\n"))
        assertFalse(ManifestInspector.looksLikeHls("<html>"))
        assertTrue(ManifestInspector.looksLikeDash("<?xml version=\"1.0\"?>\n<MPD type=\"static\">"))
        assertTrue(ManifestInspector.looksLikeDash("<mpd:MPD xmlns:mpd=\"x\">"))
        assertFalse(ManifestInspector.looksLikeDash("<html><body>MPD</body></html>"))
    }

    @Test
    fun masterPlaylistReportsVariantsAndBestHeight() {
        val info = ManifestInspector.inspectHls(master, truncated = false)
        assertEquals(MediaType.HLS, info.type)
        assertEquals("up to 720p, 2 variants", info.qualityInfo)
        assertNull(info.durationSeconds)
        assertEquals(Protection.NONE, info.protection)
    }

    @Test
    fun vodPlaylistSumsDurationOnlyWhenFullyRead() {
        assertEquals(19L, ManifestInspector.inspectHls(vod, truncated = false).durationSeconds)
        assertEquals(false, ManifestInspector.inspectHls(vod, truncated = false).isLive)
        assertNull(ManifestInspector.inspectHls(vod, truncated = true).durationSeconds)
        val live = vod.replace("#EXT-X-ENDLIST", "")
        assertEquals(true, ManifestInspector.inspectHls(live, truncated = false).isLive)
        assertNull(ManifestInspector.inspectHls(live, truncated = true).isLive)
    }

    @Test
    fun hlsEncryptionIsDistinguishedFromDrm() {
        val aes = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.test/key\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST"
        assertEquals(Protection.ENCRYPTED, ManifestInspector.inspectHls(aes, false).protection)
        val none = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\na.ts"
        assertEquals(Protection.NONE, ManifestInspector.inspectHls(none, false).protection)
        val fairplay = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://abc\",KEYFORMAT=\"com.apple.streamingkeydelivery\",KEYFORMATVERSIONS=\"1\"\n#EXTINF:4,\na.ts"
        assertEquals(Protection.DRM, ManifestInspector.inspectHls(fairplay, false).protection)
        val widevine = "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:text/plain;base64,AA\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\""
        assertEquals(Protection.DRM, ManifestInspector.inspectHls(widevine, false).protection)
    }

    @Test
    fun dashReportsHeightsDurationAndContentProtection() {
        val mpd = """<?xml version="1.0"?>
            <MPD type="static" mediaPresentationDuration="PT1H2M3.5S">
              <Period><AdaptationSet>
                <Representation id="1" height="480" bandwidth="1"/>
                <Representation id="2" height="1080" bandwidth="2"/>
              </AdaptationSet></Period></MPD>"""
        val info = ManifestInspector.inspectDash(mpd, false)
        assertEquals(MediaType.DASH, info.type)
        assertEquals("up to 1080p, 2 streams", info.qualityInfo)
        assertEquals(3723L, info.durationSeconds)
        assertEquals(false, info.isLive)
        assertEquals(Protection.NONE, info.protection)
        val drm = mpd.replace("<Period>", "<Period><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/>")
        assertEquals(Protection.DRM, ManifestInspector.inspectDash(drm, false).protection)
        val dynamic = mpd.replace("type=\"static\"", "type=\"dynamic\"")
        assertNull(ManifestInspector.inspectDash(dynamic, false).durationSeconds)
        assertEquals(true, ManifestInspector.inspectDash(dynamic, false).isLive)
    }

    @Test
    fun isoDurationParsing() {
        assertEquals(90L, ManifestInspector.parseIsoDuration("PT1M30S"))
        assertEquals(86_400L + 3_600, ManifestInspector.parseIsoDuration("P1DT1H"))
        assertNull(ManifestInspector.parseIsoDuration("garbage"))
        assertNull(ManifestInspector.parseIsoDuration("PT"))
    }

    @Test
    fun attributeListsHandleQuotedCommas() {
        val a = ManifestInspector.parseAttributes("BANDWIDTH=1,CODECS=\"avc1,mp4a\",RESOLUTION=1x2")
        assertEquals("avc1,mp4a", a["CODECS"])
        assertEquals("1x2", a["RESOLUTION"])
        assertEquals("1", a["BANDWIDTH"])
    }
}
