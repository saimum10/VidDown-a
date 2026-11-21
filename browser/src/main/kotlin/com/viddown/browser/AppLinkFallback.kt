package com.viddown.browser

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException

/**
 * Finds the https web address behind a link that would otherwise start another app (fb://, tiktok://, intent://).
 *
 * Nothing in here launches anything. The result is only a candidate address that [UrlPolicy] may hand to the in-app browser, and it
 * is accepted only when it is a plain https URL (no user name, no password, no whitespace). A fallback that is itself another
 * app link is never followed, so the answer is always one step, never a chain.
 */
internal object AppLinkFallback {
    private const val MAX_URL_LENGTH = 2048

    /** Schemes that are never searched for a web address: they are not app links, they are local or script content. */
    private val NO_FALLBACK = UrlPolicy.NON_APP_SCHEMES + setOf("mailto", "tel", "sms", "smsto", "geo", "market")

    /** Query parameters in which an app link usually carries the address of its web version, best first. */
    private val FALLBACK_KEYS = listOf("browser_fallback_url", "fallback_url", "fallback", "web_url", "weburl", "url", "href", "link")

    private val NUMERIC_ID = Regex("^[0-9]{1,24}$")
    private val TIKTOK_USER = Regex("^[A-Za-z0-9._]{1,40}$")

    /** The https address that stands for [url], or null when there is none that is safe. */
    fun resolve(url: String): String? {
        val trimmed = url.trim()
        val scheme = UrlPolicy.schemeOf(trimmed) ?: return null
        if (scheme in NO_FALLBACK) return null
        return when {
            scheme == "intent" -> fromIntent(trimmed)
            scheme == "fb" -> embedded(trimmed) ?: fromFacebook(trimmed)
            scheme == "tiktok" || scheme == "musically" || scheme.startsWith("snssdk") -> embedded(trimmed) ?: fromTikTok(trimmed)
            else -> embedded(trimmed)
        }
    }

    /**
     * intent://host/path#Intent;scheme=https;package=...;S.browser_fallback_url=<encoded>;end
     * The fallback address wins; without one, an intent whose own scheme is https is just that web page.
     */
    private fun fromIntent(url: String): String? {
        val marker = url.indexOf("#Intent;", ignoreCase = true)
        if (marker < "intent:".length) return null
        val body = url.substring("intent:".length, marker)
        val extras = url.substring(marker + "#Intent;".length).split(';')
        for (extra in extras) {
            if (extra.startsWith("S.browser_fallback_url=")) {
                safeHttps(percentDecode(extra.substringAfter('=')))?.let { return it }
            }
        }
        if (extras.any { it.equals("scheme=https", ignoreCase = true) } && body.startsWith("//")) {
            return safeHttps("https:$body")
        }
        return null
    }

    /** fb://facewebmodal/f?href=https%3A%2F%2F... and similar: an https address carried in the query. */
    private fun embedded(url: String): String? {
        val params = queryParams(url)
        for (key in FALLBACK_KEYS) {
            safeHttps(params[key])?.let { return it }
        }
        return null
    }

    /** A few well-known Facebook app links and their m.facebook.com pages. Anything not listed here has no fallback. */
    private fun fromFacebook(url: String): String? {
        val (kind, rest) = partsOf(url)
        val query = queryParams(url)
        val id = (rest.firstOrNull() ?: query["id"])?.takeIf { NUMERIC_ID.matches(it) }
        return when (kind) {
            "", "feed", "home" -> "https://m.facebook.com/"
            "profile" -> id?.let { "https://m.facebook.com/profile.php?id=$it" }
            "page" -> id?.let { "https://m.facebook.com/$it" }
            "group" -> id?.let { "https://m.facebook.com/groups/$it" }
            "event" -> id?.let { "https://m.facebook.com/events/$it" }
            "video" -> id?.let { "https://m.facebook.com/watch/?v=$it" }
            "watch" -> (id ?: query["v"]?.takeIf { NUMERIC_ID.matches(it) })?.let { "https://m.facebook.com/watch/?v=$it" }
                ?: "https://m.facebook.com/watch/"
            else -> null
        }
    }

    /** A few well-known TikTok app links and their tiktok.com pages. Anything not listed here has no fallback. */
    private fun fromTikTok(url: String): String? {
        val (kind, rest) = partsOf(url)
        val query = queryParams(url)
        return when (kind) {
            "", "home", "feed" -> "https://www.tiktok.com/"
            "video", "aweme" -> {
                val id = (rest.lastOrNull() ?: query["id"] ?: query["aweme_id"])?.takeIf { NUMERIC_ID.matches(it) }
                id?.let { "https://www.tiktok.com/@/video/$it" }
            }
            "user", "profile" -> {
                val name = (query["username"] ?: query["unique_id"] ?: rest.lastOrNull())?.removePrefix("@")?.takeIf { TIKTOK_USER.matches(it) }
                name?.let { "https://www.tiktok.com/@$it" }
            }
            else -> null
        }
    }

    /** The first part after "scheme://" (lower case) and the path segments that follow it: "fb://page/12" -> "page", ["12"]. */
    private fun partsOf(url: String): Pair<String, List<String>> {
        val rest = url.substringAfter(':').removePrefix("//")
        val path = rest.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotEmpty() }.map { percentDecode(it) }
        return segments.firstOrNull().orEmpty().lowercase() to segments.drop(1)
    }

    /** The decoded query parameters, keys in lower case. The first value of a repeated key wins. */
    private fun queryParams(url: String): Map<String, String> {
        val q = url.indexOf('?')
        if (q < 0) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in url.substring(q + 1).substringBefore('#').split('&')) {
            if (pair.isEmpty()) continue
            val key = percentDecode(pair.substringBefore('=')).lowercase()
            if (key !in map) map[key] = percentDecode(pair.substringAfter('=', ""))
        }
        return map
    }

    /** [candidate] when it is a plain https address a browser may load, otherwise null. http is not accepted as a fallback. */
    private fun safeHttps(candidate: String?): String? {
        val c = candidate?.trim() ?: return null
        if (c.isEmpty() || c.length > MAX_URL_LENGTH || c.any { it.isWhitespace() || it.isISOControl() }) return null
        if (UrlPolicy.schemeOf(c) != "https") return null
        val uri = try {
            URI(c)
        } catch (e: URISyntaxException) {
            return null
        }
        if (uri.host.isNullOrEmpty() || uri.userInfo != null) return null
        return c
    }

    /** %XX decoding that does not treat '+' as a space (an address is not form data). A broken escape is kept as it is. */
    private fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = StringBuilder(s.length)
        val bytes = ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    bytes.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        return out.toString()
    }
}
