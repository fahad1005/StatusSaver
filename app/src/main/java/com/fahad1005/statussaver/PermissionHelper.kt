package com.fahad1005.statussaver

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// PermissionHelper: the "scary permission screens" in one place.
// Since the v1.3 final spec, the home screen runs a one-time Allow
// wizard with three steps, each deep-linking to the exact system
// screen: 1) accessibility (the engine — the watcher draws the button
// itself, as an accessibility overlay), 2) file access, 3) battery
// exemption (recommended, not a hard gate). The old overlay +
// notification helpers are kept below (marked legacy): the Diagnostics
// card still reports the overlay state for the record.
object PermissionHelper {

    private const val REQ_NOTIFICATIONS = 1001
    private const val REQ_STORAGE_LEGACY = 1002

    // Legacy (v1–v1.2): "Display over other apps" — the retired
    // FloatingService needed it for its TYPE_APPLICATION_OVERLAY button.
    // Since v1.3 the button is an accessibility overlay and does NOT
    // need this. Kept only so the Diagnostics card can report the state.
    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    // 2) File access (wizard step 2). On Android 11+ WhatsApp keeps statuses in a hidden
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
            // App-specific screen first (lands on THIS app's toggle).
            // Some OEM builds drop that action — fall back to the
            // all-apps list, then to the app-details page.
            val appSpecific = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            if (!tryStart(activity, appSpecific)) {
                val general = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                if (!tryStart(activity, general)) {
                    tryStart(
                        activity,
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
            }
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

    // Legacy (v1–v1.2): Notifications — was needed on Android 13+ so the
    //    retired FloatingService could show its persistent notification.
    //    Nothing in v1.3 posts notifications; kept for the record.
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

    // 1) Accessibility (wizard step 1; v1.1; THE engine since v1.3) — NOT a "permission"
    //    Android grants in-app. The user must switch StatusWatcherService
    //    on by hand in Settings → Accessibility → Installed apps →
    //    StatusSaver. The watcher is what makes the floating button
    //    appear while WhatsApp is in the foreground (never over other
    //    apps) — and since v1.3 the watcher also draws the button
    //    itself, so without this switch there is no button at all.
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, StatusWatcherService::class.java)
        val enabledList = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // The setting is a colon-separated list of flattened component
        // names. Android stores them in long ("pkg/full.Class") or short
        // ("pkg/.Class") form depending on the device — accept both.
        val candidates = setOf(
            expected.flattenToString().lowercase(),
            expected.flattenToShortString().lowercase()
        )
        return enabledList.split(':').any { it.trim().lowercase() in candidates }
    }

    fun requestAccessibilitySettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // 3) Battery (final spec, wizard step 3) — NOT a permission and NOT
    //    a hard gate: it asks Android to exempt StatusSaver from battery
    //    optimisation so aggressive battery managers don't disconnect
    //    the watcher (the v1.2 field-test killer). The request shows
    //    Android's own Allow/Deny dialog; the wizard re-checks on
    //    return via hasBatteryExemption.
    fun hasBatteryExemption(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE)
            as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestBatteryExemption(activity: Activity) {
        // The direct exemption dialog. A few OEM builds don't implement
        // it — fall back to this app's details page (Battery lives
        // there), which is two taps more but always exists.
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}")
        )
        if (!tryStart(activity, direct)) {
            tryStart(
                activity,
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }
    }

    // 4) Autostart (v1.4, wizard step 4 — OPTIONAL, advisory only, never
    //    a gate). Some OEMs hide an "Autostart" switch that decides
    //    whether an app may come back on its own after a reboot or a
    //    battery-manager kill; if it's off, the watcher can't return
    //    until the user opens the app — the v1.3 "every time I have to
    //    start and allow it" complaint. There is NO standard Android
    //    screen for this, so we try the well-known OEM settings
    //    activities in turn and land on the app-details page if none
    //    of them exist. This is a settings DEEP-LINK only: opening the
    //    screen changes nothing by itself, the user flips the switch,
    //    and no app behaviour branches on the phone's brand.
    fun requestAutostartSettings(activity: Activity) {
        val candidates = listOf(
            // Xiaomi / Redmi / Poco (MIUI permission centre)
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            // vivo / iQOO (permission manager, background-start screen)
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            // Oppo / Realme (ColorOS safeguard) and OnePlus (Oplus)
            ComponentName(
                "com.coloros.safeguard",
                "com.coloros.safeguard.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.oplus.safeguard",
                "com.oplus.safeguard.permission.startup.StartupAppListActivity"
            )
        )
        for (component in candidates) {
            if (tryStart(activity, Intent().setComponent(component))) return
        }
        // No OEM autostart screen on this phone — the app-details page
        // always exists (Autostart, where offered, also lives there).
        tryStart(
            activity,
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}")
            )
        )
    }

    // startActivity that reports failure instead of crashing the wizard
    // when an OEM build doesn't know an action.
    private fun tryStart(activity: Activity, intent: Intent): Boolean {
        return try {
            activity.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
