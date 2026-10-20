package com.fahad1005.statussaver

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File

// SaveHelper: copies one status file into the public gallery.
// Images land in Pictures/StatusSaver, videos in Movies/StatusSaver
// (see the v1.1 note in saveViaMediaStore — that distinction is the fix
// for "video says saved but never shows in the gallery").
//
// Returns true ONLY after the save is verified: every byte copied and,
// on Android 10+, the MediaStore row queried back with matching size
// and MIME type. Any failure returns false so callers show an honest
// error toast instead of a fake "Saved ✓".
object SaveHelper {

    private const val TAG = "SaveHelper"

    fun saveToGallery(context: Context, file: File, isVideo: Boolean): Boolean {
        return try {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, file, isVideo)
            } else {
                saveLegacy(context, file)
            }
            if (!ok) {
                Log.e(TAG, "Save reported failure for ${file.name}")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Save failed for ${file.name}", e)
            false
        }
    }

    // MIME type for the file. Videos are ALWAYS typed as video/* — v1 used
    // a generic extension lookup whose fallback was "image/jpeg" for every
    // file, so a video could be inserted into MediaStore mis-typed as an
    // image and then never treated as a video by gallery apps.
    private fun mimeType(file: File, isVideo: Boolean): String {
        val fromExt = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
        return when {
            isVideo && fromExt != null && fromExt.startsWith("video/") -> fromExt
            isVideo -> "video/mp4"
            fromExt != null -> fromExt
            else -> "image/jpeg"
        }
    }

    // Android 10+: insert into MediaStore with a RELATIVE_PATH, copy the
    // bytes while the row is IS_PENDING, then publish it (IS_PENDING = 0)
    // and verify the published row.
    //
    // v1.1 FIX — the video bug: v1 inserted VIDEOS into the Video
    // collection with RELATIVE_PATH "Pictures/StatusSaver". Gallery apps
    // (and MediaStore's folder views) surface videos from the Movies/
    // tree; a video parked under Pictures/ was written to disk but never
    // showed up where the user looks for videos — while images, correctly
    // paired (Images collection + Pictures path), worked fine. Videos now
    // go to Movies/StatusSaver.
    private fun saveViaMediaStore(context: Context, file: File, isVideo: Boolean): Boolean {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (isVideo) "Movies/StatusSaver" else "Pictures/StatusSaver"
        val mime = mimeType(file, isVideo)
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert returned null")

        try {
            // Copy every byte, flush, and prove the sizes match.
            val copied: Long = resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input ->
                    val n = input.copyTo(out)
                    out.flush()
                    n
                }
            } ?: throw IllegalStateException("Could not open output stream")
            if (copied != file.length()) {
                throw IllegalStateException(
                    "Copy incomplete: $copied of ${file.length()} bytes"
                )
            }

            // Publish the row so gallery apps can see it.
            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, publish, null, null)

            // VERIFY before claiming success: the row must exist, be
            // published (not pending), and carry the size + MIME type we
            // wrote. v1 toasted "Saved ✓" on "no exception" alone.
            val projection = arrayOf(
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.IS_PENDING
            )
            val verified = resolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use false
                val size = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                val type = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                // IS_PENDING is standard on Android 10+, but read it
                // defensively: if a device's MediaStore doesn't return the
                // column, size + MIME matching is proof enough.
                val pendingIdx = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                val pending = if (pendingIdx >= 0) c.getInt(pendingIdx) else 0
                size == file.length() && type == mime && pending == 0
            } ?: false
            if (!verified) {
                throw IllegalStateException("MediaStore row failed verification")
            }
            return true
        } catch (e: Exception) {
            // Never leave a half-written/pending row behind — it can show
            // up as a grey "broken" item in the gallery.
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {
                // Best effort cleanup only.
            }
            throw e
        }
    }

    // Android 9 and below: copy the file directly, then tell the
    // media scanner so it shows up in the gallery.
    private fun saveLegacy(context: Context, file: File): Boolean {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val destDir = File(pictures, "StatusSaver")
        if (!destDir.exists()) destDir.mkdirs()
        val dest = File(destDir, file.name)
        val copied = file.inputStream().use { input ->
            dest.outputStream().use { output ->
                val n = input.copyTo(output)
                output.flush()
                n
            }
        }
        if (copied != file.length() || !dest.exists()) {
            Log.e(TAG, "Legacy copy incomplete: $copied of ${file.length()} bytes")
            return false
        }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        return true
    }
}
