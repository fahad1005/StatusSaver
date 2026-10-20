# StatusSaver v1.1 — update instructions

This update fixes the video-save bug, stops the button from disappearing,
and makes the button appear **only while you watch a WhatsApp status**
(green, bottom-right, medium size).

The zip `StatusSaver-v1.1-updated-files.zip` contains ONLY the files that
changed, in their proper folders. Follow these steps exactly.

## Step 1 — Unzip
1. Find `StatusSaver-v1.1-updated-files.zip` on your PC.
2. Right-click it → **Extract All** → Extract.
3. You now have a folder with an `app` folder and some `.md` files inside.

## Step 2 — Upload the changed files to GitHub
1. Open your repo: **github.com/fahad1005/StatusSaver**
2. Click **Add file → Upload files**.
3. From the extracted folder, drag in:
   - the whole **`app`** folder, AND
   - **`README.md`**, **`BUILD_NOTES.md`**, **`UPDATE_INSTRUCTIONS.md`**
4. GitHub keeps the folder structure — files with the same name/path
   **replace** the old ones. Nothing else in your repo is touched.
   (Do NOT delete anything first.)
5. At the bottom, click **Commit changes**.

> If dragging the folder doesn't work in your browser, do it file by
> file instead: open the matching folder in the repo first (for example
> `app/src/main/java/com/fahad1005/statussaver`), then **Add file →
> Upload files** and drop that folder's files there. Repeat for each
> folder. The important thing is every file lands at its exact path.

## Step 3 — Wait for the green build
1. Click the **Actions** tab.
2. A new "Build APK" run starts automatically. Wait ~2–3 minutes.
3. You want the **green check mark**. (A red X means something's wrong —
   send Bullet a photo of the failed step.)

## Step 4 — Download + install the new APK
1. Click the finished run → scroll to **Artifacts** → download
   **StatusSaver-debug-apk**.
2. Unzip it, send `app-debug.apk` to your phone, and install it.
3. **If the phone refuses to install over the old app** ("App not
   installed"): uninstall the old StatusSaver first, then install the
   new one. (The test-build signature can change between builds — this
   is normal for debug builds. You'll just grant the permissions again.)

## Step 5 — One NEW permission (Accessibility)
v1.1 has a 4th permission card in the app. Do this once:
1. Open **StatusSaver** → card **"4. Accessibility — smart button"** →
   tap **Open Accessibility settings**.
2. In Android Settings go to **Accessibility → Installed apps →
   StatusSaver**.
3. Turn it **ON**. Android shows a warning screen first — tap **Allow**.
   (StatusSaver only detects *which screen is open*. It cannot read your
   messages or anything on the screen — that's blocked in its config.)
4. Back in StatusSaver, all 4 cards should now be gone. Tap
   **Show floating button** if the service isn't already running.

## Step 6 — Test it (the v1.1 checklist)
1. Open WhatsApp → open a **status** → the **green button** appears at
   the **bottom-right**. ✅
2. Go back to the WhatsApp chat list (or open any other app) → the
   button is **gone**. ✅
3. Open an **image status** → tap the green button → "Saved to gallery ✓"
   → find it in your gallery under **Pictures → StatusSaver**. ✅
4. Open a **video status** → tap the green button → find the video in
   your gallery under **Movies → StatusSaver**. ✅ (This was the v1 bug.)
5. If a save ever fails now, the message says **"Save failed"** instead
   of pretending — tell Bullet exactly what you tapped when it happens.

## What changed (for your records)
- **Fixed:** videos now actually save (Movies/StatusSaver, verified copy).
- **Fixed:** the button/service can no longer crash silently.
- **New:** button shows only during WhatsApp status viewing
  (Accessibility watcher), never over other apps.
- **New look:** 56dp WhatsApp-green circle, bottom-right, no dragging.
- Version is now **1.1 (versionCode 2)** — full file list is in
  `BUILD_NOTES.md`.
