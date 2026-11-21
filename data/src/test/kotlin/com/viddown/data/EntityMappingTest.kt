package com.viddown.data

import com.viddown.data.db.DownloadEntity
import com.viddown.data.db.toEntity
import com.viddown.data.db.toTask
import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.ErrorKind
import com.viddown.engine.api.FormatOption
import com.viddown.engine.api.MediaRequest
import com.viddown.engine.api.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityMappingTest {
    private val format = FormatOption(
        id = "137+140", kind = StreamKind.VIDEO_ONLY, container = "mp4", width = 1920, height = 1080,
        videoCodec = "avc1", audioCodec = null, sizeBytes = 123_456L, sizeIsEstimate = true, requiresMerge = true,
    )

    private fun task(format: FormatOption? = this.format, error: DownloadError? = null) = DownloadTask(
        id = "id-1", engineId = EngineId.YTDLP,
        request = MediaRequest("https://x.test/v", "https://x.test/page", mapOf("Cookie" to "sid=SECRET")),
        format = format, title = "Title", durationSeconds = 187L, state = DownloadState.FAILED, bytesDownloaded = 10, totalBytes = 100,
        outputPath = "/out/a.mp4", error = error, createdAtMillis = 5L,
    )

    @Test
    fun fullRoundTripKeepsEngineFormatAndError() {
        val error = DownloadError.serverRejected(503, "GET https://x.test/v?token=SECRET")
        val back = task(error = error).toEntity(nowMillis = 99L).toTask()!!
        assertEquals(EngineId.YTDLP, back.engineId)
        assertEquals(format, back.format)
        assertEquals(DownloadState.FAILED, back.state)
        assertEquals(ErrorKind.SERVER_REJECTED, back.error!!.kind)
        assertEquals(503, back.error!!.httpStatus)
        assertEquals(error.suggestedAction, back.error!!.suggestedAction)
        assertEquals("Title", back.title)
        assertEquals(187L, back.durationSeconds)
        assertEquals(10L, back.bytesDownloaded)
        assertEquals(100L, back.totalBytes)
        assertEquals("/out/a.mp4", back.outputPath)
        assertEquals(5L, back.createdAtMillis)
    }

    @Test
    fun requestHeadersAreNeverPersisted() {
        val entity = task().toEntity(1L)
        assertFalse(entity.toString().contains("SECRET"))
        assertTrue(entity.toTask()!!.request.headers.isEmpty())
    }

    @Test
    fun missingFormatAndErrorStayNull() {
        val back = task(format = null, error = null).toEntity(1L).toTask()!!
        assertNull(back.format)
        assertNull(back.error)
    }

    @Test
    fun storedTechnicalDetailIsAlreadyRedacted() {
        val e = task(error = DownloadError.connectionInterrupted("Cookie: sid=SECRET2")).toEntity(1L)
        assertFalse(e.errorTechnicalDetail!!.contains("SECRET2"))
    }

    @Test
    fun rowsFromNewerVersionsAreSkippedInsteadOfCrashing() {
        val good = task().toEntity(1L)
        assertNull(good.copy(engine = "some_future_engine").toTask())
        assertNull(good.copy(state = "TELEPORTING").toTask())
        assertNull(good.copy(errorKind = "SOMETHING_NEW", errorUserMessage = "x").toTask()!!.error)
    }

    @Test
    fun entityKeepsTheUpdateTime() {
        val e: DownloadEntity = task().toEntity(nowMillis = 777L)
        assertEquals(777L, e.updatedAtMillis)
        assertEquals("ytdlp", e.engine)
    }
}
