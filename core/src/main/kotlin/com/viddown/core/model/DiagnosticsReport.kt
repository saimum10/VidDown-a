package com.viddown.core.model

import com.viddown.engine.api.DownloadState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.Redactor

/**
 * A plain-text report the user can choose to share when something does not work. It is built on the device and leaves it only
 * if the user sends it. It never contains a full link (only the host), a file name, a title, a header, a cookie or a folder path.
 */
object DiagnosticsReport {
    private const val MAX_FAILURES = 10

    fun build(
        appVersion: String,
        androidApi: Int,
        abis: List<String>,
        settings: AppSettings,
        engines: List<Pair<String, String>>,
        tasks: List<DownloadTask>,
    ): String = buildString {
        appendLine("VidDown diagnostics")
        appendLine("App version: $appVersion")
        appendLine("Android API level: $androidApi")
        appendLine("Device ABIs: ${abis.joinToString(", ").ifEmpty { "unknown" }}")
        appendLine()
        appendLine("Settings")
        appendLine(
            "  Selected engine: " + (if (settings.unrecognizedEngineKey != null) "none (the saved choice is not recognised by this version)" else settings.engineMode.label),
        )
        appendLine("  Ask before downloading: ${settings.askBeforeDownloading}")
        appendLine("  Wi-Fi only: ${settings.wifiOnly}")
        appendLine("  Simultaneous downloads: ${settings.maxSimultaneousDownloads}")
        appendLine("  Notifications: ${settings.notificationsEnabled}")
        appendLine("  Save location: ${if (settings.downloadTreeUri == null) "default" else "custom folder"}")
        appendLine()
        appendLine("Engines")
        for ((name, status) in engines) appendLine("  $name: $status")
        appendLine()
        appendLine("Downloads: ${tasks.size}")
        for (state in DownloadState.entries) {
            val n = tasks.count { it.state == state }
            if (n > 0) appendLine("  ${state.name}: $n")
        }
        val failures = tasks.filter { it.state == DownloadState.FAILED }.sortedByDescending { it.createdAtMillis }.take(MAX_FAILURES)
        if (failures.isNotEmpty()) {
            appendLine()
            appendLine("Latest failures (newest first)")
            for (t in failures) {
                val e = t.error
                appendLine(
                    "  engine=${t.engineId.label} host=${hostOf(t.request.url)} kind=${e?.kind?.name ?: "unknown"}" +
                        (e?.httpStatus?.let { " http=$it" } ?: "") +
                        (e?.technicalDetail?.takeIf { it.isNotBlank() }?.let { " detail=${oneLine(hostsOnly(Redactor.redact(it)))}" } ?: ""),
                )
            }
        }
    }.trimEnd() + "\n"

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host?.takeIf { it.isNotBlank() } ?: "unknown"
    } catch (e: java.net.URISyntaxException) {
        "unknown"
    }

    private val urlInText = Regex("(?i)https?://[^\\s\"'<>]+")

    /** Every link inside a message is reduced to "https://host": a path can already say what was being watched. */
    private fun hostsOnly(text: String): String = urlInText.replace(text) { m ->
        val scheme = m.value.substringBefore("://").lowercase()
        "$scheme://${hostOf(m.value)}"
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").take(300)
}
