package com.viddown.app

import android.content.Intent
import com.viddown.browser.ExternalUrl

/** Addresses handed to VidDown by other apps. Only plain http(s) addresses are accepted; everything else is ignored. */
object ExternalLinks {
    fun urlFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND ->
            if (intent.type?.startsWith("text/") == true) ExternalUrl.fromSharedText(intent.getStringExtra(Intent.EXTRA_TEXT)) else null
        Intent.ACTION_VIEW -> ExternalUrl.fromViewData(intent.dataString)
        else -> null
    }
}
