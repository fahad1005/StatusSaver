package com.fahad1005.statussaver

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent

// StatusWatcherService (v1.1, hardened in v1.2): watches which app window
// is in front so the floating download button appears ONLY while a
// WhatsApp status is playing, and hides the moment the user leaves that
// screen.
//
// It uses the Accessibility framework purely as a "which screen is this?"
// sensor: it reads the foreground activity's package + class name and
// NOTHING else — canRetrieveWindowContent is false in the config, so it
// cannot see message text, names, or anything on the screen.
//
// v1.2 changes (v1.1's button sometimes never appeared on a vivo phone):
//  - WIDER detector (see isStatusPlayback): WhatsApp renames its status
//    screens between versions, so v1.1's exact names could miss.
//  - Every real window change is written to Prefs (the Diagnostics card
//    on the home screen reads it back — proof of what the watcher saw,
//    no logcat needed), and FloatingService re-reads that record when it
//    (re)starts, so the watcher↔button link no longer depends only on
//    one live static call landing at the right moment.
//  - watcherConnected is set/cleared in onServiceConnected / onUnbind /
//    onDestroy, so the Diagnostics card can tell "switch is On in
//    Settings" apart from "the watcher is actually connected and
//    receiving events" — on some phones (vivo especially) those differ.
//
// Process note (checked for v1.2): the manifest declares NO
// android:process for either service, so this watcher and
// FloatingService run in the SAME process and the direct static calls
// between them are valid — no IPC, no broadcasts, no background
// foreground-service-start restrictions to trip over.
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

        // Class-name fragments that must NEVER count as "watching a
        // status", even if another rule matches: the "About" text-status
        // EDITOR (it has "textstatus" in the name — you are typing, not
        // viewing), and WhatsApp's settings / profile screens.
        private val EXCLUDED_FRAGMENTS = listOf("textstatus", "settings", "profile")

        // The status screen's name must contain "status" AND one of these.
        // v1.1 only accepted "playback"; WhatsApp also uses player/view/
        // story/media-style names across versions and OEM builds.
        private val STATUS_HINTS = listOf("playback", "player", "view", "story", "media")

        // A persisted screen record older than this is stale evidence —
        // used only on (re)connect/(re)start handshakes, never to keep
        // the button up indefinitely.
        private const val FRESH_MS = 30_000L

        // ---- Diagnostic state (read by MainActivity's Diagnostics card) ----
        // TRUE only while this service is actually bound by Android and
        // receiving events. Distinct from the Settings toggle on purpose:
        // the toggle can say On while the service was never (re)bound.
        // @Volatile: written on the accessibility thread, read on the UI
        // thread. Same process, so no IPC needed.
        @Volatile
        var watcherConnected = false
            private set
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

        val matched = isStatusPlayback(pkg, cls)

        // v1.2: persist EVERY real window change (any app), with the
        // match verdict. The Diagnostics card shows this record, and
        // FloatingService re-reads it when it (re)starts. Our own
        // package is filtered above, so coming back to StatusSaver to
        // read the card never overwrites the evidence.
        Prefs.saveLastScreen(this, pkg, cls, matched, System.currentTimeMillis())

        if (matched != lastShown) {
            lastShown = matched
            FloatingService.onStatusViewingChanged(matched)
        }
    }

    override fun onInterrupt() {
        // Required override. Nothing to clean up — we hold no resources.
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        watcherConnected = true // v1.2 diagnostic
        Log.i(TAG, "Status watcher connected")
        // Handshake (v1.2): if the floating-button service is already
        // running, bring it up to date immediately — it may have
        // (re)started while this watcher was disconnected and missed
        // the "user is watching a status" moment. Only fresh records
        // count; a stale MATCH from an earlier session must not pop
        // the button up now.
        if (FloatingService.isRunning) {
            val last = Prefs.lastScreen(this)
            if (last != null &&
                System.currentTimeMillis() - last.timeMs < FRESH_MS
            ) {
                lastShown = last.matched
                FloatingService.onStatusViewingChanged(last.matched)
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Android unbound us (Settings toggle off, system reclaim, OEM
        // battery manager...). From here we receive NO events, whatever
        // the Settings list may still claim — the Diagnostics card must
        // say NOT CONNECTED. Safe state for the button: hidden.
        watcherConnected = false
        lastShown = null
        FloatingService.onStatusViewingChanged(false)
        Log.i(TAG, "Status watcher unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // If the watcher goes away, the safe state is "button hidden".
        watcherConnected = false
        lastShown = null
        FloatingService.onStatusViewingChanged(false)
        super.onDestroy()
    }

    // Is this window WhatsApp's status player?
    //
    // v1.2 rule (wider than v1.1, still principled):
    //  1. Package must be WhatsApp or WhatsApp Business.
    //  2. Exclusions win first: the About text-status editor, settings
    //     and profile screens are never "watching a status".
    //  3. The exact v1.1 names still match (StatusPlayback /
    //     status.playback / StatusPlayer).
    //  4. Otherwise the class name must contain "status" AND one of
    //     playback/player/view/story/media (any case) — this covers
    //     WhatsApp renaming the screen between versions.
    // If a future rename escapes even this, the Diagnostics card shows
    // the exact class name WhatsApp used — add it here and rebuild.
    private fun isStatusPlayback(pkg: String, cls: String): Boolean {
        if (pkg !in WHATSAPP_PACKAGES) return false
        val lower = cls.lowercase()
        if (EXCLUDED_FRAGMENTS.any { lower.contains(it) }) return false
        if (cls.contains("StatusPlayback")) return true
        if (cls.contains("status.playback")) return true
        if (cls.contains("StatusPlayer")) return true
        if (!lower.contains("status")) return false
        return STATUS_HINTS.any { lower.contains(it) }
    }
}
