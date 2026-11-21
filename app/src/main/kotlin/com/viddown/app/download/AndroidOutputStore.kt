package com.viddown.app.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import com.viddown.download.DeleteResult
import com.viddown.download.OutputStore
import com.viddown.download.PublishResult
import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.FileNames
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Puts a finished download where the user can see it:
 *
 *  1. a folder the user picked (Storage Access Framework tree), when one is set;
 *  2. otherwise VidDown in the root of the main storage (/storage/emulated/0/VidDown), when the app has "All files access"
 *     (Android 11+) or the storage permission (Android 9 and older);
 *  3. otherwise Download/VidDown through MediaStore (Android 10 and newer);
 *  4. otherwise the app's own Download folder on external storage (Android 9 and older).
 *
 * A half-written destination file is always removed again, also when the coroutine is cancelled.
 */
class AndroidOutputStore(
    private val context: Context,
    private val treeUri: suspend () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : OutputStore {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun publish(source: File, displayName: String, mimeType: String?): PublishResult = withContext(io) {
        val name = FileNames.sanitize(displayName)
        val tree = treeUri()
        try {
            when {
                tree != null -> publishToTree(Uri.parse(tree), source, name, mimeType)
                StorageAccess.hasRootAccess(context) -> publishToRoot(source, name, mimeType)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> publishToMediaStore(source, name, mimeType)
                else -> publishToAppFolder(source, name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            PublishResult.Failed(DownloadError.permissionDenied("${e.javaClass.simpleName}: ${e.message}"))
        } catch (e: IOException) {
            PublishResult.Failed(mapIo(e))
        } catch (e: IllegalArgumentException) {
            PublishResult.Failed(DownloadError.invalidDestination("${e.javaClass.simpleName}: ${e.message}"))
        } catch (e: IllegalStateException) {
            PublishResult.Failed(DownloadError.invalidDestination("${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    // ------------------------------------------------------------------------------------------ MediaStore

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun publishToMediaStore(source: File, name: String, mime: String?): PublishResult {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            if (mime != null) put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until the copy is complete
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return PublishResult.Failed(DownloadError.invalidDestination("Android did not create the file in Downloads"))
        try {
            copyInto(uri, source)
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, done, null, null) < 1) throw IOException("Android did not finish the file in Downloads")
        } catch (t: Throwable) {
            deleteQuietly(uri)
            throw t
        }
        return PublishResult.Published(uri.toString(), displayNameOf(uri) ?: name)
    }

    // ------------------------------------------------------------------------------------------ chosen folder (SAF)

    private suspend fun publishToTree(tree: Uri, source: File, name: String, mime: String?): PublishResult {
        val permitted = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        if (!permitted) {
            return PublishResult.Failed(DownloadError.permissionDenied("The chosen download folder is no longer accessible. Choose it again in Settings."))
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = DocumentsContract.createDocument(resolver, parent, mime ?: "application/octet-stream", name)
            ?: return PublishResult.Failed(DownloadError.invalidDestination("The chosen folder refused to create the file"))
        try {
            copyInto(uri, source)
        } catch (t: Throwable) {
            try {
                DocumentsContract.deleteDocument(resolver, uri)
            } catch (_: Exception) {
                // nothing more can be done for a document that cannot be removed
            }
            throw t
        }
        return PublishResult.Published(uri.toString(), displayNameOf(uri) ?: name)
    }

    // ------------------------------------------------------------------------------------------ app folder (Android 9 and older)

    private fun appFolder(): File? = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    private suspend fun publishToAppFolder(source: File, name: String): PublishResult {
        val dir = appFolder() ?: return PublishResult.Failed(DownloadError.invalidDestination("External storage is not available"))
        return publishToDirectory(dir, source, name, mime = null, scan = false)
    }

    // ------------------------------------------------------------------------------------------ VidDown in the main storage

    private suspend fun publishToRoot(source: File, name: String, mime: String?): PublishResult =
        publishToDirectory(StorageAccess.rootFolder(), source, name, mime, scan = true)

    /** Copies [source] into [dir] under a name nobody uses yet. [scan] tells the media scanner about it so that galleries show it. */
    private suspend fun publishToDirectory(dir: File, source: File, name: String, mime: String?, scan: Boolean): PublishResult {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            return PublishResult.Failed(DownloadError.invalidDestination("The download folder could not be created"))
        }
        var target: File? = null
        for (attempt in 1..MAX_NAME_ATTEMPTS) {
            val candidate = File(dir, FileNames.uniqueName(name) { File(dir, it).exists() })
            if (candidate.createNewFile()) { // atomic: another download may have taken the name meanwhile
                target = candidate
                break
            }
        }
        if (target == null) return PublishResult.Failed(DownloadError.invalidDestination("No free file name could be found"))
        try {
            source.inputStream().use { input -> target.outputStream().use { out -> copy(input, out, source.length()) } }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        if (scan) {
            try {
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), mime?.let { arrayOf(it) }, null)
            } catch (_: Exception) {
                // the file is saved either way; only the gallery may show it later
            }
        }
        return PublishResult.Published(target.absolutePath, target.name)
    }

    // ------------------------------------------------------------------------------------------ delete / exists

    override suspend fun delete(location: String): DeleteResult = withContext(io) {
        try {
            if (location.startsWith("content://")) deleteContent(Uri.parse(location)) else deleteFile(location)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            DeleteResult.Failed("Android did not allow VidDown to delete this file. Delete it in your file manager instead.")
        } catch (e: Exception) {
            DeleteResult.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun deleteContent(uri: Uri): DeleteResult {
        if (!existsContent(uri)) return DeleteResult.NotFound
        val removed = if (uri.authority == MediaStore.AUTHORITY) {
            resolver.delete(uri, null, null) > 0
        } else {
            try {
                DocumentsContract.deleteDocument(resolver, uri)
            } catch (e: FileNotFoundException) {
                return DeleteResult.NotFound
            }
        }
        return if (removed) DeleteResult.Deleted else DeleteResult.Failed("The file could not be deleted")
    }

    private fun deleteFile(path: String): DeleteResult {
        val file = File(path)
        // Only files directly inside one of VidDown's own folders: a changed database entry must never delete anything else.
        val allowed = listOfNotNull(appFolder()?.canonicalFile, StorageAccess.rootFolder().canonicalFile)
        if (file.canonicalFile.parentFile !in allowed) return DeleteResult.Failed("This file is not in the VidDown download folder")
        if (!file.exists()) return DeleteResult.NotFound
        return if (file.delete()) DeleteResult.Deleted else DeleteResult.Failed("The file could not be deleted")
    }

    override suspend fun exists(location: String): Boolean = withContext(io) {
        try {
            if (location.startsWith("content://")) existsContent(Uri.parse(location)) else File(location).isFile
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun existsContent(uri: Uri): Boolean = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: FileNotFoundException) {
        false
    }

    // ------------------------------------------------------------------------------------------ helpers

    private suspend fun copyInto(uri: Uri, source: File) {
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Android did not open the destination file")
        out.use { stream -> source.inputStream().use { input -> copy(input, stream, source.length()) } }
    }

    /** A copy loop that notices cancellation and verifies that every byte arrived. */
    private suspend fun copy(input: InputStream, out: OutputStream, expected: Long) {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        out.flush()
        if (total != expected) throw IOException("Copied $total of $expected bytes")
    }

    private fun displayNameOf(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (_: Exception) {
            // a pending row that cannot be removed is cleaned up by Android later
        }
    }

    private fun mapIo(e: IOException): DownloadError {
        val detail = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        val m = (e.message ?: "").lowercase()
        return when {
            "no space left" in m || "enospc" in m || "quota" in m -> DownloadError.insufficientStorage(detail)
            "permission denied" in m || "eacces" in m -> DownloadError.permissionDenied(detail)
            else -> DownloadError.invalidDestination(detail)
        }
    }

    private companion object {
        const val FOLDER = StorageAccess.FOLDER
        const val BUFFER = 64 * 1024
        const val MAX_NAME_ATTEMPTS = 100
    }
}
