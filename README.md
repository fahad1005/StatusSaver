# StatusSaver — v1.4.1

**The status downloader you never have to leave WhatsApp for.**
A small floating **download button** lives inside WhatsApp **whenever
WhatsApp is open**. Viewing a status? One tap — it's saved to your gallery.
When you leave WhatsApp, the button disappears. No switching apps, no
button floating over your other apps.

## What's new in v1.4.1 (consolidation)

Built on the unreleased v1.4 (everything below still applies), from a
second batch of screen recordings:

- **The button saves the status you're actually looking at.** The old
  rule — "newest file in WhatsApp's cache" — re-saved the *previous*
  status on camera: WhatsApp pre-caches and finishes downloads on its
  own schedule, so the viewed status isn't reliably the newest. Now,
  opening the status viewer snapshots the cache, and a save takes the
  file that **appeared or changed for this viewing** (session
  targeting). Fallback, when WhatsApp wrote nothing (a fully cached
  re-view): newest overall, with the toast naming the file.
- **A real launcher icon.** The icon was a white arrow with no
  background — a blank white tile in the app drawer. It's now the
  brand mark: white download arrow on WhatsApp green (#25D366),
  as a proper adaptive icon (with a layered fallback for older
  Android).

## What's new in v1.4 (field-test fixes)

Built from Fahad's full v1.3 phone test — including a 154-second
screen recording. The architecture didn't change; five defects in how
it *felt* did:

- **Setup is truly one-time now.** v1.3 latched nothing: if the phone
  put the watcher to sleep, the app greeted you with the whole wizard
  again — "every time I have to start and allow it". v1.4 latches
  setup completion in Prefs. After that, a sleeping watcher gets ONE
  slim banner — **"Button is asleep — Wake it"** — and a genuinely
  revoked files permission gets its single repair step. Nothing else
  ever re-opens. A new optional wizard **Step 4 (Autostart)** deep-links
  to your phone's autostart screen (where it has one), and a silent
  boot receiver warms the app's state after a restart.
- **A tap always saves.** v1.3's "smart tap" refused taps the status
  detector didn't recognise ("some download, some don't"). The gate
  is gone: inside WhatsApp, tapping the button saves the newest
  status, every time.
- **Saves wait for the file to finish.** Statuses were being copied
  while WhatsApp was still writing them (the recording shows taps
  landing on still-buffering statuses). Saving now waits for the
  file's size to settle (~5 s budget for videos, ~3 s for images)
  before copying — then verifies exactly as before.
- **No more flicker.** The button blinked off/on while swiping
  between statuses. Hiding now requires WhatsApp to be gone for a
  steady ~0.6 s; showing stays instant.
- **ONE gallery album.** Videos no longer hide in a separate
  `Movies/StatusSaver` album (see BUG 5 in BUILD_NOTES): photos and
  videos both land in the **StatusSaver** album
  (`Pictures/StatusSaver`). Old saves stay visible in My Saved.
- **Auto-save only fires live.** It can no longer trigger from the
  watcher's restored state after a restart, and it says
  **"Auto-saved ✓"** when it saves. Diagnostics shows an
  **Auto-save: ON/OFF** row.

## What's new in v1.3 (architecture fix)

v1.2's own Diagnostics card caught the real killer on the vivo test
phone: after the phone killed the app process, Android revived the
accessibility **watcher** but never revived the separate button service
— toggle said ON, engine was dead, button gone forever. v1.3 removes
the second service entirely:

- **One engine.** The watcher now draws the button itself, as an
  *accessibility overlay* (a window type Android reserves for
  accessibility services — no "display over other apps" permission
  needed). If the process dies, the system rebinds the watcher and it
  restores the button from its own records. The persistent
  "StatusSaver is running" notification is gone — there is no separate
  service left to keep alive.
- **Honest toggle.** The Show/Hide button now just records your choice
  and nudges the watcher. If the watcher isn't connected, it tells you
  "Turn on Accessibility first" instead of pretending.
- **Button on every WhatsApp screen (final spec).** Visibility is
  package-level: WhatsApp in the foreground = button there; anything
  else = button gone. The status detector still runs — it now drives
  the **smart tap** (in a status: save; anywhere else: a friendly
  "Open a status first" explanation — never a silent no-op) and the
  optional auto-save below.
- **One-time Allow wizard.** First launch walks through 3 steps —
  Accessibility → File access → Battery — each with one big Allow
  button that deep-links to the exact system screen. Finished steps
  collapse to slim ✓ rows; when the two required steps are done the
  wizard becomes a single "You're all set ✓" row and only returns if
  a required permission is later switched off.
- **My Saved tab.** Everything you've saved, inside the app — tap an
  item to View, Share, or Delete it (MediaStore-backed, newest first).
- **Auto-save switch (default OFF).** "Auto-save every status I view":
  entering the status viewer saves the newest status by itself
  (debounced ~1 s, duplicate-guarded against your last save).
- **Gallery visibility.** Saves now stamp the file's dates and nudge
  the media scanner, so gallery apps surface the StatusSaver album
  right away (in v1.2 the files were in the Files app but the Gallery
  app lagged behind).
- **Last save attempt, on the Diagnostics card.** Every save is
  recorded — time, file, size, SAVED or FAILED + why (automatic saves
  are marked) — so "did it save?" is answerable from one screenshot.

## What's new in v1.2 (diagnostic release)

On one phone (a vivo), v1.1's button never appeared even with every switch
On. v1.2 replaces guessing with proof — and hardens the weak points the
investigation found, on **every** Android brand (nothing in v1.2 is
brand-specific):

- **Diagnostics card on the home screen.** Five live readouts: overlay
  permission, accessibility switch, watcher **CONNECTED / NOT CONNECTED**
  (a different fact from the Settings toggle — some phones show the
  service "On" while it is not actually bound), floating service
  running, and the **last screen the watcher saw** with the detector's
  **MATCH / NO MATCH** verdict and how long ago. A **Copy diagnostics**
  button copies the whole panel to the clipboard.
- **Wider status detector.** v1.1 accepted only WhatsApp's exact
  status-player class names. WhatsApp renames screens between versions;
  v1.2 also matches any WhatsApp window whose class name contains
  "status" plus playback/player/view/story/media — while still
  excluding the About text-status editor and settings/profile screens.
- **Hardened watcher↔button link.** The watcher now records every
  window change in local storage, and the button service re-reads that
  record when it (re)starts: if you are mid-status, the button restores
  itself instead of waiting for a window change that may never come.
  The watcher pushes its state to the service the moment it connects,
  too — the ordering races are covered from both sides.
- **Battery guidance for all phones.** Aggressive battery managers are
  the #1 cause of "the watcher disconnected". The app now says so in
  the Accessibility card, and the per-brand table is in
  [Keep the watcher alive — battery settings per phone brand](#keep-the-watcher-alive--battery-settings-per-phone-brand)
  below.

## What's new in v1.1 (from real-phone testing of v1)

- **Video save bug fixed.** In v1, saved videos never appeared in the
  gallery (they were written under `Pictures/` with an unverified
  MediaStore insert, and the app toasted "Saved ✓" anyway). Videos now go
  to `Movies/StatusSaver`, images to `Pictures/StatusSaver`, and the app
  verifies the save (byte count + MediaStore row) before saying "Saved".
- **Button only during status viewing.** A small accessibility-based
  watcher (`StatusWatcherService`) detects WhatsApp's status player and
  shows/hides the button. It reads window names only — it cannot see
  screen content.
- **Button redesigned.** 56dp WhatsApp-green circle, bottom-right, fixed
  position. No more dragging.
- **Crash-proofing.** A background error could kill the whole app (and the
  button with it) in v1. The save path and service lifecycle are now fully
  guarded and logged.

## What's inside (file tour)

| File | What it does |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | Home screen: the 4-step Allow wizard (step 4 optional) + the returning-user "Button is asleep" banner, floating-button toggle, auto-save switch, Diagnostics card, Recent / My Saved tabs + grids, ad banner |
| `app/src/main/java/.../StatusWatcherService.kt` | Accessibility watcher AND button engine (v1.3): draws the button itself as an accessibility overlay whenever WhatsApp is in the foreground (v1.4: ~0.6 s hide grace — no flicker); a tap saves unconditionally; the status detector drives only the (live-transition-guarded) auto-save; records every window it sees for the Diagnostics card |
| `app/src/main/java/.../BootReceiver.kt` | v1.4: silent post-reboot warm-up — reads the persisted state, nothing user-visible |
| `app/src/main/java/.../Prefs.kt` | Tiny local notebook (SharedPreferences): your button on/off choice, the auto-save switch, the setup-completion latch + step-4 dismissal (v1.4), the watcher's last-seen screen record, the last save attempt, and the last-saved file (auto-save's duplicate guard) |
| `app/src/main/java/.../StatusScanner.kt` | Finds WhatsApp's hidden `.Statuses` folders, lists statuses newest-first |
| `app/src/main/java/.../SaveHelper.kt` | Copies a status into your gallery's ONE StatusSaver album (`Pictures/StatusSaver` for photos and videos since v1.4); waits for the file to finish being written before copying; verified before reporting success; stamps dates + nudges the media scanner so galleries show it at once; records every attempt |
| `app/src/main/java/.../PermissionHelper.kt` | The wizard's system screens: accessibility, file access, battery exemption, autostart deep-links (plus legacy helpers kept for the Diagnostics record) |
| `app/src/main/res/xml/accessibility_service_config.xml` | Watcher config: window-change events only, no screen-content access |
| `app/src/main/AndroidManifest.xml` | The app's ID card: permissions, screens, the watcher service, AdMob app id |
| `app/build.gradle` | Build recipe: app id `com.fahad1005.statussaver`, libraries (incl. AdMob) |
| `.github/workflows/build.yml` | **CI/CD**: every push to `main` auto-builds an APK you can download |
| `PRIVACY_POLICY.md` | Required for the Play Store: we collect nothing |
| `STORE_LISTING.md` | App name, description, screenshot plan, localization notes |
| `UPDATE_INSTRUCTIONS.md` | Step-by-step: how to upload the v1.4 changed files to GitHub and test them |

## How to get these files into YOUR GitHub repo (5 min)

1. Go to github.com/new → repository name **StatusSaver** → Public → Create repository.
2. In your new repo, click **Add file → Upload files**.
3. Open the `statussaver` folder on your computer, select **everything inside it**
   (the `app` folder, the `.github` folder, and all the files), and drag them
   into the GitHub upload area. GitHub keeps the folder structure.
4. Click **Commit changes**.
5. Done. The build starts automatically (see next section).

## How the automatic build works

1. Every time you commit to `main`, GitHub runs the workflow in
   `.github/workflows/build.yml` on a fresh Ubuntu machine.
2. It installs Java 17, the Android SDK, and Gradle 8.7, then builds the app.
3. When it finishes, go to the **Actions** tab → click the latest run →
   **Artifacts** → download **StatusSaver-debug-apk**.
4. Copy the APK to your phone and install it to test.

No Android Studio needed on your PC. The robot builds it for you.

## Before the Play Store — checklist

### 1. Replace the AdMob sample IDs (required)
Right now the app shows Google's **test ads**. Before release:
1. Create your account at **apps.admob.com** (your name, your money).
2. Create an app in AdMob → copy your **App ID** (looks like `ca-app-pub-XXXX~YYYY`).
3. Create a **banner ad unit** → copy its **Ad unit ID** (looks like `ca-app-pub-XXXX/YYYY`).
4. In this repo, replace:
   - `app/src/main/AndroidManifest.xml` → the `APPLICATION_ID` meta-data value
     (marked with `TODO(FAHAD)`).
   - `app/src/main/res/values/strings.xml` → `banner_ad_unit_id`
     (also marked with `TODO(FAHAD)`).

### 2. Play Console account — $25 one-time
Sign up at **play.google.com/console**. Needs your ID and your payment.
Nobody can do this step for you.

### 3. MANAGE_EXTERNAL_STORAGE declaration (read this)
This app requests **All files access** on Android 11+. Google reviews this
manually at publish time. In the Play Console declaration form, explain:

> StatusSaver's core feature is saving WhatsApp status photos and videos to
> the user's gallery. WhatsApp stores viewed statuses in a hidden,
> app-specific directory (`.Statuses`) that is not reachable through the
> MediaStore APIs. Broad file access is required to read these files. The app
> accesses only the WhatsApp status directories and copies the user's chosen
> files into one gallery album, `Pictures/StatusSaver` (photos and videos).
> No files are uploaded, shared, or read beyond these folders.

Tip: record a short demo video of the app working — reviewers love it.

### 4. Privacy policy (required)
`PRIVACY_POLICY.md` is drafted. Host it on any public URL
(e.g. a GitHub Pages site or your own domain) and paste the link in the
Play Console. Replace `[YOUR EMAIL HERE]` with your contact email first.

### 5. App signing
Let **Google Play manage your app signing key** (the default option).
Keep your upload key safe — losing it is painful.

### 6. Accessibility declaration (NEW in v1.1 — read before publishing)
v1.1 uses an Accessibility service so the button can appear only during
status viewing. Google Play tightly controls Accessibility APIs: at
publish time you must submit the Accessibility declaration explaining the
use, and the app's core purpose must justify it. Have this text ready:

> StatusSaver uses the Accessibility API for one purpose only: detecting
> when WhatsApp is in the foreground, so its download button can be
> shown inside WhatsApp (and hidden everywhere else), and detecting
> the status-viewing screen so a tap saves the status being viewed.
> The service observes window-change events (package and activity
> class names). It does not retrieve window content
> (canRetrieveWindowContent is disabled), does not read text, does not
> perform gestures, and collects no data.

Note: there is a real chance Google restricts or rejects Accessibility
use for this category — if that happens, the fallback would be a
manual-toggle build without status detection (the pre-v1.1 design,
recoverable from git history). Decide at publish time with the reviewer
feedback in hand; it does not block sideload testing or development.

### 7. Test on a real phone first
Install the debug APK on your own phone and follow the in-app **Allow
wizard**: Step 1 **Accessibility** (its Allow button opens Android
Settings → Accessibility → **Installed apps** → **StatusSaver smart
button** → turn it ON; Android shows a warning screen first, that's
normal), Step 2 **File access**, Step 3 **Battery** (recommended),
Step 4 **Autostart** (optional — Done/Skip dismisses it).
Then tap **Show floating button**, open WhatsApp: the green button
sits at the bottom-right on every WhatsApp screen, and disappears when
you leave WhatsApp. Open a status and tap it → check the gallery —
ONE album, **StatusSaver** (`Pictures/StatusSaver`, photos and videos
together) — or the app's **My Saved** tab. If the button misbehaves,
check the **Diagnostics card** on the home screen (see "Reading the
Diagnostics card" below) and do your brand's row in the battery table
above it.

## Keep the watcher alive — battery settings per phone brand

The Accessibility watcher (which, since v1.3, is the whole button
engine) must be allowed to run in the **background**. Many phones ship
with aggressive battery managers that disconnect it — the Settings
switch still says "On", but nothing works. This is the most common
cause of "the button never appears", on every brand. Find your brand
and do its row once:

| Phone brand | What to allow |
|---|---|
| **Stock Android / Google Pixel** | Settings → Apps → StatusSaver → Battery → **Unrestricted** |
| **Samsung** | Settings → Battery → Background usage limits → **Never sleeping apps** → add StatusSaver |
| **Xiaomi / Redmi / Poco** | Open StatusSaver's App info → **Autostart ON**; and Battery saver → **No restrictions** |
| **Oppo / Realme / OnePlus** | App info → Battery → allow **Auto-launch** and **background activity** |
| **vivo / iQOO** | Settings → Battery → **High background battery usage** → StatusSaver **ON** |
| **Huawei** | Settings → Battery → App launch → StatusSaver → **Manage manually**, turn all three toggles ON |
| **Infinix / Tecno / Itel (Transsion)** | Phone Master (or Battery settings) → allow StatusSaver **background running + autostart** |

The Diagnostics card tells you if this is your problem: if **Watcher**
 flips to **NOT CONNECTED** after the phone sits idle, the battery
manager disconnected it — redo your brand's row above. Since v1.4 the
home screen also says it plainly: a **"Button is asleep — Wake it"**
banner appears (and only that banner — never the whole setup again).
Wizard **Step 4 (Autostart)** exists for the same reason: on phones
with an autostart switch, it's the difference between the button
returning by itself after a restart and it not returning at all.

## Reading the Diagnostics card (v1.4)

| Row | Healthy | If not |
|---|---|---|
| Overlay permission | anything — labelled *legacy, not required since v1.3* | Ignore this row; the button is an accessibility overlay now |
| Accessibility switch | ON (in Settings) | Settings → Accessibility → Installed apps → StatusSaver → ON |
| Watcher | CONNECTED | Switch says On but the watcher isn't bound — toggle it Off/On, and check the battery table above |
| Button engine | WATCHER · ALIVE | Same fact as the Watcher row: the watcher IS the engine — if it's DEAD, see the row above |
| Button visible now | YES while WhatsApp is on screen, NO everywhere else | If WhatsApp is open and this says NO, the watcher or the toggle is the problem — see the rows above |
| Last seen screen | `com.whatsapp / …Status… (MATCH)` from seconds ago after viewing a status | **NO MATCH** = the row names the exact screen your WhatsApp version uses — that name is the fix (it goes into the detector) |
| Last save attempt | `… · file.mp4 · 3.21 MB · SAVED` | FAILED shows the reason in brackets. If it says SAVED but your Gallery app hides it, check the Files app (Internal storage → Pictures → StatusSaver) — the file is there; it's the gallery's indexing |
| Auto-save | ON or OFF (your choice; default OFF) | If statuses seem to "download by themselves", check this row first — ON means that's the feature working; automatic saves are also marked `(auto)` on the row above |

## How it works (the 30-second version)

1. You view statuses in WhatsApp. WhatsApp quietly caches them in a hidden
   folder on your phone.
2. The watcher notices WhatsApp is open and shows the green button,
   bottom-right, on every WhatsApp screen. Tap it → we wait for the
   **newest** cached status file to finish downloading, copy it into
   your gallery's one **StatusSaver** album (`Pictures/StatusSaver`
   for photos and videos alike) and verify the copy before the
   "Saved ✓" message. (Since v1.4 a tap anywhere in WhatsApp saves —
   the button only exists there, so a tap can only mean "save this".)
3. Leave WhatsApp and the button hides again (after a ~0.6 s grace, so
   swiping between statuses never makes it flicker).
4. If the newest file is older than ~2 minutes, we still save it but tell
   you which file it was (it's a heuristic — see `StatusWatcherService.kt`).
5. Turn on **Auto-save** and step 2 happens by itself: opening a status
   saves it ("Auto-saved ✓"), no tap — never twice for the same file,
   and never from the watcher's restored state after a restart.

## Please respect others
Statuses are someone's personal moments. Save your own, or save with the
sharer's permission.
