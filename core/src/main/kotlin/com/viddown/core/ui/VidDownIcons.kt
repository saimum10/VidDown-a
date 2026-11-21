package com.viddown.core.ui

import androidx.annotation.DrawableRes
import com.viddown.core.R

/**
 * The one icon set: 24dp outline icons, 2dp round strokes, no fill (res/drawable/vd_ic_*.xml), tinted by the composable that shows them.
 * Add new icons in the same style; do not mix in icons from other sets and never use emoji as icons.
 */
object VidDownIcons {
    @DrawableRes val Home = R.drawable.vd_ic_home
    @DrawableRes val Browser = R.drawable.vd_ic_browser
    @DrawableRes val Downloads = R.drawable.vd_ic_downloads
    @DrawableRes val Library = R.drawable.vd_ic_library
    @DrawableRes val Settings = R.drawable.vd_ic_settings
    @DrawableRes val Back = R.drawable.vd_ic_back
    @DrawableRes val Warning = R.drawable.vd_ic_warning
    @DrawableRes val Forward = R.drawable.vd_ic_forward
    @DrawableRes val Refresh = R.drawable.vd_ic_refresh
    @DrawableRes val Close = R.drawable.vd_ic_close
    @DrawableRes val Tabs = R.drawable.vd_ic_tabs
    @DrawableRes val Bookmark = R.drawable.vd_ic_bookmark
    @DrawableRes val More = R.drawable.vd_ic_more
    @DrawableRes val Download = R.drawable.vd_ic_download
    @DrawableRes val Add = R.drawable.vd_ic_add
    @DrawableRes val History = R.drawable.vd_ic_history
    @DrawableRes val Share = R.drawable.vd_ic_share
    @DrawableRes val Lock = R.drawable.vd_ic_lock
    @DrawableRes val LockOpen = R.drawable.vd_ic_lock_open
    @DrawableRes val Delete = R.drawable.vd_ic_delete
    @DrawableRes val Paste = R.drawable.vd_ic_paste
}
