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
// EVERYTHING lands in ONE album: Pictures/StatusSaver (v1.4 — see the
// note in saveViaMediaStore; v1.1–v1.3 split videos into
// Movies/StatusSaver and users couldn't find them).
//
// A save counts as SAVED only after it is verified: every byte
// copied and, on Android 10+, the MediaStore row queried back with
// matching size and MIME type. Any failure is reported as FAILED so
// callers show an honest error toast instead of a fake "Saved ✓" —
// and since v1.5 the result is a SaveResult (below), which can also
// say ALREADY_SAVED.
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
//
// v1.4 addition — FILE READINESS (BUG 2): a tap can land while
// WhatsApp is still WRITING the status file (a video still streaming
// into the cache). Copying at that moment captured a truncated file —
// some copies then failed verification, some produced short/broken
// videos; either way the user saw "some download, some don't". Before
// copying we now wait for the file's size to stop changing (see
// awaitStableFile). It lives here, in the one save path, so button
// saves, automatic saves and in-app grid saves all benefit.
//
// v1.5 addition — GLOBAL DEDUPE + a result type that can say so.
// The v1.4.1 field film shows the SAME .jpg landing in the gallery
// twice (11:51 PM, then again ~12:02 AM) from ordinary manual taps:
// the only duplicate guard in the code watched the single most
// recent file and was consulted by auto-save alone. Now the check
// lives HERE, in the one choke point every save flows through,
// against a persisted SET of everything ever saved (Prefs), and the
// return value distinguishes "saved" / "already saved" / "failed
// (why)" so each caller can answer honestly.
object SaveHelper {

    // The outcome of one save attempt. Failed carries the reason —
    // the same text SaveHelper records in the Diagnostics LastSave
    // record — so a caller never has to invent its own explanation.
    sealed class SaveResult {
        object Saved : SaveResult()
        object AlreadySaved : SaveResult()
        data class Failed(val reason: String) : SaveResult()
    }

    private const val TAG = "SaveHelper"

    // File-readiness polling: check the length every 300 ms; the file
    // is "ready" when the length is unchanged across 2 consecutive
    // polls; give up after the budget — ~5 s for videos (they stream
    // into the cache longer; Fahad's recording showed statuses still
    // buffering while he tapped), ~3 s for images — and copy whatever
    // exists (the read-back verification still decides honestly).
    private const val STABILITY_POLL_MS = 300L
    private const val STABILITY_POLLS_NEEDED = 2
    private const val STABILITY_MAX_POLLS_VIDEO = 17 // ≈ 5.1 s
    private const val STABILITY_MAX_POLLS_IMAGE = 10 // ≈ 3.0 s

    // Waits until the file's size stops growing. Pure patience, no
    // failure mode of its own: any oddity (file vanishing mid-wait,
    // interruption) just ends the wait and lets the copy + verify
    // below tell the truth.
    private fun awaitStableFile(file: File, isVideo: Boolean) {
        val maxPolls =
            if (isVideo) STABILITY_MAX_POLLS_VIDEO else STABILITY_MAX_POLLS_IMAGE
        try {
            var lastLength = file.length()
            var stablePolls = 0
            var polls = 0
            while (polls < maxPolls) {
                Thread.sleep(STABILITY_POLL_MS)
                polls++
                val nowLength = file.length()
                if (nowLength == lastLength && nowLength > 0L) {
                    stablePolls++
                    if (stablePolls >= STABILITY_POLLS_NEEDED) return
                } else {
                    stablePolls = 0
                    lastLength = nowLength
                }
            }
            Log.i(
                TAG,
                "File size still changing after ${maxPolls * STABILITY_POLL_MS} ms " +
                    "wait: ${file.name}"
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "Stability wait skipped for ${file.name}", e)
        }
    }

    // viaAuto (final spec): true when the watcher saved on its own
    // because the user turned on "Auto-save every status I view". The
    // attempt is then recorded with reason "auto" so the Diagnostics
    // card can tell automatic saves from tapped ones.
    //
    // v1.5: returns SaveResult (was Boolean). An attempt whose file
    // identity (name + post-stability size) is already in Prefs'
    // saved set returns AlreadySaved WITHOUT copying — from every
    // entry point, not just auto-save — and is still recorded in the
    // Diagnostics LastSave record, with reason "already saved".
    fun saveToGallery(
        context: Context,
        file: File,
        isVideo: Boolean,
        viaAuto: Boolean = false
    ): SaveResult {
        var failureReason = ""
        var alreadySaved = false
        val ok = try {
            // v1.4: never copy a file WhatsApp may still be writing.
            // The dedupe check runs AFTER this wait on purpose: the
            // identity's size must be the file's settled size, not a
            // mid-download length that will never match anything.
            awaitStableFile(file, isVideo)
            if (file.length() <= 0L) {
                // Nothing to copy: WhatsApp hasn't written the status
                // yet (or already cleared it). Fail honestly so the
                // caller toasts "Save failed" instead of "saving" an
                // empty shell — and the reason lands in Diagnostics.
                throw IllegalStateException(
                    "Status file is empty — WhatsApp may still be downloading it"
                )
            }
            // v1.5: the dedupe choke point. One check, one place —
            // button, auto-save and grid all pass through here, so
            // no path can duplicate a save another path already made.
            if (Prefs.isAlreadySaved(context, file.name, file.length())) {
                alreadySaved = true
                false
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, file, isVideo)
            } else {
                saveLegacy(context, file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed for ${file.name}", e)
            failureReason = e.message ?: e.javaClass.simpleName
            false
        }
        // v1.3: record the attempt for the Diagnostics card. Guarded —
        // bookkeeping must never break the save path itself.
        // v1.5: an already-saved attempt is recorded too (ok = false —
        // no bytes were copied by THIS attempt — with the reason
        // "already saved"; the Diagnostics card renders that reason
        // as ALREADY SAVED, not as a failure).
        try {
            val reason = when {
                alreadySaved && viaAuto -> "auto: already saved"
                alreadySaved -> "already saved"
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
            // v1.5: ALSO add the identity to the persistent saved set
            // — that set, not this single record, is now the dedupe
            // authority (this record stays for auto-save's cheap
            // pre-check).
            if (ok) {
                Prefs.saveLastSavedFile(context, file.name, file.length())
                Prefs.recordSavedIdentity(context, file.name, file.length())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not record save attempt", e)
        }
        return when {
            alreadySaved -> SaveResult.AlreadySaved
            ok -> SaveResult.Saved
            else -> SaveResult.Failed(
                failureReason.ifEmpty { "copy or verification failed" }
            )
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
    // v1.1 FIX (history) — the video bug: v1 inserted VIDEOS into the
    // Video collection with RELATIVE_PATH "Pictures/StatusSaver", and
    // gallery VIDEO VIEWS didn't surface them; images, correctly paired
    // (Images collection + Pictures path), worked. v1.1→v1.3 therefore
    // sent videos to Movies/StatusSaver.
    //
    // v1.4 REVERSAL — ONE album (BUG 5): Fahad's v1.3 recording ends in
    // his Gallery, where the only StatusSaver album he can find holds
    // 3 images — the videos sit in a separate Movies/StatusSaver album
    // that vivo's Gallery effectively buries, so to the user "videos
    // don't save". The Play-Store category leader uses ONE album. So:
    // the COLLECTION stays type-correct (videos → Video collection,
    // images → Images collection, MIME untouched), but the
    // RELATIVE_PATH is "Pictures/StatusSaver" for BOTH — MediaStore
    // accepts video rows under a Pictures path, folder/album views
    // show the mixed album "StatusSaver", and there is exactly one
    // place to look. My Saved still reads the old Movies/StatusSaver
    // path so v1.0–v1.3 saves remain listed.
    private fun saveViaMediaStore(context: Context, file: File, isVideo: Boolean): Boolean {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = "Pictures/StatusSaver"
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
