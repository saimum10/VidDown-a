package com.viddown.engine.ytdlp

/** One progress line printed by yt-dlp through our own --progress-template. Unknown values are null ("NA" in yt-dlp's output). */
internal data class RawProgress(
    val downloadedBytes: Long?,
    /** The exact total reported by the server. */
    val totalBytes: Long?,
    /** yt-dlp's own estimate (fragmented streams): never shown as a total. */
    val estimatedTotalBytes: Long?,
    val speedBytesPerSecond: Long?,
    val etaSeconds: Long?,
)

internal object ProgressParser {
    const val PROGRESS_PREFIX = "VDPROG|"
    const val FILE_PREFIX = "VDFILE|"

    /** Passed to --progress-template. Only the download phase prints progress. */
    const val PROGRESS_TEMPLATE =
        "download:VDPROG|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s"

    /** Passed to --print: the final path of the file, printed when yt-dlp has finished with it. */
    const val FILE_TEMPLATE = "after_move:VDFILE|%(filepath)s"

    fun parseProgress(line: String): RawProgress? {
        if (!line.startsWith(PROGRESS_PREFIX)) return null
        val parts = line.removePrefix(PROGRESS_PREFIX).split('|')
        if (parts.size < 5) return null
        return RawProgress(
            downloadedBytes = number(parts[0]),
            totalBytes = number(parts[1]),
            estimatedTotalBytes = number(parts[2]),
            speedBytesPerSecond = number(parts[3]),
            etaSeconds = number(parts[4]),
        )
    }

    /** The path from a FILE_TEMPLATE line, or null for any other line. */
    fun parseFile(line: String): String? =
        if (line.startsWith(FILE_PREFIX)) line.removePrefix(FILE_PREFIX).trim().takeIf { it.isNotEmpty() && it != "NA" } else null

    private fun number(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty() || t == "NA" || t == "None") return null
        val d = t.toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite() || d < 0) return null
        return d.toLong()
    }
}
