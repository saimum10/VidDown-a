package com.viddown.app.browser

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.WebView
import com.viddown.browser.PressedLink

/**
 * Long press on a link, a video thumbnail or a video of the page: finds the address the page itself would copy for that spot and
 * hands it to [install]'s callback, which shows the link menu (Open, Open in new tab, Copy link, Share, Download).
 *
 *  * A plain link is answered by Android's own hit test at once.
 *  * Anything else (a thumbnail inside a link, a `<video>`, an embedded player) is asked from the page with one fixed read-only
 *    script ([PressedLink]); no JavaScript bridge is exposed. Without JavaScript only what Android's hit test finds is offered.
 *  * The address of the page being shown is never used: where no link or source is found, no menu appears and the long press keeps
 *    its normal behaviour (selecting text).
 */
object LinkLongPress {
    @SuppressLint("ClickableViewAccessibility")
    fun install(view: WebView, javascriptEnabled: () -> Boolean, onLink: (String) -> Unit) {
        var lastX = 0f
        var lastY = 0f
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastX = event.x
                lastY = event.y
            }
            false // only watching: the page still gets every touch
        }
        view.setOnLongClickListener { v ->
            val web = v as WebView
            val hit = web.hitTestResult
            val extra = hit.extra
            when (hit.type) {
                WebView.HitTestResult.EDIT_TEXT_TYPE -> false
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    val link = PressedLink.choose(null, extra)
                    if (link != null) {
                        onLink(link)
                        true
                    } else {
                        false
                    }
                }
                else -> {
                    if (javascriptEnabled()) {
                        askPage(web, lastX, lastY, onLink)
                    } else if (hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                        askLinkOfImage(web, onLink)
                    }
                    false
                }
            }
        }
    }

    private fun askPage(web: WebView, x: Float, y: Float, onLink: (String) -> Unit) {
        val density = web.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        try {
            web.evaluateJavascript(PressedLink.script((x / density).toDouble(), (y / density).toDouble())) { raw ->
                PressedLink.choose(PressedLink.parse(raw))?.let(onLink)
            }
        } catch (t: Throwable) {
            // A view that is being torn down cannot run scripts: nothing to offer.
        }
    }

    /** Without JavaScript: the link around a pressed image is asked from the WebView itself. */
    private fun askLinkOfImage(web: WebView, onLink: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper()) { message ->
            PressedLink.choose(null, message.data?.getString("url"))?.let(onLink)
            true
        }
        try {
            web.requestFocusNodeHref(handler.obtainMessage())
        } catch (t: Throwable) {
            // nothing to offer
        }
    }
}
