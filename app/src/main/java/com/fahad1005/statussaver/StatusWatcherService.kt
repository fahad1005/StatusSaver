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
//    still recorded for Diagnostics; since v1.4 it drives ONLY the
//    optional auto-save (the tap was ungated — see onButtonTap).
//  - tap = save: a tap in WhatsApp saves the status being viewed —
//    the exact code path FloatingService used (StatusScanner +
//    SaveHelper, verified save, honest toast). v1.3's matcher gate
//    ("smart tap") refused some real statuses; v1.4 removed it: the
//    button only exists inside WhatsApp, so a tap can only mean
//    "save what I'm looking at". v1.4.1: WHICH file is "what I'm
//    looking at" is decided by session targeting (snapshot the cache
//    on viewer entry, save what appeared/changed since) — "newest by
//    mtime" saved the previous status in the field. v1.5: the tap
//    gained ONE gate back — not the v1.3 per-event matcher, but a
//    session fact: the viewer must have been ENTERED during this
//    WhatsApp stint, else the tap answers "Open a status first"
//    (the v1.4.1 film shows a chat tap, with no status viewed,
//    saving an 11-minute-old cache file). See onButtonTap.
//  - optional auto-save: if the user switched it on, entering the
//    status viewer saves that status by itself (debounced,
//    duplicate-guarded, session-targeted since v1.4.1, and — v1.4 —
//    armed only by a LIVE transition witnessed in this binding
//    session, never by restored state).
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

        // Hide grace (v1.4, BUG 4): a NON-WhatsApp foreground must
        // persist this long before the button is hidden. Window events
        // during WhatsApp-internal transitions (status swipes, dialogs,
        // split-second system windows) used to flip the foreground
        // package back and forth and the button visibly flickered —
        // present on one status, gone on the next, back again. Showing
        // stays instant; only hiding waits for the evidence to settle.
        // v1.5: 600 → 1200 ms. The v1.4.1 field film + Diagnostics
        // show FOREIGN windows (e.g. the screen recorder's own vivo
        // overlay) stealing foreground events for longer than 600 ms
        // mid-viewing — the button must ride those out too.
        // Trade-off, stated honestly: after the user really leaves
        // WhatsApp, the button may now linger ~1.2 s over the next
        // app before it comes down.
        private const val HIDE_GRACE_MS = 1200L

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

    // Auto-save session guard (v1.4, BUG 3). FALSE at every (re)bind.
    // It becomes TRUE only when this binding session has seen a LIVE
    // window event for a WhatsApp screen that is NOT the status viewer
    // (chat list, a chat, …). Rationale: state restored in
    // onServiceConnected is REPLAYED evidence, not a live transition —
    // if the process died while the user sat in a status and the system
    // rebinds us mid-status, the first live status event must NOT read
    // as "the user just opened a status" and save by itself. Only once
    // the watcher has genuinely watched the user somewhere else in
    // WhatsApp first may a later entry into the viewer arm auto-save.
    private var autoSaveArmedThisSession = false

    // Tap session gate (v1.5). TRUE once the watcher has seen a LIVE
    // entry into the status viewer during the CURRENT WhatsApp
    // foreground stint; reset to FALSE the moment whatsappOnScreen
    // flips true→false (the user left WhatsApp — the next stint
    // starts unproven), and at every (re)bind. onButtonTap consults
    // it: without a viewed status this stint, there is nothing the
    // tap could honestly mean, so it answers "Open a status first"
    // instead of saving whatever stale file the targeting fallback
    // would pick. It is deliberately NOT the v1.3 matcher gate: that
    // one judged the LAST event's class name and refused real
    // statuses; this one only remembers that a viewer was entered at
    // some point in the stint, so a tap in a chat right AFTER
    // viewing still saves the viewed status.
    private var statusViewedThisStint = false

    // The watcher's one reusable toast (v1.5). System toasts QUEUE:
    // in the v1.4.1 film, "Saved ✓" toasts from earlier saves kept
    // popping up during later statuses, reading as ghost saves that
    // nobody triggered. Every watcher toast now cancels the previous
    // one first, so the feedback on screen is always the LATEST
    // event's — never a stale queue draining.
    private var currentToast: Toast? = null

    // ---- The button (owned by this service since v1.3) ----
    private var windowManager: WindowManager? = null
    private var button: ImageView? = null
    private var buttonAttached = false

    // A scheduled hide, inside its grace window (v1.4). Cancelled the
    // moment WhatsApp is the foreground again.
    private var pendingHide: Runnable? = null

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

        // A live WhatsApp screen that is NOT the status viewer is what
        // arms auto-save for this binding session (v1.4 guard — see
        // autoSaveArmedThisSession). Live events only: nothing restored
        // from Prefs can set this.
        if (!matched && pkg in WHATSAPP_PACKAGES) {
            autoSaveArmedThisSession = true
        }

        // Auto-save reacts to TRANSITIONS into the status viewer only:
        // arm the debounce when a status starts being viewed, cancel
        // it the moment the viewer is left. armAutoSave additionally
        // refuses while this session hasn't been armed by a live
        // non-status event (restored state replays).
        val wasStatus = statusOnScreen
        val wasWhatsApp = whatsappOnScreen
        statusOnScreen = matched
        whatsappOnScreen = pkg in WHATSAPP_PACKAGES
        // v1.5 tap gate bookkeeping: leaving WhatsApp ends the stint
        // — the next stint must re-earn the tap with a fresh viewer
        // entry. (Entering the viewer sets the flag below.)
        if (wasWhatsApp && !whatsappOnScreen) {
            statusViewedThisStint = false
        }
        if (matched && !wasStatus) {
            // v1.4.1: a NEW status-viewing session starts here. Take
            // the session snapshot FIRST (the baseline the save
            // targeting diffs against), then arm auto-save — its
            // debounce fires long after the snapshot lands.
            // v1.5: this live entry also opens the tap gate for the
            // rest of this WhatsApp stint.
            statusViewedThisStint = true
            takeSessionSnapshot()
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
        // New binding session: auto-save starts DISARMED (v1.4). The
        // state restored just below is for the BUTTON's visibility —
        // it must never arm or fire an auto-save (BUG 3: "some statuses
        // download by themselves" after the watcher came back).
        autoSaveArmedThisSession = false
        // v1.5: a (re)bind starts a new stint for the tap gate too —
        // restored state can never open it; only a live viewer entry
        // witnessed by THIS binding can.
        statusViewedThisStint = false
        Log.i(TAG, "Status watcher connected (watcher owns the button)")
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
            if (last != null &&
                System.currentTimeMillis() - last.timeMs < FRESH_MS
            ) {
                whatsappOnScreen = last.pkg in WHATSAPP_PACKAGES
                statusOnScreen = last.matched
            } else {
                whatsappOnScreen = false
                statusOnScreen = false
            }
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
        autoSaveArmedThisSession = false
        statusViewedThisStint = false
        cancelAutoSave()
        cancelPendingHide()
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
        autoSaveArmedThisSession = false
        statusViewedThisStint = false
        cancelAutoSave()
        cancelPendingHide()
        detachButton()
        super.onDestroy()
    }

    // ---- The floating button ----

    // The rule for the button's existence (final spec — visibility is
    // PACKAGE-level):
    //   visible  ⇔  user switched it ON (persisted choice)
    //               AND WhatsApp / WhatsApp Business is in the foreground.
    // updateButton() re-derives this on EVERY window event, on
    // (re)connect, and whenever MainActivity flips the choice (via
    // reevaluate()). Whether a STATUS is on screen never gates
    // visibility; it drives auto-save — and, since v1.5, whether a
    // viewer entry has opened the tap gate for this stint (the tap
    // itself is still not gated per-screen: see onButtonTap).
    //
    // v1.4 hysteresis (BUG 4 — the flicker): SHOWING applies the rule
    // instantly and idempotently (attachButton self-verifies: a view
    // that should be up but isn't gets re-attached; a view already up
    // is left strictly alone — no detach+reattach churn). HIDING is
    // debounced: a non-WhatsApp foreground must persist for
    // HIDE_GRACE_MS before the button comes down, so the split-second
    // foreign windows WhatsApp throws up mid-transition (and swiping
    // between statuses) can never blink the button. The one exception
    // is the user's own OFF switch: that hides immediately.
    private fun updateButton() {
        try {
            val enabled = Prefs.isButtonEnabled(this)
            when {
                enabled && whatsappOnScreen -> {
                    cancelPendingHide()
                    attachButton()
                }
                enabled -> scheduleHide()
                else -> {
                    cancelPendingHide()
                    detachButton()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateButton failed", e)
        }
    }

    // Start the hide grace clock (once). The runnable re-derives at
    // fire time — if WhatsApp came back it was cancelled before this
    // could run; if the foreground is STILL not WhatsApp after the
    // grace, the hide is real and applies.
    private fun scheduleHide() {
        if (pendingHide != null) return
        val task = Runnable {
            pendingHide = null
            try {
                if (!whatsappOnScreen) detachButton()
            } catch (e: Exception) {
                Log.e(TAG, "Delayed hide failed", e)
            }
        }
        pendingHide = task
        handler.postDelayed(task, HIDE_GRACE_MS)
    }

    private fun cancelPendingHide() {
        pendingHide?.let { handler.removeCallbacks(it) }
        pendingHide = null
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
            // Decision log (v1.4): applied changes ONLY — this line
            // must mean the window really went up, so the Diagnostics
            // "Button visible now" row and the log can never disagree
            // with the screen.
            Log.i(TAG, "Button decision: SHOW (attached)")
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
        var removed = false
        if (view != null && view.parent != null) {
            try {
                windowManager?.removeView(view)
                removed = true
            } catch (e: Exception) {
                // Already gone — nothing to do.
                Log.w(TAG, "Button removeView failed (already detached?)", e)
            }
        }
        buttonAttached = false
        isButtonVisible = false
        // Decision log (v1.4): only when a window really came down.
        if (removed) Log.i(TAG, "Button decision: HIDE (detached)")
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

    // ---- Tap action: SESSION-GATED MANUAL TAP (v1.5) ----
    // History: v1.3's "smart tap" refused to save unless the status-
    // class matcher said MATCH for the LAST event — and when
    // WhatsApp's build named its viewer in a way the matcher missed
    // (or the last event was a transient window), a tap in a REAL
    // status got "Open a status first" and saved nothing. v1.4
    // therefore removed the gate entirely: a tap anywhere in
    // WhatsApp saved the newest status file, every time.
    //
    // v1.4.1's film shows the cost of NO gate: Fahad taps the button
    // while sitting in a CHAT — no status viewed — and the app saves
    // an image WhatsApp had cached 11 minutes earlier. A tap with no
    // viewing behind it has no honest target.
    //
    // v1.5's gate is a SESSION fact, not a per-event verdict: the
    // watcher must have ENTERED the status viewer at least once
    // during the current WhatsApp foreground stint
    // (statusViewedThisStint, set on the live entry transition,
    // cleared when WhatsApp leaves the foreground and at every
    // rebind). With the gate open, the tap saves via session
    // targeting exactly as v1.4.1 — including from a chat right after
    // viewing, where the viewed status IS the target. With it shut,
    // the tap saves nothing and answers "Open a status first". Real
    // statuses can no longer be refused the way v1.3 refused them:
    // the gate opens on entry and stays open for the whole stint,
    // whatever later window events say.
    //
    // The non-WhatsApp branch is defensive only (the button shouldn't
    // be up at all then): it keeps the friendly explanation rather
    // than saving blindly. A pending hide counts as "still WhatsApp":
    // it means a foreign window flipped the foreground within the
    // hide grace (~1.2 s) mid-transition while the button stayed up
    // (BUG 4's grace) — a tap in that window is a WhatsApp tap.
    private fun onButtonTap() {
        if (!(whatsappOnScreen || pendingHide != null)) {
            toastOnUi(getString(R.string.open_status_first))
            return
        }
        if (!statusViewedThisStint) {
            toastOnUi(getString(R.string.open_status_first))
            return
        }
        saveCurrentStatus()
    }

    // ---- Session targeting (v1.4.1) ----
    // Which file is the user actually looking at? Pre-v1.4.1 the save
    // took "the newest file in .Statuses" — and that is NOT a promise
    // WhatsApp keeps. It writes statuses into the cache on its own
    // schedule (pre-caching while the status list is scrolled,
    // finishing downloads late), so the newest file was sometimes the
    // PREVIOUS status. Fahad's screen recording shows it plainly: he
    // taps on a gem status and the app saves the truck video from the
    // status before it — the toast even names the same wrong file
    // twice.
    //
    // The fix is a baseline + diff. Every time the watcher ENTERS the
    // status viewer (a live transition into the matched state — the
    // same transition that arms auto-save), it snapshots the whole
    // cache into Prefs: filename → (size, mtime). A watcher save then
    // diffs the live cache against that snapshot:
    //   - files that are NEW since the snapshot, or whose size/mtime
    //     CHANGED, are the candidates — what WhatsApp fetched or
    //     finished for THIS viewing;
    //   - exactly one candidate → save it;
    //   - several → the latest-mtime candidate (WhatsApp fetched the
    //     current status most recently; anything else it touched
    //     during the same viewing was pre-fetch, written earlier);
    //   - none — re-viewing a fully cached status, WhatsApp wrote
    //     nothing → fall back to newest overall (the old heuristic),
    //     and the age-disclosure toast below names the file so the
    //     user can SEE which one it was.
    // Only watcher saves (button tap + auto-save) use this; the in-app
    // grid's per-item Save buttons already name their file explicitly.

    // Called on the status-entry transition. Runs the directory scan
    // on a background thread: the event handler must stay light, and
    // the snapshot has the whole viewing session to land before any
    // save can diff against it.
    private fun takeSessionSnapshot() {
        Thread {
            try {
                val snap = StatusScanner.snapshot()
                Prefs.saveSessionSnapshot(this, snap, System.currentTimeMillis())
                Log.i(TAG, "Session snapshot: ${snap.size} cached statuses")
            } catch (e: Exception) {
                Log.e(TAG, "Session snapshot failed", e)
            }
        }.start()
    }

    // The watcher's save target right now, per the rules above. Null
    // only when the cache holds no status files at all.
    private fun resolveSessionTarget(): StatusScanner.StatusFile? {
        val snapshot = try {
            Prefs.sessionSnapshot(this)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read session snapshot", e)
            null
        }
        if (snapshot != null) {
            val changed = StatusScanner.changedSince(snapshot)
            if (changed.isNotEmpty()) {
                Log.i(
                    TAG,
                    "Session target: ${changed.first().file.name} " +
                        "(${changed.size} changed since entry)"
                )
                return changed.first()
            }
        }
        return StatusScanner.newest()
    }

    // ---- Save the status being viewed ----
    // The save itself is the same verified SaveHelper path since
    // v1.1/v1.2 (stability wait + byte-count + read-back verification,
    // honest toasts). Runs on a background thread, and every failure
    // is caught + logged — v1 taught us that an uncaught exception
    // here kills the whole process (and, now, the watcher itself).

    private fun saveCurrentStatus() {
        Thread {
            try {
                val target = resolveSessionTarget()
                if (target == null) {
                    toastOnUi(getString(R.string.no_statuses))
                    return@Thread
                }
                val result = SaveHelper.saveToGallery(this, target.file, target.isVideo)
                // v1.5: SaveResult says WHICH honest answer applies —
                // a repeat tap on an already-saved status is not a
                // failure and must not claim a fresh save either.
                val msg = when (result) {
                    is SaveHelper.SaveResult.Failed ->
                        getString(R.string.save_failed)
                    SaveHelper.SaveResult.AlreadySaved ->
                        getString(R.string.already_saved)
                    SaveHelper.SaveResult.Saved -> {
                        // Sanity check: if the saved file is much
                        // older than "now", the user probably isn't
                        // looking at it — still save it, but say
                        // which file it was.
                        val ageMin = TimeUnit.MILLISECONDS.toMinutes(
                            System.currentTimeMillis() - target.file.lastModified()
                        )
                        if (ageMin <= 2) {
                            getString(R.string.saved_gallery)
                        } else {
                            getString(R.string.saved_which_file, target.file.name, ageMin)
                        }
                    }
                }
                toastOnUi(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Save from floating button failed", e)
                toastOnUi(getString(R.string.save_failed))
            }
        }.start()
    }

    // v1.5 toast discipline: ONE reusable toast. Cancel whatever is
    // showing before showing the new message — system toasts queue
    // by default, and a queued "Saved ✓" surfacing a minute later,
    // during a different status, reads exactly like an unrequested
    // ghost save. Cancelling first keeps at most one watcher toast
    // alive, and it is always the most recent event's.
    private fun toastOnUi(msg: String) {
        handler.post {
            try {
                currentToast?.cancel()
                val toast = Toast.makeText(this, msg, Toast.LENGTH_LONG)
                currentToast = toast
                toast.show()
            } catch (e: Exception) {
                Log.e(TAG, "Toast failed", e)
            }
        }
    }

    // ---- Auto-save (final spec, default OFF; session guard v1.4) ----
    // When the user turns on "Auto-save every status I view", entering
    // the status viewer arms a short timer; if the user is still (or
    // again) viewing a status when it fires, the session-targeted
    // status saves itself — no tap. Duplicate guards (two layers):
    // SaveHelper records every successful save's identity (name +
    // length) in Prefs; auto-save cheaply skips its target if it IS
    // the most recent record, and — since v1.5 — SaveHelper itself
    // refuses to copy ANY identity already in the persistent saved
    // set (returning AlreadySaved), so re-viewing a status never
    // saves it twice even across paths. Attempts are recorded by
    // SaveHelper with reason "auto".
    //
    // v1.4 session guard: arming ALSO requires
    // autoSaveArmedThisSession — proof that this binding session has
    // already seen the user live on a non-status WhatsApp screen.
    // Without it, a watcher rebound mid-status (process death, reboot)
    // replayed its restored state as a fresh "entry" and statuses
    // appeared to download by themselves.

    private fun armAutoSave() {
        cancelAutoSave()
        if (!autoSaveArmedThisSession) return
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
                // v1.4.1: auto-save targets the SESSION file too (see
                // resolveSessionTarget) — the status that brought us
                // here, not whatever happens to be newest in the cache.
                val target = resolveSessionTarget() ?: return@Thread
                val already = Prefs.lastSavedFile(this)
                if (already != null &&
                    already.first == target.file.name &&
                    already.second == target.file.length()
                ) {
                    Log.i(TAG, "Auto-save skipped (already saved): ${target.file.name}")
                    return@Thread
                }
                val result = SaveHelper.saveToGallery(
                    this, target.file, target.isVideo, viaAuto = true
                )
                // Brief confirmation is fine for an actual save — and
                // since v1.4 it SAYS it was automatic ("Auto-saved ✓"),
                // so an automatic save is never mistaken for something
                // the user tapped (or for the app acting on its own
                // without a rule). A failure — and, since v1.5, an
                // already-saved skip — stays silent here (no toast
                // spam while the user is just browsing) but IS
                // recorded — Diagnostics' Last save attempt row
                // shows it.
                if (result is SaveHelper.SaveResult.Saved) {
                    toastOnUi(getString(R.string.autosaved_gallery))
                }
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
