# StatusSaver — build notes (v1, 2026-10-19; v1.1 update 2026-10-20)

Engineering log for the v1 build.

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
