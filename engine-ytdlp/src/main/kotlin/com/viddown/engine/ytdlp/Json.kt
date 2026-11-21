package com.viddown.engine.ytdlp

/**
 * A small strict JSON reader for the output of `yt-dlp --dump-single-json` and `ffprobe -print_format json`.
 * Objects become Map<String, Any?>, arrays List<Any?>, numbers Long (when integral and in range) or Double, plus String,
 * Boolean and null. Python's json module may write NaN / Infinity, which are not JSON: they are read as null.
 * Nothing here executes or evaluates anything: untrusted text goes in, plain values come out.
 */
internal object Json {
    class ParseException(message: String) : Exception(message)

    private const val MAX_DEPTH = 100

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (!reader.atEnd()) throw ParseException("Unexpected data after the JSON value (offset ${reader.pos})")
        return value
    }

    private class Reader(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        private fun fail(what: String): Nothing = throw ParseException("$what (offset $pos)")

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("JSON is nested too deeply")
            if (atEnd()) fail("Unexpected end of JSON")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                'N' -> literal("NaN", null)
                'I' -> literal("Infinity", null)
                '-' -> if (s.startsWith("-Infinity", pos)) literal("-Infinity", null) else readNumber()
                else -> if (c in '0'..'9') readNumber() else fail("Unexpected character '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("Invalid literal")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[pos] != '"') fail("Expected a string key")
                val key = readString()
                skipWhitespace()
                if (atEnd() || s[pos] != ':') fail("Expected ':'")
                pos++
                skipWhitespace()
                map[key] = readValue(depth + 1)
                skipWhitespace()
                if (atEnd()) fail("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(readValue(depth + 1))
                skipWhitespace()
                if (atEnd()) fail("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("Unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Truncated \\u escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("Invalid \\u escape")
                                out.append(code.toChar()) // surrogate pairs arrive as two escapes and join correctly in a UTF-16 string
                                pos += 4
                            }
                            else -> fail("Invalid escape '\\$e'")
                        }
                    }
                    c.code < 0x20 -> fail("Unescaped control character in a string")
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): Any? {
            val start = pos
            if (s[pos] == '-') pos++
            val digitsStart = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == digitsStart) fail("Invalid number")
            var integral = true
            if (pos < s.length && s[pos] == '.') {
                integral = false
                pos++
                val fractionStart = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == fractionStart) fail("Invalid number")
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                integral = false
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                val exponentStart = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == exponentStart) fail("Invalid number")
            }
            val text = s.substring(start, pos)
            if (integral) {
                text.toLongOrNull()?.let { return it }
            }
            return text.toDoubleOrNull() ?: fail("Invalid number")
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

internal fun Any?.asList(): List<Any?>? = this as? List<Any?>

internal fun Any?.asString(): String? = when (this) {
    is String -> this
    is Number, is Boolean -> toString()
    else -> null
}

internal fun Any?.asDouble(): Double? = when (this) {
    is Double -> takeIf { !it.isNaN() && !it.isInfinite() }
    is Number -> toDouble()
    is String -> toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
    else -> null
}

internal fun Any?.asLong(): Long? = when (this) {
    is Long -> this
    is Int -> toLong()
    is Double -> takeIf { !it.isNaN() && !it.isInfinite() && it >= Long.MIN_VALUE && it <= Long.MAX_VALUE }?.toLong()
    is String -> toLongOrNull() ?: toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }?.toLong()
    else -> null
}

internal fun Any?.asInt(): Int? = asLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal fun Any?.asBoolean(): Boolean? = when (this) {
    is Boolean -> this
    is String -> when (lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
    else -> null
}
