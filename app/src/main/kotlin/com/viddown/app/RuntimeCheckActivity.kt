package com.viddown.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Diagnostics screen: runs the real native runtime self-test (CPython, yt-dlp, FFmpeg). Opened from Settings. */
class RuntimeCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.viddown_logo)
            contentDescription = "VidDown logo"
        }
        val title = TextView(this).apply {
            text = "VidDown - native runtime check"
            textSize = 20f
            setPadding(pad / 2, 0, 0, 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val density = resources.displayMetrics.density
            addView(logo, LinearLayout.LayoutParams((45 * density).toInt(), (64 * density).toInt()))
            addView(title)
        }
        val button = Button(this).apply {
            text = "Run runtime self-test"
        }
        val output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            text = if (BuildConfig.NATIVE_ABIS.isBlank()) {
                "This build contains no native runtime (no ABI has passed runtime verification yet)."
            } else {
                "Press the button to run the checks."
            }
        }
        val scroll = ScrollView(this).apply { addView(output) }

        button.setOnClickListener {
            button.isEnabled = false
            output.text = "Running..."
            val appContext = applicationContext
            Thread {
                val text = try {
                    SelfTest.format(SelfTest.run(appContext))
                } catch (e: Exception) {
                    "Self-test crashed: $e"
                }
                runOnUiThread {
                    output.text = text
                    button.isEnabled = true
                }
            }.start()
        }

        root.addView(header)
        root.addView(button)
        root.addView(scroll)
        setContentView(root)
    }
}
