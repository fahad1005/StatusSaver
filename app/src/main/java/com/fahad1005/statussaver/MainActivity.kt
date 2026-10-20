package com.fahad1005.statussaver

import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// MainActivity: the app's home screen.
// 1) A one-time Allow WIZARD (final spec): three steps, one big Allow
//    button each, every button deep-links to the exact system screen —
//    1) Accessibility (the button engine), 2) File access, 3) Battery
//    (recommended, not a gate). Steps re-check themselves every time
//    the screen returns; finished steps collapse to a slim ✓ row, and
//    once steps 1+2 are done the whole wizard becomes one row:
//    "You're all set ✓". If a required permission is later switched
//    off, only the missing step comes back, as a repair card.
// 2) A toggle that flips the persisted button on/off choice and nudges
//    the watcher to apply it. Locked until wizard steps 1+2 are done —
//    it scrolls to the wizard instead of pretending.
// 3) An auto-save switch ("Auto-save every status I view", default OFF).
// 4) A Diagnostics card: live readouts that prove which link in the
//    chain (watcher → button → last save) is working, plus a Copy
//    button so the whole panel can be shared.
// 5) Tabs — Recent statuses (WhatsApp's cache, each with a Save
//    button) and My Saved (what's already in the gallery's StatusSaver
//    albums, with View / Share / Delete).
// 6) A banner ad at the bottom (test ads until you add your own IDs).
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // Wizard views
    private lateinit var scrollView: ScrollView
    private lateinit var wizardContainer: LinearLayout
    private lateinit var wizardTitle: TextView
    private lateinit var wizardAllSet: TextView
    private lateinit var stepAccessibility: LinearLayout
    private lateinit var stepAccessibilityDone: TextView
    private lateinit var stepFiles: LinearLayout
    private lateinit var stepFilesDone: TextView
    private lateinit var stepBattery: LinearLayout
    private lateinit var stepBatteryDone: TextView

    private lateinit var btnToggle: Button
    private lateinit var switchAutoSave: SwitchCompat
    private lateinit var tabRecent: Button
    private lateinit var tabSaved: Button
    private lateinit var statusesHeader: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adView: AdView
    // Diagnostics card rows (one value per row, monospace in the layout)
    private lateinit var diagOverlay: TextView
    private lateinit var diagAccessibility: TextView
    private lateinit var diagWatcher: TextView
    private lateinit var diagEngine: TextView
    private lateinit var diagVisible: TextView
    private lateinit var diagLastScreen: TextView
    private lateinit var diagLastSave: TextView

    private val statuses = mutableListOf<StatusScanner.StatusFile>()
    private lateinit var recentAdapter: StatusAdapter
    private val savedItems = mutableListOf<SavedItem>()
    private lateinit var savedAdapter: SavedAdapter

    private enum class Tab { RECENT, SAVED }
    private var currentTab = Tab.RECENT

    // Guards the auto-save switch listener while onResume syncs it.
    private var syncingAutoSave = false

    // One delayed re-check after returning from a Settings screen: the
    // accessibility watcher can take a moment to (re)connect, and the
    // wizard/diagnostics must not show a stale NOT CONNECTED from the
    // instant before it bound.
    private val uiHandler = Handler(Looper.getMainLooper())
    private val delayedRefresh = Runnable {
        refreshWizard()
        updateToggleLabel()
        updateDiagnostic()
    }

    // MediaStore delete consent (Android 11+ asks the user before an
    // app deletes media in some cases; Android 10 does it via
    // RecoverableSecurityException). One launcher serves both flows.
    private var pendingDeleteSender: IntentSender? = null
    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        pendingDeleteSender = null
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, getString(R.string.deleted_ok), Toast.LENGTH_SHORT).show()
            loadSaved()
        } else {
            Toast.makeText(this, getString(R.string.delete_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // One item in the phone's StatusSaver gallery albums.
    private data class SavedItem(
        val uri: Uri,
        val id: Long,
        val name: String,
        val isVideo: Boolean,
        val dateAddedSec: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scrollView = findViewById(R.id.scrollView)
        wizardContainer = findViewById(R.id.wizardContainer)
        wizardTitle = findViewById(R.id.wizardTitle)
        wizardAllSet = findViewById(R.id.wizardAllSet)
        stepAccessibility = findViewById(R.id.stepAccessibility)
        stepAccessibilityDone = findViewById(R.id.stepAccessibilityDone)
        stepFiles = findViewById(R.id.stepFiles)
        stepFilesDone = findViewById(R.id.stepFilesDone)
        stepBattery = findViewById(R.id.stepBattery)
        stepBatteryDone = findViewById(R.id.stepBatteryDone)
        btnToggle = findViewById(R.id.btnToggle)
        switchAutoSave = findViewById(R.id.switchAutoSave)
        tabRecent = findViewById(R.id.tabRecent)
        tabSaved = findViewById(R.id.tabSaved)
        statusesHeader = findViewById(R.id.statusesHeader)
        recycler = findViewById(R.id.recycler)
        emptyText = findViewById(R.id.emptyText)
        adView = findViewById(R.id.adView)
        diagOverlay = findViewById(R.id.diagOverlay)
        diagAccessibility = findViewById(R.id.diagAccessibility)
        diagWatcher = findViewById(R.id.diagWatcher)
        diagEngine = findViewById(R.id.diagEngine)
        diagVisible = findViewById(R.id.diagVisible)
        diagLastScreen = findViewById(R.id.diagLastScreen)
        diagLastSave = findViewById(R.id.diagLastSave)

        // Test ads for now — replace with your real AdMob IDs before release (see README).
        MobileAds.initialize(this) {}
        adView.loadAd(AdRequest.Builder().build())

        findViewById<Button>(R.id.btnStepAccessibility).setOnClickListener {
            PermissionHelper.requestAccessibilitySettings(this)
        }
        findViewById<Button>(R.id.btnStepFiles).setOnClickListener {
            PermissionHelper.requestFileAccess(this)
        }
        findViewById<Button>(R.id.btnStepBattery).setOnClickListener {
            PermissionHelper.requestBatteryExemption(this)
        }
        btnToggle.setOnClickListener { toggleFloatingButton() }
        findViewById<Button>(R.id.btnCopyDiagnostics).setOnClickListener {
            copyDiagnostics()
        }
        switchAutoSave.setOnCheckedChangeListener { _, isChecked ->
            if (syncingAutoSave) return@setOnCheckedChangeListener
            Prefs.setAutoSave(this, isChecked)
            Toast.makeText(
                this,
                getString(if (isChecked) R.string.autosave_on else R.string.autosave_off),
                Toast.LENGTH_SHORT
            ).show()
        }
        tabRecent.setOnClickListener { selectTab(Tab.RECENT) }
        tabSaved.setOnClickListener { selectTab(Tab.SAVED) }

        recentAdapter = StatusAdapter()
        savedAdapter = SavedAdapter()
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = recentAdapter
        tabRecent.setTypeface(null, Typeface.BOLD)
    }

    // Permissions can change while we're away (user flips a toggle in
    // Settings), so re-check every time the screen comes back.
    override fun onResume() {
        super.onResume()
        refreshWizard()
        updateToggleLabel()
        updateDiagnostic()
        syncAutoSaveSwitch()
        loadCurrentTab()
        uiHandler.removeCallbacks(delayedRefresh)
        uiHandler.postDelayed(delayedRefresh, 1200)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(delayedRefresh)
        super.onPause()
    }

    // ---- The Allow wizard ----
    // Done-checks:
    //   Step 1: the Settings list says our service is enabled AND the
    //     watcher is truly CONNECTED (the two can disagree — see the
    //     Diagnostics notes; the delayed re-check in onResume covers
    //     the bind lag right after the user flips the switch).
    //   Step 2: PermissionHelper.hasFileAccess (all-files on 11+,
    //     legacy storage permission below).
    //   Step 3: the battery-optimisation exemption. Recommended only —
    //     it never blocks the toggle or the "all set" state.
    private fun step1Done(): Boolean =
        PermissionHelper.isAccessibilityEnabled(this) &&
            StatusWatcherService.watcherConnected

    private fun refreshWizard() {
        val requiredDone = step1Done() && PermissionHelper.hasFileAccess(this)
        wizardTitle.visibility = if (requiredDone) View.GONE else View.VISIBLE
        wizardAllSet.visibility = if (requiredDone) View.VISIBLE else View.GONE
        applyStep(
            stepAccessibility, stepAccessibilityDone,
            step1Done(), requiredDone
        )
        applyStep(
            stepFiles, stepFilesDone,
            PermissionHelper.hasFileAccess(this), requiredDone
        )
        applyStep(
            stepBattery, stepBatteryDone,
            PermissionHelper.hasBatteryExemption(this), requiredDone
        )
    }

    // One step's two faces: the full card (title + text + Allow button)
    // while it's missing, a slim "✓ …" row once granted. When the whole
    // wizard is complete, both faces hide — the "You're all set" row is
    // the wizard. If a required permission is later revoked, the wizard
    // un-collapses and only the missing step's card shows (plus the
    // other steps' slim ✓ rows).
    private fun applyStep(block: View, doneRow: View, done: Boolean, collapsed: Boolean) {
        when {
            collapsed -> {
                block.visibility = View.GONE
                doneRow.visibility = View.GONE
            }
            done -> {
                block.visibility = View.GONE
                doneRow.visibility = View.VISIBLE
            }
            else -> {
                block.visibility = View.VISIBLE
                doneRow.visibility = View.GONE
            }
        }
    }

    // Point the user at the wizard: scroll it into view, flash it once,
    // and say why — instead of a toggle that silently does nothing.
    private fun pointAtWizard() {
        Toast.makeText(this, getString(R.string.setup_first), Toast.LENGTH_LONG).show()
        scrollView.post {
            scrollView.smoothScrollTo(0, wizardContainer.top)
            wizardContainer.setBackgroundColor(
                ContextCompat.getColor(this, R.color.gray_100)
            )
            wizardContainer.postDelayed({
                wizardContainer.setBackgroundColor(
                    ContextCompat.getColor(this, R.color.white)
                )
            }, 900)
        }
    }

    // v1.3: the toggle no longer starts/stops a service — there isn't
    // one. It flips the persisted choice in Prefs and nudges the
    // watcher, which owns the button and applies the choice immediately
    // (and re-applies it itself after every restart, from the same
    // Prefs record). The label reads the SAME Prefs record, so label
    // and engine state come from one source of truth — in v1.2 the
    // label (intent) and the service's running flag lived in different
    // lifecycles and could disagree forever after a process kill.
    //
    // Locked until wizard steps 1+2 are done: if the user taps early,
    // we scroll to the wizard and say so — never a silent no-op. If the
    // steps are done but the watcher hasn't connected yet (bind lag,
    // or a phone that shows the switch On without binding), we say
    // that honestly too.
    private fun toggleFloatingButton() {
        if (Prefs.isButtonEnabled(this)) {
            Prefs.setButtonEnabled(this, false)
            StatusWatcherService.reevaluate()
        } else {
            if (!step1Done() || !PermissionHelper.hasFileAccess(this)) {
                if (!PermissionHelper.isAccessibilityEnabled(this)) {
                    Toast.makeText(
                        this, getString(R.string.accessibility_first), Toast.LENGTH_SHORT
                    ).show()
                }
                pointAtWizard()
                return
            }
            if (!StatusWatcherService.watcherConnected) {
                Toast.makeText(
                    this, getString(R.string.watcher_warming), Toast.LENGTH_LONG
                ).show()
                return
            }
            Prefs.setButtonEnabled(this, true)
            StatusWatcherService.reevaluate()
        }
        updateToggleLabel()
    }

    private fun updateToggleLabel() {
        btnToggle.text = if (Prefs.isButtonEnabled(this)) {
            getString(R.string.hide_button)
        } else {
            getString(R.string.show_button)
        }
    }

    private fun syncAutoSaveSwitch() {
        syncingAutoSave = true
        switchAutoSave.isChecked = Prefs.isAutoSave(this)
        syncingAutoSave = false
    }

    // ---- Diagnostics card ----
    // One row per link in the chain, refreshed every onResume:
    //   overlay permission (legacy since v1.3 — shown for the record,
    //     the button no longer needs it)
    //   → accessibility switch (the Settings list)
    //   → watcher actually CONNECTED (bound + receiving events — a
    //     different fact from the switch, and on some phones the two
    //     disagree)
    //   → button engine: the watcher itself (ALIVE = connected)
    //   → button visible RIGHT NOW (the watcher's actual overlay state)
    //   → the last screen the watcher recorded (from Prefs, so it is
    //     still there after the app was closed and reopened), with the
    //     detector's MATCH / NO MATCH verdict
    //   → the last save attempt (from Prefs, recorded by SaveHelper):
    //     when, which file, how big, SAVED or FAILED + why; automatic
    //     saves are marked (auto).
    // Field values are computed once here and reused by the Copy button,
    // so the screenshot and the copied text can never disagree.
    private fun diagnosticsRows(): List<Pair<String, String>> {
        val overlayState = if (PermissionHelper.hasOverlayPermission(this)) "ON" else "OFF"
        val overlay = "$overlayState (legacy — not required since v1.3)"
        val accessibility =
            if (PermissionHelper.isAccessibilityEnabled(this)) "ON (in Settings)" else "OFF"
        val connected = StatusWatcherService.watcherConnected
        val watcher = if (connected) "CONNECTED" else "NOT CONNECTED"
        val engine = if (connected) "WATCHER · ALIVE" else "WATCHER · DEAD"
        val visibleNow = if (StatusWatcherService.isButtonVisible) "YES" else "NO"
        val last = Prefs.lastScreen(this)
        val lastScreen = if (last == null) {
            "— (nothing seen yet)"
        } else {
            val sec = (System.currentTimeMillis() - last.timeMs) / 1000
            val ago = if (sec < 120) "${sec}s ago" else "${sec / 60} min ago"
            "${last.pkg} / ${last.cls} " +
                (if (last.matched) "(MATCH)" else "(NO MATCH)") + " · $ago"
        }
        val save = Prefs.lastSaveAttempt(this)
        val lastSave = if (save == null) {
            "— (no saves yet)"
        } else {
            val time = SimpleDateFormat("h:mm a", Locale.US).format(Date(save.timeMs))
            val result = if (save.ok) {
                if (save.reason == "auto") "SAVED (auto)" else "SAVED"
            } else {
                val why = save.reason.take(60).ifEmpty { "unknown error" }
                "FAILED ($why)"
            }
            "$time · ${save.fileName} · ${humanSize(save.sizeBytes)} · $result"
        }
        return listOf(
            "Overlay permission" to overlay,
            "Accessibility switch" to accessibility,
            "Watcher" to watcher,
            "Button engine" to engine,
            "Button visible now" to visibleNow,
            "Last seen screen" to lastScreen,
            "Last save attempt" to lastSave
        )
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 ->
            String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 ->
            String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun updateDiagnostic() {
        val rows = diagnosticsRows()
        diagOverlay.text = "${rows[0].first}: ${rows[0].second}"
        diagAccessibility.text = "${rows[1].first}: ${rows[1].second}"
        diagWatcher.text = "${rows[2].first}: ${rows[2].second}"
        diagEngine.text = "${rows[3].first}: ${rows[3].second}"
        diagVisible.text = "${rows[4].first}: ${rows[4].second}"
        diagLastScreen.text = "${rows[5].first}: ${rows[5].second}"
        diagLastSave.text = "${rows[6].first}: ${rows[6].second}"
    }

    private fun copyDiagnostics() {
        val text = StringBuilder("StatusSaver v1.3 diagnostics\n")
        for ((label, value) in diagnosticsRows()) {
            text.append("$label: $value\n")
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("StatusSaver diagnostics", text.toString()))
        Toast.makeText(this, getString(R.string.diagnostics_copied), Toast.LENGTH_SHORT).show()
    }

    // ---- Tabs + grids ----

    private fun selectTab(tab: Tab) {
        if (tab == currentTab) return
        currentTab = tab
        tabRecent.setTypeface(
            null, if (tab == Tab.RECENT) Typeface.BOLD else Typeface.NORMAL
        )
        tabSaved.setTypeface(
            null, if (tab == Tab.SAVED) Typeface.BOLD else Typeface.NORMAL
        )
        statusesHeader.text = getString(
            if (tab == Tab.RECENT) R.string.statuses_header else R.string.saved_header
        )
        loadCurrentTab()
    }

    private fun loadCurrentTab() {
        if (currentTab == Tab.RECENT) loadStatuses() else loadSaved()
    }

    // ---- Recent statuses (WhatsApp's hidden cache) ----

    // Scan on a background thread — never block the UI with file I/O.
    private fun loadStatuses() {
        recycler.adapter = recentAdapter
        if (!PermissionHelper.hasFileAccess(this)) {
            statuses.clear()
            recentAdapter.notifyDataSetChanged()
            emptyText.visibility = View.VISIBLE
            emptyText.text = getString(R.string.grant_files_first)
            return
        }
        Thread {
            val found = StatusScanner.scan()
            runOnUiThread {
                if (currentTab != Tab.RECENT) return@runOnUiThread
                statuses.clear()
                statuses.addAll(found)
                recentAdapter.notifyDataSetChanged()
                emptyText.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
                if (found.isEmpty()) emptyText.text = getString(R.string.empty_statuses)
            }
        }.start()
    }

    // The recent grid: thumbnail (+ "VIDEO" badge for videos) + Save button.
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
            h.btnSave.visibility = View.VISIBLE
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
                    val ok = SaveHelper.saveToGallery(this@MainActivity, item.file, item.isVideo)
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

    // ---- My Saved (the gallery's StatusSaver albums) ----

    private fun loadSaved() {
        recycler.adapter = savedAdapter
        Thread {
            val found = querySaved()
            runOnUiThread {
                if (currentTab != Tab.SAVED) return@runOnUiThread
                savedItems.clear()
                savedItems.addAll(found)
                savedAdapter.notifyDataSetChanged()
                emptyText.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
                if (found.isEmpty()) emptyText.text = getString(R.string.saved_empty)
            }
        }.start()
    }

    // Everything StatusSaver has put in the gallery: images from
    // Pictures/StatusSaver, videos from Movies/StatusSaver, newest
    // first. Parameterized selection only (no string-built SQL).
    // RELATIVE_PATH exists on Android 10+; below that, SaveHelper's
    // legacy path wrote plain files, so match on the DATA path instead.
    private fun querySaved(): List<SavedItem> {
        val out = mutableListOf<SavedItem>()
        collectSaved(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isVideo = false, relPath = "Pictures/StatusSaver/", out = out
        )
        collectSaved(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isVideo = true, relPath = "Movies/StatusSaver/", out = out
        )
        return out.sortedByDescending { it.dateAddedSec }
    }

    private fun collectSaved(
        collection: Uri,
        isVideo: Boolean,
        relPath: String,
        out: MutableList<SavedItem>
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0"
            args = arrayOf(relPath)
        } else {
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            args = arrayOf("%/StatusSaver/%")
        }
        try {
            contentResolver.query(collection, projection, selection, args, null)
                ?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        out.add(
                            SavedItem(
                                uri = ContentUris.withAppendedId(collection, id),
                                id = id,
                                name = c.getString(nameCol) ?: "",
                                isVideo = isVideo,
                                dateAddedSec = c.getLong(dateCol)
                            )
                        )
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "My Saved query failed for $collection", e)
        }
    }

    // Thumbnails straight from MediaStore: loadThumbnail on Android
    // 10+, the classic Thumbnails tables below. No image library.
    private fun savedThumbnail(item: SavedItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
            } else if (!item.isVideo) {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    contentResolver, item.id,
                    MediaStore.Images.Thumbnails.MINI_KIND, null
                )
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    contentResolver, item.id,
                    MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    // The My Saved grid: same cell as Recent, but the per-item Save
    // button makes no sense here — tapping the item opens View / Share
    // / Delete instead.
    private inner class SavedAdapter : RecyclerView.Adapter<SavedAdapter.Holder>() {

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

        override fun getItemCount(): Int = savedItems.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val item = savedItems[pos]
            h.badge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            h.btnSave.visibility = View.GONE
            h.thumb.setImageBitmap(null)
            h.thumb.tag = item.uri.toString()
            Thread {
                val bmp = savedThumbnail(item)
                h.thumb.post {
                    if (bmp != null && h.thumb.tag == item.uri.toString()) {
                        h.thumb.setImageBitmap(bmp)
                    }
                }
            }.start()
            h.itemView.setOnClickListener { showSavedActions(item) }
        }
    }

    private fun showSavedActions(item: SavedItem) {
        val actions = arrayOf(
            getString(R.string.action_view),
            getString(R.string.action_share),
            getString(R.string.action_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> viewSaved(item)
                    1 -> shareSaved(item)
                    2 -> deleteSaved(item)
                }
            }
            .show()
    }

    private fun savedMime(item: SavedItem): String =
        contentResolver.getType(item.uri)
            ?: if (item.isVideo) "video/*" else "image/*"

    private fun viewSaved(item: SavedItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, savedMime(item))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.action_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareSaved(item: SavedItem) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = savedMime(item)
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.action_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // Delete: try directly first (we created these files, so on most
    // phones that's the end of it). If the system insists on the
    // user's consent — MediaStore.createDeleteRequest on Android 11+,
    // RecoverableSecurityException on Android 10 — launch that consent
    // screen and let its result speak.
    private fun deleteSaved(item: SavedItem) {
        Thread {
            var deleted = false
            var consentSender: IntentSender? = null
            try {
                deleted = contentResolver.delete(item.uri, null, null) > 0
            } catch (e: Exception) {
                Log.w(TAG, "Direct delete refused for ${item.uri}", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    e is RecoverableSecurityException
                ) {
                    consentSender = e.userAction.actionIntent.intentSender
                }
            }
            val sender = consentSender
            runOnUiThread {
                when {
                    deleted -> {
                        Toast.makeText(
                            this, getString(R.string.deleted_ok), Toast.LENGTH_SHORT
                        ).show()
                        loadSaved()
                    }
                    sender != null -> {
                        pendingDeleteSender = sender
                        deleteConsentLauncher.launch(
                            IntentSenderRequest.Builder(sender).build()
                        )
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        try {
                            val request = MediaStore.createDeleteRequest(
                                contentResolver, listOf(item.uri)
                            )
                            pendingDeleteSender = request.intentSender
                            deleteConsentLauncher.launch(
                                IntentSenderRequest.Builder(request.intentSender).build()
                            )
                        } catch (e2: Exception) {
                            Log.e(TAG, "createDeleteRequest failed", e2)
                            Toast.makeText(
                                this, getString(R.string.delete_failed), Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    else -> Toast.makeText(
                        this, getString(R.string.delete_failed), Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }
}
