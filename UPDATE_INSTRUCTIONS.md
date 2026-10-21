# StatusSaver v1.6 — update instructions

**⚠️ This zip SUPERSEDES the v1.5 zip.** If you still have
`StatusSaver-v1.5-updated-files.zip`, delete it and use
**`StatusSaver-v1.6-updated-files.zip`** only. (Uploading this set
on top of what's in the repo replaces those files; nothing extra
to delete — v1.6 adds no new files.)

**New in v1.6** (your OPTION 1 pick + the front-door fix, from
the v1.5 test):

- **The button now lives ONLY inside a status.** In v1.5 it sat
  on the chat list and inside chats too — tappable in places
  where no status was on screen, and able to blink on screens it
  had no business on. Now: **no status open → no button at all.**
  It appears within a second of opening a status, stays steady
  while you swipe between statuses, and goes away ~1 second
  after you back out. A tap can only ever mean "save THIS" —
  ghost taps are impossible, because outside a status there is
  nothing to tap.
- **Setup is now front-door first.** The wizard's **Step 1 is
  File access** — the only permission the app itself needs. With
  just that one step, the statuses grid and My Saved work fully
  (that's the two-click setup the big apps use). Everything the
  button needs moved into an **optional "Power-up" section**
  under it — Accessibility, Battery, Autostart — with a **Skip
  for now** button. Skip it and it never asks again; the "Show
  floating button" toggle brings it back whenever you want it.
- Everything that already worked is untouched: the right-file
  targeting, save-once, the honest toasts, the one album, the
  fixed icon.

**New in v1.5** (kept in full): a status can only be saved ONCE
(a repeat tap answers **"Already saved ✓"** and copies nothing;
deleting it in My Saved makes it saveable again) · toasts
replace each other instantly instead of queueing up and landing
late · the button rides out ~1.2 s of interruptions (recorder
popups and the like) before hiding.

**New in v1.4.1** (kept in full): the button saves the status
you're LOOKING at (the cache is noted when a status opens; the
save takes the file that appeared/changed for this viewing) ·
proper app icon (white arrow on WhatsApp green) · setup is
remembered forever — a sleeping watcher gets ONE slim "Button
is asleep — Wake it" banner, never the whole wizard again ·
saves wait for the status file to finish downloading before
copying · ONE gallery album, **StatusSaver**
(`Pictures/StatusSaver`), photos and videos together.

Same code works on **all** Android phones — nothing
brand-specific.

The zip `StatusSaver-v1.6-updated-files.zip` contains ONLY the
files that changed, in their proper folders. `build.yml` is NOT
touched. **No file needs deleting this time** — v1.6 changes
existing files only.

## Step 1 — Unzip
1. Find `StatusSaver-v1.6-updated-files.zip` on your PC.
2. Right-click it → **Extract All** → Extract.
3. You now have a folder containing an `app` folder and two
   `.md` files (`BUILD_NOTES.md`, `UPDATE_INSTRUCTIONS.md`).

## Step 2 — Upload the changed files to GitHub
1. Open your repo: **github.com/fahad1005/StatusSaver**
2. Click **Add file → Upload files**.
3. **Open the extracted folder and drag in its CONTENTS** — the
   whole **`app`** folder, plus **`BUILD_NOTES.md`** and
   **`UPDATE_INSTRUCTIONS.md`**.
   ⚠️ Drag the *contents*, not the wrapper folder — the old trap:
   **check before committing that every path starts with
   `/app/...`** (or is one of the two `.md` files). If you see
   `StatusSaver-v1.6-updated-files/` in front of a path, you
   dragged the wrapper again — remove it and drag the inside
   instead.
4. GitHub keeps the folder structure — files with the same
   name/path **replace** the old ones. Nothing else in your repo
   is touched.
5. **Commit changes.**

## Step 3 — Wait for the green build
1. Click the **Actions** tab.
2. The newest "Build APK" run (from your commit) is the one that
   matters. Wait ~2–3 minutes.
3. You want the **green check mark**. (If it's a red X: open the
   run, click the red step **"Build the app (debug APK)"**,
   scroll to the lines starting with `e:` at the end of the log,
   and send Bullet a photo — exactly like the v1.3 fix.)

## Step 4 — Download + install the new APK
1. Click the finished run → scroll to **Artifacts** → download
   **StatusSaver-debug-apk**.
2. Unzip it, send `app-debug.apk` to your phone (Google Drive,
   not Gmail — Gmail blocks APKs), and install it.
3. **You can install v1.6 OVER the version on your phone** — your
   settings (setup, button on/off, auto-save choice) carry over.
   If the phone refuses ("App not installed"), uninstall the old
   StatusSaver first, then install; you'll just do the one-time
   setup again. (Normal: Play Protect may warn because the app
   uses Accessibility — it's your own app; proceed.)

## Step 5 — The v1.6 test
Do it in this order — each step tests one fix:

1. **Setup state.** Open StatusSaver. Your v1.5 setup carries
   over, so expect **"You're all set ✓"** — NOT a fresh wizard.
   (If the **"Button is asleep"** banner shows instead, the
   watcher is asleep right now: tap **Wake it**, flip
   "StatusSaver smart button" OFF→ON, come back; the banner
   should clear.)
2. **Toggle.** Make sure the big button says **Hide floating
   button** (that means your choice is ON).
3. **THE REBOOT TEST (revised for v1.6).** **Restart your
   phone.** When it's back, **do NOT open StatusSaver**. Open
   **WhatsApp directly**:
   - the chat list must show **NO green button** — that's new
     and CORRECT now (before v1.6 it sat there);
   - **open any status** — the button must appear within about
     a second, without you opening or allowing anything.
   (If it doesn't appear in the status: open StatusSaver ONCE
   and check Diagnostics — `Watcher: NOT CONNECTED` means the
   phone blocked the watcher's return; do the Power-up's
   Autostart step + your brand's battery row in the README,
   then repeat the reboot test.)
3b. **THE BUTTON MAP CHECK (new in v1.6 — the headline fix).**
   In WhatsApp, visit each place and check the button:
   - **Chats list** → button **ABSENT**
   - **Updates / Status tab** (the list of contacts' statuses)
     → button **ABSENT**
   - **Open a status** → button **PRESENT** within ~a second
   - **Swipe through several statuses** → button **STAYS**,
     steady, no blinking
   - **Back out to the status list** → button **GONE** within
     ~1–2 seconds
   All five must be true. This is OPTION 1 working as designed.
4. **Recents test.** Swipe StatusSaver away from Recents (open
   it first if needed), then open WhatsApp and open a status —
   button appears as in 3b.
5. **Save FIVE mixed statuses — all five must save.** In
   WhatsApp, open in turn: a photo status, a short video, a
   **long video**, a text/status-card post, and one more photo
   or video. For each: **wait until it has fully loaded** (no
   spinner), then tap the green button → **"Saved to gallery
   ✓"** each time.
5b. **THE WRONG-FILE CHECK (from v1.4.1).** Open **status A**,
   go back to the status list, then open a DIFFERENT **status
   B** and tap the green button while B is on screen. The save
   must be **B** — check the toast and the newest item in My
   Saved.
5c. **THE SECOND-TAP CHECK (from v1.5).** Stay on a status you
   have just saved and tap the green button **again, on the
   SAME status**. The toast must say **"Already saved ✓"** —
   and the StatusSaver album (and My Saved) must NOT gain a
   second copy. Note the album count before and after: it must
   not grow.
6. **ONE album.** Open your Gallery: there should be ONE
   **StatusSaver** album, and ALL five saves inside it — photos
   and videos together. Also check the app's **My Saved** tab —
   the five new saves plus your older ones should all be
   listed; tap one → View / Share / Delete.
7. **Auto-save honesty check.** Back in StatusSaver, turn ON
   **"Auto-save every status I view"**. Go to WhatsApp's **chat
   list first**, then open a NEW status and just watch it —
   after a second or two: **"Auto-saved ✓"**. Open the SAME
   status again — no second copy. Then turn auto-save OFF
   again if you prefer tapping.
8. **Diagnostics.** Screenshot the Diagnostics card and send it
   to Bullet — its header now says **v1.6**. Healthy looks
   like: `Watcher: CONNECTED` · `Button engine: WATCHER ·
   ALIVE` · `Last save attempt: … · SAVED` · `Auto-save: OFF`
   (or ON, your choice).

## Step 6 — The fresh-install check (second phone, or after an uninstall)
This tests the new front door — do it on the second phone if
you have StatusSaver there, or skip until you next install
fresh:

9. **Grid only, NO button setup.** Install the APK fresh. Open
   StatusSaver and do **ONLY Step 1 (File access)** — do NOT
   touch the Power-up section. The statuses grid below must
   load, and tapping **Save** on a grid item must save it.
   The app is now fully working with one permission — like
   the big apps.
10. **Skip stays skipped.** In the **Power-up** section, tap
    **Skip for now**. Close the app completely and reopen it:
    the Power-up section must **NOT** come back, and "You're
    all set ✓" should show.
11. **The way back works.** Tap **Show floating button** — the
    Power-up section must reappear. Complete its Accessibility
    step, then open WhatsApp and open a status: the green
    button is there. Setup done — this phone now behaves
    exactly like your main one.

## Step 7 — If something's off

| Symptom | Meaning | Fix |
|---|---|---|
| Green button visible on the chat list or Updates tab | Should NOT happen in v1.6 — the button exists only inside an open status | Screenshot it + send the Diagnostics card — that's a v1.6 bug |
| Button never appears inside a status | The watcher is asleep, OR WhatsApp's viewer screen name changed and the detector missed it | Check Diagnostics: `Watcher: NOT CONNECTED` → Wake it (banner) / redo the Power-up's Accessibility step. If CONNECTED, send the Diagnostics screenshot — the **Last seen screen** row shows the exact screen name + MATCH/NO MATCH, which is what a fix needs. The in-app grid keeps saving meanwhile |
| Button missing after reboot (inside a status) | The phone blocked the watcher's return | If the **"Button is asleep"** banner shows, tap **Wake it** (OFF→ON). Then do the Power-up's **Autostart** step + your brand's battery row (README) and repeat the reboot test |
| Power-up section keeps reappearing after Skip | Should NOT happen — Skip is remembered | Screenshot the home screen + say exactly what you tapped before it came back, and send to Bullet |
| Full wizard shows again as fresh setup | Should NOT happen after setup completed once | Screenshot the whole home screen + Diagnostics and send to Bullet — that's a bug to fix |
| A save says "Save failed" on a long video | The video was still downloading when tapped, even after the wait | Let the status finish loading (no spinner), tap again. If it still fails, send the Diagnostics screenshot — the Last save row says why |
| Saved ✓ but the album looks empty | Gallery indexing lag | Check the Files app → Pictures → StatusSaver; the files are there. Send Bullet the Diagnostics screenshot — the Last save row proves the save |
| Second tap on the same status saves a duplicate | Should NOT happen — the second tap must say "Already saved ✓" | Send the Diagnostics screenshot — the Last save row should read ALREADY SAVED; if it says SAVED twice for the same file, that's a bug |

## What changed (for your records)
- **Button placement (v1.6):** the button exists only during a
  status-viewing session — it opens when a status opens, rides
  out ~1.2 s of interruptions (swipes, popups, recorder
  overlays), and closes ~1.2 s after you leave the status. No
  button in chats or lists means no accidental taps and no
  saves with no status behind them — the v1.5 chat-tap gate is
  gone because the situation it guarded can't occur.
- **Setup order (v1.6):** Step 1 is File access (the core —
  grid + My Saved work with it alone). Accessibility, Battery
  and Autostart are now the optional **Power-up** section for
  the button, with a persistent **Skip for now**; the "Show
  floating button" toggle reopens it. "Setup complete" is now
  remembered from the file-access step; a sleeping watcher
  still gets the one "Wake it" banner, never a fresh wizard.
- **Save once (v1.5):** every save path checks one shared
  saved-list first — a status already in your gallery answers
  "Already saved ✓" and never copies again. Deleting it in
  My Saved makes it saveable again.
- **Honest toasts (v1.5):** a new toast replaces the previous
  one instead of queueing behind it — no more stale "Saved ✓"
  messages landing during later statuses.
- **Right file (v1.4.1):** watcher saves (tap + auto-save) use
  session targeting — the cache is snapshotted when a status
  opens, and the save takes the file that appeared/changed for
  THIS viewing (fallback: newest, with the toast naming it).
- **Icon (v1.4.1):** proper launcher icon — white download
  arrow on WhatsApp green.
- **Gallery:** ONE album — `Pictures/StatusSaver` for photos
  AND videos. (v1.0–v1.3 videos sit in `Movies/StatusSaver`;
  My Saved still lists them.)
- **Auto-save:** fires only on statuses you actually open
  after the engine is awake — never from restored state — and
  says "Auto-saved ✓". Diagnostics shows `Auto-save: ON/OFF`.
- Version is now **1.6 (versionCode 8)** — full engineering
  detail is in `BUILD_NOTES.md`.
