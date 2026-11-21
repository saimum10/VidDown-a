package com.viddown.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMimeTypesTest {
    @Test
    fun extensionsMapBothWays() {
        assertEquals("video/mp4", MediaMimeTypes.forExtension("MP4"))
        assertEquals("video/webm", MediaMimeTypes.forExtension(".webm"))
        assertNull(MediaMimeTypes.forExtension("zip"))
        assertNull(MediaMimeTypes.forExtension(null))
        assertEquals("mp4", MediaMimeTypes.extensionFor("video/mp4; codecs=\"avc1.42E01E\""))
        assertEquals("m4a", MediaMimeTypes.extensionFor("Audio/MP4"))
        assertNull(MediaMimeTypes.extensionFor("text/html"))
        assertNull(MediaMimeTypes.extensionFor(null))
    }

    @Test
    fun everyMappedMimeRoundTripsToAKnownExtension() {
        for (ext in listOf("mp4", "webm", "mkv", "mov", "mp3", "m4a", "ogg", "opus", "flac", "wav")) {
            val mime = MediaMimeTypes.forExtension(ext)!!
            val back = MediaMimeTypes.extensionFor(mime)
            assertTrue("$ext -> $mime -> $back", MediaMimeTypes.isKnownMediaExtension(back))
        }
    }

    @Test
    fun audioExtensionsAreRecognised() {
        assertTrue(MediaMimeTypes.isAudioExtension("mp3"))
        assertTrue(MediaMimeTypes.isAudioExtension(".FLAC"))
        assertFalse(MediaMimeTypes.isAudioExtension("mp4"))
        assertFalse(MediaMimeTypes.isAudioExtension(null))
    }

    @Test
    fun normalizeDropsParametersAndCase() {
        assertEquals("video/mp4", MediaMimeTypes.normalize(" Video/MP4 ; charset=x"))
        assertNull(MediaMimeTypes.normalize(" ; x"))
        assertNull(MediaMimeTypes.normalize(null))
    }
}
