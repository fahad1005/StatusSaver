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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit

// FloatingService: the app's differentiator. A small download button that
// hovers over WhatsApp. Tap it → the newest viewed status is saved to the
// gallery, without leaving WhatsApp. Long-press + drag moves the button.
// It runs as a foreground service so Android doesn't kill it.
class FloatingService : Service() {

    companion object {
        const val ACTION_STOP = "com.fahad1005.statussaver.ACTION_STOP"

        // Lets the main screen show "Show/Hide floating button" correctly.
        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "statussaver_running"
        private const val NOTIF_ID = 1
        private const val LONG_PRESS_MS = 450L
    }

    private var windowManager: WindowManager? = null
    private var button: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Foreground services must show a notification within seconds.
        startForeground(NOTIF_ID, buildNotification())
        showFloatingButton()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Tapping the notification stops the service (and removes the button).
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeFloatingButton()
        isRunning = false
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
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    // ---- The floating button itself ----

    private fun showFloatingButton() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val size = (64 * resources.displayMetrics.density).toInt()
        params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 300
        }

        button = ImageView(this).apply {
            setImageResource(android.R.drawable.stat_sys_download)
            setBackgroundResource(R.drawable.floating_bg)
            setColorFilter(0xFFFFFFFF.toInt())
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnTouchListener(FloatingTouchListener())
        }
        windowManager?.addView(button, params)
    }

    private fun removeFloatingButton() {
        try {
            windowManager?.removeView(button)
        } catch (e: Exception) {
            // Already gone — nothing to do.
        }
        button = null
        windowManager = null
    }

    // Tap = save. Long-press then drag = move the button.
    private inner class FloatingTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downTime = 0L
        private var dragging = false
        private val longPress = Runnable { dragging = true }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params?.x ?: 0
                    startY = params?.y ?: 0
                    downTime = System.currentTimeMillis()
                    dragging = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        params?.x = startX + (event.rawX - downX).toInt()
                        params?.y = startY + (event.rawY - downY).toInt()
                        windowManager?.updateViewLayout(button, params)
                    } else if (movedFar(event)) {
                        // Moved before the long-press fired: cancel the drag
                        // timer. This gesture is neither tap nor drag.
                        handler.removeCallbacks(longPress)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    val wasTap = !dragging &&
                        System.currentTimeMillis() - downTime < 600 &&
                        !movedFar(event)
                    dragging = false
                    if (wasTap && event.action == MotionEvent.ACTION_UP) {
                        saveNewestStatus()
                    }
                }
            }
            return true
        }

        private fun movedFar(event: MotionEvent): Boolean {
            val dx = (event.rawX - downX).toDouble()
            val dy = (event.rawY - downY).toDouble()
            return Math.hypot(dx, dy) > 24
        }
    }

    // ---- Tap action: save the newest status ----

    private fun saveNewestStatus() {
        Thread {
            val newest = StatusScanner.newest()
            if (newest == null) {
                toastOnUi(getString(R.string.no_statuses))
                return@Thread
            }
            val ok = SaveHelper.saveToGallery(this, newest.file)
            // Sanity check: if the newest file is much older than "now", the
            // user probably isn't looking at it — still save it, but say which.
            val ageMin =
                TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - newest.file.lastModified())
            val msg = if (!ok) {
                getString(R.string.save_failed)
            } else if (ageMin <= 2) {
                getString(R.string.saved_gallery)
            } else {
                getString(R.string.saved_which_file, newest.file.name, ageMin)
            }
            toastOnUi(msg)
        }.start()
    }

    private fun toastOnUi(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
