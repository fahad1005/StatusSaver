# StatusSaver — v1

**The status downloader you never have to leave WhatsApp for.**
A small floating **download button** hovers over WhatsApp while you view a
status. One tap — it's saved to your gallery. No switching apps.

## What's inside (file tour)

| File | What it does |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | Home screen: 3 permission cards, floating-button toggle, status grid, ad banner |
| `app/src/main/java/.../FloatingService.kt` | The floating button: tap = save newest status, long-press + drag = move it |
| `app/src/main/java/.../StatusScanner.kt` | Finds WhatsApp's hidden `.Statuses` folders, lists statuses newest-first |
| `app/src/main/java/.../SaveHelper.kt` | Copies a status into your gallery under `Pictures/StatusSaver` |
| `app/src/main/java/.../PermissionHelper.kt` | The 3 permission screens: overlay → file access → notifications |
| `app/src/main/AndroidManifest.xml` | The app's ID card: permissions, screens, AdMob app id |
| `app/build.gradle` | Build recipe: app id `com.fahad1005.statussaver`, libraries (incl. AdMob) |
| `.github/workflows/build.yml` | **CI/CD**: every push to `main` auto-builds an APK you can download |
| `PRIVACY_POLICY.md` | Required for the Play Store: we collect nothing |
| `STORE_LISTING.md` | App name, description, screenshot plan, localization notes |

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
> files into `Pictures/StatusSaver`. No files are uploaded, shared, or read
> beyond these folders.

Tip: record a short demo video of the app working — reviewers love it.

### 4. Privacy policy (required)
`PRIVACY_POLICY.md` is drafted. Host it on any public URL
(e.g. a GitHub Pages site or your own domain) and paste the link in the
Play Console. Replace `[YOUR EMAIL HERE]` with your contact email first.

### 5. App signing
Let **Google Play manage your app signing key** (the default option).
Keep your upload key safe — losing it is painful.

### 6. Test on a real phone first
Install the debug APK on your own phone. Grant the 3 permissions, view a
WhatsApp status, tap the floating button. If it saves to your gallery,
you're ready for the release build.

## How it works (the 30-second version)

1. You view statuses in WhatsApp. WhatsApp quietly caches them in a hidden
   folder on your phone.
2. Our floating button hovers on screen. Tap it → we copy the **newest**
   cached file into `Pictures/StatusSaver` in your gallery.
3. If the newest file is older than ~2 minutes, we still save it but tell
   you which file it was (it's a heuristic — see `FloatingService.kt`).

## Please respect others
Statuses are someone's personal moments. Save your own, or save with the
sharer's permission.
