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
//
// v1.3 additions:
//  - Every attempt (success OR failure, with the failure's reason) is
//    recorded in Prefs, so the Diagnostics card can answer "did it
//    save?" from one screenshot.
//  - Gallery visibility: the MediaStore insert now carries DATE_TAKEN
//    and DATE_ADDED, and after the verified publish we also hand the
//    file to MediaScannerConnection. On the v1.2 field test the files
//    WERE in the Files app immediately but the phone's Gallery app
//    didn't surface them — OEM galleries lean on these dates and on
//    scanner indexing, so we now feed both.
object SaveHelper {

    private const val TAG = "SaveHelper"

    // viaAuto (final spec): true when the watcher saved on its own
    // because the user turned on "Auto-save every status I view". The
    // attempt is then recorded with reason "auto" so the Diagnostics
    // card can tell automatic saves from tapped ones.
    fun saveToGallery(
        context: Context,
        file: File,
        isVideo: Boolean,
        viaAuto: Boolean = false
    ): Boolean {
        var failureReason = ""
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, file, isVideo)
            } else {
                saveLegacy(context, file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed for ${file.name}", e)
            failureReason = e.message ?: e.javaClass.simpleName
            false
        }
        if (!ok && failureReason.isEmpty()) {
            failureReason = "copy or verification failed"
        }
        // v1.3: record the attempt for the Diagnostics card. Guarded —
        // bookkeeping must never break the save path itself.
        try {
            val reason = when {
                ok && viaAuto -> "auto"
                ok -> ""
                viaAuto -> "auto: $failureReason"
                else -> failureReason
            }
            Prefs.saveLastSaveAttempt(
                context,
                System.currentTimeMillis(),
                file.name,
                file.length(),
                ok,
                reason
            )
            // Final spec: remember WHICH file was saved (any path —
            // button, grid, automatic) so auto-save's duplicate guard
            // can skip re-saving the very same file.
            if (ok) {
                Prefs.saveLastSavedFile(context, file.name, file.length())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not record save attempt", e)
        }
        return ok
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
            // v1.3 gallery visibility: DATE_TAKEN is in MILLIseconds,
            // DATE_ADDED in SECONDS (MediaStore's units — do not swap
            // them). Some OEM gallery apps sort/filter albums by these
            // columns and were slow to surface rows that lacked them.
            put(MediaStore.MediaColumns.DATE_TAKEN, file.lastModified())
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
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

            // v1.3 gallery visibility, part 2: even with a correct,
            // verified MediaStore row, some OEM galleries only pick new
            // files up when the media scanner walks them. Resolve the
            // row's real path (_data) and hand it to the scanner. Best
            // effort only — the save is already verified above, so a
            // missing path or a scanner hiccup must not fail it.
            try {
                val path = resolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                    } else {
                        null
                    }
                }
                if (!path.isNullOrEmpty()) {
                    MediaScannerConnection.scanFile(
                        context.applicationContext, arrayOf(path), arrayOf(mime), null
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaScanner nudge skipped", e)
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
            // Thrown (not returned) so saveToGallery can record WHY the
            // save failed in the Diagnostics record — same false result
            // for the caller, better evidence.
            throw IllegalStateException(
                "Legacy copy incomplete: $copied of ${file.length()} bytes"
            )
        }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        return true
    }
}
