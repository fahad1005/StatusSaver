# StatusSaver v1.2 — update instructions

v1.2 is a **diagnostic release**: it finds out WHY the floating button
didn't appear on your phone in v1.1. It adds a **Diagnostics card** to
the home screen, a wider status detector, and a hardened connection
between the watcher and the button. Same code works on **all** Android
phones — nothing is vivo-only.

The zip `StatusSaver-v1.2-updated-files.zip` contains ONLY the files
that changed, in their proper folders. `build.yml` is NOT touched —
the builder recipe didn't change.

## Step 1 — Unzip
1. Find `StatusSaver-v1.2-updated-files.zip` on your PC.
2. Right-click it → **Extract All** → Extract.
3. You now have a folder containing an `app` folder and some `.md` files.

## Step 2 — Upload the changed files to GitHub
1. Open your repo: **github.com/fahad1005/StatusSaver**
2. Click **Add file → Upload files**.
3. **Open the extracted folder and drag in its CONTENTS** — the whole
   **`app`** folder, plus **`README.md`**, **`BUILD_NOTES.md`** and
   **`UPDATE_INSTRUCTIONS.md`**.
   ⚠️ Drag the *contents*, not the wrapper folder — remember the v1.1
   trap: **check before committing that every path starts with
   `/app/...`** (or is one of the three `.md` files). If you see
   `StatusSaver-v1.2-updated-files/` in front of a path, you dragged
   the wrapper again — remove it and drag the inside instead.
4. GitHub keeps the folder structure — files with the same name/path
   **replace** the old ones; the one new file (`Prefs.kt`) lands in its
   folder automatically. Nothing else in your repo is touched.
   (Do NOT delete anything first.)
5. At the bottom, click **Commit changes**.

## Step 3 — Wait for the green build
1. Click the **Actions** tab.
2. A new "Build APK" run starts automatically. Wait ~2–3 minutes.
3. You want the **green check mark**. (A red X means the build failed —
   open the run, click the red step, and send Bullet a photo of the
   error at the end of the log.)

## Step 4 — Download + install the new APK
1. Click the finished run → scroll to **Artifacts** → download
   **StatusSaver-debug-apk**.
2. Unzip it, send `app-debug.apk` to your phone (Google Drive, not
   Gmail — Gmail blocks APKs), and install it.
3. **If the phone refuses to install over the old app** ("App not
   installed"): uninstall the old StatusSaver first, then install the
   new one. (The test-build signature can change between builds — this
   is normal. You'll just grant the permissions again. Also normal:
   Play Protect may warn again because the app uses Accessibility —
   it's your own app; proceed.)

## Step 5 — Set up (once)
1. Open **StatusSaver** and grant the 4 permission cards in order:
   overlay → files → notifications → **Accessibility**
   (Settings → Accessibility → **Installed apps / Downloaded apps** →
   **StatusSaver smart button** → ON → Allow).
2. Also allow **Display over other apps** for StatusSaver if asked,
   and do your phone's battery step — **every brand hides this
   differently** (full table in README, "Keep the watcher alive"):
   - vivo: Battery → **High background battery usage** → ON ✔ (you did this)
   - Pixel: App Battery → **Unrestricted**
   - Samsung: Battery → **Never sleeping apps** → add StatusSaver
   - Xiaomi/Redmi/Poco: **Autostart ON** + battery **No restrictions**
   - Oppo/Realme/OnePlus: allow **Auto-launch** + background activity
   - Huawei: App launch → manage manually → all toggles ON
   - Infinix/Tecno: Phone Master → allow background + autostart
3. Back in StatusSaver, tap **Show floating button**.

## Step 6 — The test (2 minutes — this is what v1.2 is FOR)
1. In StatusSaver, look at the **Diagnostics** card (below the
   Show/Hide button). It should already say:
   `Watcher: CONNECTED` and `Floating service: RUNNING`.
2. Open **WhatsApp → view any status for about 5 seconds**.
   (The green button should appear bottom-right — if it does, we're done!)
3. Come back to **StatusSaver** (the card refreshes itself).
4. **Screenshot the Diagnostics card and send it to Bullet.**
   — or tap **Copy diagnostics** and paste the text instead.

## Step 7 — How Bullet reads your screenshot

| Card says | Meaning | Fix |
|---|---|---|
| `Watcher: NOT CONNECTED` (but switch ON) | Phone shows the service On but never actually connected it — common with aggressive battery managers | Toggle Accessibility Off → On; redo Step 5's battery row for your brand |
| `Last seen screen: com.whatsapp / … (NO MATCH)` | The watcher IS working, but your WhatsApp version names its status screen differently | None for you — the screenshot names the exact screen; Bullet adds it to the detector (one line) |
| `Floating service: NOT RUNNING` | The button service got killed | Tap **Show floating button**; redo the battery row — the phone is killing it |
| `Last seen screen: … (MATCH)` + everything RUNNING/CONNECTED | The whole chain works | If the button *still* didn't show, that's a new clue — report exactly that |

## What changed (for your records)
- **New:** Diagnostics card (5 readouts + Copy button) — proof instead of guessing.
- **Fixed (detector):** wider status-screen matching for all WhatsApp versions.
- **Fixed (connection):** watcher and button now re-sync from a saved
  record whenever either restarts — no more "stuck hidden" after the
  phone kills and restarts a service.
- **New:** your button ON/OFF choice is remembered across restarts.
- Version is now **1.2 (versionCode 3)** — full engineering detail is
  in `BUILD_NOTES.md`.
