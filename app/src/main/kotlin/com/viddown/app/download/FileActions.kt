package com.viddown.app.download

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.viddown.engine.api.FileNames
import com.viddown.engine.api.MediaMimeTypes
import java.io.File

/** Opening and sharing finished downloads. Content URIs are passed on with a read grant; plain files go through FileProvider. */
object FileActions {
    fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** A content:// URI that other apps may read, or null when the location cannot be turned into one. */
    fun uriFor(context: Context, location: String): Uri? = try {
        if (location.startsWith("content://")) Uri.parse(location) else FileProvider.getUriForFile(context, authority(context), File(location))
    } catch (e: IllegalArgumentException) {
        null // a file outside the folders declared in file_paths.xml is never exposed
    }

    fun mimeTypeFor(context: Context, location: String, uri: Uri): String {
        if (location.startsWith("content://")) context.contentResolver.getType(uri)?.let { return it }
        val name = if (location.startsWith("content://")) null else File(location).name
        val ext = name?.let { FileNames.extensionOf(it) }
        return MediaMimeTypes.forExtension(ext)
            ?: ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "*/*"
    }

    fun openIntent(context: Context, location: String): Intent? {
        val uri = uriFor(context, location) ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeTypeFor(context, location, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareIntent(context: Context, location: String): Intent? {
        val uri = uriFor(context, location) ?: return null
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeTypeFor(context, location, uri))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uri) // makes the read grant reach the chosen app on every Android version
        return Intent.createChooser(send, null)
    }

    /** Starts [intent]; returns false when no app can handle it. */
    fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(if (context is android.app.Activity) intent else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
