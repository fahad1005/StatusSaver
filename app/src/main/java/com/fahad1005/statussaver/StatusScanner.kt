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

    // The single newest status — this is what the floating button saves.
    // Note: it's a heuristic. The folder holds recently-viewed statuses,
    // so the newest file is almost always the one on screen.
    fun newest(): StatusFile? = scan().firstOrNull()

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
