package com.viddown.engine.api

/**
 * The cookies the in-app browser would send to a site, turned into the Netscape cookie file that yt-dlp reads with --cookies.
 *
 * The browser only gives one "name=value; name2=value2" header for an address, so every cookie is written for the site's own domain
 * (never another one). Nothing here signs in anywhere: it only hands over what the browser already holds. The text may contain
 * secrets: it is written to a private temporary file for one run and never logged (see [Redactor]).
 */
object BrowserCookies {
    private const val MAX_HEADER_CHARS = 32_768
    private const val MAX_COOKIES = 200
    private const val FILE_HEADER = "# Netscape HTTP Cookie File"

    /** Second-level labels that sit under a two-letter country code (example.co.uk, site.com.bd): the site's domain is three labels there. */
    private val countrySecondLevel = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

    /** The Cookie header in [headers] (any letter case), or null when there is none or it is blank. */
    fun headerOf(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

    /**
     * The content of a Netscape cookie file for [cookieHeader] as sent to [url], or null when nothing usable is left (no host, too
     * large, or every cookie was refused). A cookie whose name or value holds a control character (tab, line break...) is skipped, so
     * a value can never add a line or a field of its own. [expiresAtSeconds] is a Unix time in the future.
     */
    fun toNetscapeFile(cookieHeader: String, url: String, expiresAtSeconds: Long): String? {
        if (cookieHeader.length > MAX_HEADER_CHARS) return null
        val host = hostOf(url) ?: return null
        val (domain, includeSubdomains) = domainFor(host)
        val secure = if (url.trim().startsWith("https://", ignoreCase = true)) "TRUE" else "FALSE"
        val flag = if (includeSubdomains) "TRUE" else "FALSE"
        val lines = ArrayList<String>()
        for (part in cookieHeader.split(';')) {
            val pair = part.trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty() || !isPlain(name) || !isPlain(value) || name.any { it == ' ' }) continue
            lines += "$domain\t$flag\t/\t$secure\t$expiresAtSeconds\t$name\t$value"
            if (lines.size >= MAX_COOKIES) break
        }
        if (lines.isEmpty()) return null
        return buildString {
            append(FILE_HEADER).append('\n')
            for (line in lines) append(line).append('\n')
        }
    }

    private fun isPlain(text: String): Boolean = text.none { it.code < 0x20 || it.code == 0x7f }

    /** The cookie domain for [host] and whether subdomains share it. Addresses without a name (127.0.0.1, localhost) stay host-only. */
    private fun domainFor(host: String): Pair<String, Boolean> {
        if (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")) return ".youtube.com" to true
        if (!host.contains('.') || host.all { it.isDigit() || it == '.' }) return host to false
        val labels = host.split('.')
        val keep = if (labels.size > 2 && labels.last().length == 2 && labels[labels.size - 2] in countrySecondLevel) 3 else 2
        return "." + labels.takeLast(keep.coerceAtMost(labels.size)).joinToString(".") to true
    }

    /** The lower-case host of an http(s) address, or null (no host, an IPv6 literal, or no scheme). */
    private fun hostOf(url: String): String? {
        val text = url.trim()
        val schemeEnd = text.indexOf("://")
        if (schemeEnd < 1) return null
        var start = schemeEnd + 3
        var end = start
        while (end < text.length && text[end] != '/' && text[end] != '?' && text[end] != '#' && text[end] != '\\') end++
        val at = text.lastIndexOf('@', end - 1)
        if (at >= start) start = at + 1
        if (start < end && text[start] == '[') return null
        val colon = text.indexOf(':', start)
        if (colon in start until end) end = colon
        while (end > start && text[end - 1] == '.') end--
        return text.substring(start, end).lowercase().takeIf { it.isNotEmpty() }
    }
}
