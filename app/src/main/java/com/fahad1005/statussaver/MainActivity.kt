package com.fahad1005.statussaver

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

// MainActivity: the app's home screen.
// 1) First-run permission cards (overlay → files → notifications), in order.
// 2) A toggle to show/hide the floating download button.
// 3) A grid of detected statuses, each with its own Save button.
// 4) A banner ad at the bottom (test ads until you add your own AdMob IDs).
class MainActivity : AppCompatActivity() {

    private lateinit var cardOverlay: LinearLayout
    private lateinit var cardFiles: LinearLayout
    private lateinit var cardNotifications: LinearLayout
    private lateinit var btnToggle: Button
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adView: AdView

    private val statuses = mutableListOf<StatusScanner.StatusFile>()
    private lateinit var adapter: StatusAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cardOverlay = findViewById(R.id.cardOverlay)
        cardFiles = findViewById(R.id.cardFiles)
        cardNotifications = findViewById(R.id.cardNotifications)
        btnToggle = findViewById(R.id.btnToggle)
        recycler = findViewById(R.id.recycler)
        emptyText = findViewById(R.id.emptyText)
        adView = findViewById(R.id.adView)

        // Test ads for now — replace with your real AdMob IDs before release (see README).
        MobileAds.initialize(this) {}
        adView.loadAd(AdRequest.Builder().build())

        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            PermissionHelper.requestOverlayPermission(this)
        }
        findViewById<Button>(R.id.btnGrantFiles).setOnClickListener {
            PermissionHelper.requestFileAccess(this)
        }
        findViewById<Button>(R.id.btnGrantNotifications).setOnClickListener {
            PermissionHelper.requestNotificationPermission(this)
        }
        btnToggle.setOnClickListener { toggleFloatingService() }

        adapter = StatusAdapter()
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter
    }

    // Permissions can change while we're away (user flips a toggle in
    // Settings), so re-check every time the screen comes back.
    override fun onResume() {
        super.onResume()
        refreshPermissionCards()
        updateToggleLabel()
        loadStatuses()
    }

    private fun refreshPermissionCards() {
        cardOverlay.visibility =
            if (PermissionHelper.hasOverlayPermission(this)) View.GONE else View.VISIBLE
        cardFiles.visibility =
            if (PermissionHelper.hasFileAccess(this)) View.GONE else View.VISIBLE
        cardNotifications.visibility =
            if (PermissionHelper.hasNotificationPermission(this)) View.GONE else View.VISIBLE
    }

    private fun toggleFloatingService() {
        val intent = Intent(this, FloatingService::class.java)
        if (FloatingService.isRunning) {
            stopService(intent)
        } else {
            if (!PermissionHelper.hasOverlayPermission(this) ||
                !PermissionHelper.hasFileAccess(this)
            ) {
                Toast.makeText(
                    this, getString(R.string.grant_first), Toast.LENGTH_SHORT
                ).show()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        updateToggleLabel()
    }

    private fun updateToggleLabel() {
        btnToggle.text = if (FloatingService.isRunning) {
            getString(R.string.hide_button)
        } else {
            getString(R.string.show_button)
        }
    }

    // Scan on a background thread — never block the UI with file I/O.
    private fun loadStatuses() {
        if (!PermissionHelper.hasFileAccess(this)) {
            statuses.clear()
            adapter.notifyDataSetChanged()
            emptyText.visibility = View.VISIBLE
            emptyText.text = getString(R.string.grant_files_first)
            return
        }
        Thread {
            val found = StatusScanner.scan()
            runOnUiThread {
                statuses.clear()
                statuses.addAll(found)
                adapter.notifyDataSetChanged()
                emptyText.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    // The grid: thumbnail (+ "VIDEO" badge for videos) + Save button per status.
    private inner class StatusAdapter : RecyclerView.Adapter<StatusAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val badge: TextView = v.findViewById(R.id.badge)
            val btnSave: Button = v.findViewById(R.id.btnSave)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_status, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = statuses.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val item = statuses[pos]
            h.badge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            // Tag guards against recycled views showing a stale thumbnail.
            h.thumb.setImageBitmap(null)
            h.thumb.tag = item.file.absolutePath
            Thread {
                val bmp: Bitmap? = StatusScanner.thumbnail(item.file, item.isVideo)
                h.thumb.post {
                    if (bmp != null && h.thumb.tag == item.file.absolutePath) {
                        h.thumb.setImageBitmap(bmp)
                    }
                }
            }.start()
            h.btnSave.setOnClickListener {
                Thread {
                    val ok = SaveHelper.saveToGallery(this@MainActivity, item.file)
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            if (ok) getString(R.string.saved_gallery)
                            else getString(R.string.save_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.start()
            }
        }
    }
}
