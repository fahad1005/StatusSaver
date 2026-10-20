package com.fahad1005.statussaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.util.Size
import java.io.File

// StatusScanner: finds WhatsApp's hidden status folders and lists
// the viewed statuses inside, newest first. Pure logic, no UI.
object StatusScanner {

    // One status found on the phone: its file + whether it's a video.
    data class StatusFile(val file: File, val isVideo: Boolean)

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png")
    private val VIDEO_EXT = setOf("mp4")

    // WhatsApp (and WhatsApp Business) stash viewed statuses in these hidden
    // folders. Newer Android versions use the Android/media path,
    // older installs use the top-level path. We check all of them.
    private fun candidateDirs(): List<File> {
        val sdcard = Environment.getExternalStorageDirectory()
        return listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
            "WhatsApp/Media/.Statuses",
            "Android/media/com.whatsapp.w4b/WhatsApp/Media/.Statuses",
            "WhatsApp Business/Media/.Statuses"
        ).map { File(sdcard, it) }
    }

    // All statuses found, newest first. Duplicates (same file seen via
    // two paths) are removed.
    fun scan(): List<StatusFile> {
        val found = linkedMapOf<String, StatusFile>()
        for (dir in candidateDirs()) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                if (f.name == ".nomedia") continue
                val ext = f.extension.lowercase()
                val isVideo = ext in VIDEO_EXT
                if (ext !in IMAGE_EXT && !isVideo) continue
                found.putIfAbsent(f.absolutePath, StatusFile(f, isVideo))
            }
        }
        return found.values.sortedByDescending { it.file.lastModified() }
    }

    // The single newest status by mtime. Note: it's a heuristic — the
    // folder holds recently-viewed statuses, so the newest file is
    // USUALLY the one on screen. "Usually" failed in the field (see
    // changedSince below), so since v1.4.1 this is only the FALLBACK
    // target for watcher saves, and the in-app grid doesn't use it at
    // all (each row saves its own file).
    fun newest(): StatusFile? = scan().firstOrNull()

    // v1.4.1 SESSION TARGETING — a snapshot of the whole cache as
    // filename → (sizeBytes, lastModifiedMs). Keyed by NAME, not path:
    // the same status can be visible through two candidate folders
    // (current WhatsApp + an old install path) under the same
    // content-hash name — for targeting, the name IS the identity.
    // scan() is newest-first, so the first sighting of a name wins.
    fun snapshot(): Map<String, Pair<Long, Long>> {
        val out = LinkedHashMap<String, Pair<Long, Long>>()
        for (s in scan()) {
            out.putIfAbsent(s.file.name, s.file.length() to s.file.lastModified())
        }
        return out
    }

    // v1.4.1: the files that APPEARED or CHANGED since a snapshot was
    // taken — not in the snapshot at all, or present with a different
    // size or mtime. Newest first (scan() order), so the FIRST entry
    // is the watcher's save target.
    //
    // Why this exists: WhatsApp does not make the status you are
    // looking at the newest file in the cache. It pre-caches while
    // the status LIST is scrolled and finishes downloads on its own
    // schedule, so a previously-viewed file can hold the freshest
    // mtime while the current status's file was written earlier. The
    // v1.3 recording proved it: a tap on a gem status saved the
    // PREVIOUS status's video (same wrong filename in the toast
    // twice). Diffing against the snapshot taken when the viewer was
    // ENTERED isolates what WhatsApp fetched for THIS viewing.
    fun changedSince(snapshot: Map<String, Pair<Long, Long>>): List<StatusFile> {
        return scan().filter { s ->
            val before = snapshot[s.file.name]
            before == null ||
                before.first != s.file.length() ||
                before.second != s.file.lastModified()
        }
    }

    // Small preview image for the grid. Photos are downsized so the
    // grid stays fast; videos get their first frame.
    fun thumbnail(file: File, isVideo: Boolean): Bitmap? {
        return try {
            if (!isVideo) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(file, Size(320, 320), null)
            } else {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val frame = retriever.getFrameAtTime(0)
                retriever.release()
                frame
            }
        } catch (e: Exception) {
            null
        }
    }
}
