package com.viddown.app.download

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Keeps (or gives back) the access the user granted to a download folder they picked. */
object TreePermissions {
    private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Returns null on success, otherwise a short reason. The folder is only usable when write access is really persisted. */
    fun take(context: Context, uri: Uri): String? = try {
        context.contentResolver.takePersistableUriPermission(uri, FLAGS)
        val writable = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (writable) null else "Android did not give VidDown write access to this folder."
    } catch (e: SecurityException) {
        e.message ?: "Access to this folder was refused."
    }

    /** Android keeps only a limited number of persisted grants: give the old one back when the folder changes. */
    fun release(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uriString), FLAGS)
        } catch (e: SecurityException) {
            // it was not held any more: nothing to give back
        }
    }
}
