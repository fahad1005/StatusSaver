package com.fahad1005.statussaver

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.Toast
import java.util.concurrent.TimeUnit

// StatusWatcherService — the app's whole button engine since v1.3.
//
// It uses the Accessibility framework purely as a "which screen is this?"
// sensor: it reads the foreground activity's package + class name and
// NOTHING else — canRetrieveWindowContent is false in the config, so it
// cannot see message text, names, or anything on the screen.
//
// v1.3 ARCHITECTURE CHANGE — the watcher now OWNS the floating button.
// Why: on Fahad's vivo phone (2026-10-20 field test of v1.2) the button
// worked, then died for good: the Diagnostics card showed the in-app
// toggle ON ("Hide floating button") but "Floating service: NOT
// RUNNING". The process had been killed (vivo battery manager / recents
// swipe), Android revived THIS accessibility watcher (the system keeps
// enabled accessibility services bound), but it did NOT revive the
// START_STICKY FloatingService — OEMs routinely suppress sticky-service
// restarts — and nothing noticed or healed. Two services meant two
// things that could die independently; the label (intent) and the
// running flag could disagree forever.
//
// So now there is ONE engine. This service:
//  - watches the foreground window (unchanged detector),
//  - creates the download button itself as an accessibility overlay
//    (WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY — a window
//    type only accessibility services may use; it needs NO
//    SYSTEM_ALERT_WINDOW permission),
//  - shows it whenever (the user's persisted ON choice) AND (WhatsApp
//    or WhatsApp Business is the foreground app) — FINAL SPEC change:
//    visibility is package-level, re-derived on EVERY window event as
//    a pure function of those two facts, never edge-triggered only.
//    The status-screen detector below still runs and its verdict is
//    still recorded for Diagnostics; it now drives the TAP instead of
//    the visibility (smart tap) and the optional auto-save.
//  - tap = smart: if a status is actually being viewed, save it — the
//    exact code path FloatingService used (StatusScanner + SaveHelper,
//    verified save, honest toast); anywhere else in WhatsApp, explain
//    ("Open a status first…") instead of silently doing nothing,
//  - optional auto-save: if the user switched it on, entering the
//    status viewer saves the newest status by itself (debounced,
//    duplicate-guarded).
// If the process dies, the system rebinds this one service and
// onServiceConnected re-reads the persisted choice + last-screen record
// and restores the correct button state. The persistent notification
// and the foreground service are gone — deliberately.
//
// Show/hide uses addView/removeView, NOT visibility flips: when the
// button is hidden there is no window at all — nothing drawn, nothing
// touchable, nothing for another app to trip over — and "is the button
// up right now?" has one unambiguous answer (isButtonVisible below),
// which the Diagnostics card reads.
//
// Process note: no component declares android:process, so this service
// and MainActivity share one process and the static hooks below are
// direct calls — no IPC, no broadcasts.
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
        // used only on (re)connect handshakes, never to keep the button
        // up indefinitely.
        private const val FRESH_MS = 30_000L

        // Auto-save debounce: entering the status viewer starts this
        // timer; leaving (or another status starting) restarts it. It
        // keeps a quick scroll past a status from saving files the
        // user barely saw, and collapses the burst of window events a
        // single status opening produces into one save.
        private const val AUTO_SAVE_DEBOUNCE_MS = 900L

        // ---- Diagnostic state (read by MainActivity's Diagnostics card) ----
        // TRUE only while this service is actually bound by Android and
        // receiving events. Distinct from the Settings toggle on purpose:
        // the toggle can say On while the service was never (re)bound.
        // @Volatile: written on the accessibility thread, read on the UI
        // thread. Same process, so no IPC needed.
        @Volatile
        var watcherConnected = false
            private set

        // TRUE while the button window is actually attached on screen.
        // This is ground truth for the Diagnostics card — not an intent,
        // not a flag about a service in another lifecycle.
        @Volatile
        var isButtonVisible = false
            private set

        // The live watcher instance (same process), so MainActivity's
        // toggle can ask it to re-evaluate the button immediately after
        // the user flips the persisted on/off choice.
        @Volatile
        private var instance: StatusWatcherService? = null

        // Called by MainActivity right after it flips Prefs.buttonEnabled.
        // Safe to call any time: if the watcher isn't connected it's a
        // no-op — and in that case MainActivity tells the user to turn
        // Accessibility on first instead of pretending (see MainActivity).
        fun reevaluate() {
            val svc = instance ?: return
            svc.handler.post { svc.updateButton() }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    // Detector state, per the last real window event (or the fresh
    // persisted record on connect):
    // - statusOnScreen: the status DETECTOR matched (a status is being
    //   viewed). Drives smart tap + auto-save, NOT visibility.
    // - whatsappOnScreen: the foreground app is WhatsApp / WhatsApp
    //   Business, whatever screen it's showing. Drives visibility.
    private var statusOnScreen = false
    private var whatsappOnScreen = false

    // Pending auto-save, if any (see AUTO_SAVE_DEBOUNCE_MS).
    private var pendingAutoSave: Runnable? = null

    // ---- The button (owned by this service since v1.3) ----
    private var windowManager: WindowManager? = null
    private var button: ImageView? = null
    private var buttonAttached = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return

        // Our own windows (including the button overlay itself and our
        // toasts) are not signals.
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

        // Persist EVERY real window change (any app), with the match
        // verdict. The Diagnostics card shows this record, and
        // onServiceConnected re-reads it after a process restart. Our own
        // package is filtered above, so coming back to StatusSaver to
        // read the card never overwrites the evidence.
        Prefs.saveLastScreen(this, pkg, cls, matched, System.currentTimeMillis())

        // Auto-save reacts to TRANSITIONS into the status viewer only:
        // arm the debounce when a status starts being viewed, cancel
        // it the moment the viewer is left.
        val wasStatus = statusOnScreen
        statusOnScreen = matched
        whatsappOnScreen = pkg in WHATSAPP_PACKAGES
        if (matched && !wasStatus) {
            armAutoSave()
        } else if (!matched && wasStatus) {
            cancelAutoSave()
        }

        updateButton()
    }

    override fun onInterrupt() {
        // Required override. Nothing to clean up — we hold no resources
        // beyond the button, whose lifecycle follows the service's.
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        watcherConnected = true
        Log.i(TAG, "Status watcher connected (v1.3: watcher owns the button)")
        // Self-heal handshake: we may have been (re)bound after the
        // whole process was killed. Re-read the persisted evidence and
        // evaluate the current screen as far as it lets us: a FRESH
        // record (<30 s) is trustworthy — if it says the user is inside
        // WhatsApp right now, restore the button immediately instead
        // of waiting for a window change that may never come while the
        // user stares at a status. A stale record proves nothing, so
        // the button starts hidden and the next real event decides.
        try {
            val last = Prefs.lastScreen(this)
            val fresh = last != null &&
                System.currentTimeMillis() - last.timeMs < FRESH_MS
            whatsappOnScreen = fresh && last!!.pkg in WHATSAPP_PACKAGES
            statusOnScreen = fresh && last.matched
        } catch (e: Exception) {
            Log.e(TAG, "Could not re-read persisted screen record", e)
            whatsappOnScreen = false
            statusOnScreen = false
        }
        updateButton()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Android unbound us (Settings toggle off, system reclaim, OEM
        // battery manager...). From here we receive NO events, whatever
        // the Settings list may still claim — the Diagnostics card must
        // say NOT CONNECTED. Safe state for the button: removed.
        watcherConnected = false
        if (instance === this) instance = null
        statusOnScreen = false
        whatsappOnScreen = false
        cancelAutoSave()
        detachButton()
        Log.i(TAG, "Status watcher unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // If the watcher goes away, the button goes with it — there is
        // no second service left to hold it up.
        watcherConnected = false
        if (instance === this) instance = null
        statusOnScreen = false
        whatsappOnScreen = false
        cancelAutoSave()
        detachButton()
        super.onDestroy()
    }

    // ---- The floating button ----

    // The ONE rule for the button's existence (final spec — visibility
    // is PACKAGE-level):
    //   visible  ⇔  user switched it ON (persisted choice)
    //               AND WhatsApp / WhatsApp Business is in the foreground.
    // updateButton() re-derives this from scratch on EVERY window
    // event, on (re)connect, and whenever MainActivity flips the
    // choice (via reevaluate()) — it is a pure function of the two
    // facts above, never edge-triggered: attachButton() re-adds the
    // view if it turns out missing, detachButton() removes it if it
    // turns out present. Whether a STATUS is on screen no longer gates
    // visibility; it gates the tap (see onButtonTap) and auto-save.
    private fun updateButton() {
        try {
            val shouldShow = Prefs.isButtonEnabled(this) && whatsappOnScreen
            if (shouldShow) attachButton() else detachButton()
        } catch (e: Exception) {
            Log.e(TAG, "updateButton failed", e)
        }
    }

    private fun attachButton() {
        val view = button ?: createButtonView().also { button = it }
        // Already up? A view added through WindowManager has a parent
        // (its ViewRootImpl). Trust the parent, not just our flag: if
        // the flag says attached but the view has no parent, it was
        // dropped underneath us — fall through and re-add it.
        if (buttonAttached && view.parent != null) {
            isButtonVisible = true
            return
        }
        try {
            val wm = windowManager
                ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .also { windowManager = it }
            wm.addView(view, buttonLayoutParams())
            buttonAttached = true
            isButtonVisible = true
        } catch (e: IllegalStateException) {
            // "View already added" — the window IS up; our flag was
            // the only thing wrong. Correct it from the same evidence.
            buttonAttached = view.parent != null
            isButtonVisible = buttonAttached
            if (!buttonAttached) {
                Log.e(TAG, "Button addView raced and lost; will retry next event")
            }
        } catch (e: Exception) {
            // Never claim the button is up when it isn't — the
            // Diagnostics card reads isButtonVisible as ground truth.
            Log.e(TAG, "Could not attach floating button", e)
            buttonAttached = false
            isButtonVisible = false
        }
    }

    private fun detachButton() {
        val view = button
        if (view != null && view.parent != null) {
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // Already gone — nothing to do.
                Log.w(TAG, "Button removeView failed (already detached?)", e)
            }
        }
        buttonAttached = false
        isButtonVisible = false
    }

    // Same look as v1–v1.2: 56dp WhatsApp-green circle, white download
    // icon, pinned bottom-right above WhatsApp's reply bar. The ONLY
    // change is the window type: TYPE_ACCESSIBILITY_OVERLAY, which
    // Android reserves for accessibility services — no "display over
    // other apps" permission involved.
    private fun createButtonView(): ImageView {
        val density = resources.displayMetrics.density
        return ImageView(this).apply {
            setImageResource(R.drawable.ic_download)
            setBackgroundResource(R.drawable.floating_bg)
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 6 * density
            }
            contentDescription = getString(R.string.floating_button_desc)
            setOnClickListener { onButtonTap() }
        }
    }

    private fun buttonLayoutParams(): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE: the button takes taps but never steals
            // keyboard/IME focus from WhatsApp. (NOT_TOUCHABLE must NOT
            // be set — the tap is the whole point.)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            // Fixed spot: bottom-right, lifted ~110dp so it clears
            // WhatsApp's reply bar on the status screen.
            x = (16 * density).toInt()
            y = (110 * density).toInt()
        }
    }

    // ---- Tap action: SMART TAP (final spec) ----
    // The button is visible on EVERY WhatsApp screen now, so a tap no
    // longer implies "a status is open". Rule:
    //  - a status IS being viewed → save it (the flow below);
    //  - anything else (chat list, a chat, settings…) → say so, plainly:
    //    "Open a status first, then tap the green button."
    // No silent no-ops: every tap gets either a save or an explanation.
    private fun onButtonTap() {
        if (isStatusBeingViewed()) {
            saveNewestStatus()
        } else {
            toastOnUi(getString(R.string.open_status_first))
        }
    }

    // "Being viewed" = the live detector state, with the persisted
    // record as back-up: a MATCH recorded <30 s ago still counts (the
    // tap itself, a popup, or a transient window may have been the
    // last event we processed).
    private fun isStatusBeingViewed(): Boolean {
        if (statusOnScreen) return true
        val last = try {
            Prefs.lastScreen(this)
        } catch (e: Exception) {
            null
        } ?: return false
        return last.matched &&
            System.currentTimeMillis() - last.timeMs < FRESH_MS
    }

    // ---- Save the newest status ----
    // Moved verbatim from FloatingService (v1.1/v1.2): same scanner, same
    // verified SaveHelper path, same honest toasts. Runs on a background
    // thread, and every failure is caught + logged — v1 taught us that
    // an uncaught exception here kills the whole process (and, now,
    // the watcher itself).

    private fun saveNewestStatus() {
        Thread {
            try {
                val newest = StatusScanner.newest()
                if (newest == null) {
                    toastOnUi(getString(R.string.no_statuses))
                    return@Thread
                }
                val ok = SaveHelper.saveToGallery(this, newest.file, newest.isVideo)
                // Sanity check: if the newest file is much older than "now",
                // the user probably isn't looking at it — still save it,
                // but say which file it was.
                val ageMin = TimeUnit.MILLISECONDS.toMinutes(
                    System.currentTimeMillis() - newest.file.lastModified()
                )
                val msg = if (!ok) {
                    getString(R.string.save_failed)
                } else if (ageMin <= 2) {
                    getString(R.string.saved_gallery)
                } else {
                    getString(R.string.saved_which_file, newest.file.name, ageMin)
                }
                toastOnUi(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Save from floating button failed", e)
                toastOnUi(getString(R.string.save_failed))
            }
        }.start()
    }

    private fun toastOnUi(msg: String) {
        handler.post {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Toast failed", e)
            }
        }
    }

    // ---- Auto-save (final spec, default OFF) ----
    // When the user turns on "Auto-save every status I view", entering
    // the status viewer arms a short timer; if the user is still (or
    // again) viewing a status when it fires, the newest status saves
    // itself — no tap. Duplicate guard: SaveHelper records every
    // successful save's identity (name + length) in Prefs, manual saves
    // included; auto-save skips the newest file if it IS that record,
    // so re-viewing a status never saves it twice. Attempts are
    // recorded by SaveHelper with reason "auto".

    private fun armAutoSave() {
        cancelAutoSave()
        if (!Prefs.isAutoSave(this)) return
        val task = Runnable {
            pendingAutoSave = null
            if (statusOnScreen) autoSaveNow()
        }
        pendingAutoSave = task
        handler.postDelayed(task, AUTO_SAVE_DEBOUNCE_MS)
    }

    private fun cancelAutoSave() {
        pendingAutoSave?.let { handler.removeCallbacks(it) }
        pendingAutoSave = null
    }

    private fun autoSaveNow() {
        Thread {
            try {
                val newest = StatusScanner.newest() ?: return@Thread
                val already = Prefs.lastSavedFile(this)
                if (already != null &&
                    already.first == newest.file.name &&
                    already.second == newest.file.length()
                ) {
                    Log.i(TAG, "Auto-save skipped (already saved): ${newest.file.name}")
                    return@Thread
                }
                val ok = SaveHelper.saveToGallery(
                    this, newest.file, newest.isVideo, viaAuto = true
                )
                // Brief confirmation is fine for an actual save; a
                // failure stays silent here (no toast spam while the
                // user is just browsing) but IS recorded — the
                // Diagnostics card's Last save attempt row shows it.
                if (ok) toastOnUi(getString(R.string.saved_gallery))
            } catch (e: Exception) {
                Log.e(TAG, "Auto-save failed", e)
            }
        }.start()
    }

    // Is this window WhatsApp's status player?
    //
    // Rule (unchanged since v1.2):
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
