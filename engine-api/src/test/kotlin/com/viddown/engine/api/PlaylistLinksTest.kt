package com.viddown.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistLinksTest {
    @Test
    fun youtubePlaylistAddressesAreRecognised() {
        assertTrue(PlaylistLinks.looksLikePlaylist("https://www.youtube.com/playlist?list=PLabc_123-x"))
        assertTrue(PlaylistLinks.looksLikePlaylist("https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc123&index=2"))
        assertTrue(PlaylistLinks.looksLikePlaylist("https://youtu.be/dQw4w9WgXcQ?list=PLabc123"))
        assertTrue(PlaylistLinks.looksLikePlaylist("https://music.youtube.com/playlist?list=OLAK5uy_x"))
    }

    @Test
    fun aSingleVideoOrAnotherSiteIsNotTakenForAPlaylist() {
        assertFalse(PlaylistLinks.looksLikePlaylist("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(PlaylistLinks.looksLikePlaylist("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(PlaylistLinks.looksLikePlaylist("https://vimeo.com/123?list=abc"))
        assertFalse(PlaylistLinks.looksLikePlaylist("https://evil.test/watch?list=PL1&u=youtube.com"))
        assertFalse(PlaylistLinks.looksLikePlaylist("https://www.youtube.com/watch?v=abc&list="))
    }

    @Test
    fun theListIdIsOnlyTakenWhenItIsPlain() {
        assertEquals("PLabc123", PlaylistLinks.youtubeListId("https://www.youtube.com/watch?v=x&list=PLabc123#t=5"))
        assertNull(PlaylistLinks.youtubeListId("https://www.youtube.com/playlist?list=PL abc"))
        assertNull(PlaylistLinks.youtubeListId("https://www.youtube.com/playlist?list=PL%20abc"))
        assertNull(PlaylistLinks.youtubeListId("https://www.youtube.com/playlist?list=a/b"))
    }

    @Test
    fun theCanonicalAddressOnlyCarriesTheId() {
        assertEquals(
            "https://www.youtube.com/playlist?list=PLabc123",
            PlaylistLinks.canonicalYoutubePlaylistUrl("https://www.youtube.com/watch?v=x&list=PLabc123&index=4"),
        )
        assertNull(PlaylistLinks.canonicalYoutubePlaylistUrl("https://www.youtube.com/watch?v=x"))
    }

    @Test
    fun aPlaylistErrorIsFlaggedAndNothingElseIs() {
        assertTrue(DownloadError.playlistLink(EngineId.YTDLP).isPlaylist)
        assertEquals(ErrorKind.UNSUPPORTED_RESOURCE, DownloadError.playlistLink(null).kind)
        assertFalse(DownloadError.unsupportedResource(EngineId.YTDLP).isPlaylist)
    }

    @Test
    fun theSingleVideoOfAWatchPageDropsOnlyThePlaylist() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            PlaylistLinks.singleVideoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc123&index=2&start_radio=1"),
        )
        assertEquals(
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=30",
            PlaylistLinks.singleVideoUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc123&t=30#x"),
        )
        assertEquals("https://youtu.be/dQw4w9WgXcQ", PlaylistLinks.singleVideoUrl("https://youtu.be/dQw4w9WgXcQ?list=PLabc123"))
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=zz", PlaylistLinks.singleVideoUrl("https://youtu.be/dQw4w9WgXcQ?list=PLabc123&si=zz"))
    }

    @Test
    fun aPlaylistPageOrAnotherSiteHasNoSingleVideo() {
        assertNull(PlaylistLinks.singleVideoUrl("https://www.youtube.com/playlist?list=PLabc123"))
        assertNull(PlaylistLinks.singleVideoUrl("https://www.youtube.com/watch?list=PLabc123"))
        assertNull(PlaylistLinks.singleVideoUrl("https://www.youtube.com/watch?v=abc"))
        assertNull(PlaylistLinks.singleVideoUrl("https://vimeo.com/123?v=1&list=abc"))
    }
}
