package com.viddown.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadErrorTest {
    private fun everyKind(): List<DownloadError> = listOf(
        DownloadError.unsupportedResource(EngineId.NEWPIPE),
        DownloadError.noMediaDetected(),
        DownloadError.networkUnavailable(),
        DownloadError.connectionInterrupted(),
        DownloadError.serverRejected(403),
        DownloadError.resourceUnavailable(),
        DownloadError.engineUnavailable(EngineId.YTDLP, "Not available in this build"),
        DownloadError.nativeDependencyFailed(EngineId.YTDLP),
        DownloadError.ffmpegFailure(),
        DownloadError.insufficientStorage(),
        DownloadError.permissionDenied(),
        DownloadError.invalidDestination(),
        DownloadError.unsupportedFormat(EngineId.NEWPIPE),
        DownloadError.extractionFailed(EngineId.NEWPIPE),
        DownloadError.cancelled(),
    )

    @Test
    fun everyErrorKindHasAFactoryAndAMessage() {
        val errors = everyKind()
        assertEquals(ErrorKind.entries.toSet(), errors.map { it.kind }.toSet())
        for (e in errors) assertTrue("${e.kind} has no user message", e.userMessage.isNotBlank())
    }

    @Test
    fun technicalDetailIsRedacted() {
        val e = DownloadError.connectionInterrupted("GET https://x.test/a?token=SECRET failed\nCookie: sid=SECRET2")
        assertFalse(e.technicalDetail, e.technicalDetail.contains("SECRET"))
        assertTrue(e.technicalDetail.contains("https://x.test/a"))
    }

    @Test
    fun serverErrorsSuggestRetryClientErrorsDoNot() {
        assertEquals(SuggestedAction.RETRY, DownloadError.serverRejected(503).suggestedAction)
        assertEquals(SuggestedAction.RETRY, DownloadError.serverRejected(429).suggestedAction)
        assertEquals(SuggestedAction.OPEN_SOURCE_PAGE, DownloadError.serverRejected(404).suggestedAction)
        assertEquals(403, DownloadError.serverRejected(403).httpStatus)
    }

    @Test
    fun unsupportedResourceNamesTheEngineAndSuggestsChangingIt() {
        val e = DownloadError.unsupportedResource(EngineId.NEWPIPE, "HLS")
        assertTrue(e.userMessage, e.userMessage.contains("NewPipe"))
        assertTrue(e.userMessage, e.userMessage.contains("Settings"))
        assertEquals(SuggestedAction.CHANGE_ENGINE, e.suggestedAction)
    }

    @Test
    fun tlsFailureIsNotRetriedAndSaysCertificatesAreNeverBypassed() {
        val e = DownloadError.insecureConnection("PKIX path building failed")
        assertEquals(ErrorKind.SERVER_REJECTED, e.kind)
        assertEquals(null, e.httpStatus)
        assertTrue(e.userMessage, e.userMessage.contains("certificate"))
        assertEquals(SuggestedAction.OPEN_SOURCE_PAGE, e.suggestedAction)
    }

    @Test
    fun cancelledHasNoSuggestedAction() {
        assertEquals(SuggestedAction.NONE, DownloadError.cancelled().suggestedAction)
    }

    @Test
    fun accessRestrictionsSayTheyAreNotWorkedAround() {
        val e = DownloadError.accessRestricted(EngineId.NEWPIPE, "This video is age-restricted.", "AgeRestrictedContentException")
        assertEquals(ErrorKind.RESOURCE_UNAVAILABLE, e.kind)
        assertEquals(SuggestedAction.OPEN_SOURCE_PAGE, e.suggestedAction)
        assertTrue(e.userMessage.startsWith("This video is age-restricted."))
        assertTrue(e.userMessage.contains("does not sign in or bypass"))
    }

    @Test
    fun noUsableFormatNamesTheEngineAndTheReasonAndSuggestsAnotherEngine() {
        val e = DownloadError.noUsableFormat(EngineId.NEWPIPE, "Its video and audio are separate streams that need FFmpeg.", "videoOnly=3")
        assertEquals(ErrorKind.UNSUPPORTED_FORMAT, e.kind)
        assertEquals(SuggestedAction.CHANGE_ENGINE, e.suggestedAction)
        assertTrue(e.userMessage.contains("NewPipe"))
        assertTrue(e.userMessage.contains("FFmpeg"))
    }
}
