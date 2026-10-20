package com.fahad1005.statussaver

import android.content.Context

// Prefs (v1.2, extended in v1.3): the app's tiny notebook, stored in
// SharedPreferences.
//
// Three jobs:
// 1) Remember whether the user wants the floating button ON. The process
//    can be killed and the watcher rebound by Android; this notebook
//    survives, so the rebound watcher still knows the user's choice —
//    and the home-screen toggle reads it too, so the label (intent) and
//    the engine can never silently disagree the way they did in v1.2.
// 2) Remember the LAST foreground screen the accessibility watcher saw
//    (package + class name + did the status detector match + when).
//    This is the evidence the Diagnostics card on the home screen shows,
//    and the watcher re-reads it in onServiceConnected so a restart can
//    restore the correct button visibility instead of guessing.
// 3) v1.3: remember the LAST SAVE ATTEMPT (when, file name, size,
//    saved/failed + why). Save questions ("it said saved but it's not
//    in my gallery") become answerable from one Diagnostics screenshot.
// 4) v1.3 final spec: the AUTO-SAVE switch (default OFF) and the
//    identity (name + length) of the most recent successful save, which
//    auto-save uses as its duplicate guard — automatic saves update it
//    too (via SaveHelper), and so do manual ones, so auto-save never
//    re-saves a file the user already grabbed by hand.
// 5) v1.4: whether the one-time setup has EVER been completed (the
//    wizard's two required steps granted at least once). Once true,
//    the wizard must never present itself as a fresh setup again — a
//    sleeping watcher gets ONE slim "Button is asleep — Wake it"
//    banner instead. Also: whether the user dismissed the optional
//    autostart step (step 4), so it never nags.
//
// Everything here is plain local storage: nothing leaves the phone.
object Prefs {

    private const val FILE = "statussaver_state"

    private const val KEY_BUTTON_ENABLED = "button_enabled"
    private const val KEY_LAST_PKG = "last_pkg"
    private const val KEY_LAST_CLS = "last_cls"
    private const val KEY_LAST_MATCHED = "last_matched"
    private const val KEY_LAST_TIME = "last_time"
    private const val KEY_SAVE_TIME = "save_time"
    private const val KEY_SAVE_NAME = "save_name"
    private const val KEY_SAVE_SIZE = "save_size"
    private const val KEY_SAVE_OK = "save_ok"
    private const val KEY_SAVE_REASON = "save_reason"
    private const val KEY_AUTO_SAVE = "auto_save"
    private const val KEY_LAST_SAVED_NAME = "last_saved_name"
    private const val KEY_LAST_SAVED_LEN = "last_saved_len"
    private const val KEY_SETUP_COMPLETE = "setup_complete"
    private const val KEY_STEP4_DISMISSED = "step4_dismissed"
    private const val KEY_SESSION_SNAPSHOT = "session_snapshot"
    private const val KEY_SESSION_SNAPSHOT_TIME = "session_snapshot_time"

    // One observed foreground window, as recorded by the watcher.
    data class LastScreen(
        val pkg: String,
        val cls: String,
        val matched: Boolean,
        val timeMs: Long
    )

    // One save attempt, as recorded by SaveHelper (v1.3). reason is ""
    // on success; on failure it is the exception's message (or a short
    // plain-words explanation), so the Diagnostics card can show WHY.
    data class LastSave(
        val timeMs: Long,
        val fileName: String,
        val sizeBytes: Long,
        val ok: Boolean,
        val reason: String
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setButtonEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUTTON_ENABLED, enabled).apply()
    }

    fun isButtonEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUTTON_ENABLED, false)

    fun saveLastScreen(
        context: Context,
        pkg: String,
        cls: String,
        matched: Boolean,
        timeMs: Long
    ) {
        prefs(context).edit()
            .putString(KEY_LAST_PKG, pkg)
            .putString(KEY_LAST_CLS, cls)
            .putBoolean(KEY_LAST_MATCHED, matched)
            .putLong(KEY_LAST_TIME, timeMs)
            .apply()
    }

    // Null until the watcher has seen at least one real window.
    fun lastScreen(context: Context): LastScreen? {
        val p = prefs(context)
        val time = p.getLong(KEY_LAST_TIME, 0L)
        val pkg = p.getString(KEY_LAST_PKG, null)
        val cls = p.getString(KEY_LAST_CLS, null)
        if (time == 0L || pkg == null || cls == null) return null
        return LastScreen(pkg, cls, p.getBoolean(KEY_LAST_MATCHED, false), time)
    }

    // Called by SaveHelper on EVERY save attempt, success or failure.
    fun saveLastSaveAttempt(
        context: Context,
        timeMs: Long,
        fileName: String,
        sizeBytes: Long,
        ok: Boolean,
        reason: String
    ) {
        prefs(context).edit()
            .putLong(KEY_SAVE_TIME, timeMs)
            .putString(KEY_SAVE_NAME, fileName)
            .putLong(KEY_SAVE_SIZE, sizeBytes)
            .putBoolean(KEY_SAVE_OK, ok)
            .putString(KEY_SAVE_REASON, reason)
            .apply()
    }

    // ---- Auto-save (final spec) ----

    // The "Auto-save every status I view" switch. Default OFF: saving
    // without a tap is powerful, so it is strictly the user's choice.
    fun setAutoSave(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SAVE, enabled).apply()
    }

    fun isAutoSave(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SAVE, false)

    // Identity of the most recent SUCCESSFUL save (name + byte length),
    // written by SaveHelper after every verified save, manual or
    // automatic. Auto-save compares the newest status file against this
    // record and skips it if identical — viewing the same status twice
    // must not save it twice.
    fun saveLastSavedFile(context: Context, name: String, length: Long) {
        prefs(context).edit()
            .putString(KEY_LAST_SAVED_NAME, name)
            .putLong(KEY_LAST_SAVED_LEN, length)
            .apply()
    }

    // Null until the first successful save.
    fun lastSavedFile(context: Context): Pair<String, Long>? {
        val p = prefs(context)
        val name = p.getString(KEY_LAST_SAVED_NAME, null) ?: return null
        return name to p.getLong(KEY_LAST_SAVED_LEN, -1L)
    }

    // ---- Status-viewing session snapshot (v1.4.1) ----
    // When the watcher ENTERS the status viewer it snapshots the
    // whole .Statuses cache (StatusScanner.snapshot) and stores the
    // map here; a watcher save later diffs the live cache against
    // this baseline to find the file WhatsApp fetched for the status
    // actually being viewed (see StatusWatcherService's session
    // targeting). Persisted (not just held in memory) so the baseline
    // survives the process being killed and rebound mid-viewing.
    //
    // Serialized as lines "name|size|mtime". WhatsApp cache filenames
    // are content hashes ([0-9a-f] + .mp4/.jpg/.jpeg/.png), so '|'
    // and '\n' can never appear inside a name — the format is
    // unambiguous without escaping.
    fun saveSessionSnapshot(
        context: Context,
        snapshot: Map<String, Pair<Long, Long>>,
        timeMs: Long
    ) {
        val text = snapshot.entries.joinToString("\n") { (name, sizeMtime) ->
            "$name|${sizeMtime.first}|${sizeMtime.second}"
        }
        prefs(context).edit()
            .putString(KEY_SESSION_SNAPSHOT, text)
            .putLong(KEY_SESSION_SNAPSHOT_TIME, timeMs)
            .apply()
    }

    // The stored baseline, or null if none was ever taken (or the
    // stored text is unreadable). An EMPTY map is a valid snapshot
    // (the cache was empty at entry — every file found later is new).
    fun sessionSnapshot(context: Context): Map<String, Pair<Long, Long>>? {
        val p = prefs(context)
        if (p.getLong(KEY_SESSION_SNAPSHOT_TIME, 0L) == 0L) return null
        val text = p.getString(KEY_SESSION_SNAPSHOT, null) ?: return null
        if (text.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Pair<Long, Long>>()
        for (line in text.split("\n")) {
            val parts = line.split("|")
            if (parts.size != 3) continue
            val size = parts[1].toLongOrNull() ?: continue
            val mtime = parts[2].toLongOrNull() ?: continue
            out[parts[0]] = size to mtime
        }
        return out
    }

    // ---- Setup progress (v1.4) ----

    // TRUE once the wizard's required steps (accessibility connected +
    // file access) have been observed done at least once. MainActivity
    // sets it the first time refreshWizard sees setup complete; from
    // then on the wizard NEVER re-presents as a fresh setup — if the
    // watcher later falls asleep (OEM battery managers disconnect it
    // while the Settings switch still says On), the home screen shows
    // a single "Button is asleep — Wake it" banner instead of making
    // the user feel they must redo the whole setup.
    fun setSetupComplete(context: Context, complete: Boolean) {
        prefs(context).edit().putBoolean(KEY_SETUP_COMPLETE, complete).apply()
    }

    fun isSetupComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETUP_COMPLETE, false)

    // The optional autostart step (wizard step 4) is advisory only.
    // "Done / Skip" on it lands here so it never shows again.
    fun setWizardStep4Dismissed(context: Context, dismissed: Boolean) {
        prefs(context).edit().putBoolean(KEY_STEP4_DISMISSED, dismissed).apply()
    }

    fun isWizardStep4Dismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STEP4_DISMISSED, false)

    // Null until the first save attempt has happened.
    fun lastSaveAttempt(context: Context): LastSave? {
        val p = prefs(context)
        val time = p.getLong(KEY_SAVE_TIME, 0L)
        val name = p.getString(KEY_SAVE_NAME, null) ?: return null
        if (time == 0L) return null
        return LastSave(
            time,
            name,
            p.getLong(KEY_SAVE_SIZE, 0L),
            p.getBoolean(KEY_SAVE_OK, false),
            p.getString(KEY_SAVE_REASON, "") ?: ""
        )
    }
}
