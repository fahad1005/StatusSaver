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
