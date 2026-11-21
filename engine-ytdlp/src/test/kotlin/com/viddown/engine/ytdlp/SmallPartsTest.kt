package com.viddown.engine.ytdlp

import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergePlannerTest {
    @Test
    fun choosesTheContainerByStreamCopyCompatibility() {
        assertEquals("mp4", MergePlanner.containerFor("avc1.640028", "mp4a.40.2"))
        assertEquals("webm", MergePlanner.containerFor("vp09.00.40.08", "opus"))
        assertEquals("webm", MergePlanner.containerFor("av01.0.08M.08", "opus"))
        assertEquals("mkv", MergePlanner.containerFor("avc1.640028", "opus"))
        assertEquals("mkv", MergePlanner.containerFor("vp9", "mp4a.40.2"))
        assertNull(MergePlanner.containerFor(null, "opus"))
        assertNull(MergePlanner.containerFor("avc1", "unknown"))
    }

    @Test
    fun fallsBackToMatroskaOnce() {
        assertEquals(listOf("mp4", "mkv"), MergePlanner.attempts("mp4"))
        assertEquals(listOf("mkv"), MergePlanner.attempts("mkv"))
    }
}

class ProgressParserTest {
    @Test
    fun parsesAProgressLine() {
        val p = ProgressParser.parseProgress("VDPROG|1048576|NA|20971520|524288.5|30")!!
        assertEquals(1048576L, p.downloadedBytes)
        assertNull(p.totalBytes)
        assertEquals(20971520L, p.estimatedTotalBytes)
        assertEquals(524288L, p.speedBytesPerSecond)
        assertEquals(30L, p.etaSeconds)
    }

    @Test
    fun ignoresOtherLinesAndGarbage() {
        assertNull(ProgressParser.parseProgress("[download] 50%"))
        assertNull(ProgressParser.parseProgress("VDPROG|1|2"))
        assertNull(ProgressParser.parseProgress("VDPROG|x|-5|NA|NA|NA")!!.totalBytes)
    }

    @Test
    fun parsesThePrintedFilePath() {
        assertEquals("/data/x/vd-stage-1.mp4", ProgressParser.parseFile("VDFILE|/data/x/vd-stage-1.mp4"))
        assertNull(ProgressParser.parseFile("VDFILE|NA"))
        assertNull(ProgressParser.parseFile("something else"))
    }
}

class YtDlpArgsTest {
    private val request = MediaRequest(
        url = "https://site.test/watch?v=1",
        headers = mapOf("Referer" to "https://site.test/", "Cookie" to "secret=1", "User-Agent" to "UA", "Authorization" to "Bearer x"),
    )

    @Test
    fun theAddressComesAfterTheSeparatorSoItCanNeverBeAnOption() {
        val args = YtDlpArgs.inspect(MediaRequest(url = "https://site.test/--exec"))
        assertEquals("--", args[args.size - 2])
        assertEquals("https://site.test/--exec", args.last())
    }

    @Test
    fun forwardsOnlyHarmlessHeadersAndNeverCookiesOrCredentials() {
        val joined = YtDlpArgs.inspect(request).joinToString(" ")
        assertTrue(joined.contains("Referer:https://site.test/"))
        assertTrue(joined.contains("User-Agent:UA"))
        assertFalse(joined.contains("secret"))
        assertFalse(joined.contains("Bearer"))
        assertFalse(joined.contains("no-check-certificate"))
    }

    @Test
    fun aCookieFileIsPassedAsAPathAndNeverAsCookieText() {
        for (args in listOf(
            YtDlpArgs.inspect(request, "/cache/vd-cookies-1.txt"),
            YtDlpArgs.playlist(request, 10, "/cache/vd-cookies-1.txt"),
            YtDlpArgs.download(request, "18", "/dir", "vd-stage-1.%(ext)s", "/cache/vd-cookies-1.txt"),
            YtDlpArgs.subtitle(request, "en", "/dir", "vd-sub", "/cache/vd-cookies-1.txt"),
        )) {
            assertEquals("/cache/vd-cookies-1.txt", args[args.indexOf("--cookies") + 1])
            assertTrue(args.indexOf("--cookies") < args.indexOf("--"))
            assertFalse(args.joinToString(" ").contains("secret"))
            assertFalse(args.joinToString(" ").contains("Bearer"))
        }
    }

    @Test
    fun withoutACookieFileThereIsNoCookiesOption() {
        assertFalse(YtDlpArgs.inspect(request).contains("--cookies"))
    }

    @Test
    fun headerValuesWithLineBreaksAreDropped() {
        val args = YtDlpArgs.inspect(MediaRequest("https://a.test/", headers = mapOf("Referer" to "x\r\nEvil: 1")))
        assertFalse(args.any { it.contains("Evil") })
    }

    @Test
    fun downloadUsesADirectoryOptionAndAFixedNameTemplate() {
        val args = YtDlpArgs.download(request, "137", "/data/100%/dir", "vd-stage-1.%(ext)s")
        assertEquals("/data/100%/dir", args[args.indexOf("-P") + 1])
        assertEquals("vd-stage-1.%(ext)s", args[args.indexOf("-o") + 1])
        assertEquals("137", args[args.indexOf("-f") + 1])
        assertEquals("never", args[args.indexOf("--fixup") + 1])
    }

    @Test
    fun formatIdsMustNotLookLikeSelectorExpressions() {
        for (ok in listOf("18", "hls-720p", "dash-video=2000000", "http-1080p")) assertTrue(ok, YtDlpArgs.isSafeFormatId(ok))
        for (bad in listOf("", "a+b", "a/b", "a,b", "a b", "[x]", "-f", "a;b")) assertFalse(bad, YtDlpArgs.isSafeFormatId(bad))
    }
}

class YtDlpErrorsTest {
    private fun err(stderr: String, code: Int = 1): DownloadError = YtDlpErrors.fromMessage(stderr, code)

    @Test
    fun mapsRestrictionsWithoutOfferingABypass() {
        val bot = err("ERROR: [site] Sign in to confirm you’re not a bot")
        assertEquals(ErrorKind.RESOURCE_UNAVAILABLE, bot.kind)
        assertTrue(bot.userMessage.contains("does not sign in"))
        assertTrue(err("ERROR: This video is private").userMessage.contains("login"))
        assertTrue(err("ERROR: Sign in to confirm your age. This video may be inappropriate for some users.").userMessage.contains("age-restricted"))
        assertTrue(err("ERROR: This video is not available in your country").userMessage.contains("region"))
        assertTrue(err("ERROR: This video is DRM protected").userMessage.contains("DRM"))
    }

    @Test
    fun saysThatNoJavaScriptRuntimeIsTheProblem() {
        val e = err("WARNING: [youtube] No supported JavaScript runtime could be found\nERROR: [youtube] abc: n challenge solving failed")
        assertEquals(ErrorKind.EXTRACTION_FAILED, e.kind)
        assertTrue(e.userMessage.contains("JavaScript runtime"))
    }

    @Test
    fun mapsNetworkTlsStorageAndHttpErrors() {
        assertEquals(ErrorKind.NETWORK_UNAVAILABLE, err("ERROR: Unable to download webpage: [Errno -2] Name or service not known").kind)
        assertEquals(ErrorKind.SERVER_REJECTED, err("ERROR: [certificate verify failed] unable to get local issuer certificate").kind)
        assertEquals(ErrorKind.INSUFFICIENT_STORAGE, err("ERROR: unable to write data: [Errno 28] No space left on device").kind)
        val http = err("ERROR: unable to download video data: HTTP Error 404: Not Found")
        assertEquals(404, http.httpStatus)
        assertEquals(ErrorKind.UNSUPPORTED_RESOURCE, err("ERROR: Unsupported URL: https://x.test/").kind)
        assertEquals(ErrorKind.UNSUPPORTED_FORMAT, err("ERROR: Requested format is not available. Use --list-formats").kind)
    }

    @Test
    fun aBrokenRuntimeIsANativeDependencyFailure() {
        val e = err("Traceback (most recent call last):\n  File \"x\"\nModuleNotFoundError: No module named '_ssl'")
        assertEquals(ErrorKind.NATIVE_DEPENDENCY_FAILED, e.kind)
    }

    @Test
    fun unknownMessagesStayVisibleAndSecretsAreRedacted() {
        val e = err("ERROR: something odd https://x.test/v?token=abc123 happened")
        assertEquals(ErrorKind.EXTRACTION_FAILED, e.kind)
        assertTrue(e.technicalDetail.contains("something odd"))
        assertFalse(e.technicalDetail.contains("abc123"))
    }

    @Test
    fun timeoutsAndStallsAreInterruptions() {
        val timedOut = ToolResult(-1, "", "", timedOut = true, stalled = false, truncated = false)
        assertEquals(ErrorKind.CONNECTION_INTERRUPTED, YtDlpErrors.fromRun(timedOut).kind)
        val stalled = ToolResult(-1, "", "", timedOut = false, stalled = true, truncated = false)
        assertEquals(ErrorKind.CONNECTION_INTERRUPTED, YtDlpErrors.fromRun(stalled).kind)
    }
}

class ProbeAndValidatorTest {
    private val mp4Json = """{"streams":[{"codec_type":"video","codec_name":"h264"},{"codec_type":"audio","codec_name":"aac"}],
        "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"120.5"}}"""
    private val coverArtJson = """{"streams":[{"codec_type":"audio","codec_name":"aac"},
        {"codec_type":"video","codec_name":"mjpeg","disposition":{"attached_pic":1}}],"format":{"format_name":"mov,mp4,m4a","duration":"10"}}"""
    private val tsJson = """{"streams":[{"codec_type":"video","codec_name":"h264"}],"format":{"format_name":"mpegts","duration":"4"}}"""

    @Test
    fun parsesFfprobeOutput() {
        val p = ProbeParser.parse(mp4Json)!!
        assertTrue(p.hasVideo && p.hasAudio)
        assertFalse(p.isMpegTs)
        assertEquals(120.5, p.durationSeconds!!, 0.0)
        assertTrue(ProbeParser.parse(tsJson)!!.isMpegTs)
        assertNull(ProbeParser.parse("not json"))
        assertNull(ProbeParser.parse("{}"))
    }

    @Test
    fun coverArtIsNotVideo() {
        val p = ProbeParser.parse(coverArtJson)!!
        assertFalse(p.hasVideo)
        assertTrue(p.hasAudio)
    }

    @Test
    fun validatesStreamsAndDuration() {
        val p = ProbeParser.parse(mp4Json)!!
        assertNull(OutputValidator.problem(p, StreamKind.VIDEO_AND_AUDIO, 1000, 120))
        assertNotNull(OutputValidator.problem(p, StreamKind.VIDEO_AND_AUDIO, 0, 120))
        assertNotNull(OutputValidator.problem(p, StreamKind.VIDEO_AND_AUDIO, 1000, 600))
        assertNull(OutputValidator.problem(p, StreamKind.VIDEO_AND_AUDIO, 1000, null))
        val noAudio = ProbeParser.parse(tsJson)!!
        assertNotNull(OutputValidator.problem(noAudio, StreamKind.VIDEO_AND_AUDIO, 1000, null))
        assertNull(OutputValidator.problem(noAudio, StreamKind.VIDEO_ONLY, 1000, null))
        assertNotNull(OutputValidator.problem(noAudio, StreamKind.AUDIO_ONLY, 1000, null))
    }
}
