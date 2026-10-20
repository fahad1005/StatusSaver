# StatusSaver — build notes (v1, 2026-10-19; v1.1 & v1.2 updates 2026-10-20)

Engineering log for the v1 build.

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
