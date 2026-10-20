# StatusSaver — v1.2

**The status downloader you never have to leave WhatsApp for.**
A small floating **download button** appears over WhatsApp **only while you
are viewing a status**. One tap — it's saved to your gallery. When you leave
the status, the button disappears. No switching apps, no button floating
over your other apps.

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
| `app/src/main/java/.../MainActivity.kt` | Home screen: 4 permission cards, floating-button toggle, Diagnostics card (v1.2), status grid, ad banner |
| `app/src/main/java/.../FloatingService.kt` | The floating button service: shows/hides the button on command, tap = save newest status; on (re)start it restores visibility from the watcher's persisted record |
| `app/src/main/java/.../StatusWatcherService.kt` | Accessibility watcher: shows the button only while a WhatsApp status is playing; records every window it sees for the Diagnostics card |
| `app/src/main/java/.../Prefs.kt` | Tiny local notebook (SharedPreferences): your button on/off choice + the watcher's last-seen screen record |
| `app/src/main/java/.../StatusScanner.kt` | Finds WhatsApp's hidden `.Statuses` folders, lists statuses newest-first |
| `app/src/main/java/.../SaveHelper.kt` | Copies a status into your gallery: images → `Pictures/StatusSaver`, videos → `Movies/StatusSaver`, verified before reporting success |
| `app/src/main/java/.../PermissionHelper.kt` | The 4 permission screens: overlay → file access → notifications → accessibility |
| `app/src/main/res/xml/accessibility_service_config.xml` | Watcher config: window-change events only, no screen-content access |
| `app/src/main/AndroidManifest.xml` | The app's ID card: permissions, screens, services, AdMob app id |
| `app/build.gradle` | Build recipe: app id `com.fahad1005.statussaver`, libraries (incl. AdMob) |
| `.github/workflows/build.yml` | **CI/CD**: every push to `main` auto-builds an APK you can download |
| `PRIVACY_POLICY.md` | Required for the Play Store: we collect nothing |
| `STORE_LISTING.md` | App name, description, screenshot plan, localization notes |
| `UPDATE_INSTRUCTIONS.md` | Step-by-step: how to upload the v1.2 changed files to GitHub and test them |

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
> files into `Pictures/StatusSaver` (photos) and `Movies/StatusSaver`
> (videos). No files are uploaded, shared, or read beyond these folders.

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
> when WhatsApp's status-viewing screen is in the foreground, so its
> download button is shown only at that moment and hidden everywhere
> else. The service observes window-change events (package and activity
> class names). It does not retrieve window content
> (canRetrieveWindowContent is disabled), does not read text, does not
> perform gestures, and collects no data.

Note: there is a real chance Google restricts or rejects Accessibility
use for this category — if that happens, the fallback is the v1 behaviour
(button toggleable manually from the persistent notification). Decide at
publish time with the reviewer feedback in hand; it does not block
sideload testing or development.

### 7. Test on a real phone first
Install the debug APK on your own phone. Grant the 4 permissions — the new
one is **Accessibility**: in the app tap card 4 → Android Settings →
Accessibility → **Installed apps** → **StatusSaver** → turn it ON (Android
shows a warning screen first; that's normal). Then view a WhatsApp status:
the green button should appear at the bottom-right, and disappear when
you leave the status. Tap it → check the gallery (photos in
`Pictures/StatusSaver`, videos in `Movies/StatusSaver`). If the button
misbehaves, check the **Diagnostics card** on the home screen (see
"Reading the Diagnostics card" below) and do your brand's row in the
battery table above it.

## Keep the watcher alive — battery settings per phone brand

The Accessibility watcher and the floating-button service must be allowed
to run in the **background**. Many phones ship with aggressive battery
managers that disconnect them — the Settings switch still says "On",
but nothing works. This is the most common cause of "the button never
appears", on every brand. Find your brand and do its row once:

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
manager disconnected it — redo your brand's row above.

## Reading the Diagnostics card (v1.2)

| Row | Healthy | If not |
|---|---|---|
| Overlay permission | ON | Grant card 1 (Display over other apps) |
| Accessibility switch | ON (in Settings) | Settings → Accessibility → Installed apps → StatusSaver → ON |
| Watcher | CONNECTED | Switch says On but the service isn't bound — toggle it Off/On, and check the battery table above |
| Floating service | RUNNING | Tap "Show floating button" on the home screen |
| Last seen screen | `com.whatsapp / …Status… (MATCH)` from seconds ago after viewing a status | **NO MATCH** = the row names the exact screen your WhatsApp version uses — that name is the fix (it goes into the detector) |

## How it works (the 30-second version)

1. You view statuses in WhatsApp. WhatsApp quietly caches them in a hidden
   folder on your phone.
2. The watcher notices WhatsApp's status player is on screen and shows
   the green button, bottom-right. Tap it → we copy the **newest** cached
   file into your gallery (`Pictures/StatusSaver` for photos,
   `Movies/StatusSaver` for videos) and verify the copy before the
   "Saved ✓" message.
3. Leave the status and the button hides again.
4. If the newest file is older than ~2 minutes, we still save it but tell
   you which file it was (it's a heuristic — see `FloatingService.kt`).

## Please respect others
Statuses are someone's personal moments. Save your own, or save with the
sharer's permission.
