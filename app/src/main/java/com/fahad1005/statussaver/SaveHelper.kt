package com.fahad1005.statussaver

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

// SaveHelper: copies one status file into the public gallery,
// under Pictures/StatusSaver. Returns true if it worked.
object SaveHelper {

    fun saveToGallery(context: Context, file: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, file)
            } else {
                saveLegacy(context, file)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun mimeType(file: File): String {
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "image/jpeg"
    }

    // Android 10+: the modern way — insert into MediaStore with a
    // RELATIVE_PATH so it lands in Pictures/StatusSaver.
    private fun saveViaMediaStore(context: Context, file: File) {
        val isVideo = file.extension.lowercase() == "mp4"
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(file))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/StatusSaver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IllegalStateException("Could not open output stream")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    // Android 9 and below: copy the file directly, then tell the
    // media scanner so it shows up in the gallery.
    private fun saveLegacy(context: Context, file: File) {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val destDir = File(pictures, "StatusSaver")
        if (!destDir.exists()) destDir.mkdirs()
        val dest = File(destDir, file.name)
        file.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
    }
}
