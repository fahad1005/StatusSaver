# StatusSaver — build notes (v1, 2026-10-19; v1.1–v1.5 updates 2026-10-20)

Engineering log for the v1 build.

## v1.5 (2026-10-20) — dedupe everywhere, honest toasts, a tap gate with a memory

Built on the v1.4.1 disk state. Every change traces to the
v1.4.1 phone test — Fahad's screen recordings, his Diagnostics
paste (watcher CONNECTED, auto-save OFF, last save 11:51 PM
`ce802871…jpg` · SAVED), and a code read of the save paths.

### Field evidence
- **THE SAME STATUS SAVED TWICE.** The Diagnostics paste records
  `ce802871a94f4bbcacadb457a6ed8a9d.jpg` SAVED at 11:51 PM; the
  next recording shows the same file saved AGAIN ~12:02 AM by an
  ordinary manual tap. Code read: the only duplicate guard watched
  the single most recent save and was consulted **only in
  autoSaveNow** — manual taps (and grid saves) had no dedupe at
  all.
- **TOASTS THAT LOOK LIKE GHOST SAVES.** In the recordings,
  "Saved ✓" toasts surface during statuses where no save was
  tapped. Android toasts QUEUE: each save's toast waits its turn,
  so feedback for an earlier save lands a minute later, on a
  different screen, indistinguishable from the app saving by
  itself.
- **A CHAT TAP SAVED AN 11-MINUTE-OLD IMAGE.** v1.4's ungated tap
  fires anywhere in WhatsApp: a tap inside a chat, with no status
  viewed, resolved the session-target fallback (newest cache file)
  and saved a status cached 11 minutes earlier.
- **FOREIGN WINDOWS STEAL THE FOREGROUND.** The Diagnostics
  "Last seen screen" shows non-WhatsApp windows (e.g. the vivo
  screen recorder's own overlay) taking foreground events
  mid-viewing — the 600 ms hide grace was too short to ride them
  out, so the button could still drop during a viewing.

### FIX 1 — global dedupe at the choke point (`SaveHelper`, `Prefs`)
Dedupe moved INTO `SaveHelper.saveToGallery` — the one function
every save flows through (button tap, auto-save, in-app grid).
`Prefs` now keeps a persisted SET of saved identities,
`name|size`, capped at the most recent 300 (FIFO trim), serialized
as "name|size" lines (content-hash filenames make the separators
collision-free, same as the session snapshot).

Flow: stability wait first (identity size = the SETTLED size, not
a mid-download length) → empty-file check → set lookup → copy +
verify as before. A hit returns without copying.

`saveToGallery` now returns a sealed `SaveResult` —
`Saved` / `AlreadySaved` / `Failed(reason)` — and ALL three
callers handle all three cases:
- watcher tap: Failed → "Save failed"; AlreadySaved → new string
  "Already saved ✓"; Saved → the existing saved/age-naming toasts.
- watcher auto-save: toast only on Saved (an already-saved skip is
  silent, as failures already were); the old last-saved pre-check
  stays as a cheap scan-avoidance layer in front.
- grid: Saved → "Saved to gallery ✓"; AlreadySaved → "Already
  saved ✓"; Failed → "Save failed".

Every attempt is still recorded in the Diagnostics LastSave
record; an already-saved attempt is recorded with ok = false (this
attempt copied nothing) and reason **"already saved"** (auto path:
"auto: already saved"); the Diagnostics card renders those as
**ALREADY SAVED**, distinct from both SAVED and FAILED.

Deleting from My Saved forgets the identity: `MainActivity`'s
delete path (direct delete AND the consent-flow launcher, whose
target item is now remembered) calls
`Prefs.forgetSavedIdentity(name, size)` on success — `SavedItem`
gained the MediaStore SIZE for exactly this — so a deleted status
can be saved again.

### FIX 2 — one reusable watcher toast (`StatusWatcherService`)
The watcher keeps a single `Toast` instance; `toastOnUi` cancels
the previous toast before showing the next. Feedback can never be
stale: at most one watcher toast is alive and it belongs to the
latest event. MainActivity's one-off UI toasts are untouched (they
answer direct taps in the app's own UI, where queueing reads
correctly).

### FIX 3 — tap session gate (`StatusWatcherService`)
The button stays visible on every WhatsApp screen (unchanged);
what changed is what a tap MEANS before any status was viewed. A
new in-service flag, `statusViewedThisStint`:
- set TRUE on the live false→true viewer-entry transition (the
  same transition that snapshots the session and arms auto-save);
- set FALSE when `whatsappOnScreen` flips true→false (WhatsApp
  left the foreground — the next stint starts unproven), and at
  every (re)bind/unbind/destroy, so restored state can never open
  the gate.
Tap with the flag FALSE → the existing "Open a status first"
toast, nothing saved. Flag TRUE → save via `resolveSessionTarget`
exactly as v1.4.1, so a tap in a chat right AFTER viewing still
saves the viewed status — intended.

This is deliberately NOT v1.3's matcher gate: that judged the
LAST window event and refused real statuses when the class name
missed. This gate is a session fact — once a viewer entry happened
in this stint, the gate stays open for the stint regardless of
what later events say.

### FIX 4 — hide grace 600 → 1200 ms
`HIDE_GRACE_MS` 600 → 1200, per the foreign-window evidence above.
Trade-off, stated honestly (also in the code comment): after the
user really leaves WhatsApp, the button may linger ~1.2 s over
the next app before coming down.

### Honest limits
- **Targeting is still a heuristic for videos.** Session diffing
  picks what WhatsApp changed since viewer entry; if a video is
  still being written while an image prefetch completes, the image
  can still win the diff and the tap saves the image, not the
  video. v1.5's dedupe + gate remove the HARMFUL failures
  (duplicates, stale-target chat saves, phantom feedback) — they
  cannot create targeting evidence WhatsApp never wrote. The
  size-stability wait still gives the video its ~5 s to finish
  before the copy.
- The dedupe set remembers 300 identities; a status saved more
  than 300 saves ago can be saved a second time (its first copy is
  still in the album, and deleting it there forgets the identity
  anyway).
- Identity is name + size: if WhatsApp re-writes a cache file
  under the same content-hash name with different bytes, that is a
  NEW identity and will save — by design.
- The tap gate trusts the same class-name matcher as auto-save
  for "viewer entered". If a future WhatsApp renames its viewer
  past the matcher, taps in a first status would answer "Open a
  status first" until the matcher learns the new name — the
  Diagnostics Last-seen row shows the exact class, as before.

### Files changed in v1.5
- `SaveHelper.kt` — `SaveResult` sealed type; dedupe check inside
  `saveToGallery` (after the stability wait); identity recorded on
  verified saves; already-saved attempts recorded with reason
  "already saved".
- `Prefs.kt` — saved-identities set (`isAlreadySaved`,
  `recordSavedIdentity`, `forgetSavedIdentity`; cap 300, FIFO).
- `StatusWatcherService.kt` — `statusViewedThisStint` gate
  (set/reset + `onButtonTap` consults it); single reusable toast
  (cancel-before-show); both save callers handle `SaveResult`;
  `HIDE_GRACE_MS` 1200.
- `MainActivity.kt` — grid save handles `SaveResult`;
  Diagnostics renders ALREADY SAVED + header label v1.5;
  `SavedItem` carries SIZE; delete paths (direct + consent)
  forget the dedupe identity on success.
- `strings.xml` — NEW `already_saved` ("Already saved ✓");
  Diagnostics title → v1.5.
- `app/build.gradle` — versionCode 7 / versionName "1.5".
- `BUILD_NOTES.md`, `UPDATE_INSTRUCTIONS.md` — this section and
  the v1.5 ritual (new checks: re-tap must answer "Already saved
  ✓" with no album growth; a chat tap BEFORE any status must
  answer "Open a status first").

### Residual risks (v1.5)
- The video-targeting heuristic above is the known remaining
  weakness; the v1.5 phone test's long-video step is its judge.
- A fully-pre-cached re-view (no diff) still falls back to newest
  overall — unchanged from v1.4.1, with the age-naming toast.
- Compilation remains GitHub Actions' verdict (no compiler here);
  static self-review: zero `!!`, all R.string/R.id references
  resolve, all XML parses, braces/parens/brackets balance in every
  Kotlin file, and a grep confirms all three `saveToGallery`
  callers use the new result type.

## v1.4.1 (2026-10-20) — consolidation: save the status you're LOOKING at

v1.4.1 is built ON TOP of the (never-uploaded) v1.4 state and
SUPERSEDES the v1.4 zip — only the v1.4.1 zip ships. Everything in
the v1.4 section below still applies unchanged; this section adds
two fixes from a second batch of screen recordings.

### Field evidence (recordings, v1.3 on Fahad's vivo)
- **WRONG-FILE SAVE, filmed.** Fahad taps the button while viewing
  a gem status (Gulbacha's) and the app saves — again — the PREVIOUS
  status's video (the Riaz Town truck .mp4 he'd already saved). The
  toast names the same wrong file twice. Root cause: the tap saved
  "the newest file in .Statuses" **by mtime**, and WhatsApp does NOT
  reliably make the currently-viewed status the newest. It
  pre-caches while the status LIST is scrolled and finishes
  downloads on its own schedule, so a previously-viewed file can
  carry the freshest mtime while the current status's file was
  written earlier (or is still arriving).
- **BLANK LAUNCHER ICON.** The app drawer shows StatusSaver as a
  blank white tile. Root cause is in the resources (below).

### FIX — session targeting for watcher saves
**Mechanism** (`StatusWatcherService`, `StatusScanner`, `Prefs`):
when the watcher ENTERS the status viewer — the live false→true
matcher transition, the same one that arms auto-save — it snapshots
the whole .Statuses cache (`StatusScanner.snapshot()`:
filename → (size, mtime)) and persists it in Prefs
(`Prefs.saveSessionSnapshot`, serialized "name|size|mtime" lines;
WhatsApp cache names are content hashes, so the separators can't
collide). The scan runs on a background thread; the snapshot has
the whole viewing to land before any save diffs against it.

On a watcher-initiated save (button tap OR auto-save —
`resolveSessionTarget()`), candidates = the files that are **NEW
since the snapshot or whose (size or mtime) CHANGED** since it
(`StatusScanner.changedSince`):
- **exactly one candidate** → save it;
- **several** → the one with the **latest mtime among the
  candidates** (the current status is what WhatsApp fetched most
  recently; anything else touched in the same window was
  pre-fetch, written earlier);
- **none** → **fall back to newest overall** (the pre-v1.4.1
  heuristic). This is the re-viewing case: the status was already
  fully cached, WhatsApp wrote nothing, and there is no diff
  evidence to do better with — the age-disclosure toast then names
  the saved file, as it has since v1.1.

The in-app grid is untouched: its Save buttons name their file
explicitly and never needed targeting. The Bug-2 size-stability
wait, read-back verification, LastSave recording and the v1.4
auto-save session-arming are all unchanged — the snapshot is taken
on the same live transition that arms, and restored state still
never arms or fires anything by itself.

**Honest limits.** If WhatsApp pre-cached several statuses during
list scrolling BEFORE the viewer was entered, those files are in
the snapshot, unchanged — indistinguishable from old cache — and
if the viewed status is one of them and nothing else changes, the
fallback (newest overall) decides, with the same wrong-file risk
as before. Session targeting removes the filmed failure mode
(previous status re-written/re-touched after entry); it cannot
create evidence WhatsApp never wrote. The tiebreak among changed
files is latest-mtime, which is a heuristic too — just one scoped
to "what changed during THIS viewing" instead of the whole cache.

### FIX — launcher icon (resources only)
**Root cause, verified on disk.** The manifest declared
`android:icon`/`android:roundIcon` = `@drawable/ic_launcher_foreground`
— a 108dp vector containing ONLY a white-stroked download arrow
(`strokeColor="#FFFFFF"`) over **transparency**. There was no
adaptive icon anywhere (no `mipmap-anydpi*` directory existed), and
no background layer at all (colors.xml still carries the template's
orphaned `ic_launcher_background` teal, referenced by nothing). On
a light launcher, white-on-transparent renders as a blank white
tile — exactly Fahad's drawer.

**Fix:** the brand mark, both eras of Android icon:
- `drawable/ic_launcher_background.xml` (NEW): solid WhatsApp
  green field, #25D366 — the app's own green (`whatsapp_green`,
  the floating button's color).
- `mipmap-anydpi-v26/ic_launcher.xml` +
  `ic_launcher_round.xml` (NEW): adaptive icons = that background
  + the existing arrow foreground (the arrow already sits inside
  the safe zone, x36–72 / y28–78 of the 108 canvas).
- `mipmap-anydpi/ic_launcher.xml` + `ic_launcher_round.xml` (NEW):
  pre-Android-8 layer-list fallback with the same two layers.
- Manifest now points at `@mipmap/ic_launcher` /
  `@mipmap/ic_launcher_round`. No code touched.

### Files changed in v1.4.1 (on top of the v1.4 set)
- `StatusWatcherService.kt` — snapshot on status entry
  (`takeSessionSnapshot`); `resolveSessionTarget()`; tap and
  auto-save both save the session target.
- `StatusScanner.kt` — `snapshot()` + `changedSince()` added;
  `newest()` demoted to documented fallback.
- `Prefs.kt` — session snapshot persistence.
- `MainActivity.kt`, `strings.xml` — Diagnostics labels say v1.4.1.
- `AndroidManifest.xml` — icon attributes → `@mipmap/ic_launcher`.
- `drawable/ic_launcher_background.xml`, `mipmap-anydpi*/ic_launcher*.xml`
  — NEW icon resources; `ic_launcher_foreground.xml` comment only.
- `app/build.gradle` — versionCode 6 / versionName "1.4.1".
- `BUILD_NOTES.md`, `UPDATE_INSTRUCTIONS.md`, `README.md` — this
  section, the superseding instructions, the version header.

### Residual risks (v1.4.1)
- The fallback case above (fully-pre-cached status, silent cache)
  keeps the old heuristic's risk; watch the A/B test in
  UPDATE_INSTRUCTIONS step 5 — it exercises entry-diffing, the
  filmed failure mode.
- Snapshot persistence is a single baseline: two viewers in two
  WhatsApp windows (split-screen) would share it. WhatsApp's status
  viewer is full-screen in practice; accepted.
- Adaptive-icon rendering is the launcher's job; the vivo drawer is
  the field test. The mark is high-contrast by construction (white
  on #25D366), so a mask surprise can change the SHAPE, not the
  legibility.

## v1.4 (2026-10-20) — field-test fixes: one-time means one-time

### Field evidence (v1.3 on Fahad's vivo, evening test #2)
The v1.3 architecture works — his Diagnostics screenshot shows
`Button engine: WATCHER · ALIVE` and a verified 2.98 MB video save.
His verdict was still "not working properly", in his own words:

1. *"Every time I allow then button appear… every time I have to
   start and allow it. It's annoying."*
2. *"I click download but some image and videos downloads and some
   don't download."*
3. *"Some are download itself."*
4. (From his 154-second screen recording, frame-sampled) The button
   **flickers**: present on one status, absent on the next several
   (video, text, video), present again — inside WhatsApp, watcher
   connected, the whole time.
5. (Same recording) Taps land while statuses are still **buffering**
   (spinner visible) — the file being copied is still being written.
6. (Same recording) It ends in his Gallery: the only StatusSaver
   album he can find holds **3 images**. The videos are in
   `Movies/StatusSaver`, an album vivo's Gallery buries. To the user:
   "videos don't save."
7. (Same recording) **Accessibility was found switched OFF when the
   recording started** — the phone (or a cleaner) had flipped it, and
   he had to re-enable it before anything could work.

He also benchmarked the category leader ("Status Saver", 10M+
downloads): ONE storage-allow popup, no button, no accessibility —
its core never depends on a live service. Our edge remains the
button; v1.4 is about making the button deserve to exist.

### A plain statement about #7 (Accessibility switched off)
**There is no code-level way to re-enable an accessibility service.**
Android reserves that switch to the user, deliberately — any app that
could flip it programmatically would be malware-shaped. Anyone who
promises otherwise is lying. So the fix for #7 is UX, not code:
- the failure must be **obvious and cheap to repair**: the v1.4
  "Button is asleep — Wake it" banner (below) is one tap from the
  exact settings screen, with instructions that cover the
  shows-ON-but-asleep case (OFF→ON is the wake);
- the occasions should be **rarer**: wizard step 3 (battery
  exemption), the new optional step 4 (autostart deep-link), and the
  per-brand battery table in the README all exist to keep OEM
  managers from disconnecting the watcher in the first place.
That is the whole fix, stated plainly so nobody re-files it as a bug
that "just needs a code change".

### BUG 1 — "Every time I have to start and allow it"
Root cause: v1.3 kept no memory of setup ever having been completed.
`refreshWizard()` derived everything live: watcher momentarily not
connected (vivo asleep) ⇒ step 1 "not done" ⇒ the whole wizard
un-collapsed — the user experienced a sleeping watcher as a full
re-setup demand. Fixes:
- **(a) Setup-completion latch (Prefs.setupComplete).** Set the first
  time the wizard observes steps 1+2 done. From then on,
  `refreshWizard()` runs in returning-user mode: the wizard NEVER
  re-expands. Watcher not connected ⇒ one slim banner ("Button is
  asleep" + **Wake it** → `ACTION_ACCESSIBILITY_SETTINGS`). Files
  permission genuinely revoked ⇒ only the step-2 repair card. All
  healthy ⇒ the single "You're all set ✓" row. Steps 1/3 cannot
  reappear.
- **(b) Toggle truthfulness — verified, no change needed.**
  `Prefs.setButtonEnabled` has exactly two call sites, both inside
  the toggle's own click handler. Nothing in onCreate, onResume, or
  any upgrade path writes it; `updateToggleLabel()` re-reads Prefs on
  every `onResume` (plus the 1.2 s delayed refresh). The v1.2 disease
  (label and engine disagreeing) stays cured.
- **(c) BootReceiver + RECEIVE_BOOT_COMPLETED (new).** On
  BOOT_COMPLETED it runs in-process, reads back the persisted state
  (logs it), and nudges `StatusWatcherService.reevaluate()` if the
  watcher is already bound. No notification, no UI, no service start.
  Honest framing: the REAL post-reboot mechanism is the system
  re-binding the enabled accessibility service; the receiver exists
  so the process and its state are warm and consistent, per spec.
- **(d) Optional wizard Step 4 — Autostart (never a gate).**
  `PermissionHelper.requestAutostartSettings` tries, in order, the
  MIUI permcenter autostart activity, vivo's permission-manager
  background-start activity, and the ColorOS/Oplus safeguard startup
  lists, then falls back to app-details. Settings deep-links only —
  opening one changes nothing by itself, and NO app logic branches on
  brand. Done-check: none (there is no API to read another app's
  switch); the user's **Done / Skip** persists
  `Prefs.wizardStep4Dismissed` and the step never shows again.
- **(e) Layer-1 stays instant — verified.** `onResume` calls
  `loadCurrentTab()` unconditionally; the wizard/banner are passive
  views in the same scroll column and never gate browsing or grid
  saves. (Without file access the grid still can't list statuses —
  that's the permission's nature, not a wizard block.)

### BUG 2 — "Some download, some don't" (button tap)
Two compounding causes, both removed:
- **(a) The smart-tap MATCH gate is GONE for manual taps.** v1.3
  refused to save unless the class matcher currently said MATCH;
  matcher misses (a renamed viewer class, a last event that was a
  transient window) turned real statuses into "Open a status first"
  refusals. Now: foreground package is WhatsApp (incl. the BUG-4
  grace window) ⇒ `saveNewestStatus()` runs, always. The matcher
  survives ONLY as the auto-save trigger, where a miss costs nothing.
  The age-naming toast still discloses when the newest file is old.
- **(b) File-readiness wait (SaveHelper.awaitStableFile).** Before
  copying, poll the target's length every 300 ms; proceed when
  unchanged across 2 consecutive polls; budget ~5 s for videos /
  ~3 s for images (his recording shows taps on still-buffering
  statuses — WhatsApp was still writing those files, so copies were
  truncated and failed verification, or worse, "saved" short).
  After the budget, copy what exists; the existing byte-count +
  MediaStore read-back verification still decides success, and a
  0-byte target now fails honestly with the reason recorded
  ("Status file is empty — WhatsApp may still be downloading it")
  instead of being "saved" as an empty shell. It's inside
  `saveToGallery`, so grid saves and auto-saves get it too. All save
  call sites already run on background threads, so the wait never
  blocks a UI.
- **(c) Target selection — deliberately unchanged.** Window events
  carry no media-type information, so "newest matching current
  screen type" is not knowable; newest overall + the stability wait
  is the fix. (BUG 5 below removes the other half of the "videos
  don't save" impression.)

### BUG 3 — "Some download by themselves"
Even with auto-save ON by choice, it could fire without a live
"entry": the process dies mid-status, the system rebinds the
watcher, `onServiceConnected` restores `statusOnScreen=true` from
the persisted record — and the first live event (or a NO MATCH→MATCH
flip against the restored baseline) could read as a fresh transition
and arm the debounce. v1.4 adds an explicit session guard:
`autoSaveArmedThisSession` starts FALSE at every (re)bind and turns
TRUE only when a LIVE event in this session shows a WhatsApp screen
that is NOT the status viewer. `armAutoSave()` refuses while
unarmed. Restored state still restores the BUTTON (visibility is not
a save), but it can never arm or fire a save: the user must be seen,
live, somewhere else in WhatsApp first. Labels: automatic saves now
toast **"Auto-saved ✓"** (manual stays "Saved to gallery ✓"), and
Diagnostics gains an **Auto-save: ON/OFF** row so "it downloads by
itself" is checkable in one screenshot. Default remains OFF.

### BUG 4 — Button flicker across status swipes
`updateButton()` applied the visibility rule instantly on every
event, and during WhatsApp-internal transitions (status swipes,
dialogs) the foreground package flickers through foreign values
(system windows, the launcher) for split seconds — each flip was a
real detach, the next a real re-attach: the button blinked.
Fix — hysteresis in `updateButton()`:
- SHOW: immediate, and idempotent/self-verifying as before
  (`attachButton` re-adds only if the view is actually missing;
  never detach+reattach on an unchanged state).
- HIDE: a non-WhatsApp foreground must persist ~600 ms
  (`HIDE_GRACE_MS`, a posted runnable that re-derives at fire time)
  before the detach applies. A WhatsApp event cancels the pending
  hide. The user's toggle-OFF still hides instantly.
- Applied changes only are logged (`Button decision: SHOW/HIDE`), so
  the log and Diagnostics' "Button visible now" tell one story.
Known trade-off, accepted: after genuinely leaving WhatsApp, the
button can linger up to ~0.6 s over the next app while the grace
runs out.

### BUG 5 — "Videos don't save" = the album split (v1.1 decision reversed)
v1.1 moved videos to `Movies/StatusSaver` because gallery VIDEO
VIEWS didn't surface videos parked under Pictures/. Field evidence
now says the split is the bigger evil: users look for the ALBUM, and
vivo's Gallery buries the Movies one — one album with 3 images reads
as "videos don't save". The category leader uses one album.
v1.4 unifies: the MediaStore COLLECTION stays type-correct (Video
collection + video MIME for videos), but `RELATIVE_PATH` is
`Pictures/StatusSaver` for everything — one album, "StatusSaver",
mixed content. My Saved reads `Pictures/StatusSaver/` for both
collections AND `Movies/StatusSaver/` for videos, so v1.0–v1.3 saves
keep showing. The pre-Q legacy path already wrote everything to
`Pictures/StatusSaver`; unchanged. (The v1.1 concern is noted for
the record: if a specific gallery's "Videos" view hides the unified
album's videos, the Files app still shows them, and My Saved lists
them regardless — the album-first behaviour is what users asked
for, matching the market leader.)

### Files changed in v1.4
Changed: `app/build.gradle` (versionCode 5 / versionName "1.4"),
`app/src/main/AndroidManifest.xml` (RECEIVE_BOOT_COMPLETED +
BootReceiver), `.../StatusWatcherService.kt` (ungated tap, session
arm guard, "Auto-saved ✓", hide-grace hysteresis + decision logs),
`.../MainActivity.kt` (setup latch + returning-user mode + asleep
banner, step-4 wiring, Diagnostics Auto-save row, My Saved dual-path
read), `.../Prefs.kt` (setupComplete + step4Dismissed),
`.../SaveHelper.kt` (stability wait, empty-target honesty, one
album), `.../PermissionHelper.kt` (autostart deep-links),
`app/src/main/res/layout/activity_main.xml` (banner, step 4,
Diagnostics row), `app/src/main/res/values/strings.xml` (step
renumbering, banner/step-4/auto-save copy, Diagnostics v1.4 header),
`README.md`, `BUILD_NOTES.md`, `UPDATE_INSTRUCTIONS.md`,
`PRIVACY_POLICY.md`, `STORE_LISTING.md` (one-album wording).
Added: `.../BootReceiver.kt`.
Not changed: `StatusScanner.kt`, `accessibility_service_config.xml`,
drawables, colors — and NO brand-specific behaviour anywhere (the
autostart step is a settings deep-link chain, like the per-brand
battery table in the README: guidance, not logic).

### Residual risks (v1.4)
- **The 600 ms grace cuts both ways.** A genuinely-departed
  WhatsApp leaves the button up to ~0.6 s over the next screen; a
  foreign window lasting LONGER than 600 ms over a status (a system
  permission dialog) will still hide the button until the next
  WhatsApp event re-shows it (which happens on dialog dismissal).
- **Stability wait vs. very slow connections.** A huge video on a
  slow network can still be growing after ~5 s; we copy what exists
  and verification may fail with an honest toast — tap again once
  the status has fully loaded. Raising the budget delays every save;
  5 s is the chosen balance.
- **Auto-save's first status after a rebind never auto-saves**
  (session guard) — by design (BUG 3), but a user testing exactly
  that scenario may read it as "auto-save is broken". The test in
  UPDATE_INSTRUCTIONS walks chat-list → status for this reason.
- **Autostart activity names drift.** OEMs rename settings
  activities between OS versions; a missed name just falls back to
  the app-details page (two taps more), never a crash or a dead end.
- Force-stop remains final (unchanged from v1.3): it unbinds
  accessibility until the user re-enables it — see "A plain
  statement about #7" above.

## v1.3 (2026-10-20) — architecture fix: the watcher owns the button

### Field evidence (v1.2 on Fahad's vivo phone, evening test)
v1.2's diagnostics split the case cleanly:
- **Saves WORK.** The Files app shows the StatusSaver album with 3 items
  (1 jpg 81 KB, 2 mp4 2.08 MB + 3.21 MB, today's timestamps) — the
  verified save path is proven. BUT his **Gallery app does not surface
  them** → a gallery-visibility problem, not a save problem.
- **The button worked, then died permanently.** At ~6:26 PM the button
  appeared in a status and a video saved. Later screenshots:
  MainActivity toggle intent ON ("Hide floating button"), Diagnostics
  reading `Floating service: NOT RUNNING`, `Watcher: CONNECTED`, button
  never appearing again.

### Root cause (the one v1.2's design couldn't survive)
The process was killed (vivo battery manager / recents). Android
revived the **accessibility watcher** — the system keeps enabled
accessibility services bound — but did **NOT** revive the START_STICKY
**FloatingService**: OEMs routinely suppress sticky-service restarts,
and even on stock Android sticky restart is a favour, not a contract.
Nothing noticed, nothing healed: statics reset, the toggle label
(intent, from the activity) and the service flag (a different
lifecycle) disagreed forever. Any architecture where the button's
owner can die separately from the component the system guarantees to
revive will eventually strand the button exactly like this.

### The fix — one engine
- **`StatusWatcherService` owns the button.** It creates the button
  view itself (identical design: 56dp, #25D366 `floating_bg`, white
  `ic_download`, BOTTOM|END 16dp/110dp, `FLAG_NOT_FOCUSABLE`) as a
  `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY` window — a
  type reserved for accessibility services, needing **no
  SYSTEM_ALERT_WINDOW permission**. Rule (as first built): attached ⇔
  persisted user choice ON **and** current screen MATCHES the status
  detector (matcher, exclusions and the persisted last-seen record
  unchanged from v1.2). **Superseded the same day by the final spec —
  see "Final-spec completion" below: visibility is now package-level
  (WhatsApp in the foreground), and the detector drives the tap and
  auto-save instead.** Show/hide is **addView/removeView** (not
  visibility): hidden means *no window exists* — nothing drawn,
  nothing touchable — and "visible now" has one unambiguous answer
  for diagnostics.
- **Tap = save newest status**, moved verbatim from FloatingService
  (StatusScanner.newest + SaveHelper.saveToGallery, age-naming toast
  and all). SaveHelper is untouched in behaviour, so the button path
  and the in-app grid path stay identical.
- **`FloatingService` is DELETED** (class + manifest entry). The
  persistent notification goes with it — intended: the in-app toggle +
  the watcher are now the whole engine; there is nothing left that
  needs a "running" notice. `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS` are
  removed from the manifest (checked: nothing else used them — no
  other service, no notifications; AdMob needs none of them).
  `SYSTEM_ALERT_WINDOW` stays *declared* but unused: the Diagnostics
  card reports it as "legacy — not required since v1.3". (Play review
  may prefer dropping it entirely — a one-line manifest change if a
  reviewer asks; keeping it costs nothing at runtime.)
- **Toggle = persisted choice + nudge.** MainActivity's toggle flips
  `Prefs.buttonEnabled` and calls the static
  `StatusWatcherService.reevaluate()` hook (posts `updateButton()` on
  the watcher's main handler). The label reads the SAME Prefs record —
  one source of truth, so label and engine can no longer disagree.
  If the watcher is **not connected**, enabling is refused honestly
  with "Turn on Accessibility first" and nothing is recorded — no
  more ON labels pointing at dead engines.
- **Self-heal.** `onServiceConnected` re-reads Prefs (choice +
  last-screen record) and evaluates immediately: a FRESH (<30 s) MATCH
  restores the button mid-status; anything else starts hidden and the
  next window event decides. `onUnbind`/`onDestroy` remove the button
  — the safe state is *no window*.
- **Diagnostics v1.3 rows:** Overlay permission (labelled legacy),
  Accessibility switch, Watcher CONNECTED, **Button engine:
  WATCHER · ALIVE/DEAD** (= watcher connected), **Button visible now:
  YES/NO** (the watcher's real attach state), Last seen screen
  (unchanged), and NEW **Last save attempt** — SaveHelper now records
  every attempt in Prefs (time · file name · size · SAVED, or FAILED +
  the exception's reason). "It saved but it's not in my gallery" is
  now answerable from one screenshot: if the row says SAVED and Files
  shows the file, it's the gallery's indexing, full stop.
- **Gallery visibility.** The MediaStore insert now sets `DATE_TAKEN`
  (source file's mtime, milliseconds) and `DATE_ADDED` (now, seconds —
  MediaStore's units, don't swap them), and after the verified publish
  the saved file is handed to `MediaScannerConnection.scanFile`
  (path resolved via the inserted row's `_data` column; skipped
  silently if null; wrapped so it can never fail a verified save).
  Byte-count + read-back verification are byte-for-byte as v1.1/v1.2.
- **Permission flow (as first built): 2 cards** — 1) File access,
  2) Accessibility. **Superseded the same day by the final spec's
  one-time Allow wizard (3 steps) — see "Final-spec completion".**
  MANAGE_EXTERNAL_STORAGE / legacy storage logic untouched — status
  reading still needs it.
- Design decision #2 below (FGS "specialUse") is **retired** by this
  release; it stays in the log as history.

### Files changed in v1.3
Changed: `app/build.gradle` (versionCode 4 / versionName "1.3"),
`app/src/main/AndroidManifest.xml` (FloatingService entry + 3
permissions removed), `.../StatusWatcherService.kt` (button ownership,
self-heal, save path), `.../MainActivity.kt` (toggle, 2-card flow,
diagnostics rows — the final-spec pass then replaced the cards with
the Allow wizard and added tabs + auto-save; see "Final-spec
completion" below), `.../Prefs.kt` (last-save record), `.../SaveHelper.kt`
(dates + scanner nudge + attempt recording), `.../PermissionHelper.kt`
(comments/copy), `app/src/main/res/layout/activity_main.xml`,
`app/src/main/res/values/strings.xml`, `README.md`, `BUILD_NOTES.md`,
`UPDATE_INSTRUCTIONS.md`, `PRIVACY_POLICY.md`, `STORE_LISTING.md`.
**Deleted: `.../FloatingService.kt`** (via GitHub web UI — see
UPDATE_INSTRUCTIONS.md; the update zip cannot carry a deletion).
Not changed: `StatusScanner.kt`, `accessibility_service_config.xml`
(same events, same no-content-access), drawables, colors.

### Final-spec completion (2026-10-20, after Fahad approved the spec)

The first v1.3 build above was frozen mid-flight on Fahad's order
("Before you create it") so he could add product requirements. He
then approved the final spec, and this pass finished it. English
only — localization was explicitly deferred by Fahad; no
`values-ur`/`values-hi` were added.

1. **Visibility = package-level.** `updateButton()` now shows the
   button ⇔ `Prefs.buttonEnabled` AND the foreground package is
   `com.whatsapp` / `com.whatsapp.w4b` (tracked on every event as
   `whatsappOnScreen`). It remains a pure re-derivation per event:
   `attachButton()` now also verifies `view.parent != null` and
   re-adds a view that was dropped underneath us; `detachButton()`
   removes by parent, not by flag. The status matcher still runs,
   still persists its record (Diagnostics "Last seen screen" +
   MATCH verdict unchanged) — it just no longer gates visibility.
   onServiceConnected restores BOTH states from a fresh (<30 s)
   record: package from the record's pkg, status from its verdict.
2. **Smart tap.** The button's click is now `onButtonTap()`: if
   `isStatusBeingViewed()` (live detector state, or a persisted
   MATCH <30 s old) → the unchanged verified save-newest flow;
   otherwise a friendly Toast ("Open a status first, then tap the
   green button."). No silent no-ops anywhere.
3. **One-time Allow wizard** (replaces the 2 permission cards).
   Sequential steps in one card, each with ONE full-width "Allow"
   button deep-linking to the exact screen: Step 1 Accessibility →
   `ACTION_ACCESSIBILITY_SETTINGS` (done = settings-enabled AND
   watcher CONNECTED); Step 2 Files →
   `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (package Uri;
   fallback `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`, then app
   details); Step 3 Battery → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   (package Uri; fallback app details; done =
   `PowerManager.isIgnoringBatteryOptimizations`; recommended, NOT a
   gate — new helpers in PermissionHelper). Completed steps collapse
   to slim "✓ …" rows; when steps 1+2 are done the wizard collapses
   to a single "You're all set ✓" row and reopens only the missing
   step if a required permission is revoked. The enable toggle is
   locked until steps 1+2 are done — an early tap scrolls to +
   flashes the wizard and toasts "Finish the setup steps first".
   A 1.2 s delayed re-check after onResume covers watcher bind lag.
4. **My Saved tab.** Tab row (Recent statuses | My Saved) over the
   existing grid. My Saved queries MediaStore's Images + Video
   collections with parameterized selection — Android 10+:
   `RELATIVE_PATH = ? AND IS_PENDING = 0` with
   `Pictures/StatusSaver/` resp. `Movies/StatusSaver/`; below 10:
   `DATA LIKE '%/StatusSaver/%'` (the legacy save path). Merged,
   sorted by DATE_ADDED desc. Thumbnails via
   `ContentResolver.loadThumbnail` (29+), classic Thumbnails tables
   below — no new dependencies. Tap → View / Share / Delete dialog:
   View = ACTION_VIEW + grant-read; Share = ACTION_SEND chooser with
   the content Uri; Delete = direct delete first, consent via
   `MediaStore.createDeleteRequest` (30+) or
   `RecoverableSecurityException`'s sender (29) through one
   ActivityResult launcher.
5. **Auto-save switch** (Prefs, default OFF). The watcher arms a
   900 ms debounce on a false→true transition into detector MATCH
   and cancels on exit; on fire (still matched) it runs the save
   flow with `viaAuto = true`. Duplicate guard: SaveHelper now
   records the last successful save's (name, length) in Prefs after
   EVERY verified save — manual ones included — and auto-save skips
   the newest status if it matches that record. Attempts land in the
   same LastSave record with reason "auto" (Diagnostics shows
   "SAVED (auto)"). Auto-save failures are recorded but not toasted
   (no spam while browsing); a brief success toast is shown.

Files touched by this pass: `StatusWatcherService.kt` (visibility,
smart tap, auto-save), `MainActivity.kt` (wizard, tabs, My Saved,
auto-save switch, diagnostics "(auto)" marker), `Prefs.kt`
(auto-save flag + last-saved record), `SaveHelper.kt` (`viaAuto`
param + last-saved update), `PermissionHelper.kt` (battery helpers,
file-access fallbacks), `activity_main.xml` (wizard + switch + tabs),
`strings.xml` (wizard/tabs/auto-save copy; `accessibility_desc`
updated to package-level truth), `UPDATE_INSTRUCTIONS.md` (rewritten,
incl. the GitHub delete steps for FloatingService.kt and the
permanence test), `PRIVACY_POLICY.md` + `STORE_LISTING.md`
(accessibility-engine permission wording; no more display-over-apps /
notification claims for the button), `README.md` (final-spec state).

### Residual risks (v1.3)
- **Force-stop is still final.** If the user (or an OEM cleaner)
  force-stops the app, Android disables the accessibility binding
  until the user re-enables it in Settings — true for every
  accessibility-based app; the Diagnostics card shows NOT CONNECTED
  and card 2 is the way back.
- **TYPE_ACCESSIBILITY_OVERLAY quirks.** The window type is documented
  for exactly this (accessibility services placing their own UI), and
  click delivery to such windows is standard; a handful of OEM builds
  have historically been picky about accessibility overlays — the
  vivo field test is the arbiter, and "Button visible now" + a status
  screenshot separates an attach failure from a detector miss.
- **Play Accessibility declaration** — unchanged from v1.1 §6 of the
  README; if anything the case is stronger now (the service's purpose
  is visibly the app's core feature).
- **Package-level visibility (final spec)** — the button is present on
  WhatsApp's chat list and inside chats too, by Fahad's explicit
  choice. The smart tap keeps that harmless (explanation toast, no
  save), but some users may find an always-present button in chats
  noisy; the in-app toggle is the off switch. Relatedly, the
  duplicate guard compares name + length only: two different statuses
  with identical name and size would read as "the same file" to
  auto-save (WhatsApp's cache names are content hashes, so a real
  collision means it almost certainly IS the same status).

## v1.2 (2026-10-20) — diagnostic release: "button never appears" on a vivo phone

Phone test of v1.1 on a vivo (Funtouch OS): in-app grid, saving and ads
all worked; Accessibility showed "On", high-background-battery was
allowed, overlay had worked since v1, in-app toggle was ON — yet the
floating button NEVER appeared in WhatsApp's status view. Four suspects
were investigated in the v1.1 code before changing anything:

### Suspect check (evidence, not guesses)

1. **Watcher in a separate process → static reference null? RULED OUT.**
   Read `AndroidManifest.xml` line by line: neither
   `StatusWatcherService` nor `FloatingService` declares
   `android:process` (nor does any other component). Both services run
   in the app's default process, so the v1.1 same-process static
   instance design is valid in principle — the show/hide calls were
   NOT silently no-ops for this reason. No manifest change needed.
2. **Watcher shows "On" but isn't actually bound (vivo quirk)? CANNOT
   be ruled out from outside — v1.1 kept no evidence.** v1.1 had no
   record of whether `onServiceConnected` ever ran, or whether the
   service was later unbound by Funtouch's battery manager (which can
   leave the Settings list saying "On"). → v1.2 adds a
   `watcherConnected` flag set in `onServiceConnected` and cleared in
   `onUnbind` (new override — v1.1 only cleared in `onDestroy`, which
   Android is not obliged to call on unbind) and `onDestroy`, displayed
   on the Diagnostics card separately from the Settings-list check.
3. **WhatsApp class-name mismatch? PLAUSIBLE — v1.1's matcher was
   narrow, and it kept no evidence either.** v1.1 accepted only
   `StatusPlayback` / `status.playback` / `StatusPlayer` (plus a
   status+playback fallback). If his WhatsApp build names the viewer
   differently (e.g. a view/story/media-style class), every event was
   a silent NO MATCH. → v1.2 widens the rule (below) AND records the
   last seen class name so one screenshot ends the question forever.
4. **FloatingService not running / visibility lost on restart?
   CONTRIBUTING RACE FOUND in v1.1.** The button was created `GONE`,
   and visibility was only ever flipped by a watcher *change* event.
   If the process was killed (Funtouch!) and Android restarted
   FloatingService via START_STICKY while the user sat in the status
   screen, the new button stayed `GONE` — the watcher (also restarted,
   its `lastShown` dedup reset) would only speak on the NEXT window
   change, which might never come while the user stares at a status.
   Same hole in reverse if the watcher reconnected after the service.
   → v1.2 closes both directions (below).

### What v1.2 changes

- **Diagnostics card (MainActivity).** Five monospace rows refreshed on
  every `onResume`: Overlay permission (`Settings.canDrawOverlays`),
  Accessibility switch (`PermissionHelper.isAccessibilityEnabled` —
  the Settings.Secure list), Watcher CONNECTED/NOT CONNECTED (the new
  bound-flag — deliberately separate from the switch row), Floating
  service RUNNING (`FloatingService.isRunning`), and Last seen screen
  from the persisted record: `pkg / class (MATCH|NO MATCH) · Ns ago`.
  A **Copy diagnostics** button puts the same values on the clipboard
  (rows and copy text are built from one `diagnosticsRows()` call, so
  they cannot disagree).
- **Persisted watcher record (`Prefs.kt`, new).** The watcher writes
  EVERY processed `TYPE_WINDOW_STATE_CHANGED` event (post-filter: own
  package and transient popup/IME windows are skipped, as before) to
  SharedPreferences: package, class, matched, timestamp. Also stores
  the user's explicit button on/off choice (set by MainActivity's
  toggle and by the notification's stop action), which survives
  process death.
- **Link hardening (`FloatingService`, `StatusWatcherService`).**
  `FloatingService.onCreate` + `onStartCommand` call
  `applyPersistedWatcherState()`: fresh (<30 s) MATCH in the record →
  restore the button visible; stale/NO MATCH → leave hidden (hiding
  remains the watcher's job). `StatusWatcherService.onServiceConnected`
  pushes the persisted state to FloatingService if it is running, so a
  watcher that (re)connects second still synchronises the service.
  Deliberately NOT done: starting the FGS from the accessibility
  service — background FGS starts are restricted on modern Android and
  would throw; the START_STICKY restart + re-read covers recovery.
- **Wider matcher (`StatusWatcherService.isStatusPlayback`).**
  Package ∈ {com.whatsapp, com.whatsapp.w4b}; exclusions first
  (`textstatus` About-editor, `settings`, `profile`); exact v1.1 names
  kept; then the widened rule: class contains `status` AND any of
  `playback`/`player`/`view`/`story`/`media` (case-insensitive).
- **Universal battery guidance.** NO brand-specific code anywhere —
  the matcher, diagnostics and services are brand-agnostic. The
  Accessibility card carries one short universal line (allow
  background running), and README carries the compact per-brand table
  (Pixel/Samsung/Xiaomi/Oppo/vivo/Huawei/Transsion), since the
  setting's name and place differ per brand. This matters beyond vivo:
  Samsung's "never sleeping apps" and Transsion's Phone Master kill
  watchers just as dead.

### Files changed in v1.2
Changed: `app/build.gradle` (versionCode 3 / versionName "1.2"),
`.../StatusWatcherService.kt`, `.../FloatingService.kt`,
`.../MainActivity.kt`, `app/src/main/res/layout/activity_main.xml`,
`app/src/main/res/values/strings.xml`, `README.md`, `BUILD_NOTES.md`,
`UPDATE_INSTRUCTIONS.md`.
Added: `.../Prefs.kt`.
Not changed (verified, no fix needed): `AndroidManifest.xml` — no
`android:process` anywhere; `accessibility_service_config.xml`.

### What the Diagnostics panel will prove on his phone
One screenshot after viewing a status for ~5 s distinguishes all four
suspects: Watcher NOT CONNECTED + switch ON ⇒ suspect 2 (binding/OEM);
Watcher CONNECTED + Last seen = a WhatsApp class + NO MATCH ⇒ suspect 3
(and the exact class name is the one-line fix); Last seen MATCH +
Floating NOT RUNNING ⇒ suspect 4 (service killed — battery row);
MATCH + RUNNING + button still invisible ⇒ a new, narrower bug in the
overlay itself. No logcat, no guessing.


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
