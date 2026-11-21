package com.viddown.engine.newpipe

import com.viddown.engine.api.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatMapperTest {
    private fun stream(
        id: String?, role: StreamRole, url: String? = "https://cdn.example/$id", delivery: Delivery = Delivery.PROGRESSIVE_HTTP,
        height: Int? = null, bitrate: Int? = null, size: Long? = null, container: String? = "mp4", label: String? = null,
    ) = ExtractedStream(id, role, delivery, url, container, null, height = height, bitrateKbps = bitrate, sizeBytes = size, label = label)

    private fun media(vararg s: ExtractedStream, duration: Long? = 100, live: Boolean = false) =
        ExtractedMedia("Example", "A title", duration, null, null, live, s.toList())

    @Test
    fun offersOnlyCompleteFilesAndCountsWhatItLeavesOut() {
        val m = media(
            stream("18", StreamRole.VIDEO_AND_AUDIO, height = 360),
            stream("137", StreamRole.VIDEO_ONLY, height = 1080),
            stream("140", StreamRole.AUDIO_ONLY, container = "m4a", bitrate = 128),
            stream("hls", StreamRole.VIDEO_AND_AUDIO, delivery = Delivery.HLS, height = 720),
            stream("nourl", StreamRole.VIDEO_AND_AUDIO, url = null, height = 480),
        )
        val r = FormatMapper.map(m)
        assertEquals(listOf("av:18", "a:140"), r.formats.map { it.id })
        assertEquals(1, r.skippedVideoOnly)
        assertEquals(1, r.skippedManifest)
        assertEquals(1, r.skippedNoAddress)
        assertTrue(r.formats.none { it.requiresMerge })
        assertEquals(StreamKind.VIDEO_AND_AUDIO, r.formats[0].kind)
        assertEquals(StreamKind.AUDIO_ONLY, r.formats[1].kind)
    }

    @Test
    fun listsBestQualityFirstWithinAGroup() {
        val r = FormatMapper.map(media(stream("a", StreamRole.VIDEO_AND_AUDIO, height = 360), stream("b", StreamRole.VIDEO_AND_AUDIO, height = 720)))
        assertEquals(listOf(720, 360), r.formats.map { it.height })
    }

    @Test
    fun sizeIsExactWhenReportedAnEstimateFromBitrateAndDurationOtherwiseAndUnknownWithoutBoth() {
        val r = FormatMapper.map(
            media(
                stream("1", StreamRole.VIDEO_AND_AUDIO, height = 360, size = 5_000_000, bitrate = 800),
                stream("2", StreamRole.VIDEO_AND_AUDIO, height = 480, bitrate = 800),
                stream("3", StreamRole.VIDEO_AND_AUDIO, height = 720),
                duration = 100,
            ),
        )
        val byId = r.formats.associateBy { it.id }
        assertEquals(5_000_000L, byId["av:1"]!!.sizeBytes)
        assertFalse(byId["av:1"]!!.sizeIsEstimate)
        assertEquals(10_000_000L, byId["av:2"]!!.sizeBytes) // 800 kbps = 100 000 B/s x 100 s
        assertTrue(byId["av:2"]!!.sizeIsEstimate)
        assertNull(byId["av:3"]!!.sizeBytes)
        assertFalse(byId["av:3"]!!.sizeIsEstimate)
    }

    @Test
    fun noEstimateWithoutAKnownDuration() {
        val r = FormatMapper.map(media(stream("1", StreamRole.VIDEO_AND_AUDIO, height = 360, bitrate = 800), duration = null))
        assertNull(r.formats.single().sizeBytes)
    }

    @Test
    fun idsAreUniqueAndStableAndBlankNativeIdsUseTheirPosition() {
        val m = media(
            stream("22", StreamRole.VIDEO_AND_AUDIO, height = 720, container = "mp4"),
            stream("22", StreamRole.VIDEO_AND_AUDIO, height = 720, container = "webm"),
            stream(" ", StreamRole.AUDIO_ONLY, container = "m4a", bitrate = 64),
            stream(null, StreamRole.AUDIO_ONLY, container = "webm", bitrate = 96),
        )
        val ids = FormatMapper.assignIds(m.streams).map { it.first }
        assertEquals(listOf("av:22", "av:22~2", "a:n2", "a:n3"), ids)
        assertEquals(ids, FormatMapper.assignIds(m.streams).map { it.first })
        assertEquals("webm", FormatMapper.find(m, "av:22~2")!!.container)
    }

    @Test
    fun identicalStreamsListedTwiceAreShownOnce() {
        val a = stream("1", StreamRole.VIDEO_AND_AUDIO, height = 360)
        val r = FormatMapper.map(media(a, a.copy(nativeId = "2")))
        assertEquals(1, r.formats.size)
    }

    @Test
    fun findNeverSubstitutesAndRefusesStreamsThatCannotBeDownloaded() {
        val m = media(stream("v", StreamRole.VIDEO_ONLY, height = 1080), stream("18", StreamRole.VIDEO_AND_AUDIO, height = 360))
        assertNull(FormatMapper.find(m, "v:v"))
        assertNull(FormatMapper.find(m, "av:999"))
        assertNotNull(FormatMapper.find(m, "av:18"))
    }

    @Test
    fun audioTrackLabelIsKeptAsNote() {
        val r = FormatMapper.map(media(stream("1", StreamRole.AUDIO_ONLY, container = "m4a", label = "de")))
        assertEquals("de", r.formats.single().note)
    }

    @Test
    fun resolutionLabelsAreParsedOrUnknown() {
        assertEquals(ResolutionLabel.Parsed(720, 60.0), ResolutionLabel.parse("720p60"))
        assertEquals(ResolutionLabel.Parsed(1080, null), ResolutionLabel.parse("1080p"))
        assertEquals(ResolutionLabel.Parsed(2160, 60.0), ResolutionLabel.parse("2160p60 HDR"))
        assertEquals(ResolutionLabel.Parsed(null, null), ResolutionLabel.parse(""))
        assertEquals(ResolutionLabel.Parsed(null, null), ResolutionLabel.parse("medium"))
        assertEquals(ResolutionLabel.Parsed(null, null), ResolutionLabel.parse(null))
    }

    @Test
    fun cancelTokenRunsHooksOnceAndImmediatelyWhenAlreadyCancelled() {
        val t = CancelToken()
        var n = 0
        t.onCancel { n++ }
        val removed = t.onCancel { n += 100 }
        removed()
        t.cancel(); t.cancel()
        assertEquals(1, n)
        var late = 0
        t.onCancel { late++ }
        assertEquals(1, late)
        assertTrue(t.isCancelled)
    }

    // ------------------------------------------------------------------------------------------------ merged qualities

    private fun videoOnly(id: String, container: String, codec: String?, height: Int, bitrate: Int, size: Long? = null) = ExtractedStream(
        id, StreamRole.VIDEO_ONLY, Delivery.PROGRESSIVE_HTTP, "https://cdn.example/$id", container, null,
        height = height, videoCodec = codec, bitrateKbps = bitrate, sizeBytes = size,
    )

    private fun audioOnly(id: String, container: String, codec: String?, bitrate: Int, size: Long? = null, label: String? = null) = ExtractedStream(
        id, StreamRole.AUDIO_ONLY, Delivery.PROGRESSIVE_HTTP, "https://cdn.example/$id", container, null,
        audioCodec = codec, bitrateKbps = bitrate, sizeBytes = size, label = label,
    )

    @Test
    fun withAMergerEachVideoOnlyStreamIsPairedWithTheAudioOfItsOwnContainerFamily() {
        val m = media(
            stream("18", StreamRole.VIDEO_AND_AUDIO, height = 360),
            videoOnly("137", "mp4", "avc1.640028", 1080, 4000, size = 40_000_000),
            videoOnly("248", "webm", "vp09.00.40.08", 1080, 2500, size = 30_000_000),
            audioOnly("140", "m4a", "mp4a.40.2", 128, size = 3_000_000),
            audioOnly("251", "webm", "opus", 160, size = 3_500_000),
        )
        val r = FormatMapper.map(m, canMerge = true)
        val merged = r.formats.filter { it.requiresMerge }.associateBy { it.id }
        assertEquals(setOf("mrg:v:137+a:140", "mrg:v:248+a:251"), merged.keys)
        val mp4 = merged.getValue("mrg:v:137+a:140")
        assertEquals("mp4", mp4.container)
        assertEquals(StreamKind.VIDEO_AND_AUDIO, mp4.kind)
        assertEquals(43_000_000L, mp4.sizeBytes) // both sizes exact, so the sum is exact
        assertFalse(mp4.sizeIsEstimate)
        assertEquals("webm", merged.getValue("mrg:v:248+a:251").container)
        assertEquals(0, r.skippedVideoOnly)
        assertTrue(r.formats.any { it.id == "av:18" && !it.requiresMerge })
    }

    @Test
    fun aVideoOnlyStreamWithoutAnyAudioPartnerIsCountedNotOffered() {
        val r = FormatMapper.map(media(videoOnly("137", "mp4", "avc1.640028", 1080, 4000)), canMerge = true)
        assertTrue(r.formats.isEmpty())
        assertEquals(1, r.skippedVideoOnly)
    }

    @Test
    fun withoutAMergerVideoOnlyStreamsAreStillOnlyCounted() {
        val m = media(videoOnly("137", "mp4", "avc1.640028", 1080, 4000), audioOnly("140", "m4a", "mp4a.40.2", 128))
        val r = FormatMapper.map(m)
        assertTrue(r.formats.none { it.requiresMerge })
        assertEquals(1, r.skippedVideoOnly)
    }

    @Test
    fun codecsThatDoNotBelongInOneContainerGoIntoMatroska() {
        val m = media(videoOnly("248", "webm", "vp09.00.40.08", 1080, 2500), audioOnly("140", "m4a", "mp4a.40.2", 128))
        assertEquals("mkv", FormatMapper.map(m, canMerge = true).formats.single { it.requiresMerge }.container)
        val unknown = media(videoOnly("137", "mp4", null, 1080, 4000), audioOnly("140", "m4a", "mp4a.40.2", 128))
        assertEquals("mkv", FormatMapper.map(unknown, canMerge = true).formats.single { it.requiresMerge }.container)
    }

    @Test
    fun theSitesDefaultAudioTrackIsPreferredOverADubbedOne() {
        val m = media(
            videoOnly("137", "mp4", "avc1.640028", 1080, 4000),
            audioOnly("140", "m4a", "mp4a.40.2", 192, label = "Deutsch"),
            audioOnly("141", "m4a", "mp4a.40.2", 128, label = "English (original)"),
        )
        assertEquals("mrg:v:137+a:141", FormatMapper.map(m, canMerge = true).formats.single { it.requiresMerge }.id)
    }

    @Test
    fun aMergedSizeIsAnEstimateWhenEitherSizeIsOnlyEstimatedAndUnknownWhenEitherIsMissing() {
        val estimated = media(
            videoOnly("137", "mp4", "avc1.640028", 1080, 800, size = 10_000_000),
            audioOnly("140", "m4a", "mp4a.40.2", 80),
            duration = 100,
        )
        val e = FormatMapper.map(estimated, canMerge = true).formats.single { it.requiresMerge }
        assertEquals(10_000_000L + 1_000_000L, e.sizeBytes) // 80 kbps = 10 000 B/s x 100 s
        assertTrue(e.sizeIsEstimate)
        val unknown = media(videoOnly("137", "mp4", "avc1.640028", 1080, 800), audioOnly("140", "m4a", "mp4a.40.2", 80), duration = null)
        assertNull(FormatMapper.map(unknown, canMerge = true).formats.single { it.requiresMerge }.sizeBytes)
    }

    @Test
    fun aMergedIdIsFoundAgainOnlyWhileBothItsStreamsAreStillOffered() {
        val m = media(videoOnly("137", "mp4", "avc1.640028", 1080, 4000), audioOnly("140", "m4a", "mp4a.40.2", 128))
        val found = FormatMapper.select(m, "mrg:v:137+a:140")
        assertTrue(found is FormatMapper.Selection.Merged)
        found as FormatMapper.Selection.Merged
        assertEquals("137", found.video.nativeId)
        assertEquals("140", found.audio.nativeId)
        assertEquals("mp4", found.container)
        assertNull(FormatMapper.select(media(videoOnly("137", "mp4", "avc1.640028", 1080, 4000)), "mrg:v:137+a:140"))
        assertNull(FormatMapper.select(m, "mrg:v:140+a:137")) // the roles do not fit: never guessed
        assertNull(FormatMapper.select(m, "mrg:nonsense"))
    }

    @Test
    fun aSingleFileIdIsStillFoundAsOneStream() {
        val m = media(stream("18", StreamRole.VIDEO_AND_AUDIO, height = 360))
        assertTrue(FormatMapper.select(m, "av:18") is FormatMapper.Selection.Single)
        assertNull(FormatMapper.select(m, "av:19"))
    }
}
