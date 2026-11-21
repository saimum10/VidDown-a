package com.viddown.engine.api

/**
 * Removes secrets from text that may be logged, shown in diagnostics or stored: authorization headers, cookies, tokens,
 * passwords and credentials embedded in URLs. Redaction is conservative: when in doubt the value is hidden.
 */
object Redactor {
    const val MASK = "[REDACTED]"

    private val sensitiveHeaderNames = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token", "x-csrf-token",
    )

    private val sensitiveQueryKeys = setOf(
        "token", "access_token", "refresh_token", "id_token", "auth", "authorization", "key", "apikey", "api_key",
        "signature", "sig", "password", "passwd", "pass", "pwd", "secret", "session", "sessionid", "sid", "cookie",
        "credential", "x-amz-signature", "x-amz-security-token", "policy", "hdnts",
    )

    // "Authorization: Bearer abc", "Cookie: a=b; c=d" (the value runs to the end of the line)
    private val headerLine = Regex("(?im)^(\\s*)(${sensitiveHeaderNames.joinToString("|")})(\\s*[:=]\\s*)(.*)$")

    // key=value pairs in query strings and form bodies
    private val keyValue = Regex(
        "(?i)([?&;\\s\"'](?:${sensitiveQueryKeys.joinToString("|") { Regex.escape(it) }}))(=)([^&\\s\"'#;]*)",
    )

    // https://user:password@host
    private val urlCredentials = Regex("(?i)([a-z][a-z0-9+.-]*://)([^/\\s:@]+):([^/\\s@]+)@")

    // Bearer / Basic tokens wherever they appear
    private val bearer = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{4,}")

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var out: String = text
        out = headerLine.replace(out) { m -> "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}$MASK" }
        out = urlCredentials.replace(out) { m -> "${m.groupValues[1]}${m.groupValues[2]}:$MASK@" }
        out = keyValue.replace(out) { m -> "${m.groupValues[1]}=$MASK" }
        out = bearer.replace(out) { m -> "${m.groupValues[1]} $MASK" }
        return out
    }

    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            if (name.lowercase() in sensitiveHeaderNames) MASK else redact(value)
        }

    /** Removes the whole query string and fragment from a URL: useful when even parameter names should not be logged. */
    fun stripQuery(url: String): String = url.substringBefore('#').substringBefore('?')
}
