package com.fahad1005.statussaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// BootReceiver (v1.4): a silent warm-up after the phone restarts.
//
// The real post-reboot mechanism is Android's own: an accessibility
// service the user has switched on is re-bound by the SYSTEM after
// boot, and StatusWatcherService.onServiceConnected restores the
// button from Prefs. This receiver adds nothing user-visible on top
// of that — deliberately: NO notification, NO activity, NO service
// start (background starts are restricted on modern Android anyway).
// It simply runs in the app process when BOOT_COMPLETED arrives and
// reads back the persisted state, so the process and the preferences
// are warm and consistent, and nudges the watcher to re-derive the
// button if it happens to be bound already.
//
// Part of the v1.4 fix for "every time I have to start and allow it":
// after a reboot, opening WhatsApp directly — without opening
// StatusSaver first — must be enough.
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            // Touch every piece of persisted state the button depends
            // on. The values are logged, not acted on: the watcher
            // re-reads them itself when the system binds it.
            val setupComplete = Prefs.isSetupComplete(context)
            val buttonEnabled = Prefs.isButtonEnabled(context)
            val autoSave = Prefs.isAutoSave(context)
            Log.i(
                TAG,
                "Boot warm-up: setupComplete=$setupComplete " +
                    "buttonEnabled=$buttonEnabled autoSave=$autoSave"
            )
            // If the watcher is already bound (same process), let it
            // re-derive the button from the same records right away.
            // A no-op when it isn't — the system binds it in its own
            // time, and its onServiceConnected does the same job.
            StatusWatcherService.reevaluate()
        } catch (e: Exception) {
            // A warm-up must never crash the boot path.
            Log.e(TAG, "Boot warm-up failed", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
