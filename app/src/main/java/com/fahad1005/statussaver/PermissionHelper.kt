package com.fahad1005.statussaver

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// PermissionHelper: all three "scary permission screens" in one place.
// The main screen asks for them in order: overlay → files → notifications.
object PermissionHelper {

    private const val REQ_NOTIFICATIONS = 1001
    private const val REQ_STORAGE_LEGACY = 1002

    // 1) "Display over other apps" — needed for the floating button.
    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    // 2) File access. On Android 11+ WhatsApp keeps statuses in a hidden
    //    folder that normal gallery permissions can't see, so we need the
    //    "All files access" special screen. (Play Store asks you to declare
    //    why — the suggested text is in the README.)
    //    On Android 10 and below, normal storage permissions are enough.
    fun hasFileAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestFileAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQ_STORAGE_LEGACY
            )
        }
    }

    // 3) Notifications — needed on Android 13+ so the floating-button
    //    service can show its persistent notification.
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }
}
