package com.viddown.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viddown.app.ui.ExternalOpen
import com.viddown.app.ui.VidDownRoot

class MainActivity : ComponentActivity() {
    private var external by mutableStateOf<ExternalOpen?>(null)
    private var externalCounter = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VidDownApplication).container
        // A recreated activity (rotation, theme change) still has the original intent: it was handled the first time.
        if (savedInstanceState == null) handle(intent)
        setContent { VidDownRoot(container, external) { external = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val url = ExternalLinks.urlFrom(intent) ?: return
        external = ExternalOpen(++externalCounter, url)
    }
}
