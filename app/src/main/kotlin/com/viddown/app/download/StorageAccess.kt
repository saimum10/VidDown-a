package com.viddown.app.download

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * The default save folder is VidDown in the root of the main storage (/storage/emulated/0/VidDown).
 * Android only lets an app write there with "All files access" (Android 11+) or the storage permission (Android 9 and older).
 * Without it downloads go to Download/VidDown through MediaStore, as before.
 */
object StorageAccess {
    const val FOLDER = "VidDown"

    fun rootFolder(): File = File(Environment.getExternalStorageDirectory(), FOLDER)

    fun hasRootAccess(context: Context): Boolean {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return false
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
            // Android 10 ignores legacy storage for apps that target a newer version, so the root folder cannot be used there.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> false
            else -> ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** True when access is granted on a screen of the system Settings (Android 11+), false when it is a normal permission dialog. */
    fun usesSystemSettings(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The system Settings screen where the user allows "All files access" for this app (Android 11+). */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun settingsFallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}
