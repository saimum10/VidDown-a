package com.viddown.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    @Test
    fun authorizationHeaderLineIsMasked() {
        val out = Redactor.redact("GET /x HTTP/1.1\nAuthorization: Bearer abcdef123456\nAccept: */*")
        assertFalse(out, out.contains("abcdef123456"))
        assertTrue(out, out.contains("Authorization: ${Redactor.MASK}"))
        assertTrue(out, out.contains("Accept: */*"))
    }

    @Test
    fun cookieAndSetCookieAreMasked() {
        val out = Redactor.redact("Cookie: sid=SECRET1; theme=dark\nSet-Cookie: token=SECRET2; Path=/")
        assertFalse(out, out.contains("SECRET1"))
        assertFalse(out, out.contains("SECRET2"))
    }

    @Test
    fun credentialsInUrlAreMasked() {
        val out = Redactor.redact("failed for https://alice:hunter2@example.com/video.mp4")
        assertFalse(out, out.contains("hunter2"))
        assertTrue(out, out.contains("https://alice:${Redactor.MASK}@example.com/video.mp4"))
    }

    @Test
    fun sensitiveQueryParametersAreMaskedButOthersKept() {
        val out = Redactor.redact("https://cdn.example.com/v.mp4?id=42&token=ABC123&quality=hd&signature=ZZZ")
        assertFalse(out, out.contains("ABC123"))
        assertFalse(out, out.contains("ZZZ"))
        assertTrue(out, out.contains("id=42"))
        assertTrue(out, out.contains("quality=hd"))
    }

    @Test
    fun bearerTokenInsideTextIsMasked() {
        val out = Redactor.redact("server said: invalid Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig for user")
        assertFalse(out, out.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun passwordFormFieldIsMasked() {
        val out = Redactor.redact("username=bob&password=s3cret!&remember=1")
        assertFalse(out, out.contains("s3cret"))
    }

    @Test
    fun plainTextIsUntouched() {
        val text = "Connection reset by peer after 1048576 bytes"
        assertEquals(text, Redactor.redact(text))
    }

    @Test
    fun nullAndEmptyAreSafe() {
        assertEquals("", Redactor.redact(null))
        assertEquals("", Redactor.redact(""))
    }

    @Test
    fun headerMapMasksSensitiveNamesAndScrubsOtherValues() {
        val out = Redactor.redactHeaders(mapOf("Cookie" to "a=b", "Referer" to "https://x.test/?token=T1", "Accept" to "*/*"))
        assertEquals(Redactor.MASK, out["Cookie"])
        assertFalse(out["Referer"]!!.contains("T1"))
        assertEquals("*/*", out["Accept"])
    }

    @Test
    fun stripQueryRemovesQueryAndFragment() {
        assertEquals("https://a.test/p", Redactor.stripQuery("https://a.test/p?x=1#frag"))
    }

    @Test
    fun shortBearerTokensInsideTextAreHiddenToo() {
        val out = Redactor.redact("request failed; Authorization: Bearer abc123 was rejected")
        assertFalse(out, out.contains("abc123"))
        assertTrue(out, out.contains("was rejected"))
    }
}
