package com.fahad1005.statussaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit

// FloatingService: the app's differentiator — a download button that saves
// the WhatsApp status you are watching, without leaving WhatsApp.
//
// v1.1 changes (from real-phone testing):
//  - The button now appears ONLY while a WhatsApp status is playing.
//    StatusWatcherService (accessibility) watches the foreground window
//    and calls onStatusViewingChanged() to show/hide it. It never floats
//    over other apps any more.
//  - Fixed look: a 56dp WhatsApp-green circle, pinned bottom-right above
//    WhatsApp's reply bar. No dragging (v1's long-press drag is gone).
//  - Crash-proofing: v1's save ran on a bare Thread with no try/catch —
//    one exception there crashed the whole process and the button
//    "disappeared". Every entry point is now guarded and logged, and the
//    service is START_STICKY so Android restarts it if it is ever killed.
class FloatingService : Service() {

    companion object {
        const val ACTION_STOP = "com.fahad1005.statussaver.ACTION_STOP"

        private const val TAG = "FloatingService"
        private const val CHANNEL_ID = "statussaver_running"
        private const val NOTIF_ID = 1

        // Lets the main screen show "Show/Hide floating button" correctly.
        @Volatile
        var isRunning = false
            private set

        // The live service instance (same process), so StatusWatcherService
        // can flip button visibility directly — no broadcasts, no intents,
        // and no background-service-start restrictions to trip over.
        @Volatile
        private var instance: FloatingService? = null

        // Called by StatusWatcherService when the foreground window changes.
        // Safe to call any time: if the service isn't running, it's a no-op.
        fun onStatusViewingChanged(viewing: Boolean) {
            val svc = instance ?: return
            svc.handler.post { svc.setButtonVisible(viewing) }
        }
    }

    private var windowManager: WindowManager? = null
    private var button: ImageView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            createChannel()
            // Foreground services must show a notification within seconds.
            startForeground(NOTIF_ID, buildNotification())
            createFloatingButton()
            isRunning = true
        } catch (e: Exception) {
            // Never let a startup failure kill the process silently.
            Log.e(TAG, "onCreate failed", e)
            isRunning = false
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Tapping the notification stops the service (and the button).
            if (intent?.action == ACTION_STOP) {
                stopSelf()
                return START_NOT_STICKY
            }
            // Restarted by Android (START_STICKY) or started again by the
            // app: make sure the foreground state and the button exist.
            if (isRunning) {
                startForeground(NOTIF_ID, buildNotification())
                if (button == null) createFloatingButton()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed", e)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            removeFloatingButton()
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy cleanup failed", e)
        }
        isRunning = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Notification ("StatusSaver is running — tap to hide button") ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "StatusSaver running",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    // ---- The floating button itself ----

    private fun createFloatingButton() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing — not creating button")
            stopSelf()
            return
        }
        if (button != null) return
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val density = resources.displayMetrics.density
            val size = (56 * density).toInt()
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                // Fixed spot: bottom-right, lifted ~110dp so it clears
                // WhatsApp's reply bar on the status screen.
                x = (16 * density).toInt()
                y = (110 * density).toInt()
            }

            button = ImageView(this).apply {
                setImageResource(R.drawable.ic_download)
                setBackgroundResource(R.drawable.floating_bg)
                val pad = (14 * density).toInt()
                setPadding(pad, pad, pad, pad)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = 6 * density
                }
                contentDescription = getString(R.string.floating_button_desc)
                // Starts hidden — StatusWatcherService shows it only while
                // a WhatsApp status is on screen.
                visibility = View.GONE
                setOnClickListener { saveNewestStatus() }
            }
            windowManager?.addView(button, params)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create floating button", e)
            button = null
        }
    }

    private fun setButtonVisible(visible: Boolean) {
        try {
            val b = button ?: return
            b.visibility = if (visible) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Could not change button visibility", e)
        }
    }

    private fun removeFloatingButton() {
        try {
            if (button != null) windowManager?.removeView(button)
        } catch (e: Exception) {
            // Already gone — nothing to do.
        }
        button = null
        windowManager = null
    }

    // ---- Tap action: save the newest status ----

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
                // v1 crashed the whole process from here (uncaught exception
                // on a background thread) — which is exactly how the button
                // "disappeared" during testing. Log it, toast honestly,
                // and keep the service alive.
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
}
