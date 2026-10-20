# StatusSaver — build notes (v1, 2026-10-19; v1.1–v1.3 updates 2026-10-20)

Engineering log for the v1 build.

## v1.3 (2026-10-20) — architecture fix: the watcher owns the button

### Field evidence (v1.2 on Fahad's vivo phone, evening test)
v1.2's diagnostics split the case cleanly:
- **Saves WORK.** The Files app shows the StatusSaver album with 3 items
  (1 jpg 81 KB, 2 mp4 2.08 MB + 3.21 MB, today's timestamps) — the
  verified save path is proven. BUT his **Gallery app does not surface
  them** → a gallery-visibility problem, not a save problem.
- **The button worked, then died permanently.** At ~6:26 PM the button
  appeared in a status and a video saved. Later screenshots:
  MainActivity toggle intent ON ("Hide floating button"), Diagnostics
  reading `Floating service: NOT RUNNING`, `Watcher: CONNECTED`, button
  never appearing again.

### Root cause (the one v1.2's design couldn't survive)
The process was killed (vivo battery manager / recents). Android
revived the **accessibility watcher** — the system keeps enabled
accessibility services bound — but did **NOT** revive the START_STICKY
**FloatingService**: OEMs routinely suppress sticky-service restarts,
and even on stock Android sticky restart is a favour, not a contract.
Nothing noticed, nothing healed: statics reset, the toggle label
(intent, from the activity) and the service flag (a different
lifecycle) disagreed forever. Any architecture where the button's
owner can die separately from the component the system guarantees to
revive will eventually strand the button exactly like this.

### The fix — one engine
- **`StatusWatcherService` owns the button.** It creates the button
  view itself (identical design: 56dp, #25D366 `floating_bg`, white
  `ic_download`, BOTTOM|END 16dp/110dp, `FLAG_NOT_FOCUSABLE`) as a
  `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY` window — a
  type reserved for accessibility services, needing **no
  SYSTEM_ALERT_WINDOW permission**. Rule (as first built): attached ⇔
  persisted user choice ON **and** current screen MATCHES the status
  detector (matcher, exclusions and the persisted last-seen record
  unchanged from v1.2). **Superseded the same day by the final spec —
  see "Final-spec completion" below: visibility is now package-level
  (WhatsApp in the foreground), and the detector drives the tap and
  auto-save instead.** Show/hide is **addView/removeView** (not
  visibility): hidden means *no window exists* — nothing drawn,
  nothing touchable — and "visible now" has one unambiguous answer
  for diagnostics.
- **Tap = save newest status**, moved verbatim from FloatingService
  (StatusScanner.newest + SaveHelper.saveToGallery, age-naming toast
  and all). SaveHelper is untouched in behaviour, so the button path
  and the in-app grid path stay identical.
- **`FloatingService` is DELETED** (class + manifest entry). The
  persistent notification goes with it — intended: the in-app toggle +
  the watcher are now the whole engine; there is nothing left that
  needs a "running" notice. `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS` are
  removed from the manifest (checked: nothing else used them — no
  other service, no notifications; AdMob needs none of them).
  `SYSTEM_ALERT_WINDOW` stays *declared* but unused: the Diagnostics
  card reports it as "legacy — not required since v1.3". (Play review
  may prefer dropping it entirely — a one-line manifest change if a
  reviewer asks; keeping it costs nothing at runtime.)
- **Toggle = persisted choice + nudge.** MainActivity's toggle flips
  `Prefs.buttonEnabled` and calls the static
  `StatusWatcherService.reevaluate()` hook (posts `updateButton()` on
  the watcher's main handler). The label reads the SAME Prefs record —
  one source of truth, so label and engine can no longer disagree.
  If the watcher is **not connected**, enabling is refused honestly
  with "Turn on Accessibility first" and nothing is recorded — no
  more ON labels pointing at dead engines.
- **Self-heal.** `onServiceConnected` re-reads Prefs (choice +
  last-screen record) and evaluates immediately: a FRESH (<30 s) MATCH
  restores the button mid-status; anything else starts hidden and the
  next window event decides. `onUnbind`/`onDestroy` remove the button
  — the safe state is *no window*.
- **Diagnostics v1.3 rows:** Overlay permission (labelled legacy),
  Accessibility switch, Watcher CONNECTED, **Button engine:
  WATCHER · ALIVE/DEAD** (= watcher connected), **Button visible now:
  YES/NO** (the watcher's real attach state), Last seen screen
  (unchanged), and NEW **Last save attempt** — SaveHelper now records
  every attempt in Prefs (time · file name · size · SAVED, or FAILED +
  the exception's reason). "It saved but it's not in my gallery" is
  now answerable from one screenshot: if the row says SAVED and Files
  shows the file, it's the gallery's indexing, full stop.
- **Gallery visibility.** The MediaStore insert now sets `DATE_TAKEN`
  (source file's mtime, milliseconds) and `DATE_ADDED` (now, seconds —
  MediaStore's units, don't swap them), and after the verified publish
  the saved file is handed to `MediaScannerConnection.scanFile`
  (path resolved via the inserted row's `_data` column; skipped
  silently if null; wrapped so it can never fail a verified save).
  Byte-count + read-back verification are byte-for-byte as v1.1/v1.2.
- **Permission flow (as first built): 2 cards** — 1) File access,
  2) Accessibility. **Superseded the same day by the final spec's
  one-time Allow wizard (3 steps) — see "Final-spec completion".**
  MANAGE_EXTERNAL_STORAGE / legacy storage logic untouched — status
  reading still needs it.
- Design decision #2 below (FGS "specialUse") is **retired** by this
  release; it stays in the log as history.

### Files changed in v1.3
Changed: `app/build.gradle` (versionCode 4 / versionName "1.3"),
`app/src/main/AndroidManifest.xml` (FloatingService entry + 3
permissions removed), `.../StatusWatcherService.kt` (button ownership,
self-heal, save path), `.../MainActivity.kt` (toggle, 2-card flow,
diagnostics rows — the final-spec pass then replaced the cards with
the Allow wizard and added tabs + auto-save; see "Final-spec
completion" below), `.../Prefs.kt` (last-save record), `.../SaveHelper.kt`
(dates + scanner nudge + attempt recording), `.../PermissionHelper.kt`
(comments/copy), `app/src/main/res/layout/activity_main.xml`,
`app/src/main/res/values/strings.xml`, `README.md`, `BUILD_NOTES.md`,
`UPDATE_INSTRUCTIONS.md`, `PRIVACY_POLICY.md`, `STORE_LISTING.md`.
**Deleted: `.../FloatingService.kt`** (via GitHub web UI — see
UPDATE_INSTRUCTIONS.md; the update zip cannot carry a deletion).
Not changed: `StatusScanner.kt`, `accessibility_service_config.xml`
(same events, same no-content-access), drawables, colors.

### Final-spec completion (2026-10-20, after Fahad approved the spec)

The first v1.3 build above was frozen mid-flight on Fahad's order
("Before you create it") so he could add product requirements. He
then approved the final spec, and this pass finished it. English
only — localization was explicitly deferred by Fahad; no
`values-ur`/`values-hi` were added.

1. **Visibility = package-level.** `updateButton()` now shows the
   button ⇔ `Prefs.buttonEnabled` AND the foreground package is
   `com.whatsapp` / `com.whatsapp.w4b` (tracked on every event as
   `whatsappOnScreen`). It remains a pure re-derivation per event:
   `attachButton()` now also verifies `view.parent != null` and
   re-adds a view that was dropped underneath us; `detachButton()`
   removes by parent, not by flag. The status matcher still runs,
   still persists its record (Diagnostics "Last seen screen" +
   MATCH verdict unchanged) — it just no longer gates visibility.
   onServiceConnected restores BOTH states from a fresh (<30 s)
   record: package from the record's pkg, status from its verdict.
2. **Smart tap.** The button's click is now `onButtonTap()`: if
   `isStatusBeingViewed()` (live detector state, or a persisted
   MATCH <30 s old) → the unchanged verified save-newest flow;
   otherwise a friendly Toast ("Open a status first, then tap the
   green button."). No silent no-ops anywhere.
3. **One-time Allow wizard** (replaces the 2 permission cards).
   Sequential steps in one card, each with ONE full-width "Allow"
   button deep-linking to the exact screen: Step 1 Accessibility →
   `ACTION_ACCESSIBILITY_SETTINGS` (done = settings-enabled AND
   watcher CONNECTED); Step 2 Files →
   `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (package Uri;
   fallback `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`, then app
   details); Step 3 Battery → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   (package Uri; fallback app details; done =
   `PowerManager.isIgnoringBatteryOptimizations`; recommended, NOT a
   gate — new helpers in PermissionHelper). Completed steps collapse
   to slim "✓ …" rows; when steps 1+2 are done the wizard collapses
   to a single "You're all set ✓" row and reopens only the missing
   step if a required permission is revoked. The enable toggle is
   locked until steps 1+2 are done — an early tap scrolls to +
   flashes the wizard and toasts "Finish the setup steps first".
   A 1.2 s delayed re-check after onResume covers watcher bind lag.
4. **My Saved tab.** Tab row (Recent statuses | My Saved) over the
   existing grid. My Saved queries MediaStore's Images + Video
   collections with parameterized selection — Android 10+:
   `RELATIVE_PATH = ? AND IS_PENDING = 0` with
   `Pictures/StatusSaver/` resp. `Movies/StatusSaver/`; below 10:
   `DATA LIKE '%/StatusSaver/%'` (the legacy save path). Merged,
   sorted by DATE_ADDED desc. Thumbnails via
   `ContentResolver.loadThumbnail` (29+), classic Thumbnails tables
   below — no new dependencies. Tap → View / Share / Delete dialog:
   View = ACTION_VIEW + grant-read; Share = ACTION_SEND chooser with
   the content Uri; Delete = direct delete first, consent via
   `MediaStore.createDeleteRequest` (30+) or
   `RecoverableSecurityException`'s sender (29) through one
   ActivityResult launcher.
5. **Auto-save switch** (Prefs, default OFF). The watcher arms a
   900 ms debounce on a false→true transition into detector MATCH
   and cancels on exit; on fire (still matched) it runs the save
   flow with `viaAuto = true`. Duplicate guard: SaveHelper now
   records the last successful save's (name, length) in Prefs after
   EVERY verified save — manual ones included — and auto-save skips
   the newest status if it matches that record. Attempts land in the
   same LastSave record with reason "auto" (Diagnostics shows
   "SAVED (auto)"). Auto-save failures are recorded but not toasted
   (no spam while browsing); a brief success toast is shown.

Files touched by this pass: `StatusWatcherService.kt` (visibility,
smart tap, auto-save), `MainActivity.kt` (wizard, tabs, My Saved,
auto-save switch, diagnostics "(auto)" marker), `Prefs.kt`
(auto-save flag + last-saved record), `SaveHelper.kt` (`viaAuto`
param + last-saved update), `PermissionHelper.kt` (battery helpers,
file-access fallbacks), `activity_main.xml` (wizard + switch + tabs),
`strings.xml` (wizard/tabs/auto-save copy; `accessibility_desc`
updated to package-level truth), `UPDATE_INSTRUCTIONS.md` (rewritten,
incl. the GitHub delete steps for FloatingService.kt and the
permanence test), `PRIVACY_POLICY.md` + `STORE_LISTING.md`
(accessibility-engine permission wording; no more display-over-apps /
notification claims for the button), `README.md` (final-spec state).

### Residual risks (v1.3)
- **Force-stop is still final.** If the user (or an OEM cleaner)
  force-stops the app, Android disables the accessibility binding
  until the user re-enables it in Settings — true for every
  accessibility-based app; the Diagnostics card shows NOT CONNECTED
  and card 2 is the way back.
- **TYPE_ACCESSIBILITY_OVERLAY quirks.** The window type is documented
  for exactly this (accessibility services placing their own UI), and
  click delivery to such windows is standard; a handful of OEM builds
  have historically been picky about accessibility overlays — the
  vivo field test is the arbiter, and "Button visible now" + a status
  screenshot separates an attach failure from a detector miss.
- **Play Accessibility declaration** — unchanged from v1.1 §6 of the
  README; if anything the case is stronger now (the service's purpose
  is visibly the app's core feature).
- **Package-level visibility (final spec)** — the button is present on
  WhatsApp's chat list and inside chats too, by Fahad's explicit
  choice. The smart tap keeps that harmless (explanation toast, no
  save), but some users may find an always-present button in chats
  noisy; the in-app toggle is the off switch. Relatedly, the
  duplicate guard compares name + length only: two different statuses
  with identical name and size would read as "the same file" to
  auto-save (WhatsApp's cache names are content hashes, so a real
  collision means it almost certainly IS the same status).

## v1.2 (2026-10-20) — diagnostic release: "button never appears" on a vivo phone

Phone test of v1.1 on a vivo (Funtouch OS): in-app grid, saving and ads
all worked; Accessibility showed "On", high-background-battery was
allowed, overlay had worked since v1, in-app toggle was ON — yet the
floating button NEVER appeared in WhatsApp's status view. Four suspects
were investigated in the v1.1 code before changing anything:

### Suspect check (evidence, not guesses)

1. **Watcher in a separate process → static reference null? RULED OUT.**
   Read `AndroidManifest.xml` line by line: neither
   `StatusWatcherService` nor `FloatingService` declares
   `android:process` (nor does any other component). Both services run
   in the app's default process, so the v1.1 same-process static
   instance design is valid in principle — the show/hide calls were
   NOT silently no-ops for this reason. No manifest change needed.
2. **Watcher shows "On" but isn't actually bound (vivo quirk)? CANNOT
   be ruled out from outside — v1.1 kept no evidence.** v1.1 had no
   record of whether `onServiceConnected` ever ran, or whether the
   service was later unbound by Funtouch's battery manager (which can
   leave the Settings list saying "On"). → v1.2 adds a
   `watcherConnected` flag set in `onServiceConnected` and cleared in
   `onUnbind` (new override — v1.1 only cleared in `onDestroy`, which
   Android is not obliged to call on unbind) and `onDestroy`, displayed
   on the Diagnostics card separately from the Settings-list check.
3. **WhatsApp class-name mismatch? PLAUSIBLE — v1.1's matcher was
   narrow, and it kept no evidence either.** v1.1 accepted only
   `StatusPlayback` / `status.playback` / `StatusPlayer` (plus a
   status+playback fallback). If his WhatsApp build names the viewer
   differently (e.g. a view/story/media-style class), every event was
   a silent NO MATCH. → v1.2 widens the rule (below) AND records the
   last seen class name so one screenshot ends the question forever.
4. **FloatingService not running / visibility lost on restart?
   CONTRIBUTING RACE FOUND in v1.1.** The button was created `GONE`,
   and visibility was only ever flipped by a watcher *change* event.
   If the process was killed (Funtouch!) and Android restarted
   FloatingService via START_STICKY while the user sat in the status
   screen, the new button stayed `GONE` — the watcher (also restarted,
   its `lastShown` dedup reset) would only speak on the NEXT window
   change, which might never come while the user stares at a status.
   Same hole in reverse if the watcher reconnected after the service.
   → v1.2 closes both directions (below).

### What v1.2 changes

- **Diagnostics card (MainActivity).** Five monospace rows refreshed on
  every `onResume`: Overlay permission (`Settings.canDrawOverlays`),
  Accessibility switch (`PermissionHelper.isAccessibilityEnabled` —
  the Settings.Secure list), Watcher CONNECTED/NOT CONNECTED (the new
  bound-flag — deliberately separate from the switch row), Floating
  service RUNNING (`FloatingService.isRunning`), and Last seen screen
  from the persisted record: `pkg / class (MATCH|NO MATCH) · Ns ago`.
  A **Copy diagnostics** button puts the same values on the clipboard
  (rows and copy text are built from one `diagnosticsRows()` call, so
  they cannot disagree).
- **Persisted watcher record (`Prefs.kt`, new).** The watcher writes
  EVERY processed `TYPE_WINDOW_STATE_CHANGED` event (post-filter: own
  package and transient popup/IME windows are skipped, as before) to
  SharedPreferences: package, class, matched, timestamp. Also stores
  the user's explicit button on/off choice (set by MainActivity's
  toggle and by the notification's stop action), which survives
  process death.
- **Link hardening (`FloatingService`, `StatusWatcherService`).**
  `FloatingService.onCreate` + `onStartCommand` call
  `applyPersistedWatcherState()`: fresh (<30 s) MATCH in the record →
  restore the button visible; stale/NO MATCH → leave hidden (hiding
  remains the watcher's job). `StatusWatcherService.onServiceConnected`
  pushes the persisted state to FloatingService if it is running, so a
  watcher that (re)connects second still synchronises the service.
  Deliberately NOT done: starting the FGS from the accessibility
  service — background FGS starts are restricted on modern Android and
  would throw; the START_STICKY restart + re-read covers recovery.
- **Wider matcher (`StatusWatcherService.isStatusPlayback`).**
  Package ∈ {com.whatsapp, com.whatsapp.w4b}; exclusions first
  (`textstatus` About-editor, `settings`, `profile`); exact v1.1 names
  kept; then the widened rule: class contains `status` AND any of
  `playback`/`player`/`view`/`story`/`media` (case-insensitive).
- **Universal battery guidance.** NO brand-specific code anywhere —
  the matcher, diagnostics and services are brand-agnostic. The
  Accessibility card carries one short universal line (allow
  background running), and README carries the compact per-brand table
  (Pixel/Samsung/Xiaomi/Oppo/vivo/Huawei/Transsion), since the
  setting's name and place differ per brand. This matters beyond vivo:
  Samsung's "never sleeping apps" and Transsion's Phone Master kill
  watchers just as dead.

### Files changed in v1.2
Changed: `app/build.gradle` (versionCode 3 / versionName "1.2"),
`.../StatusWatcherService.kt`, `.../FloatingService.kt`,
`.../MainActivity.kt`, `app/src/main/res/layout/activity_main.xml`,
`app/src/main/res/values/strings.xml`, `README.md`, `BUILD_NOTES.md`,
`UPDATE_INSTRUCTIONS.md`.
Added: `.../Prefs.kt`.
Not changed (verified, no fix needed): `AndroidManifest.xml` — no
`android:process` anywhere; `accessibility_service_config.xml`.

### What the Diagnostics panel will prove on his phone
One screenshot after viewing a status for ~5 s distinguishes all four
suspects: Watcher NOT CONNECTED + switch ON ⇒ suspect 2 (binding/OEM);
Watcher CONNECTED + Last seen = a WhatsApp class + NO MATCH ⇒ suspect 3
(and the exact class name is the one-line fix); Last seen MATCH +
Floating NOT RUNNING ⇒ suspect 4 (service killed — battery row);
MATCH + RUNNING + button still invisible ⇒ a new, narrower bug in the
overlay itself. No logcat, no guessing.


## v1.1 (2026-10-20) — fixes from Fahad's real-phone test

Phone test of v1 result: image save worked; (1) video save toasted
"Saved ✓" but the video was NOT in the gallery; (2) the floating button
later disappeared entirely; (3) UX — button too big, visible over ALL
apps. His spec for v1.1: show only while a WhatsApp status is playing,
medium size, WhatsApp green, bottom-right. All four addressed:

### 1. Video save bug — root cause (SaveHelper)
Two defects compounded:
- v1 inserted videos into the **Video** MediaStore collection with
  `RELATIVE_PATH "Pictures/StatusSaver"`. Images were correctly paired
  (Images collection + Pictures path) and worked; the video was written
  under the Pictures tree, where gallery apps' video views / MediaStore
  folder surfacing don't present it. Fix: videos → `Movies/StatusSaver`,
  images stay in `Pictures/StatusSaver`.
- Success was **unverified**: `saveToGallery` returned true unless an
  exception escaped. The copied byte count was never compared to the
  source length, the `IS_PENDING` publish update was never checked, and
  the inserted row was never read back — and the MIME type came from a
  generic extension lookup whose fallback was `image/jpeg` for ANY file.
  Fix: MIME is derived from the known `isVideo` flag (videos always
  `video/*`, default `video/mp4`); the copy is flushed and byte-counted;
  after `IS_PENDING` is cleared, the row is queried back and must match
  size + MIME + pending=0 before the caller may toast success; failures
  delete the pending row (no grey "broken" gallery ghosts) and surface
  the honest `save_failed` toast. Failures are `Log.e`-tagged
  (`SaveHelper`, `FloatingService`).

### 2. Disappearing button — root cause (FloatingService)
- `saveNewestStatus()` ran on a bare `Thread` with **no try/catch**. On
  Android, an uncaught exception on ANY thread kills the whole process —
  so one bad save (e.g. a `SecurityException` from `StatusScanner` after
  a permission flip) silently took the service and its button down.
- `onCreate()` was likewise unguarded: a `startForeground()` failure
  (Android 14 FGS enforcement) or an `addView()` failure ended the
  service on the spot.
Fix: every lifecycle/save entry point is try/catch + `Log.e`; the
service returns `START_STICKY`; `onStartCommand` re-asserts foreground
state and re-creates the button if it is missing.
- **OEM battery killers (caveat, not fully fixable in code):** Xiaomi/
  Oppo/Vivo/Transsion (Infinix/Tecno) battery managers can still kill
  even a healthy foreground service. If the button stops appearing on
  such a phone: Settings → Apps → StatusSaver → Battery → "Unrestricted"
  (and allow Autostart where the OEM offers it). START_STICKY gets us
  restarted on stock Android; it cannot override an OEM force-stop.

### 3. "Only while viewing a status" — NEW StatusWatcherService
This reverses v1 decision #3 below, deliberately, at the product
owner's explicit request: there is NO other Android API that can tell
"WhatsApp's status player is in front", so the choice was accessibility
or nothing. Implementation minimises the footprint:
- `AccessibilityService` listening to `TYPE_WINDOW_STATE_CHANGED` only.
- `canRetrieveWindowContent="false"` — it learns package + activity
  class names and nothing else; no text, no gestures, no data leaves
  the phone.
- Match rule: package is `com.whatsapp`/`com.whatsapp.w4b` AND class
  name contains `StatusPlayback` / `status.playback` / `StatusPlayer`,
  with a case-insensitive "status"+"playback" fallback if WhatsApp
  renames the class. Debug builds log every foreground class name
  (logcat tag `StatusWatcher`) so a future rename is a 1-line fix.
- Events from our own package and transient windows (popups, IME) are
  ignored to prevent flicker. The config does NOT restrict
  `packageNames` — the watcher must see other apps' windows to know the
  user left WhatsApp.
- The watcher talks to FloatingService through a same-process static
  instance reference (no broadcasts/intents, so no background FGS-start
  restrictions). If the service isn't running, events are no-ops: the
  user still enables the button from the app, as in v1.
- The button is created `GONE` and only ever flipped VISIBLE/GONE.
- **Play policy risk (known, accepted for now):** Google Play restricts
  Accessibility API use; a declaration is required at publish (draft
  text in README §6) and rejection is possible. This does not affect
  sideload testing. Fallback if rejected: manual show/hide from the
  persistent notification (v1 behaviour).

### 4. Button redesign
56dp circle, `@color/whatsapp_green` (#25D366) fill, white
`ic_download` vector, 6dp elevation, gravity BOTTOM|END with 16dp side
margin and 110dp bottom margin (clears WhatsApp's status reply bar).
Long-press drag removed — position is fixed by design now; tap is a
plain `OnClickListener`.

### Files changed in v1.1
Changed: `app/build.gradle` (versionCode 2 / versionName "1.1"),
`app/src/main/AndroidManifest.xml`, `.../SaveHelper.kt`,
`.../FloatingService.kt`, `.../PermissionHelper.kt`,
`.../MainActivity.kt`, `app/src/main/res/layout/activity_main.xml`,
`app/src/main/res/values/strings.xml`,
`app/src/main/res/values/colors.xml`,
`app/src/main/res/drawable/floating_bg.xml`, `README.md`,
`BUILD_NOTES.md`.
Added: `.../StatusWatcherService.kt`,
`app/src/main/res/xml/accessibility_service_config.xml`,
`app/src/main/res/drawable/ic_download.xml`,
`UPDATE_INSTRUCTIONS.md`.

## Design decisions

1. **File access: MANAGE_EXTERNAL_STORAGE (All files access).**
   WhatsApp caches viewed statuses in hidden `.Statuses` folders
   (`Android/media/com.whatsapp/...` on Android 11+, `WhatsApp/...` on older
   installs). These are not reachable via MediaStore, so the app requests
   All-files access through the system settings screen. Consequence: Google
   manually reviews this permission at Play publish time — the README ships
   a suggested declaration text. The app reads ONLY the four status-folder
   candidates (WhatsApp + WhatsApp Business) and copies user-chosen files to
   `Pictures/StatusSaver`. Nothing is uploaded or shared.

2. **Floating button: foreground service, type "specialUse".**
   `SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY` for the button.
   `FOREGROUND_SERVICE_SPECIAL_USE` permission + the
   `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property tag are declared for
   Android 14+. Tap = save newest status; long-press + drag = move the
   button; tapping the notification stops the service.

3. **"Newest file = the one you're viewing" is a heuristic.**
   Android offers no clean way to know which status is on screen without
   Accessibility services (a far bigger policy/trust problem). The button
   saves the most recently modified file; if it's older than ~2 minutes the
   toast names the file so the user can judge. The in-app grid (with
   per-item Save buttons) is the fallback when the heuristic misses.

4. **Ads in v1.** AdMob banner with Google's SAMPLE ids — shows test ads
   until Fahad replaces them with his own (AdMob account under his name).
   This is also the monetization lesson: the app earns from day one.

5. **Classic Views, no Compose.** findViewById + RecyclerView, plain Threads
   for I/O. Readable for a learner; no Hilt, no coroutines.

## Constraints / known limits

- Files are TRANSIENT — WhatsApp clears `.Statuses`, so saving soon after
  viewing matters.
- minSdk 24 (Android 7.0), targetSdk 34. `requestLegacyExternalStorage`
  only affects API 29.
- Sideloaded test APKs: Android may require an extra "Allow restricted
  settings" step before the overlay toggle appears — system behavior,
  not a bug.
- Android 15/16 show a persistent "displaying over other apps" privacy
  indicator — warn users this is the OS, not the app.

## CI
`.github/workflows/build.yml`: on push to `main`, ubuntu-latest, Temurin
JDK 17, `gradle/actions/setup-gradle` installs Gradle 8.7 (no wrapper
committed — this box has no Java/Gradle), `gradle :app:assembleDebug`,
uploads `app-debug.apk` as an artifact. Download it from the Actions tab.

## Still to come (v1.1+)
- Urdu/Hindi/Arabic/Portuguese strings + store listing (native-speaker check).
- His final-cut review of every file (standing gate) — he reviews via commits.
