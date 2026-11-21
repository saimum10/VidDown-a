package com.viddown.browser

import java.net.URLDecoder

/** One <video> or <audio> element as the page reports it. This is untrusted data from a web page: validated, capped, never executed. */
data class PlayerElement(
    val isAudio: Boolean,
    /** An http(s) address, or null when the element has no source or plays a blob: / other scheme. */
    val httpSource: String?,
    /** True when the source is a blob: address (a Media Source player): no file address exists. */
    val isBlob: Boolean,
    val posterUrl: String?,
    val durationSeconds: Long?,
    val width: Int?,
    val height: Int?,
    /** True when the element uses Encrypted Media Extensions (DRM). */
    val usesDrm: Boolean,
)

data class PagePlayerReport(val elements: List<PlayerElement>)

/**
 * A fixed, read-only script that the browser runs in the page (evaluateJavascript: nothing is exposed to the page, there is no
 * JavaScript bridge) and the strict parser for its answer. The script only lists <video>/<audio> elements. A hostile page can make
 * it lie, so the answer is only ever used to enrich or to look at addresses with the normal probe; it is never trusted.
 */
object PagePlayer {
    const val MAX_ELEMENTS = 20
    const val MAX_FIELD = 4096

    /** Returns \"v1\" followed by one tab-separated line per element: kind, src, poster, duration, width, height, drm. Fields are URI-encoded. */
    val SCRIPT: String = """
        (function(){try{
          var out=['v1'];
          var els=document.querySelectorAll('video,audio');
          var n=Math.min(els.length,$MAX_ELEMENTS);
          function e(x){return encodeURIComponent(x==null?'':String(x));}
          for(var i=0;i<n;i++){
            var m=els[i];
            var s=m.currentSrc||m.getAttribute('src')||'';
            var d=(isFinite(m.duration)&&m.duration>0)?Math.round(m.duration):'';
            out.push([m.tagName==='AUDIO'?'a':'v',e(s),e(m.poster||''),e(d),e(m.videoWidth||''),e(m.videoHeight||''),m.mediaKeys?'1':'0'].join('\t'));
          }
          return out.join('\n');
        }catch(x){return 'v1';}})()
    """.trimIndent()

    /**
     * [raw] is what evaluateJavascript hands back: a JSON string literal (quoted, escaped) or \"null\". Returns null when the answer is
     * not the expected format.
     */
    fun parse(raw: String?): PagePlayerReport? {
        val text = unquoteJsonString(raw) ?: return null
        val lines = text.split('\n')
        if (lines.firstOrNull() != "v1") return null
        val elements = ArrayList<PlayerElement>()
        for (line in lines.drop(1)) {
            if (elements.size >= MAX_ELEMENTS) break
            if (line.isBlank()) continue
            val f = line.split('\t')
            if (f.size != 7) continue
            val kind = f[0]
            if (kind != "a" && kind != "v") continue
            val src = decode(f[1])
            val poster = decode(f[2])
            val blob = src != null && src.lowercase().startsWith("blob:")
            elements += PlayerElement(
                isAudio = kind == "a",
                httpSource = src?.takeIf { UrlPolicy.isHttp(it) && UrlPolicy.decide(it) == UrlDecision.Load },
                isBlob = blob,
                posterUrl = poster?.takeIf { UrlPolicy.isHttp(it) },
                durationSeconds = decode(f[3])?.toLongOrNull()?.takeIf { it in 1..(60L * 60 * 24 * 30) },
                width = decode(f[4])?.toIntOrNull()?.takeIf { it in 1..20_000 },
                height = decode(f[5])?.toIntOrNull()?.takeIf { it in 1..20_000 },
                usesDrm = f[6] == "1",
            )
        }
        return PagePlayerReport(elements)
    }

    private fun decode(field: String): String? {
        if (field.isEmpty() || field.length > MAX_FIELD * 3) return null
        return try {
            URLDecoder.decode(field, "UTF-8").takeIf { it.isNotEmpty() && it.length <= MAX_FIELD }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** \"\\\"a\\\\nb\\\"\" -> a<newline>b. Returns null for \"null\", for text that is not a JSON string, or for a broken escape. */
    fun unquoteJsonString(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.length < 2 || s.first() != '"' || s.last() != '"') return null
        val out = StringBuilder(s.length)
        var i = 1
        val end = s.length - 1
        while (i < end) {
            val c = s[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= end) return null
            when (val n = s[i + 1]) {
                '"', '\\', '/' -> { out.append(n); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                'u' -> {
                    if (i + 6 > end) return null
                    val code = s.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    i += 6
                }
                else -> return null
            }
        }
        return out.toString()
    }
}
