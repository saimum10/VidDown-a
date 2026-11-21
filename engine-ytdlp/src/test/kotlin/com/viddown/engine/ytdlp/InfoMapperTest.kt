package com.viddown.engine.ytdlp

import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoMapperTest {
    private val sample = """
    {
      "title": "Sample video", "duration": 125.4, "uploader": "Someone", "thumbnail": "https://img.test/t.jpg",
      "extractor_key": "Sample", "is_live": false,
      "formats": [
        {"format_id":"sb0","ext":"mhtml","protocol":"mhtml","vcodec":"none","acodec":"none","format_note":"storyboard"},
        {"format_id":"18","ext":"mp4","protocol":"https","vcodec":"avc1.42001E","acodec":"mp4a.40.2","width":640,"height":360,"tbr":500.5,"filesize":10000000},
        {"format_id":"137","ext":"mp4","protocol":"https","vcodec":"avc1.640028","acodec":"none","width":1920,"height":1080,"fps":30,"tbr":4000,"filesize":50000000},
        {"format_id":"136","ext":"mp4","protocol":"https","vcodec":"avc1.4d401f","acodec":"none","width":1280,"height":720,"tbr":2000,"filesize_approx":25000000},
        {"format_id":"248","ext":"webm","protocol":"https","vcodec":"vp9","acodec":"none","width":1920,"height":1080,"tbr":2500,"filesize":30000000},
        {"format_id":"140","ext":"m4a","protocol":"https","vcodec":"none","acodec":"mp4a.40.2","abr":129,"tbr":129,"filesize":2000000},
        {"format_id":"251","ext":"webm","protocol":"https","vcodec":"none","acodec":"opus","abr":135,"tbr":135,"filesize":2100000},
        {"format_id":"hls-1","ext":"mp4","protocol":"m3u8","vcodec":"avc1.4d401f","acodec":"mp4a.40.2","height":720},
        {"format_id":"seg-1","ext":"mp4","protocol":"m3u8_native","vcodec":"avc1.4d401f","acodec":"mp4a.40.2","height":480},
        {"format_id":"drm-1","ext":"mp4","protocol":"https","vcodec":"avc1.4d401f","acodec":"mp4a.40.2","height":1080,"has_drm":true},
        {"format_id":"bad id","ext":"mp4","protocol":"https","vcodec":"avc1","acodec":"mp4a"}
      ]
    }
    """.trimIndent()

    private fun map(json: String = sample) = InfoMapper.map(Json.parse(json).asMap()!!)
    private fun List<FormatOption>.byId(id: String): FormatOption? = firstOrNull { it.id == id }

    @Test
    fun readsTheDescriptionOfTheMedia() {
        val m = map()
        assertEquals("Sample video", m.title)
        assertEquals(125L, m.durationSeconds)
        assertEquals("Someone", m.uploader)
        assertEquals("https://img.test/t.jpg", m.thumbnailUrl)
        assertFalse(m.isLive)
    }

    @Test
    fun offersCompleteVideoOnlyAudioOnlyAndMergedChoices() {
        val f = map().formats
        assertEquals(StreamKind.VIDEO_AND_AUDIO, f.byId("18")!!.kind)
        assertEquals(StreamKind.VIDEO_ONLY, f.byId("137")!!.kind)
        assertEquals(StreamKind.AUDIO_ONLY, f.byId("140")!!.kind)
        assertEquals(StreamKind.VIDEO_AND_AUDIO, f.byId("seg-1")!!.kind)

        val mp4 = f.byId("137+140")!!
        assertTrue(mp4.requiresMerge)
        assertEquals("mp4", mp4.container)
        assertEquals(1080, mp4.height)
        assertEquals("avc1.640028", mp4.videoCodec)
        assertEquals("mp4a.40.2", mp4.audioCodec)
        assertEquals(52_000_000L, mp4.sizeBytes)
        assertFalse(mp4.sizeIsEstimate)

        val webm = f.byId("248+251")!!
        assertEquals("webm", webm.container)
        assertEquals("opus", webm.audioCodec)
    }

    @Test
    fun anEstimatedSizeStaysFlaggedAsAnEstimate() {
        val f = map().formats
        assertTrue(f.byId("136")!!.sizeIsEstimate)
        assertEquals(25_000_000L, f.byId("136")!!.sizeBytes)
        assertTrue(f.byId("136+140")!!.sizeIsEstimate)
    }

    @Test
    fun leavesOutWhatThisBuildCannotDownload() {
        val m = map()
        val ids = m.formats.map { it.id }
        assertFalse("storyboard", ids.contains("sb0"))
        assertFalse("FFmpeg network downloader", ids.contains("hls-1"))
        assertFalse("DRM", ids.contains("drm-1"))
        assertFalse("unsafe id", ids.contains("bad id"))
        assertEquals(1, m.drmFormats)
        assertEquals(1, m.needsFfmpegNetwork)
    }

    @Test
    fun unknownValuesStayNull() {
        val m = map("""{"formats":[{"format_id":"1","ext":"mp4","protocol":"https"}]}""")
        val only = m.formats.single()
        assertEquals(StreamKind.ORIGINAL_FILE, only.kind)
        assertNull(only.height); assertNull(only.sizeBytes); assertNull(only.videoCodec); assertNull(only.audioCodec)
        assertNull(m.durationSeconds); assertNull(m.title)
    }

    @Test
    fun detectsLiveStreams() {
        assertTrue(map("""{"is_live":true,"formats":[]}""").isLive)
        assertTrue(map("""{"live_status":"is_live","formats":[]}""").isLive)
        assertTrue(map("""{"live_status":"is_upcoming","formats":[]}""").isUpcoming)
    }

    @Test
    fun noMergePartnerMeansNoMergedChoiceButTheVideoStaysListed() {
        val m = map("""{"formats":[{"format_id":"v","ext":"mp4","protocol":"https","vcodec":"avc1","acodec":"none","height":720}]}""")
        assertNotNull(m.formats.byId("v"))
        assertEquals(1, m.formats.size)
    }

    @Test
    fun saysWhyNothingIsOffered() {
        val drmOnly = map("""{"formats":[{"format_id":"a","ext":"mp4","protocol":"https","vcodec":"avc1","acodec":"mp4a","has_drm":true}]}""")
        assertTrue(drmOnly.formats.isEmpty())
        assertTrue(InfoMapper.whyNothing(drmOnly).contains("DRM"))
        val none = map("""{"formats":[]}""")
        assertTrue(InfoMapper.whyNothing(none).contains("no media formats"))
        val ffmpegOnly = map("""{"formats":[{"format_id":"a","ext":"mp4","protocol":"rtmp","vcodec":"avc1","acodec":"mp4a"}]}""")
        assertTrue(InfoMapper.whyNothing(ffmpegOnly).contains("FFmpeg"))
    }

    @Test
    fun listsHandMadeSubtitlesAndOnlyTheSpokenLanguageOfAutomaticCaptions() {
        val json = """
        {
          "title": "t", "language": "hi",
          "formats": [{"format_id":"18","ext":"mp4","protocol":"https","vcodec":"avc1","acodec":"mp4a","height":360}],
          "subtitles": {"en": [{"ext":"vtt","name":"English"}], "live_chat": [{"ext":"json"}], "bad lang": [{"ext":"vtt"}]},
          "automatic_captions": {"hi-orig": [{"ext":"vtt"}], "hi": [{"ext":"vtt"}], "fr": [{"ext":"vtt"}]}
        }
        """.trimIndent()
        val subs = map(json).subtitles
        assertEquals(listOf("en", "hi-orig"), subs.map { it.lang })
        assertEquals("English", subs[0].label)
        assertFalse(subs[0].automatic)
        assertTrue(subs[1].automatic)
    }

    @Test
    fun noSubtitlesWhenTheSiteListsNone() {
        assertTrue(map().subtitles.isEmpty())
    }
}
