package com.viddown.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test
    fun plainNamesAreKept() {
        assertEquals("video.mp4", FileNames.sanitize("video.mp4"))
        assertEquals("My Holiday 2024.mp4", FileNames.sanitize("My Holiday 2024.mp4"))
    }

    @Test
    fun pathTraversalCollapsesToTheLastSegment() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.mp4", FileNames.sanitize("..\\..\\windows\\evil.mp4"))
        assertEquals("download", FileNames.sanitize(".."))
        assertEquals("download", FileNames.sanitize("."))
        assertEquals("download", FileNames.sanitize("/"))
        assertEquals("a.mp4", FileNames.sanitize("/sdcard/Download/a.mp4"))
    }

    @Test
    fun resultNeverContainsSeparatorsOrControlCharacters() {
        val nasty = listOf("a/b", "a\\b", "a\u0000b.mp4", "a\nb", "con.txt", "x:y*z?.mp4", "\"q\".mp4", "<a>|b", "tab\there.mp4")
        for (raw in nasty) {
            val out = FileNames.sanitize(raw)
            assertFalse(raw, out.any { it == '/' || it == '\\' || it.code < 32 })
            assertFalse(raw, out == "." || out == "..")
            assertTrue(raw, out.isNotEmpty())
        }
    }

    @Test
    fun forbiddenCharactersBecomeUnderscores() {
        assertEquals("x_y_z_.mp4", FileNames.sanitize("x:y*z?.mp4"))
        assertEquals("_q_.mp4", FileNames.sanitize("\"q\".mp4"))
    }

    @Test
    fun hiddenFilesAndTrailingDotsAreNormalised() {
        assertEquals("secret.mp4", FileNames.sanitize(".secret.mp4"))
        assertEquals("movie", FileNames.sanitize("movie..."))
        assertEquals("movie", FileNames.sanitize("  movie  "))
    }

    @Test
    fun whitespaceRunsFold() {
        assertEquals("a b c.mp4", FileNames.sanitize("a   b \t c.mp4"))
    }

    @Test
    fun directionOverrideCharactersAreRemoved() {
        // U+202E (right-to-left override) is used to make "movie\u202Egpj.exe" look like "moviexe.jpg"
        val out = FileNames.sanitize("movie\u202Egpj.exe")
        assertFalse(out.contains('\u202E'))
        assertEquals("moviegpj.exe", out)
    }

    @Test
    fun reservedDeviceNamesAreChanged() {
        assertEquals("_con.mp4", FileNames.sanitize("con.mp4"))
        assertEquals("_NUL", FileNames.sanitize("NUL"))
        assertEquals("console.mp4", FileNames.sanitize("console.mp4"))
    }

    @Test
    fun emptyOrNullUsesTheFallback() {
        assertEquals("download", FileNames.sanitize(null))
        assertEquals("download", FileNames.sanitize(""))
        assertEquals("clip", FileNames.sanitize("   ", "clip"))
        assertEquals("download", FileNames.sanitize("///", "///"))
    }

    @Test
    fun longNamesFitTheByteLimitAndKeepTheExtension() {
        val out = FileNames.sanitize("a".repeat(400) + ".mp4")
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_NAME_BYTES)
        assertTrue(out, out.endsWith(".mp4"))
        // multi-byte characters are never cut in half
        val jp = FileNames.sanitize("日本語".repeat(100) + ".webm")
        assertTrue(jp.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_NAME_BYTES)
        assertTrue(jp, jp.endsWith(".webm"))
        assertFalse(jp.contains('\uFFFD'))
        assertEquals(jp, String(jp.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun emojiPairsSurviveTruncation() {
        val out = FileNames.sanitize("😀".repeat(100) + ".mp4")
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_NAME_BYTES)
        for (i in out.indices) if (Character.isHighSurrogate(out[i])) assertTrue(Character.isLowSurrogate(out[i + 1]))
    }

    @Test
    fun extensionHelpers() {
        assertEquals("mp4", FileNames.extensionOf("a.MP4"))
        assertNull(FileNames.extensionOf("noext"))
        assertNull(FileNames.extensionOf(".hidden"))
        assertNull(FileNames.extensionOf("trailing."))
        assertNull(FileNames.extensionOf("a.this-is-not-an-extension"))
        assertEquals("a", FileNames.stripExtension("a.mp4"))
        assertEquals("a", FileNames.stripExtension("a"))
        assertEquals("a.mp4", FileNames.withExtension("a", "mp4"))
        assertEquals("a.MP4", FileNames.withExtension("a.MP4", ".mp4")) // already has it (case-insensitive): unchanged
        assertEquals("a", FileNames.withExtension("a", null))
        assertEquals("My.Video.2024.mp4", FileNames.withExtension("My.Video.2024", "mp4"))
    }

    @Test
    fun uniqueNameAddsACounterBeforeTheExtension() {
        val taken = mutableSetOf("a.mp4", "a (1).mp4")
        assertEquals("a (2).mp4", FileNames.uniqueName("a.mp4") { it in taken })
        assertEquals("b.mp4", FileNames.uniqueName("b.mp4") { it in taken })
        assertEquals("noext (1)", FileNames.uniqueName("noext") { it == "noext" })
    }

    @Test
    fun urlNames() {
        assertEquals("clip.mp4", FileNames.fromUrl("https://x.test/a/b/clip.mp4?token=1#frag"))
        assertEquals("my clip.mp4", FileNames.fromUrl("https://x.test/my%20clip.mp4"))
        assertNull(FileNames.fromUrl("https://x.test/"))
        assertNull(FileNames.fromUrl("https://x.test"))
        assertEquals("passwd", FileNames.fromUrl("https://x.test/..%2F..%2Fetc%2Fpasswd"))
        assertEquals("a+b.mp4", FileNames.fromUrl("https://x.test/a+b.mp4"))
    }

    @Test
    fun contentDispositionPrefersTheExtendedForm() {
        assertEquals("naïve café.mp4", FileNames.fromContentDisposition("attachment; filename=\"fallback.mp4\"; filename*=UTF-8''na%C3%AFve%20caf%C3%A9.mp4"))
        assertEquals("report.mp4", FileNames.fromContentDisposition("attachment; filename=\"report.mp4\""))
        assertEquals("report.mp4", FileNames.fromContentDisposition("attachment; filename=report.mp4"))
        assertEquals("a;b.mp4", FileNames.fromContentDisposition("attachment; filename=\"a;b.mp4\""))
        assertEquals("say \"hi\".mp4".replace('"', '_'), FileNames.fromContentDisposition("attachment; filename=\"say \\\"hi\\\".mp4\""))
        assertNull(FileNames.fromContentDisposition("inline"))
        assertNull(FileNames.fromContentDisposition(null))
        assertNull(FileNames.fromContentDisposition("  "))
    }

    @Test
    fun contentDispositionCannotEscapeTheDirectory() {
        assertEquals("passwd", FileNames.fromContentDisposition("attachment; filename=\"../../etc/passwd\""))
        assertEquals("passwd", FileNames.fromContentDisposition("attachment; filename*=UTF-8''..%2F..%2Fetc%2Fpasswd"))
    }

    @Test
    fun brokenExtendedFormFallsBackToThePlainName() {
        assertEquals("ok.mp4", FileNames.fromContentDisposition("attachment; filename*=UTF-8''%ZZ; filename=\"ok.mp4\""))
        assertEquals("ok.mp4", FileNames.fromContentDisposition("attachment; filename*=klingon''x.mp4; filename=\"ok.mp4\""))
        // invalid UTF-8 in the extended form
        assertEquals("ok.mp4", FileNames.fromContentDisposition("attachment; filename*=UTF-8''%FF%FE; filename=\"ok.mp4\""))
    }
}
