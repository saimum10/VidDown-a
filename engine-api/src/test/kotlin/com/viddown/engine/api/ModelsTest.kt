package com.viddown.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private fun f(id: String, kind: StreamKind, h: Int? = null, kbps: Int? = null, size: Long? = null) =
        FormatOption(id = id, kind = kind, height = h, bitrateKbps = kbps, sizeBytes = size)

    @Test
    fun resolutionLabelIsNullWhenUnknown() {
        assertNull(f("a", StreamKind.AUDIO_ONLY).resolutionLabel)
        assertEquals("1080p", f("b", StreamKind.VIDEO_AND_AUDIO, h = 1080).resolutionLabel)
    }

    @Test
    fun displayOrderGroupsByKindThenBestFirstAndUnknownLast() {
        val sorted = listOf(
            f("audio128", StreamKind.AUDIO_ONLY, kbps = 128),
            f("v720", StreamKind.VIDEO_ONLY, h = 720),
            f("av360", StreamKind.VIDEO_AND_AUDIO, h = 360),
            f("av1080", StreamKind.VIDEO_AND_AUDIO, h = 1080),
            f("avUnknown", StreamKind.VIDEO_AND_AUDIO),
            f("orig", StreamKind.ORIGINAL_FILE),
            f("v1080", StreamKind.VIDEO_ONLY, h = 1080),
            f("audio256", StreamKind.AUDIO_ONLY, kbps = 256),
        ).sortedForDisplay().map { it.id }
        assertEquals(listOf("av1080", "av360", "avUnknown", "orig", "v1080", "v720", "audio256", "audio128"), sorted)
    }

    @Test
    fun mediaRequestToStringHidesSecrets() {
        val text = MediaRequest(
            url = "https://x.test/v.mp4?token=SECRET",
            headers = mapOf("Cookie" to "sid=SECRET2", "Referer" to "https://x.test/"),
        ).toString()
        assertFalse(text, text.contains("SECRET"))
        assertTrue(text, text.contains("https://x.test/"))
    }

    @Test
    fun engineIdStorageKeysRoundTripAndUnknownIsNull() {
        for (id in EngineId.entries) assertEquals(id, EngineId.fromStorageKey(id.storageKey))
        assertNull(EngineId.fromStorageKey("automatic"))
        assertNull(EngineId.fromStorageKey(null))
    }

    @Test
    fun availabilityFlag() {
        assertTrue(EngineAvailability.Available.isAvailable)
        assertFalse(EngineAvailability.NotAvailable("Not available in this build").isAvailable)
    }

    @Test
    fun capabilitiesAnswerPerMediaType() {
        val caps = EngineCapabilities(setOf(MediaType.DIRECT_MP4), supportsResume = true, listsFormats = false, mayNeedFfmpeg = false)
        assertEquals(EngineCapabilities.Handling.Supported, caps.handling(MediaType.DIRECT_MP4))
        assertTrue(caps.handling(MediaType.HLS) is EngineCapabilities.Handling.Unsupported)
    }
}
