package com.viddown.core.model

import java.net.URI
import java.net.URISyntaxException

sealed interface UrlInputResult {
    data class Url(val url: String) : UrlInputResult
    data class Search(val query: String, val url: String) : UrlInputResult
    data object Empty : UrlInputResult
    data class UnsupportedScheme(val scheme: String) : UrlInputResult
    data class Invalid(val reason: String) : UrlInputResult
}

/**
 * Turns what the user typed into an address bar into either a web address or a search. Only http and https are ever opened:
 * javascript:, file:, content:, intent: and every other scheme is refused, never passed on.
 */
object UrlInput {
    private val explicitScheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")
    private val schemeWithoutSlashes = Regex(
        "^(javascript|data|file|content|intent|mailto|tel|sms|about|blob|view-source|chrome|ftp|market)\\s*:",
        RegexOption.IGNORE_CASE,
    )
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?([/?#].*)?$")
    private val localhost = Regex("^localhost(:\\d{1,5})?([/?#].*)?$", RegexOption.IGNORE_CASE)
    private val domain = Regex("^[^\\s/?#:]+\\.[^\\s/?#:.]{2,}(:\\d{1,5})?([/?#].*)?$")

    fun parse(text: String, searchEngine: SearchEngine): UrlInputResult {
        val input = text.trim()
        if (input.isEmpty()) return UrlInputResult.Empty

        explicitScheme.find(input)?.let { m ->
            val scheme = m.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") return UrlInputResult.UnsupportedScheme(scheme)
            return validated(input)
        }
        schemeWithoutSlashes.find(input)?.let { m -> return UrlInputResult.UnsupportedScheme(m.groupValues[1].lowercase()) }

        if (input.any { it.isWhitespace() }) return search(input, searchEngine)
        return when {
            localhost.matches(input) || ipv4.matches(input) -> validated("http://$input")
            domain.matches(input) -> validated("https://$input")
            else -> search(input, searchEngine)
        }
    }

    private fun search(query: String, engine: SearchEngine) = UrlInputResult.Search(query, engine.searchUrl(query))

    private fun validated(url: String): UrlInputResult = try {
        val uri = URI(url)
        if (uri.host.isNullOrBlank()) UrlInputResult.Invalid("The address has no host name.") else UrlInputResult.Url(url)
    } catch (e: URISyntaxException) {
        UrlInputResult.Invalid("The address is not valid.")
    }
}
