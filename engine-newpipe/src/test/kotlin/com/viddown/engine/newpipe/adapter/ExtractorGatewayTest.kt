package com.viddown.engine.newpipe.adapter

import com.viddown.engine.api.ErrorKind
import com.viddown.engine.newpipe.Delivery
import com.viddown.engine.newpipe.StreamRole
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** Runs the adapter against the REAL extractor classes (no network): conversion of real stream objects and error translation. */
class ExtractorGatewayTest {
    private val gateway = ExtractorGateway(OkHttpDownloader(OkHttpClient()))
    private val youtube = ServiceList.YouTube

    private fun info(type: StreamType = StreamType.VIDEO_STREAM) =
        StreamInfo(youtube.serviceId, "https://www.youtube.com/watch?v=abcdefghijk", "https://www.youtube.com/watch?v=abcdefghijk", type, "abcdefghijk", "A video", 0)

    private fun itag(id: Int, length: Long? = null): ItagItem = ItagItem.getItag(id).also { if (length != null) it.contentLength = length }

    @Test
    fun convertsRealStreamObjectsIntoRolesFormatsAndSizes() {
        val muxed = VideoStream.Builder().setId("18").setContent("https://cdn.example/18", true).setIsVideoOnly(false).setResolution("360p")
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MPEG_4).setItagItem(itag(18, 7_654_321L)).build()
        val videoOnly = VideoStream.Builder().setId("137").setContent("https://cdn.example/137", true).setIsVideoOnly(true).setResolution("1080p")
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MPEG_4).setItagItem(itag(137)).build()
        val audio = AudioStream.Builder().setId("140").setContent("https://cdn.example/140", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.M4A).setAverageBitrate(128).setItagItem(itag(140)).build()
        val i = info().apply {
            duration = 212
            uploaderName = "Someone"
            videoStreams = listOf(muxed)
            videoOnlyStreams = listOf(videoOnly)
            audioStreams = listOf(audio)
        }
        val m = gateway.convert(youtube, i)
        assertEquals("YouTube", m.serviceName)
        assertEquals("A video", m.title)
        assertEquals(212L, m.durationSeconds)
        assertEquals("Someone", m.uploader)
        assertFalse(m.isLive)
        assertEquals(listOf(StreamRole.VIDEO_AND_AUDIO, StreamRole.VIDEO_ONLY, StreamRole.AUDIO_ONLY), m.streams.map { it.role })
        val s = m.streams[0]
        assertEquals("https://cdn.example/18", s.url)
        assertEquals(Delivery.PROGRESSIVE_HTTP, s.delivery)
        assertEquals("mp4", s.container)
        assertEquals("video/mp4", s.mimeType)
        assertEquals(360, s.height)
        assertEquals(7_654_321L, s.sizeBytes)
        assertEquals(1080, m.streams[1].height)
        assertEquals("m4a", m.streams[2].container)
        assertEquals(128, m.streams[2].bitrateKbps)
    }

    @Test
    fun aStreamWhoseContentIsNotAnAddressHasNoUrlAndAManifestIsNotProgressive() {
        val dash = VideoStream.Builder().setId("d").setContent("<MPD/>", false).setIsVideoOnly(false).setResolution("720p")
            .setDeliveryMethod(DeliveryMethod.DASH).build()
        val hls = AudioStream.Builder().setId("h").setContent("https://cdn.example/x.m3u8", true).setDeliveryMethod(DeliveryMethod.HLS).build()
        val m = gateway.convert(youtube, info().apply { videoStreams = listOf(dash); audioStreams = listOf(hls) })
        assertNull(m.streams[0].url)
        assertEquals(Delivery.DASH, m.streams[0].delivery)
        assertEquals(720, m.streams[0].height)
        assertEquals(Delivery.HLS, m.streams[1].delivery)
    }

    @Test
    fun liveStreamsAreFlagged() {
        assertTrue(gateway.convert(youtube, info(StreamType.LIVE_STREAM)).isLive)
        assertTrue(gateway.convert(youtube, info(StreamType.AUDIO_LIVE_STREAM)).isLive)
        assertFalse(gateway.convert(youtube, info(StreamType.POST_LIVE_STREAM)).isLive)
    }

    @Test
    fun recognisesStreamPagesOfARealServiceWithoutAnyNetwork() {
        assertTrue(gateway.supportsPage("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(gateway.supportsPage("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("YouTube", gateway.serviceNameFor("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(gateway.supportsPage("https://www.youtube.com/@somechannel"))   // a channel is recognised but not a downloadable stream
        assertFalse(gateway.supportsPage("https://example.invalid/page"))
        assertFalse(gateway.supportsPage("not a url"))
        assertNull(gateway.serviceNameFor("https://example.invalid/page"))
    }

    // ----------------------------------------------------------------------------------------------- error translation

    @Test
    fun restrictionsAreReportedAsRestrictionsAndNeverWorkedAround() {
        fun t(e: Throwable) = ExtractionErrors.translate(e)
        for ((e, word) in listOf(
            AgeRestrictedContentException("age") to "age-restricted",
            PrivateContentException("private") to "private",
            PaidContentException("paid") to "paid",
            GeographicRestrictionException("geo") to "region",
            SignInConfirmNotBotException("bot") to "not a bot",
            ReCaptchaException("captcha", "https://x") to "captcha",
        )) {
            val r = t(e)
            assertEquals(e.javaClass.simpleName, ErrorKind.RESOURCE_UNAVAILABLE, r.kind)
            assertTrue("${e.javaClass.simpleName}: ${r.userMessage}", r.userMessage.contains(word, ignoreCase = true))
        }
        assertTrue(t(AgeRestrictedContentException("age")).userMessage.contains("does not sign in"))
    }

    @Test
    fun aNetworkProblemInsideAnExtractionExceptionIsStillANetworkProblem() {
        val r = ExtractionErrors.translate(ExtractionException("Could not fetch", UnknownHostException("no host")))
        assertEquals(ErrorKind.NETWORK_UNAVAILABLE, r.kind)
    }

    @Test
    fun genericFailuresAreClassifiedHonestly() {
        assertEquals(ErrorKind.RESOURCE_UNAVAILABLE, ExtractionErrors.translate(ContentNotAvailableException("gone")).kind)
        val parsing = ExtractionErrors.translate(ParsingException("could not get the title"))
        assertEquals(ErrorKind.EXTRACTION_FAILED, parsing.kind)
        assertTrue(parsing.technicalDetail!!.contains("may have changed"))
        assertEquals(ErrorKind.UNSUPPORTED_RESOURCE, ExtractionErrors.translate(ExtractionException("No service can handle the url = x")).kind)
        assertEquals(ErrorKind.EXTRACTION_FAILED, ExtractionErrors.translate(IllegalStateException("boom")).kind)
    }

    @Test
    fun initialisingTheLibraryIsLazy() {
        // Creating the gateway and asking about pages must not have set NewPipe's static downloader.
        val fresh = ExtractorGateway(OkHttpDownloader(OkHttpClient()))
        fresh.supportsPage("https://youtu.be/dQw4w9WgXcQ")
        assertTrue(NewPipe.getDownloader() == null)
    }
}
