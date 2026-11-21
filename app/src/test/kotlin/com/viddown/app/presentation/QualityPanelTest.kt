package com.viddown.app.presentation

import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityPanelTest {
    private fun muxed(id: String, h: Int, codec: String = "avc1.640028", container: String = "mp4", size: Long = 10_000_000, merge: Boolean = false, kbps: Int = 1000) =
        FormatOption(id, StreamKind.VIDEO_AND_AUDIO, container = container, height = h, videoCodec = codec, audioCodec = "mp4a.40.2", sizeBytes = size, requiresMerge = merge, bitrateKbps = kbps)

    private fun videoOnly(id: String, h: Int) = FormatOption(id, StreamKind.VIDEO_ONLY, container = "mp4", height = h, videoCodec = "avc1")
    private fun audio(id: String, codec: String, container: String, kbps: Int, size: Long = 4_000_000) =
        FormatOption(id, StreamKind.AUDIO_ONLY, container = container, audioCodec = codec, bitrateKbps = kbps, sizeBytes = size)

    private val youtube = listOf(
        muxed("18", 360, size = 9_700_000),
        muxed("137+140", 1080, merge = true, size = 44_400_000),
        muxed("136+140", 720, merge = true, size = 22_300_000),
        muxed("247+251", 720, codec = "vp09.00.40.08", container = "webm", merge = true, size = 20_000_000),
        muxed("133+140", 240, merge = true, size = 7_500_000),
        videoOnly("137", 1080),
        videoOnly("136", 720),
        audio("140", "mp4a.40.2", "m4a", 129),
        audio("251", "opus", "webm", 160),
        audio("139", "mp4a.40.5", "m4a", 48),
    )

    @Test
    fun oneRowPerResolutionAndNoVideoOnly() {
        val panel = QualityPanelBuilder.build(youtube)
        assertEquals(listOf(240, 360, 720, 1080), panel.video.map { it.height })
        assertFalse(panel.all.any { it.formatId == "137" || it.formatId == "136" })
        assertEquals(listOf("v240", "v360", "v720", "v1080"), panel.video.map { it.key })
    }

    @Test
    fun sameResolutionPrefersTheMostCompatibleStreamByDefault() {
        val panel = QualityPanelBuilder.build(youtube)
        assertEquals("136+140", panel.video.first { it.height == 720 }.formatId)
    }

    @Test
    fun codecChoiceDecidesWhichStreamOfAResolutionIsTheRow() {
        val panel = QualityPanelBuilder.build(youtube, AdvancedOptions(codec = "vp9"))
        assertEquals("247+251", panel.video.first { it.height == 720 }.formatId)
        // A resolution that has no such codec keeps its row.
        assertEquals("137+140", panel.video.first { it.height == 1080 }.formatId)
    }

    @Test
    fun containerChoiceFollowsAndMatroskaTakesAnyMergedPair() {
        val webm = QualityPanelBuilder.build(youtube, AdvancedOptions(container = "webm"))
        assertEquals("247+251", webm.video.first { it.height == 720 }.formatId)
        val mkv = AdvancedOptions(container = "mkv")
        val format = youtube.first { it.id == "136+140" }
        assertEquals("mkv", mkv.applyTo(format).container)
        assertEquals("mp4", mkv.applyTo(youtube.first { it.id == "18" }).container) // not merged by VidDown: left as it is
    }

    @Test
    fun audioRowsAreFastAndHighQualityWithoutInventingFormats() {
        val panel = QualityPanelBuilder.build(youtube)
        assertEquals(listOf("Fast", "High quality"), panel.audio.map { it.title })
        assertEquals("140", panel.audio[0].formatId)
        assertEquals("251", panel.audio[1].formatId)
        assertTrue(panel.audio.all { it.isAudio })
    }

    @Test
    fun titlesAndSizesLookLikeTheDesign() {
        val panel = QualityPanelBuilder.build(youtube)
        val p720 = panel.video.first { it.height == 720 }
        assertEquals("High quality (720p)", p720.title)
        assertEquals("Clear view and quick play", p720.description)
        assertEquals("21.3 MB", p720.sizeText)
        assertEquals("Fast (240p)", panel.video.first { it.height == 240 }.title)
    }

    @Test
    fun mainListIsShortAndAlwaysShowsTheSelectedRow() {
        val panel = QualityPanelBuilder.build(youtube)
        assertEquals(listOf(240, 720), panel.mainVideo(selectedId = null).map { it.height })
        val withSelected = panel.mainVideo(selectedId = "137+140").map { it.height }
        assertEquals(listOf(240, 720, 1080), withSelected)
    }

    @Test
    fun lastQualityIsFoundAgainOrReplacedByTheClosest() {
        val panel = QualityPanelBuilder.build(youtube)
        assertEquals("v720", panel.choiceFor("v720")?.key)
        assertEquals("v360", panel.choiceFor("v480")?.key) // this video has no 480p: the sharpest below it
        assertEquals("v240", panel.choiceFor("v144")?.key) // nothing below: the smallest there is
        assertEquals("a:high", panel.choiceFor("a:high")?.key)
        assertNull(panel.choiceFor(null))
        assertNull(panel.choiceFor("nonsense"))
    }

    @Test
    fun theBestKeyIsTheSharpestResolutionThisVideoHas() {
        val panel = QualityPanelBuilder.build(youtube)
        assertEquals(panel.video.filter { it.height != null }.maxOf { it.height!! }, panel.choiceFor("best")?.height)
    }

    @Test
    fun aVideoOnlyDefaultSelectsTheRowOfTheSameResolution() {
        val panel = QualityPanelBuilder.build(youtube)
        assertEquals("137+140", panel.choiceMatching(youtube.first { it.id == "137" })?.formatId)
    }

    @Test
    fun advancedOptionsOfferOnlyWhatExists() {
        val panel = QualityPanelBuilder.build(youtube)
        assertEquals(listOf("avc", "vp9"), panel.availability.codecs)
        assertEquals(listOf("mp4", "webm", "mkv"), panel.availability.containers)
        assertTrue(panel.availability.any)
        val single = QualityPanelBuilder.build(listOf(FormatOption("o", StreamKind.ORIGINAL_FILE)))
        assertFalse(single.availability.any)
        assertEquals("orig", single.video.single().key)
    }

    @Test
    fun onlyARealSizeIsShown() {
        val exact = FormatOption("a", StreamKind.VIDEO_AND_AUDIO, container = "mp4", height = 720, videoCodec = "avc1", audioCodec = "mp4a.40.2", sizeBytes = 25_000_000, bitrateKbps = 2000)
        assertEquals("23.8 MB", QualityPanelBuilder.build(listOf(exact)).video.single().sizeText)
        // A site's own estimate is not a real size, and a missing size is never worked out from the bitrate: nothing is shown.
        assertNull(QualityPanelBuilder.build(listOf(exact.copy(sizeIsEstimate = true))).video.single().sizeText)
        assertNull(QualityPanelBuilder.build(listOf(exact.copy(sizeBytes = null))).video.single().sizeText)
    }

    @Test
    fun theSizeOfARowFollowsTheCodecThatIsChosen() {
        val auto = QualityPanelBuilder.build(youtube)
        val vp9 = QualityPanelBuilder.build(youtube, AdvancedOptions(codec = "vp9"))
        assertEquals("21.3 MB", auto.video.first { it.height == 720 }.sizeText) // H.264 by default
        assertEquals("19.1 MB", vp9.video.first { it.height == 720 }.sizeText) // the VP9 stream
    }
}
