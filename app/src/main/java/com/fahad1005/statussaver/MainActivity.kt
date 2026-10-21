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
// 1) A one-time Allow WIZARD (v1.6 front-door order): Step 1 is FILE
//    ACCESS — the only permission the app itself needs; with it, the
//    statuses grid below works fully. Everything the floating button
//    needs lives in the OPTIONAL Power-up section under it —
//    Accessibility (the button engine), Battery (recommended),
//    Autostart (advisory, Done/Skip dismisses it forever) — each with
//    one big Allow button deep-linking to the exact system screen.
//    "Skip for now" on the power-up persists in Prefs: the section
//    never nags again (the button toggle below brings it back).
//    Steps re-check themselves every time the screen returns;
//    finished steps collapse to a slim ✓ row, and once everything
//    presented is resolved the wizard becomes one row: "You're all
//    set ✓". The core setup latches in Prefs (file access seen done
//    once) — after that the wizard never re-presents as fresh setup:
//    a sleeping watcher gets one slim "Button is asleep — Wake it"
//    banner; a revoked files permission gets its repair step; that's
//    all. And none of it ever blocks the screen: the statuses grid
//    loads immediately, every time.
// 2) A toggle that flips the persisted button on/off choice and nudges
//    the watcher to apply it. Locked until file access + the power-up
//    are done — it scrolls to the wizard instead of pretending.
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
    private lateinit var asleepBanner: LinearLayout
    private lateinit var stepAccessibility: LinearLayout
    private lateinit var stepAccessibilityDone: TextView
    private lateinit var stepFiles: LinearLayout
    private lateinit var stepFilesDone: TextView
    private lateinit var stepBattery: LinearLayout
    private lateinit var stepBatteryDone: TextView
    private lateinit var stepAutostart: LinearLayout
    // Power-up group header (v1.6): heading + intro + Skip. The three
    // step blocks above (accessibility/battery/autostart) belong to
    // the group too; refreshWizard shows/hides them as a unit.
    private lateinit var powerupTitle: TextView
    private lateinit var powerupText: TextView
    private lateinit var btnSkipPowerup: Button

    // TRUE while the user is actively setting up the power-up after
    // asking for the button via the toggle (v1.6). Once the CORE
    // setup has latched, the power-up group stays out of the way
    // unless it was granted, skipped-then-reopened this way, or the
    // core setup is still incomplete. Activity-scoped on purpose: if
    // the process dies mid-flow, the group simply hides again — the
    // payoff state (power-up granted) is re-derived live anyway.
    private var powerUpExpanded = false

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
    private lateinit var diagAutoSave: TextView

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
    // v1.5: the item a consent delete is acting on, so a confirmed
    // deletion can also forget its dedupe identity (Prefs) — deleted
    // means saveable again.
    private var pendingDeleteItem: SavedItem? = null
    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        pendingDeleteSender = null
        val deletedItem = pendingDeleteItem
        pendingDeleteItem = null
        if (result.resultCode == RESULT_OK) {
            if (deletedItem != null) {
                Prefs.forgetSavedIdentity(this, deletedItem.name, deletedItem.sizeBytes)
            }
            Toast.makeText(this, getString(R.string.deleted_ok), Toast.LENGTH_SHORT).show()
            loadSaved()
        } else {
            Toast.makeText(this, getString(R.string.delete_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // One item in the phone's StatusSaver gallery albums.
    // v1.5: carries the byte size too — (name, size) is the dedupe
    // identity SaveHelper records, and deleting the item must forget
    // that identity so the status can be saved again.
    private data class SavedItem(
        val uri: Uri,
        val id: Long,
        val name: String,
        val isVideo: Boolean,
        val dateAddedSec: Long,
        val sizeBytes: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scrollView = findViewById(R.id.scrollView)
        wizardContainer = findViewById(R.id.wizardContainer)
        wizardTitle = findViewById(R.id.wizardTitle)
        wizardAllSet = findViewById(R.id.wizardAllSet)
        asleepBanner = findViewById(R.id.asleepBanner)
        stepAccessibility = findViewById(R.id.stepAccessibility)
        stepAccessibilityDone = findViewById(R.id.stepAccessibilityDone)
        stepFiles = findViewById(R.id.stepFiles)
        stepFilesDone = findViewById(R.id.stepFilesDone)
        stepBattery = findViewById(R.id.stepBattery)
        stepBatteryDone = findViewById(R.id.stepBatteryDone)
        stepAutostart = findViewById(R.id.stepAutostart)
        powerupTitle = findViewById(R.id.powerupTitle)
        powerupText = findViewById(R.id.powerupText)
        btnSkipPowerup = findViewById(R.id.btnSkipPowerup)
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
        diagAutoSave = findViewById(R.id.diagAutoSave)

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
        findViewById<Button>(R.id.btnStepAutostart).setOnClickListener {
            PermissionHelper.requestAutostartSettings(this)
        }
        findViewById<Button>(R.id.btnStepAutostartSkip).setOnClickListener {
            // Autostart is advisory: "Done / Skip" is a complete answer.
            Prefs.setWizardStep4Dismissed(this, true)
            refreshWizard()
            Toast.makeText(
                this, getString(R.string.step4_dismissed), Toast.LENGTH_SHORT
            ).show()
        }
        btnSkipPowerup.setOnClickListener {
            // v1.6: skipping the power-up is a complete, PERSISTED
            // answer — the section hides and never nags again. The
            // button toggle below is the way back (it clears this).
            Prefs.setPowerUpSkipped(this, true)
            powerUpExpanded = false
            refreshWizard()
            Toast.makeText(
                this, getString(R.string.powerup_skipped), Toast.LENGTH_LONG
            ).show()
        }
        findViewById<Button>(R.id.btnWake).setOnClickListener {
            PermissionHelper.requestAccessibilitySettings(this)
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

    // ---- The Allow wizard (v1.6: front door first, power-up optional) ----
    // Done-checks (mechanics unchanged since v1.3/v1.4):
    //   CORE — File access: PermissionHelper.hasFileAccess (all-files
    //     on 11+, legacy storage permission below). ALL the status
    //     grid needs — the app is fully useful with this alone.
    //   POWER-UP — Accessibility: the Settings list says our service
    //     is enabled AND the watcher is truly CONNECTED (the two can
    //     disagree — see the Diagnostics notes; the delayed re-check
    //     in onResume covers the bind lag right after the user flips
    //     the switch). Battery: the optimisation exemption —
    //     recommended only, never a gate. Autostart: advisory only,
    //     no done-check — the user answers it with Done/Skip,
    //     persisted in Prefs.
    //
    // Presentation rules:
    //   - The CORE step shows its card while file access is missing,
    //     a slim ✓ row once granted, and hides once the whole wizard
    //     is resolved.
    //   - The POWER-UP group (heading + intro + Skip + accessibility
    //     + battery + autostart) is presented while the core setup is
    //     still incomplete, or while the user is actively setting it
    //     up (powerUpExpanded — set by the button toggle), or while
    //     it stands unanswered AND unskipped before core setup. It
    //     hides for good when: the power-up is granted (its steps
    //     collapse and then resolve away), the user taps "Skip for
    //     now" (persisted in Prefs), or the core setup has latched
    //     and the user never engaged with it. Skipping is never a
    //     dead end: the button toggle clears the skip and reopens
    //     the group.
    //   - The "Button is asleep" banner keeps its v1.4 meaning — the
    //     accessibility switch is enabled but the watcher isn't
    //     connected (an OEM battery manager disconnected it; the
    //     Settings list can still say On) — gated on the CORE latch
    //     so a first-run user mid-flow sees steps, not a banner, and
    //     never shown while the power-up group is expanded (the
    //     steps themselves are the guide there).
    //   - Fully resolved (files granted AND power-up granted or
    //     skipped-or-lathed-away AND nothing asleep) collapses the
    //     wizard to the single "You're all set ✓" row — the BUTTON
    //     wording when the power-up was taken, the GRID wording
    //     when it wasn't.
    // The statuses grid below never waits on any of this — it loads
    // in onResume regardless (Layer-1 is instant, wizard or not).
    private fun powerUpDone(): Boolean =
        PermissionHelper.isAccessibilityEnabled(this) &&
            StatusWatcherService.watcherConnected

    private fun refreshWizard() {
        val filesOk = PermissionHelper.hasFileAccess(this)
        // CORE latch (v1.6 redefinition — see Prefs): file access
        // observed done once. In v1.4–v1.5 this latch required the
        // accessibility step too; the button is optional now, so it
        // no longer belongs in the core latch. A latch set by an
        // older version stays valid (those users have files too).
        if (filesOk && !Prefs.isSetupComplete(this)) {
            Prefs.setSetupComplete(this, true)
        }
        val coreLatched = Prefs.isSetupComplete(this)
        val powerOk = powerUpDone()
        if (powerOk) powerUpExpanded = false
        val skipped = Prefs.isPowerUpSkipped(this)
        val asleep = coreLatched &&
            !powerUpExpanded &&
            PermissionHelper.isAccessibilityEnabled(this) &&
            !StatusWatcherService.watcherConnected
        asleepBanner.visibility = if (asleep) View.VISIBLE else View.GONE
        // Resolved = nothing left that the wizard is presenting:
        // files granted, nobody asleep, and the power-up either
        // granted or put away (skipped, or never engaged before the
        // core latch — it stays reachable via the button toggle).
        val powerSettled = powerOk || skipped || coreLatched
        val resolved = filesOk && powerSettled && !asleep && !powerUpExpanded
        wizardTitle.visibility = if (resolved) View.GONE else View.VISIBLE
        wizardAllSet.visibility = if (resolved) View.VISIBLE else View.GONE
        if (resolved) {
            wizardAllSet.text = getString(
                if (powerOk) R.string.all_set else R.string.all_set_core
            )
        }
        // Core step: card while missing, ✓ row once granted, hidden
        // once resolved (applyStep's collapsed case).
        applyStep(stepFiles, stepFilesDone, filesOk, resolved)
        // Power-up group: presented while the core setup is still
        // open, or while actively being set up — never once put
        // away, and never as part of a resolved wizard.
        val groupVisible = !resolved && (powerUpExpanded || !coreLatched) &&
            !(skipped && !powerUpExpanded)
        powerupTitle.visibility = if (groupVisible) View.VISIBLE else View.GONE
        powerupText.visibility = if (groupVisible) View.VISIBLE else View.GONE
        // Skip is offered only while the power-up is ungranted —
        // after granting, the steps show their own ✓ rows instead.
        btnSkipPowerup.visibility =
            if (groupVisible && !powerOk) View.VISIBLE else View.GONE
        if (groupVisible) {
            applyStep(
                stepAccessibility, stepAccessibilityDone,
                powerOk, collapsed = false
            )
            applyStep(
                stepBattery, stepBatteryDone,
                PermissionHelper.hasBatteryExemption(this), collapsed = false
            )
        } else {
            stepAccessibility.visibility = View.GONE
            stepAccessibilityDone.visibility = View.GONE
            stepBattery.visibility = View.GONE
            stepBatteryDone.visibility = View.GONE
        }
        // Autostart (advisory) belongs to the power-up group — it
        // only matters for the button — and still answers to its
        // own Done/Skip.
        stepAutostart.visibility =
            if (groupVisible && !Prefs.isWizardStep4Dismissed(this)) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    // One step's two faces: the full card (title + text + Allow button)
    // while it's missing, a slim "✓ …" row once granted. When the whole
    // wizard is resolved, both faces hide — the "You're all set" row is
    // the wizard. refreshWizard decides which steps are presented at
    // all (the power-up group hides as a unit — see above).
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
    // Locked until file access + the power-up are done: if the user
    // taps early, we scroll to the wizard and say so — never a silent
    // no-op. v1.6: asking for the button is also the way back from a
    // skipped or put-away power-up — the tap clears any persisted
    // Skip and re-expands the section before pointing at it. If the
    // steps are done but the watcher hasn't connected yet (bind lag,
    // or a phone that shows the switch On without binding), we say
    // that honestly too.
    private fun toggleFloatingButton() {
        if (Prefs.isButtonEnabled(this)) {
            Prefs.setButtonEnabled(this, false)
            StatusWatcherService.reevaluate()
        } else {
            if (!powerUpDone() || !PermissionHelper.hasFileAccess(this)) {
                if (!powerUpDone()) {
                    Prefs.setPowerUpSkipped(this, false)
                    powerUpExpanded = true
                    refreshWizard()
                    if (!PermissionHelper.isAccessibilityEnabled(this)) {
                        Toast.makeText(
                            this, getString(R.string.accessibility_first),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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
    //   → Auto-save: ON/OFF (v1.4) — the switch's persisted state.
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
            // v1.5: an already-saved attempt is recorded with ok =
            // false (this attempt copied nothing) and the reason
            // "already saved" — render it as what it is, not as a
            // failure and not as a fresh save.
            val result = when {
                save.reason == "already saved" -> "ALREADY SAVED"
                save.reason == "auto: already saved" -> "ALREADY SAVED (auto)"
                save.ok -> if (save.reason == "auto") "SAVED (auto)" else "SAVED"
                else -> {
                    val why = save.reason.take(60).ifEmpty { "unknown error" }
                    "FAILED ($why)"
                }
            }
            "$time · ${save.fileName} · ${humanSize(save.sizeBytes)} · $result"
        }
        // v1.4: the auto-save switch state, on the card itself — when
        // a user reports "statuses download by themselves", this row
        // (plus the "(auto)" marker on the save row) settles whether
        // the feature is even on.
        val autoSave = if (Prefs.isAutoSave(this)) "ON" else "OFF"
        return listOf(
            "Overlay permission" to overlay,
            "Accessibility switch" to accessibility,
            "Watcher" to watcher,
            "Button engine" to engine,
            "Button visible now" to visibleNow,
            "Last seen screen" to lastScreen,
            "Last save attempt" to lastSave,
            "Auto-save" to autoSave
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
        diagAutoSave.text = "${rows[7].first}: ${rows[7].second}"
    }

    private fun copyDiagnostics() {
        val text = StringBuilder("StatusSaver v1.6 diagnostics\n")
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
                    // v1.5: SaveResult — a status already in the
                    // gallery answers "Already saved ✓" instead of
                    // copying a duplicate (global dedupe lives in
                    // SaveHelper, so the grid gets it for free).
                    val result = SaveHelper.saveToGallery(
                        this@MainActivity, item.file, item.isVideo
                    )
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            when (result) {
                                SaveHelper.SaveResult.Saved ->
                                    getString(R.string.saved_gallery)
                                SaveHelper.SaveResult.AlreadySaved ->
                                    getString(R.string.already_saved)
                                is SaveHelper.SaveResult.Failed ->
                                    getString(R.string.save_failed)
                            },
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

    // Everything StatusSaver has put in the gallery, newest first.
    // Since v1.4 there is ONE album — Pictures/StatusSaver — holding
    // images AND videos; v1.0–v1.3 put videos in Movies/StatusSaver,
    // so the video query covers BOTH relative paths or old saves would
    // vanish from this tab. Parameterized selection only (no
    // string-built SQL values). RELATIVE_PATH exists on Android 10+;
    // below that, SaveHelper's legacy path wrote plain files into
    // Pictures/StatusSaver, so match on the DATA path instead (the
    // LIKE pattern matches either folder, one query per collection).
    private fun querySaved(): List<SavedItem> {
        val out = mutableListOf<SavedItem>()
        collectSaved(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isVideo = false,
            relPaths = listOf("Pictures/StatusSaver/"),
            out = out
        )
        collectSaved(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isVideo = true,
            relPaths = listOf("Pictures/StatusSaver/", "Movies/StatusSaver/"),
            out = out
        )
        return out.sortedByDescending { it.dateAddedSec }
    }

    private fun collectSaved(
        collection: Uri,
        isVideo: Boolean,
        relPaths: List<String>,
        out: MutableList<SavedItem>
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE
        )
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pathClauses = relPaths.joinToString(" OR ") {
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            }
            selection = "($pathClauses) AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0"
            args = relPaths.toTypedArray()
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
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        out.add(
                            SavedItem(
                                uri = ContentUris.withAppendedId(collection, id),
                                id = id,
                                name = c.getString(nameCol) ?: "",
                                isVideo = isVideo,
                                dateAddedSec = c.getLong(dateCol),
                                sizeBytes = c.getLong(sizeCol)
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
                        // v1.5: the file is gone from the gallery —
                        // forget its dedupe identity too, so the same
                        // status can be saved again later.
                        Prefs.forgetSavedIdentity(
                            this, item.name, item.sizeBytes
                        )
                        Toast.makeText(
                            this, getString(R.string.deleted_ok), Toast.LENGTH_SHORT
                        ).show()
                        loadSaved()
                    }
                    sender != null -> {
                        pendingDeleteSender = sender
                        pendingDeleteItem = item
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
                            pendingDeleteItem = item
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
