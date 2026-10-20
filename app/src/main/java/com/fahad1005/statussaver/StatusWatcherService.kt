package com.fahad1005.statussaver

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent

// StatusWatcherService (v1.1): watches which app window is in front so the
// floating download button appears ONLY while a WhatsApp status is playing,
// and hides the moment the user leaves that screen.
//
// It uses the Accessibility framework purely as a "which screen is this?"
// sensor: it reads the foreground activity's package + class name and
// NOTHING else — canRetrieveWindowContent is false in the config, so it
// cannot see message text, names, or anything on the screen.
//
// The user must switch it on manually: Android Settings → Accessibility →
// Installed apps → StatusSaver (the app's home screen has a button that
// opens the settings page). Android shows its own warning screen first —
// that is expected and normal for every app that uses this feature.
class StatusWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "StatusWatcher"

        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        // Window classes that are transient decorations, not real screens:
        // reacting to them would make the button flicker. Ignored.
        private val TRANSIENT_CLASSES = listOf(
            "android.widget.PopupWindow",
            "android.widget.ListPopupWindow",
            "android.inputmethod",
            "PopupWindow"
        )
    }

    // Last state we pushed to the floating button, so we only send a
    // command when something actually changed.
    private var lastShown: Boolean? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return

        // Our own windows (including the overlay itself) are not signals.
        if (pkg == packageName) return
        // Transient popups/menus/keyboards are not screen changes.
        if (TRANSIENT_CLASSES.any { cls.contains(it) }) return

        // Debug builds only (FLAG_DEBUGGABLE is set on the CI debug APK,
        // not on a release build): log every foreground class name so a
        // future WhatsApp rename of the status player is easy to spot.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.d(TAG, "Foreground window: pkg=$pkg class=$cls")
        }

        val watching = isStatusPlayback(pkg, cls)
        if (watching != lastShown) {
            lastShown = watching
            FloatingService.onStatusViewingChanged(watching)
        }
    }

    override fun onInterrupt() {
        // Required override. Nothing to clean up — we hold no resources.
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Status watcher connected")
    }

    override fun onDestroy() {
        // If the watcher goes away, the safe state is "button hidden".
        lastShown = null
        FloatingService.onStatusViewingChanged(false)
        super.onDestroy()
    }

    // Is this window WhatsApp's status player?
    //
    // Primary matches follow the known activity names. The fallback covers
    // WhatsApp renaming the class in a future update: anything with both
    // "status" and "playback" (any case) in the class name counts.
    // If WhatsApp renames it to something entirely different, the debug log
    // above prints every class name seen — check logcat for "StatusWatcher"
    // to learn the new name and add it here.
    private fun isStatusPlayback(pkg: String, cls: String): Boolean {
        if (pkg !in WHATSAPP_PACKAGES) return false
        if (cls.contains("StatusPlayback")) return true
        if (cls.contains("status.playback")) return true
        if (cls.contains("StatusPlayer")) return true
        val lower = cls.lowercase()
        return lower.contains("status") && lower.contains("playback")
    }
}
