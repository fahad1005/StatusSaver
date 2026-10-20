# StatusSaver v1.4.1 — update instructions

**⚠️ This zip SUPERSEDES the v1.4 zip.** v1.4.1 contains everything
v1.4 had, plus two more fixes (below). If you still have
`StatusSaver-v1.4-updated-files.zip`, delete it and use
**`StatusSaver-v1.4.1-updated-files.zip`** only. (If you already
uploaded the v1.4 files, that's fine — uploading this set on top
replaces them; nothing extra to delete.)

**New in v1.4.1** (from your second screen recording):
- **The button now saves the status you're LOOKING at.** Your
  recording caught it saving the *previous* status's video when you
  tapped on a new one — the old rule grabbed "the newest file", and
  WhatsApp doesn't keep the viewed status newest. Now the app
  notes what's in the cache when a status opens and saves the file
  that **appeared or changed for this viewing**.
- **The app icon is fixed.** It showed as a blank white tile in
  your drawer — the icon was a white arrow with no background.
  It's now a white arrow on WhatsApp green, like the button.

v1.4 is the **"one-time means one-time"** update, built from your
full v1.3 phone test (including the screen recording — thank you,
it showed three things screenshots couldn't):

- **Setup never repeats.** Once you've finished setup, the app
  remembers. If your phone later puts the button's engine to sleep,
  you get ONE slim banner — **"Button is asleep — Wake it"** — not
  the whole wizard again. There's also a new optional **Step 4 ·
  Autostart** that helps the button come back by itself after a
  restart, and a silent boot warm-up.
- **A tap always saves now.** The old "smart tap" sometimes refused
  ("some download, some don't") — that check is gone. And saves now
  **wait for the status file to finish downloading** before copying
  (your taps were landing while statuses were still buffering).
- **No more flicker.** Swiping between statuses used to blink the
  button off and on. Hiding now waits for WhatsApp to be really gone
  (~0.6 seconds of proof).
- **ONE gallery album.** Photos and videos now land together in the
  **StatusSaver** album (`Pictures/StatusSaver`) — no more videos
  hiding in a separate Movies album. Your older saves still show in
  My Saved.
- **Auto-save can't fire by itself after a restart** — it only saves
  on statuses you actually open, and it says **"Auto-saved ✓"** when
  it does. Diagnostics now shows an `Auto-save: ON/OFF` row too.

Same code works on **all** Android phones — nothing brand-specific.

The zip `StatusSaver-v1.4.1-updated-files.zip` contains ONLY the files
that changed, in their proper folders. `build.yml` is NOT touched.
**No file needs deleting this time** — the NEW files
(`BootReceiver.kt` and the launcher-icon files) are added
automatically by the upload.

## Step 1 — Unzip
1. Find `StatusSaver-v1.4.1-updated-files.zip` on your PC.
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
   `StatusSaver-v1.4.1-updated-files/` in front of a path, you dragged
   the wrapper again — remove it and drag the inside instead.
4. GitHub keeps the folder structure — files with the same name/path
   **replace** the old ones, and `BootReceiver.kt` is added new.
   Nothing else in your repo is touched.
5. **Commit changes.**

## Step 3 — Wait for the green build
1. Click the **Actions** tab.
2. The newest "Build APK" run (from your commit) is the one that
   matters. Wait ~2–3 minutes.
3. You want the **green check mark**. (If it's a red X: open the run,
   click the red step **"Build the app (debug APK)"**, scroll to the
   lines starting with `e:` at the end of the log, and send Bullet a
   photo — exactly like the v1.3 fix.)

## Step 4 — Download + install the new APK
1. Click the finished run → scroll to **Artifacts** → download
   **StatusSaver-debug-apk**.
2. Unzip it, send `app-debug.apk` to your phone (Google Drive, not
   Gmail — Gmail blocks APKs), and install it.
3. **You can install v1.4.1 OVER the version on your phone** — your settings (setup,
   button on/off, auto-save choice) carry over. If the phone refuses
   ("App not installed"), uninstall the old StatusSaver first, then
   install; you'll just do the one-time setup again. (Normal: Play
   Protect may warn because the app uses Accessibility — it's your
   own app; proceed.)

## Step 5 — The v1.4.1 test
Do it in this order — each step tests one fix:

1. **Setup state.** Open StatusSaver. Because v1.3's setup carries
   over, you should NOT get the fresh wizard — expect **"You're all
   set ✓"** (or, if the watcher happens to be asleep right now, the
   slim **"Button is asleep"** banner — tap **Wake it**, flip
   "StatusSaver smart button" OFF→ON, come back; the banner should
   clear). If the optional **Step 4 · Autostart** card is showing,
   tap **Open autostart settings** and turn StatusSaver's autostart
   ON if your phone offers it (vivo: it's in the list the button
   opens); or tap **Done / Skip** — it won't ask again either way.
2. **Toggle.** Make sure the big button says **Hide floating button**
   (that means your choice is ON).
3. **THE REBOOT TEST (the headline fix).** **Restart your phone.**
   When it's back, **do NOT open StatusSaver**. Open **WhatsApp
   directly** — the green button must be there, on the chat list,
   without you opening or allowing anything. (If it's not: open
   StatusSaver ONCE and check Diagnostics — if it says
   `Watcher: NOT CONNECTED`, your phone's autostart/battery settings
   blocked the return; do Step 4's autostart + your brand's battery
   row in the README, then repeat the reboot test.)
4. **Recents test.** Swipe StatusSaver away from Recents (open it
   first if needed), then open WhatsApp — button still there.
5. **Save FIVE mixed statuses — all five must save.** In WhatsApp,
   open in turn: a photo status, a short video, a **long video**,
   a text/status-card post, and one more photo or video. For each:
   **wait until it has fully loaded** (no spinner), then tap the
   green button → **"Saved to gallery ✓"** each time. Bonus check:
   swipe quickly through several statuses — the button must stay
   **steady**, not blink.
5b. **THE WRONG-FILE CHECK (new in v1.4.1 — the failure your
   recording caught).** Open **status A** (any status), go back to
   the status list, then open a DIFFERENT **status B** and tap the
   green button while B is on screen. The save must be **B** —
   check the toast and the newest item in My Saved. (Before this
   fix, that tap re-saved A.)
6. **ONE album.** Open your Gallery: there should be ONE
   **StatusSaver** album, and ALL five saves inside it — photos and
   videos together. Also check the app's **My Saved** tab — the five
   new saves plus your older ones (from the v1.3 days) should all be
   listed; tap one → View / Share / Delete.
7. **Auto-save honesty check.** Back in StatusSaver, turn ON
   **"Auto-save every status I view"**. Go to WhatsApp's **chat list
   first**, then open a NEW status and just watch it — after a second
   or two: **"Auto-saved ✓"**. Open the SAME status again — no second
   copy. Then turn auto-save OFF again if you prefer tapping.
8. **Diagnostics.** Screenshot the Diagnostics card and send it to
   Bullet. Healthy looks like:
   `Watcher: CONNECTED` · `Button engine: WATCHER · ALIVE` ·
   `Last save attempt: … · SAVED` · `Auto-save: OFF` (or ON, your
   choice).

## Step 6 — If something's off

| Symptom | Meaning | Fix |
|---|---|---|
| Button missing after reboot | The phone blocked the watcher's return | In StatusSaver: if the **"Button is asleep"** banner shows, tap **Wake it** (OFF→ON). Then do **Step 4 · Autostart** + your brand's battery row (README) and repeat the reboot test |
| "Button is asleep" banner | The watcher got disconnected (battery manager) — this banner IS the fix working: one tap instead of full setup | Tap **Wake it** → flip "StatusSaver smart button" OFF→ON → return. If it keeps happening, battery row (README) |
| Full wizard shows again | Should NOT happen after setup completed once | Screenshot the whole home screen + Diagnostics and send to Bullet — that's a v1.4 bug to fix |
| A save says "Save failed" on a long video | The video was still downloading when tapped, even after the wait | Let the status finish loading (no spinner), tap again. If it still fails, send the Diagnostics screenshot — the Last save row says why |
| Saved ✓ but the album looks empty | Gallery indexing lag | Check the Files app → Pictures → StatusSaver; the files are there. Send Bullet the Diagnostics screenshot — the Last save row proves the save |
| Button blinks while swiping statuses | Should be fixed by the ~0.6 s grace | Screen-record it and send to Bullet — the recording is what found it last time |

## What changed (for your records)
- **Right file (v1.4.1):** watcher saves (tap + auto-save) now use
  session targeting — the cache is snapshotted when a status opens,
  and the save takes the file that appeared/changed for THIS
  viewing (fallback: newest, with the toast naming it). No more
  re-saving the previous status.
- **Icon (v1.4.1):** proper launcher icon — white download arrow
  on WhatsApp green (adaptive icon on Android 8+, layered icon
  below). No more blank white tile.
- **Setup:** completion is now remembered forever; sleeping watcher
  = one "Wake it" banner; optional Autostart step; silent boot
  warm-up (new `BootReceiver`).
- **Tap:** no more matcher gate — a tap in WhatsApp always saves the
  newest status; saves wait for the file to finish writing first
  (~5 s budget for videos, ~3 s for images) and still verify.
- **Button:** no flicker — hiding needs ~0.6 s of WhatsApp really
  being gone; showing is instant as before.
- **Gallery:** ONE album — `Pictures/StatusSaver` for photos AND
  videos. (v1.0–v1.3 videos sit in `Movies/StatusSaver`; My Saved
  still lists them.)
- **Auto-save:** fires only on statuses you actually open after the
  engine is awake — never from restored state — and says
  "Auto-saved ✓". Diagnostics shows `Auto-save: ON/OFF`.
- Version is now **1.4.1 (versionCode 6)** — full engineering detail is
  in `BUILD_NOTES.md`.
