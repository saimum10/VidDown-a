package com.viddown.app.ui

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.viddown.app.R
import com.viddown.core.ui.VidDownIcons

/** The four bottom-bar destinations. Settings (and its sub-screens) are reached from the top bar. */
enum class TopLevelDestination(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    Home("home", R.string.nav_home, VidDownIcons.Home),
    Browser("browser", R.string.nav_browser, VidDownIcons.Browser),
    Downloads("downloads", R.string.nav_downloads, VidDownIcons.Downloads),
    Library("library", R.string.nav_library, VidDownIcons.Library),
}

const val SETTINGS_ROUTE = "settings"
const val ABOUT_ROUTE = "about"
const val LICENSES_ROUTE = "licenses"
const val BROWSER_PAGES_ROUTE = "browser_pages"

/** The browser route takes an optional address: "browser" or "browser?url=<encoded>". */
const val BROWSER_ROUTE_PATTERN = "browser?url={url}"

fun browserRoute(url: String?): String = if (url == null) "browser" else "browser?url=${Uri.encode(url)}"

val SECONDARY_ROUTES = setOf(SETTINGS_ROUTE, ABOUT_ROUTE, LICENSES_ROUTE, BROWSER_PAGES_ROUTE)

/** An address another app asked VidDown to open (share menu, \"open with\"). [id] makes the same address arrive again as a new request. */
data class ExternalOpen(val id: Long, val url: String)
