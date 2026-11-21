package com.viddown.engine.api

import java.text.Normalizer

/**
 * File-name handling for everything that ends up on disk. Names come from servers, pages and users, so they are never trusted:
 * every name that leaves this object is a single path segment (no separators, never "." or ".."), has no control or
 * direction-override characters, no leading dots, and fits the 255-byte limit of common file systems with room to spare.
 */
object FileNames {
    /** Leaves room for a " (12)" suffix inside the usual 255-byte limit. */
    const val MAX_NAME_BYTES = 200
    private const val MAX_EXTENSION_LENGTH = 10
    private const val UNIQUE_ATTEMPTS = 10_000

    private val reservedBases = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    /**
     * Turns [raw] into a safe single file name. Only the last path segment is kept, so "../../x.mp4" becomes "x.mp4".
     * Returns [fallback] (sanitised too) when nothing usable is left.
     */
    fun sanitize(raw: String?, fallback: String = "download"): String {
        val cleaned = clean(raw)
        if (cleaned != null) return cleaned
        return clean(fallback) ?: "download"
    }

    private fun clean(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        // 1. only the last path segment
        var s = raw.substringAfterLast('/').substringAfterLast('\\')
        // 2. canonical form, so look-alike sequences cannot sneak past the checks below
        s = Normalizer.normalize(s, Normalizer.Form.NFC)
        // 3. replace what file systems reject, drop what is invisible or dangerous, fold every whitespace run into one space
        val out = StringBuilder(s.length)
        var pendingSpace = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> pendingSpace = out.isNotEmpty()
                isDropped(cp) -> Unit
                cp == '/'.code || cp == '\\'.code || cp == ':'.code || cp == '*'.code || cp == '?'.code ||
                    cp == '"'.code || cp == '<'.code || cp == '>'.code || cp == '|'.code -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.append('_')
                }
                else -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.appendCodePoint(cp)
                }
            }
        }
        // 4. no hidden files, no "." / "..", no trailing dots or spaces
        var name = out.toString().trimStart('.', ' ').trimEnd('.', ' ')
        if (name.isEmpty()) return null
        // 5. device names that some file systems refuse
        val base = name.substringBeforeLast('.')
        if (base.lowercase() in reservedBases) name = "_$name"
        return truncate(name)
    }

    private fun isDropped(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true
        else -> false
    }

    /** Shortens to [MAX_NAME_BYTES] UTF-8 bytes without cutting a character in half and without losing a short extension. */
    private fun truncate(name: String): String {
        if (utf8Length(name) <= MAX_NAME_BYTES) return name
        val ext = extensionOf(name)
        val suffix = if (ext != null) ".$ext" else ""
        val stem = if (ext != null) name.dropLast(suffix.length) else name
        val budget = MAX_NAME_BYTES - utf8Length(suffix)
        val cut = StringBuilder()
        var used = 0
        var i = 0
        while (i < stem.length) {
            val cp = stem.codePointAt(i)
            val bytes = utf8Length(String(Character.toChars(cp)))
            if (used + bytes > budget) break
            cut.appendCodePoint(cp)
            used += bytes
            i += Character.charCount(cp)
        }
        val stemCut = cut.toString().trimEnd('.', ' ')
        return (if (stemCut.isEmpty()) "download" else stemCut) + suffix
    }

    private fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** Lower-case extension without the dot ("mp4"), or null when there is none or it does not look like one. */
    fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val ext = name.substring(dot + 1)
        if (ext.length > MAX_EXTENSION_LENGTH || !ext.all { it.isLetterOrDigit() && it.code < 128 }) return null
        return ext.lowercase()
    }

    fun stripExtension(name: String): String {
        val ext = extensionOf(name) ?: return name
        return name.dropLast(ext.length + 1)
    }

    /** Appends ".[extension]" unless the name already ends with it (case-insensitive). The result is sanitised again. */
    fun withExtension(name: String, extension: String?): String {
        val ext = extension?.trim('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return name
        if (extensionOf(name) == ext) return name
        return sanitize("$name.$ext")
    }

    /**
     * "a.mp4" -> "a (1).mp4" -> "a (2).mp4" ... until [exists] says the name is free. The caller must still create the file
     * atomically (createNewFile) because another download may take the name in between.
     */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val ext = extensionOf(name)
        val stem = if (ext != null) name.dropLast(ext.length + 1) else name
        val dotExt = if (ext != null) name.substring(name.length - ext.length - 1) else ""
        for (n in 1..UNIQUE_ATTEMPTS) {
            val candidate = sanitize("$stem ($n)$dotExt")
            if (!exists(candidate)) return candidate
        }
        // Practically unreachable: fall back to a name that cannot collide.
        return sanitize("$stem (${System.nanoTime()})$dotExt")
    }

    /** The last path segment of a URL, percent-decoded and sanitised, or null when the URL has none. */
    fun fromUrl(url: String): String? {
        val withoutFragment = url.substringBefore('#').substringBefore('?')
        val path = withoutFragment.substringAfter("://", withoutFragment).substringAfter('/', "")
        val last = path.trimEnd('/').substringAfterLast('/')
        if (last.isEmpty()) return null
        val decoded = percentDecode(last, Charsets.UTF_8) ?: last
        return clean(decoded)
    }

    /**
     * File name from a Content-Disposition header (RFC 6266). "filename*" (RFC 5987) wins over "filename".
     * Returns null when the header names no file. The result is sanitised.
     */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val params = parseParameters(header)
        val extended = params["filename*"]
        if (extended != null) {
            decodeExtended(extended)?.let { name -> clean(name)?.let { return it } }
        }
        val plain = params["filename"] ?: return null
        // Some servers percent-encode the plain form too; a literal '%' that is not an escape is left alone by percentDecode.
        val decoded = if (plain.contains('%')) (percentDecode(plain, Charsets.UTF_8) ?: plain) else plain
        return clean(decoded)
    }

    /** Parameter names (lower-case) to values, honouring quoted strings that may contain ';' and escaped quotes. */
    private fun parseParameters(header: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        for (c in header) {
            when {
                escaped -> { current.append(c); escaped = false }
                quoted && c == '\\' -> { current.append(c); escaped = true }
                c == '"' -> { current.append(c); quoted = !quoted }
                c == ';' && !quoted -> { parts += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        for (part in parts.drop(1)) { // the first element is the disposition type ("attachment")
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim().lowercase()
            var value = part.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1).replace(Regex("\\\\(.)"), "$1")
            }
            if (key !in result) result[key] = value
        }
        return result
    }

    /** "UTF-8''na%C3%AFve.mp4" -> "naïve.mp4". Only UTF-8 and ISO-8859-1 are understood. */
    private fun decodeExtended(value: String): String? {
        val first = value.indexOf('\'')
        val second = if (first >= 0) value.indexOf('\'', first + 1) else -1
        if (first < 0 || second < 0) return null
        val charset = when (value.substring(0, first).trim().lowercase()) {
            "utf-8", "utf8" -> Charsets.UTF_8
            "iso-8859-1", "latin1" -> Charsets.ISO_8859_1
            else -> return null
        }
        return percentDecode(value.substring(second + 1), charset)
    }

    /** Decodes %XX sequences. A '+' stays a '+'. Returns null when a sequence is malformed or the bytes are not valid text. */
    private fun percentDecode(text: String, charset: java.nio.charset.Charset): String? {
        if (!text.contains('%')) return text
        val bytes = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val hi = Character.digit(text[i + 1], 16)
                val lo = Character.digit(text[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                // Copy a whole code point so that surrogate pairs are encoded together.
                val cp = text.codePointAt(i)
                val chunk = String(Character.toChars(cp)).toByteArray(charset)
                bytes.write(chunk, 0, chunk.size)
                i += Character.charCount(cp)
            }
        }
        val decoder = charset.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}
