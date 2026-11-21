package com.viddown.browser

import java.net.URLDecoder
import java.util.Locale

/**
 * What lies under the finger after a long press, as the page reports it. This is untrusted data from a web page: every address is
 * checked ([PressedLink.choose]) before it is offered to the user, and nothing here is ever executed.
 */
data class PressedLinkReport(
    /** The address of the link (`<a href>`) that holds the pressed spot, e.g. a video thumbnail in a list. */
    val anchor: String?,
    /** The source of the `<video>`/`<audio>` element that was pressed. */
    val media: String?,
    /** The address of an embedded player (`<iframe src>`) that was pressed (a page cannot be read inside it). */
    val frame: String?,
)

/**
 * The long-press helper of the in-app browser: a fixed, read-only script (evaluateJavascript, no JavaScript bridge) that asks the page
 * which link, video or embedded player is under a point, and the strict reader of its answer.
 *
 * The address offered is the one the page itself would copy for that spot, never the address of the page being shown:
 * the link first, else the video's own source, else the embedded player's address.
 */
object PressedLink {
    const val MAX_FIELD = 4096

    /**
     * [x] and [y] are the touch point in CSS pixels of the screen (view pixels divided by the screen density). The script turns them
     * into page coordinates (page zoom and scroll offset of the visual viewport), then looks at every element stacked at that point,
     * so a link or a video under a transparent layer is found too. Returns `p1<TAB>link<TAB>media<TAB>frame` with URI-encoded fields.
     */
    fun script(x: Double, y: Double): String {
        val px = fmt(x)
        val py = fmt(y)
        return """
        (function(){try{
          var vv=window.visualViewport;
          var s=(vv&&vv.scale>0)?vv.scale:1;
          var cx=(vv?vv.offsetLeft:0)+$px/s;
          var cy=(vv?vv.offsetTop:0)+$py/s;
          function e(v){return encodeURIComponent(v==null?'':String(v));}
          var list=document.elementsFromPoint?document.elementsFromPoint(cx,cy):[document.elementFromPoint(cx,cy)];
          var a='',m='',f='';
          for(var i=0;i<list.length&&i<12;i++){
            var t=list[i];
            if(!t||!t.tagName)continue;
            var tag=t.tagName;
            if(!a&&t.closest){var c=t.closest('a[href]');if(c&&typeof c.href==='string')a=c.href;}
            if(!m&&(tag==='VIDEO'||tag==='AUDIO'))m=t.currentSrc||t.getAttribute('src')||'';
            if(!f&&tag==='IFRAME'&&typeof t.src==='string')f=t.src;
          }
          return 'p1\t'+e(a)+'\t'+e(m)+'\t'+e(f);
        }catch(x){return 'p1\t\t\t';}})()
        """.trimIndent()
    }

    private fun fmt(v: Double): String =
        String.format(Locale.ROOT, "%.2f", if (v.isFinite()) v.coerceIn(-100_000.0, 100_000.0) else 0.0)

    /** [raw] is what evaluateJavascript hands back (a quoted JSON string, or "null"). Null when it is not the expected answer. */
    fun parse(raw: String?): PressedLinkReport? {
        val text = PagePlayer.unquoteJsonString(raw) ?: return null
        val f = text.split('\t')
        if (f.size != 4 || f[0] != "p1") return null
        return PressedLinkReport(anchor = decode(f[1]), media = decode(f[2]), frame = decode(f[3]))
    }

    /**
     * The one address to offer: the link, else the video's own source, else the embedded player, else [fallback] (the address
     * Android's own hit test found). Only http(s) addresses the browser may load count: blob:, data:, javascript: and app links
     * are never offered, so a video that streams through a blob: has no address and gets no menu.
     */
    fun choose(report: PressedLinkReport?, fallback: String? = null): String? =
        listOf(report?.anchor, report?.media, report?.frame, fallback).firstNotNullOfOrNull { usable(it) }

    private fun usable(url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD } ?: return null
        if (u.any { it.code < 0x20 }) return null
        return u.takeIf { UrlPolicy.isHttp(it) && UrlPolicy.decide(it) == UrlDecision.Load }
    }

    private fun decode(field: String): String? {
        if (field.isEmpty() || field.length > MAX_FIELD * 3) return null
        return try {
            URLDecoder.decode(field, "UTF-8").takeIf { it.isNotEmpty() && it.length <= MAX_FIELD }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
