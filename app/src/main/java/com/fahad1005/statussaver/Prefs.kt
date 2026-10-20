package com.fahad1005.statussaver

import android.content.Context

// Prefs (v1.2): the app's tiny notebook, stored in SharedPreferences.
//
// Two jobs:
// 1) Remember whether the user wants the floating button ON. A service
//    can be killed and restarted by Android; this notebook survives, so
//    the restarted service still knows the user's choice.
// 2) Remember the LAST foreground screen the accessibility watcher saw
//    (package + class name + did the status detector match + when).
//    This is the evidence the Diagnostics card on the home screen shows,
//    and FloatingService reads it back when it (re)starts so a restart
//    can restore the correct button visibility instead of guessing.
//
// Everything here is plain local storage: nothing leaves the phone.
object Prefs {

    private const val FILE = "statussaver_state"

    private const val KEY_BUTTON_ENABLED = "button_enabled"
    private const val KEY_LAST_PKG = "last_pkg"
    private const val KEY_LAST_CLS = "last_cls"
    private const val KEY_LAST_MATCHED = "last_matched"
    private const val KEY_LAST_TIME = "last_time"

    // One observed foreground window, as recorded by the watcher.
    data class LastScreen(
        val pkg: String,
        val cls: String,
        val matched: Boolean,
        val timeMs: Long
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
}
