# StatusSaver v1.3.1 — compile fix (Build #7 failure)

## What failed

Build #7 (the v1.3 build) failed at `:app:assembleDebug` with exactly ONE
compiler error, from the CI log:

```
e: StatusWatcherService.kt: (235, 43): Only safe (?.) or non-null asserted
(!!.) calls are allowed on a nullable receiver of type Prefs.LastScreen?
```

## The error

File: `app/src/main/java/com/fahad1005/statussaver/StatusWatcherService.kt`,
in `onServiceConnected()` (the watcher's self-heal handshake):

```kotlin
val last = Prefs.lastScreen(this)
val fresh = last != null &&
    System.currentTimeMillis() - last.timeMs < FRESH_MS
whatsappOnScreen = fresh && last!!.pkg in WHATSAPP_PACKAGES
statusOnScreen = fresh && last.matched        // ← line 235, the error
```

`Prefs.lastScreen()` returns a nullable `Prefs.LastScreen?`. The
`last != null` check was baked into the separate `fresh` boolean, so the
Kotlin compiler's smart cast never reached the two lines below — a null
check stored in another variable does not smart-cast the receiver. The
line above had been patched with `last!!`, but `last.matched` was left
bare and does not compile.

## The fix

The block is restructured so the null check sits in the `if` condition
itself, where the smart cast applies (no `!!` anywhere):

```kotlin
val last = Prefs.lastScreen(this)
if (last != null &&
    System.currentTimeMillis() - last.timeMs < FRESH_MS
) {
    whatsappOnScreen = last.pkg in WHATSAPP_PACKAGES
    statusOnScreen = last.matched
} else {
    whatsappOnScreen = false
    statusOnScreen = false
}
```

Behavior is unchanged: a fresh (<30 s) record restores the button state;
a stale or missing record starts with both flags false, exactly as
before. The surrounding try/catch is untouched.

## Sweep for the same mistake

- Every `Prefs.lastScreen(` use in the app was checked. The other two
  (MainActivity's Diagnostics card, and `isStatusBeingViewed()` in the
  same file) already use safe patterns — an `if (last == null) … else …`
  branch and an Elvis `?: return false` — so they compile and were NOT
  changed.
- A search for any other "null check baked into a boolean variable" and
  for any remaining `!!` in the whole Kotlin source found none — after
  this fix the codebase contains zero `!!`.

## Files in this zip

- `app/src/main/java/com/fahad1005/statussaver/StatusWatcherService.kt` (fixed)
- `FIX_NOTES.md` (this file — safe to upload too, it just sits in the repo root)

## How to apply

1. On GitHub: **Add file → Upload files** → upload the
   `StatusWatcherService.kt` from this zip (it replaces the old one at
   the same path: app → src → main → java → com → fahad1005 →
   statussaver). Do NOT upload the whole extracted folder — upload the
   file so its path stays `/app/src/main/java/...`.
   (Uploading `FIX_NOTES.md` as well is optional and harmless.)
2. **Commit changes.**
3. Open **Actions** and watch the new build: it must go **green** —
   then download **StatusSaver-debug-apk** from that run's Artifacts,
   exactly as before.
