package com.viddown.engine.direct

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentTypePolicyTest {
    private fun accept(ct: String?, name: String?) = ContentTypePolicy.classify(ct, name) as Classification.Accept
    private fun reject(ct: String?, name: String?) = ContentTypePolicy.classify(ct, name) as Classification.Reject

    @Test fun videoAndAudioTypesAreAccepted() {
        assertEquals("mp4", accept("video/mp4", null).extension)
        assertEquals("mp3", accept("audio/mpeg", "x").extension)
        assertEquals("mkv", accept("video/x-matroska; foo=bar", null).extension)
    }
    @Test fun theNamesExtensionWinsWhenItIsKnownMedia() = assertEquals("m4v", accept("video/mp4", "a.m4v").extension)
    @Test fun htmlIsAWebPage() {
        assertEquals(RejectKind.WEB_PAGE, reject("text/html; charset=utf-8", "watch").kind)
        assertEquals(RejectKind.WEB_PAGE, reject("application/xhtml+xml", null).kind)
        assertEquals(RejectKind.WEB_PAGE, reject("text/plain", "a.mp4").kind)
    }
    @Test fun playlistsAreRefusedByTypeOrExtension() {
        assertEquals(RejectKind.STREAMING_PLAYLIST, reject("application/x-mpegURL", null).kind)
        assertEquals(RejectKind.STREAMING_PLAYLIST, reject("application/dash+xml", "a.mpd").kind)
        assertEquals(RejectKind.STREAMING_PLAYLIST, reject("application/octet-stream", "index.m3u8").kind)
        assertEquals(RejectKind.STREAMING_PLAYLIST, reject(null, "x.m3u8").kind)
    }
    @Test fun genericBinaryNeedsAMediaNameOrNoName() {
        assertEquals("mp4", accept("application/octet-stream", "clip.mp4").extension)
        assertNull(accept("application/octet-stream", "stream").extension)
        assertEquals(RejectKind.NOT_MEDIA, reject("application/octet-stream", "tool.exe").kind)
        assertEquals(RejectKind.NOT_MEDIA, reject("application/zip", "a.zip").kind)
        assertEquals(RejectKind.NOT_MEDIA, reject("image/png", "a.png").kind)
        assertEquals(RejectKind.NOT_MEDIA, reject("application/pdf", null).kind)
    }
    @Test fun missingTypeNeedsAMediaName() {
        assertEquals("webm", accept(null, "a.webm").extension)
        assertEquals(RejectKind.NOT_MEDIA, reject(null, null).kind)
        assertEquals(RejectKind.NOT_MEDIA, reject(null, "a.bin").kind)
    }
    @Test fun applicationMp4IsVideo() = assertEquals("mp4", accept("application/mp4", null).extension)
}

class RetryPolicyTest {
    @Test fun backoffDoublesAndIsCapped() {
        val p = RetryPolicy(maxRetries = 5, baseDelayMs = 1000, maxDelayMs = 8000, random = Random(1))
        val d = (1..6).map { p.delayMs(it) }
        for ((i, v) in d.withIndex()) {
            val nominal = minOf(1000L shl i, 8000L)
            assertTrue("retry ${i + 1}: $v vs $nominal", v >= nominal * 8 / 10 - 1 && v <= minOf(nominal * 12 / 10, 8000L))
        }
        assertTrue(p.delayMs(20) <= 8000)
    }
    @Test fun retryAfterRaisesButNeverExceedsTheCap() {
        val p = RetryPolicy(baseDelayMs = 100, maxDelayMs = 5000, random = Random(2))
        assertTrue(p.delayMs(1, retryAfterMs = 3000) >= 3000)
        assertEquals(5000, p.delayMs(1, retryAfterMs = 999_999))
        assertTrue(p.delayMs(1, retryAfterMs = 0) <= 120)
    }
    @Test fun zeroDelaysWork() = assertEquals(0, RetryPolicy(baseDelayMs = 0, maxDelayMs = 0).delayMs(3))
    @Test fun invalidArgumentsAreRejected() {
        try { RetryPolicy(maxRetries = -1); throw AssertionError("expected") } catch (_: IllegalArgumentException) {}
        try { RetryPolicy().delayMs(0); throw AssertionError("expected") } catch (_: IllegalArgumentException) {}
    }
    @Test fun noneNeverRetries() = assertEquals(0, RetryPolicy.NONE.maxRetries)
}

class SpeedMeterTest {
    @Test fun noSpeedUntilEnoughTimeHasPassed() {
        val m = SpeedMeter()
        m.record(0, 0); m.record(500, 500_000)
        assertNull(m.bytesPerSecond())
        assertNull(m.etaSeconds(500_000, 10_000_000))
    }
    @Test fun steadyTransferGivesTheRightSpeedAndEta() {
        val m = SpeedMeter()
        for (i in 0..4) m.record(i * 1000L, i * 1_000_000L)
        assertEquals(1_000_000L, m.bytesPerSecond())
        assertEquals(6L, m.etaSeconds(4_000_000, 10_000_000))
        assertNull("unknown total -> no ETA", m.etaSeconds(4_000_000, null))
        assertNull("finished -> no ETA", m.etaSeconds(10_000_000, 10_000_000))
    }
    @Test fun oldSamplesLeaveTheWindow() {
        val m = SpeedMeter(windowMs = 5000, minSpanMs = 2000)
        m.record(0, 0); m.record(1000, 10_000_000) // a fast burst
        for (i in 2..12) m.record(i * 1000L, 10_000_000L + (i - 1) * 100_000L) // then slow
        assertTrue("${m.bytesPerSecond()}", m.bytesPerSecond()!! in 90_000..110_000)
    }
    @Test fun aStalledTransferHasNoSpeed() {
        val m = SpeedMeter()
        m.record(0, 100); m.record(3000, 100)
        assertNull(m.bytesPerSecond())
    }
    @Test fun resetForgetsTheGap() {
        val m = SpeedMeter()
        for (i in 0..3) m.record(i * 1000L, i * 1000L)
        m.reset()
        assertNull(m.bytesPerSecond())
    }
    @Test fun badSamplesAreIgnored() {
        val m = SpeedMeter()
        m.record(1000, 5000); m.record(500, 9000); m.record(2000, 4000)
        assertNull(m.bytesPerSecond())
    }
}

class ResumeMetaTest {
    @Test fun roundTripsThroughAFile() {
        val f = File.createTempFile("meta", ".txt")
        try {
            ResumeMeta("\"abc\"", "Wed, 21 Oct 2026 07:28:00 GMT").write(f)
            val r = ResumeMeta.read(f)!!
            assertEquals("\"abc\"", r.etag)
            assertEquals("\"abc\"", r.validator)
            assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", r.lastModified)
        } finally { f.delete() }
    }
    @Test fun weakEtagFallsBackToLastModified() {
        assertEquals("date", ResumeMeta("W/\"x\"", "date").validator)
        assertNull(ResumeMeta("W/\"x\"", null).validator)
        assertNull(ResumeMeta(null, null).validator)
        assertNull(ResumeMeta(" ", " ").validator)
    }
    @Test fun missingOrDamagedFilesGiveNull() {
        assertNull(ResumeMeta.read(File("/nonexistent/meta")))
        val f = File.createTempFile("meta", ".txt")
        try { f.writeText("garbage without equals"); assertNull(ResumeMeta.read(f)?.validator) } finally { f.delete() }
    }
    @Test fun newlinesInValuesCannotInjectLines() {
        val f = File.createTempFile("meta", ".txt")
        try {
            ResumeMeta("\"a\"\nlastModified=evil", null).write(f)
            assertNull(ResumeMeta.read(f)!!.lastModified)
        } finally { f.delete() }
    }
}
