package com.viddown.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRoutingTest {
    @Test
    fun youTubeAddressesAreRecognised() {
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=abc",
            "http://m.youtube.com/watch?v=abc",
            "https://music.youtube.com/watch?v=abc",
            "https://youtu.be/abc",
            "https://youtu.be/abc?t=10",
            "https://www.youtube.com/shorts/abc",
            "https://www.youtube.com/playlist?list=PL123",
            "https://www.youtube-nocookie.com/embed/abc",
            "HTTPS://WWW.YOUTUBE.COM/watch?v=abc",
            "https://www.youtube.com:443/watch?v=abc",
            "https://user@www.youtube.com/watch?v=abc",
            "https://www.youtube.com./watch?v=abc",
            "www.youtube.com/watch?v=abc",
            "youtu.be/abc",
            "https://www.youtube.com",
            "https://youtu.be",
            "https://www.youtube.com?v=abc",
        ).forEach { assertTrue(it, EngineRouting.isYouTube(it)) }
    }

    @Test
    fun otherAddressesAreNotYouTube() {
        listOf(
            "https://vimeo.com/123",
            "https://www.facebook.com/watch?v=1",
            "https://example.com/video.mp4",
            "https://notyoutube.com/watch?v=abc",
            "https://youtube.com.evil.test/watch?v=abc",
            "https://evil.test/?u=https://www.youtube.com/watch?v=abc",
            "https://evil.test/youtube.com",
            "https://youtube.com@evil.test/watch",
            "https://fakeyoutu.be/abc",
            "https://youtu.be.evil.test/abc",
            "https://rr1---sn-abc.googlevideo.com/videoplayback",
            "https://youtube.example/watch",
            "",
            "   ",
            "https://",
            "not a link",
        ).forEach { assertFalse(it, EngineRouting.isYouTube(it)) }
    }

    @Test
    fun theEngineFollowsTheAddress() {
        assertEquals(EngineId.NEWPIPE, EngineRouting.engineFor("https://youtu.be/abc"))
        assertEquals(EngineId.YTDLP, EngineRouting.engineFor("https://vimeo.com/1"))
        assertEquals(EngineId.YTDLP, EngineRouting.engineFor(null))
    }

    @Test
    fun theModesKeepTheStorageKeysOfOlderVersions() {
        assertEquals(EngineMode.AUTO, EngineMode.fromStorageKey("auto"))
        assertEquals(EngineMode.NEWPIPE, EngineMode.fromStorageKey(EngineId.NEWPIPE.storageKey))
        assertEquals(EngineMode.YTDLP, EngineMode.fromStorageKey(EngineId.YTDLP.storageKey))
        assertEquals(null, EngineMode.fromStorageKey("automatic"))
        assertEquals(EngineMode.NEWPIPE, EngineMode.of(EngineId.NEWPIPE))
    }
}
