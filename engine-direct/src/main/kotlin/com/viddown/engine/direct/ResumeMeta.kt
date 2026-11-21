package com.viddown.engine.direct

import java.io.File
import java.io.IOException

/**
 * What a partial file needs to be resumed safely in a later run: the validator the server gave for the content. It is sent as
 * If-Range, so when the file changed on the server the server answers with the whole new file and the old bytes are dropped.
 * Without a validator a partial file is not resumed after the app restarted (it could be a different file).
 */
data class ResumeMeta(val etag: String?, val lastModified: String?) {
    /** A strong ETag (weak ones, "W/...", are not allowed in If-Range) or else the Last-Modified date. */
    val validator: String?
        get() = etag?.takeIf { !it.startsWith("W/") && it.isNotBlank() } ?: lastModified?.takeIf { it.isNotBlank() }

    fun write(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val text = buildString {
            etag?.let { append("etag=").append(oneLine(it)).append('\n') }
            lastModified?.let { append("lastModified=").append(oneLine(it)).append('\n') }
        }
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // renameTo can fail when the target exists on some file systems
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Could not store resume information")
            }
        }
    }

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ')

    companion object {
        /** Null when the file does not exist or cannot be understood. */
        fun read(file: File): ResumeMeta? {
            if (!file.isFile) return null
            return try {
                var etag: String? = null
                var lastModified: String? = null
                for (line in file.readLines(Charsets.UTF_8)) {
                    val eq = line.indexOf('=')
                    if (eq <= 0) continue
                    when (line.substring(0, eq)) {
                        "etag" -> etag = line.substring(eq + 1)
                        "lastModified" -> lastModified = line.substring(eq + 1)
                    }
                }
                ResumeMeta(etag, lastModified)
            } catch (e: IOException) {
                null
            }
        }

        fun fromHeaders(etag: String?, lastModified: String?) = ResumeMeta(etag?.trim(), lastModified?.trim())
    }
}
