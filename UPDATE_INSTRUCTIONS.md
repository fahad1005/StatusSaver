# StatusSaver v1.3 — update instructions

v1.3 is the **architecture fix** plus Fahad's final-spec additions:

- The floating button now lives **inside the accessibility watcher** —
  the one part of the app Android itself keeps alive and revives. The
  old separate button service (the thing that kept dying on your vivo)
  is **deleted**.
- The button now appears on **every WhatsApp screen**, not just the
  status screen — but tapping it outside a status just explains
  "Open a status first" (smart tap).
- First launch is a **one-time Allow wizard**: 3 steps, one big Allow
  button each, no hunting through Settings.
- New **My Saved** tab (view / share / delete what you saved) and an
  **Auto-save** switch (statuses you view save themselves).
- Saves are stamped + index-nudged so the Gallery shows them at once.

Same code works on **all** Android phones — nothing is brand-specific.

The zip `StatusSaver-v1.3-updated-files.zip` contains ONLY the files
that changed, in their proper folders. `build.yml` is NOT touched —
the builder recipe didn't change. One file must be **deleted** by hand
(Step 3) — a zip can't carry a deletion.

## Step 1 — Unzip
1. Find `StatusSaver-v1.3-updated-files.zip` on your PC.
2. Right-click it → **Extract All** → Extract.
3. You now have a folder containing an `app` folder and some `.md` files.

## Step 2 — Upload the changed files to GitHub
1. Open your repo: **github.com/fahad1005/StatusSaver**
2. Click **Add file → Upload files**.
3. **Open the extracted folder and drag in its CONTENTS** — the whole
   **`app`** folder, plus **`README.md`**, **`BUILD_NOTES.md`**,
   **`UPDATE_INSTRUCTIONS.md`**, **`PRIVACY_POLICY.md`** and
   **`STORE_LISTING.md`**.
   ⚠️ Drag the *contents*, not the wrapper folder — the old trap:
   **check before committing that every path starts with `/app/...`**
   (or is one of the five `.md` files). If you see
   `StatusSaver-v1.3-updated-files/` in front of a path, you dragged
   the wrapper again — remove it and drag the inside instead.
4. GitHub keeps the folder structure — files with the same name/path
   **replace** the old ones. Nothing else in your repo is touched.
5. **Do NOT commit yet** — one deletion first (Step 3).

## Step 3 — Delete the retired FloatingService.kt on GitHub
The button's old engine must go, or the build will fail (the app no
longer knows this file).

1. In your repo, open this folder path:
   **app → src → main → java → com → fahad1005 → statussaver**
2. Click **`FloatingService.kt`** to open the file page.
3. Click the **⋯ (three dots)** button near the top-right of the file
   page → **Delete file**.
4. Scroll down and click **Commit changes**.

(If you already committed the Step 2 uploads first, that's fine —
just do this deletion as its own commit. Every commit triggers a
build; the build will only go green AFTER this deletion lands.)

## Step 4 — Wait for the green build
1. Click the **Actions** tab.
2. The newest "Build APK" run (from your last commit) is the one that
   matters. Wait ~2–3 minutes.
3. You want the **green check mark**. (A red X usually means the
   deletion in Step 3 is missing — or open the run, click the red
   step, and send Bullet a photo of the error at the end of the log.)

## Step 5 — Download + install the new APK
1. Click the finished run → scroll to **Artifacts** → download
   **StatusSaver-debug-apk**.
2. Unzip it, send `app-debug.apk` to your phone (Google Drive, not
   Gmail — Gmail blocks APKs), and install it.
3. **If the phone refuses to install over the old app** ("App not
   installed"): uninstall the old StatusSaver first, then install the
   new one. (The test-build signature can change between builds — this
   is normal. You'll just do the one-time setup again. Also normal:
   Play Protect may warn because the app uses Accessibility — it's
   your own app; proceed.)

## Step 6 — The new test (fresh-install walk-through)
Do it in this order — it tests every v1.3 promise:

1. **Wizard.** Open StatusSaver. You should see the **Quick setup**
   card with 3 steps:
   - **Step 1 · Accessibility** → tap **Allow** → in the list tap
     **StatusSaver smart button** → switch **ON** → come back.
     The step should collapse to "✓ Accessibility is on".
   - **Step 2 · File access** → tap **Allow** → switch
     "Allow access to manage all files" **ON** → come back.
     It collapses to "✓ File access is on".
   - **Step 3 · Battery** → tap **Allow** → choose **Allow** in
     Android's dialog. It collapses to "✓ Battery is OK".
   - The wizard should now be one row: **"You're all set ✓"**.
2. **Toggle.** Tap **Show floating button**.
3. **Button everywhere in WhatsApp.** Open WhatsApp — the green
   button should be there **even on the chat list**. Tap it there:
   it should say *"Open a status first, then tap the green button."*
4. **Save.** Open any status → tap the green button → **"Saved to
   gallery ✓"**. Check your **Gallery** app (StatusSaver album) AND
   the app's **My Saved** tab — the status should be in both. In My
   Saved, tap it: **View / Share / Delete** should all work.
5. **Auto-save (optional test).** Back in StatusSaver, turn ON
   **"Auto-save every status I view"**. Open a NEW status in
   WhatsApp and just watch it — after a second or two it should save
   itself ("Saved to gallery ✓"). Open the same status again — it
   must NOT save a second copy.
6. **THE PERMANENCE TEST (the v1.2 killer).** Swipe StatusSaver away
   from **Recents** (the way that killed the old button). Open
   WhatsApp again — **the green button must STILL be there**, on the
   chat list and in statuses. Leave the phone idle a few minutes and
   check again.
7. **Diagnostics.** Back in StatusSaver, **screenshot the Diagnostics
   card** and send it to Bullet (or tap **Copy diagnostics** and
   paste the text). Healthy looks like:
   `Watcher: CONNECTED` · `Button engine: WATCHER · ALIVE` ·
   `Last save attempt: … · SAVED`.

## Step 7 — If something's off

| Symptom | Meaning | Fix |
|---|---|---|
| Wizard Step 1 won't collapse | The watcher hasn't connected yet (bind lag — or the phone shows the switch On without binding) | Wait 2 seconds on the home screen; if still stuck, toggle Accessibility Off → On, and do your brand's battery row (README) |
| Build red X | Almost always the missing deletion | Redo Step 3, wait for the new run |
| Button missing after Recents swipe | The watcher got disconnected by the battery manager | Check Diagnostics: `Watcher: NOT CONNECTED` → redo Step 1 + your brand's battery row (README table) |
| "Saved ✓" but not in Gallery | Gallery indexing lag | Check the Files app → Pictures/Movies → StatusSaver; the file is there. Send Bullet the Diagnostics screenshot — the Last save row proves the save |
| Tap in a status says "Open a status first" | Your WhatsApp version names its status screen in a way the detector doesn't know yet | Send the Diagnostics screenshot — "Last seen screen" names the exact screen; it's a one-line fix |

## What changed (for your records)
- **Deleted:** `FloatingService` (the killable button service) — the
  watcher owns the button now, as an accessibility overlay.
- **Visibility:** button on every WhatsApp screen (was: status only).
  The status detector still runs — it drives the smart tap + auto-save.
- **New:** one-time Allow wizard (3 steps), My Saved tab
  (view/share/delete), Auto-save switch (default OFF).
- **Fixed:** saves carry proper dates + a media-scanner nudge, so the
  Gallery shows the StatusSaver albums immediately.
- Version is now **1.3 (versionCode 4)** — full engineering detail is
  in `BUILD_NOTES.md`.
