package com.viddown.browser

import com.viddown.engine.api.MediaType

/** What a streaming manifest says about itself. Unknown stays null. */
data class ManifestInfo(
    val type: MediaType,
    val qualityInfo: String? = null,
    val durationSeconds: Long? = null,
    val isLive: Boolean? = null,
    val protection: Protection = Protection.NONE,
)

/**
 * Reads the first bytes of an HLS (.m3u8) or DASH (.mpd) manifest. This is the evidence that a URL really is a manifest, and where
 * the manifest announces encryption the app can say so honestly. Nothing is invented: a value the text does not contain is null.
 */
object ManifestInspector {
    fun looksLikeHls(text: String): Boolean = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")

    private val dashRoot = Regex("<\\s*(?:[A-Za-z0-9_]+:)?MPD\\b")

    /** An MPD may start with an XML declaration and comments; the root element is <MPD ...>. */
    fun looksLikeDash(text: String): Boolean {
        val head = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return head.startsWith("<") && dashRoot.containsMatchIn(head.take(4096))
    }

    // ------------------------------------------------------------------------------------------------------------ HLS

    fun inspectHls(text: String, truncated: Boolean): ManifestInfo {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var variants = 0
        var maxHeight = 0
        var maxBandwidth = 0L
        var durationSum = 0.0
        var segments = 0
        var hasEndList = false
        var protection = Protection.NONE
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    variants++
                    val attrs = parseAttributes(line.substringAfter(':'))
                    attrs["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull()?.let { if (it > maxHeight) maxHeight = it }
                    attrs["BANDWIDTH"]?.toLongOrNull()?.let { if (it > maxBandwidth) maxBandwidth = it }
                }
                line.startsWith("#EXTINF:") -> {
                    segments++
                    line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()?.let { durationSum += it }
                }
                line == "#EXT-X-ENDLIST" -> hasEndList = true
                line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    protection = strongest(protection, hlsKeyProtection(parseAttributes(line.substringAfter(':'))))
                }
            }
        }
        val isMaster = variants > 0
        val quality = if (isMaster) {
            val parts = mutableListOf<String>()
            if (maxHeight > 0) parts += "up to ${maxHeight}p"
            parts += if (variants == 1) "1 variant" else "$variants variants"
            parts.joinToString(", ")
        } else {
            null
        }
        // A media playlist's length is only known when the whole VOD list was read.
        val duration = if (!isMaster && hasEndList && !truncated && segments > 0 && durationSum > 0) durationSum.toLong() else null
        val live = when {
            isMaster -> null
            hasEndList -> false
            !truncated && segments > 0 -> true
            else -> null
        }
        return ManifestInfo(MediaType.HLS, quality, duration, live, protection)
    }

    private fun hlsKeyProtection(attrs: Map<String, String>): Protection {
        val method = attrs["METHOD"]?.uppercase() ?: return Protection.NONE
        if (method == "NONE") return Protection.NONE
        val keyFormat = attrs["KEYFORMAT"]?.lowercase()
        val uri = attrs["URI"]?.lowercase()
        val drmFormat = keyFormat != null && keyFormat != "identity" &&
            (keyFormat.contains("streamingkeydelivery") || keyFormat.contains("urn:uuid:") || keyFormat.contains("widevine") ||
                keyFormat.contains("playready") || keyFormat.contains("fairplay"))
        if (drmFormat || uri?.startsWith("skd://") == true) return Protection.DRM
        return Protection.ENCRYPTED
    }

    // ----------------------------------------------------------------------------------------------------------- DASH

    private val dashProtection = Regex("<\\s*(?:[A-Za-z0-9_]+:)?ContentProtection\\b")
    private val dashRepresentation = Regex("<\\s*(?:[A-Za-z0-9_]+:)?Representation\\b[^>]*>")
    private val heightAttr = Regex("\\bheight\\s*=\\s*\"(\\d{2,5})\"")
    private val durationAttr = Regex("\\bmediaPresentationDuration\\s*=\\s*\"([^\"]+)\"")
    private val typeAttr = Regex("<\\s*(?:[A-Za-z0-9_]+:)?MPD\\b[^>]*\\btype\\s*=\\s*\"(static|dynamic)\"")

    fun inspectDash(text: String, truncated: Boolean): ManifestInfo {
        val protection = if (dashProtection.containsMatchIn(text)) Protection.DRM else Protection.NONE
        var representations = 0
        var maxHeight = 0
        for (m in dashRepresentation.findAll(text)) {
            representations++
            heightAttr.find(m.value)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it > maxHeight) maxHeight = it }
        }
        // Heights are often declared on the AdaptationSet instead of each Representation.
        if (maxHeight == 0) heightAttr.findAll(text).forEach { m -> m.groupValues[1].toIntOrNull()?.let { if (it > maxHeight) maxHeight = it } }
        val quality = if (representations > 0 || maxHeight > 0) {
            val parts = mutableListOf<String>()
            if (maxHeight > 0) parts += "up to ${maxHeight}p"
            if (representations > 0) parts += if (representations == 1) "1 stream" else "$representations streams"
            parts.joinToString(", ") + if (truncated) " (at least)" else ""
        } else {
            null
        }
        val live = typeAttr.find(text)?.groupValues?.get(1)?.let { it == "dynamic" }
        val duration = durationAttr.find(text)?.groupValues?.get(1)?.let { parseIsoDuration(it) }
        return ManifestInfo(MediaType.DASH, quality, if (live == true) null else duration, live, protection)
    }

    /** \"PT1H2M3.5S\" -> 3723. Only the time and day parts used by manifests; anything else is null. */
    fun parseIsoDuration(text: String): Long? {
        val m = Regex("^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$").matchEntire(text.trim()) ?: return null
        val (d, h, min, s) = m.destructured
        if (d.isEmpty() && h.isEmpty() && min.isEmpty() && s.isEmpty()) return null
        val total = (d.toLongOrNull() ?: 0) * 86_400 + (h.toLongOrNull() ?: 0) * 3_600 + (min.toLongOrNull() ?: 0) * 60 +
            (s.toDoubleOrNull()?.toLong() ?: 0)
        return total.takeIf { it > 0 }
    }

    // --------------------------------------------------------------------------------------------------------- shared

    private fun strongest(a: Protection, b: Protection): Protection = if (b.ordinal > a.ordinal) b else a

    /** KEY=value,KEY="quoted, value" -> map (keys upper-cased, quotes removed). */
    fun parseAttributes(list: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = list.length
        while (i < n) {
            while (i < n && (list[i] == ',' || list[i].isWhitespace())) i++
            val eq = list.indexOf('=', i)
            if (eq < 0) break
            val key = list.substring(i, eq).trim().uppercase()
            i = eq + 1
            val value: String
            if (i < n && list[i] == '"') {
                val end = list.indexOf('"', i + 1).let { if (it < 0) n else it }
                value = list.substring(i + 1, end)
                i = minOf(end + 1, n)
            } else {
                val end = list.indexOf(',', i).let { if (it < 0) n else it }
                value = list.substring(i, end).trim()
                i = end
            }
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }
}
