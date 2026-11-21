package com.viddown.core.model

import java.util.Locale

object Formatters {
    /** "512 B", "1.5 KB", "12.3 MB"... or null when the size is unknown (never "0 B" for unknown). */
    fun bytes(value: Long?): String? {
        if (value == null || value < 0) return null
        if (value < 1024) return "$value B"
        var v = value.toDouble()
        val units = listOf("KB", "MB", "GB", "TB")
        var unit = 0
        v /= 1024.0
        while (v >= 1024.0 && unit < units.lastIndex) {
            v /= 1024.0
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s", v, units[unit])
    }

    /** "1.2 MB/s", or null when the speed is unknown or not positive. */
    fun speed(bytesPerSecond: Long?): String? {
        if (bytesPerSecond == null || bytesPerSecond <= 0) return null
        return bytes(bytesPerSecond)?.let { "$it/s" }
    }

    /** "45 s left", "3 min left", "1 h 5 min left", or null when unknown. */
    fun eta(seconds: Long?): String? {
        if (seconds == null || seconds < 0) return null
        return when {
            seconds < 60 -> "$seconds s left"
            seconds < 3600 -> "${(seconds + 59) / 60} min left"
            else -> {
                val h = seconds / 3600
                val m = ((seconds % 3600) + 59) / 60
                if (m == 60L) "${h + 1} h left" else if (m == 0L) "$h h left" else "$h h $m min left"
            }
        }
    }

    /** "3:07" or "1:02:03", or null when unknown. */
    fun duration(seconds: Long?): String? {
        if (seconds == null || seconds < 0) return null
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Last path segment of a URL, percent-decoded, or null when there is none. */
    fun fileNameFromUrl(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?').substringAfter("://", url).substringAfter('/', "")
        val last = path.trimEnd('/').substringAfterLast('/')
        if (last.isEmpty()) return null
        return try {
            java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            last
        }
    }

    fun fileNameFromPath(path: String?): String? = path?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotEmpty() }
}
