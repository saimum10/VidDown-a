package com.viddown.browser

/** Addresses that arrive from other apps (the share menu, \"open with\"). Only plain http(s) addresses are accepted. */
object ExternalUrl {
    private val urlPattern = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}>'\""

    /** The first http(s) address in text shared from another app (\"Look at this https://example.com/v, it is great\"), or null. */
    fun fromSharedText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = urlPattern.find(text) ?: return null
        val cleaned = match.value.trimEnd { it in TRAILING }
        return cleaned.takeIf { UrlPolicy.decide(it) == UrlDecision.Load }
    }

    /** The data of a VIEW intent: accepted only when it is exactly one http(s) address. */
    fun fromViewData(data: String?): String? {
        val trimmed = data?.trim() ?: return null
        if (trimmed.any { it.isWhitespace() }) return null
        return trimmed.takeIf { UrlPolicy.decide(it) == UrlDecision.Load && it != "about:blank" }
    }
}
