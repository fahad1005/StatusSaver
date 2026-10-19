# StatusSaver — build notes (v1, 2026-10-19)

Engineering log for the v1 build.

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
