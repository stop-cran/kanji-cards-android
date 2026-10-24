---
name: android-emulator
description: Run, drive and screenshot the app on the Android emulator (AVDs Pixel_9, Pixel_Tablet) from a Windows PowerShell shell without Android Studio, build/install the debug app, and copy a release APK to a phone over MTP. Use when verifying UI changes, reproducing a bug, or delivering a build.
---

# Android emulator and device workflow (Windows, PowerShell)

Android Studio is not needed: the SDK lives in `$env:LOCALAPPDATA\Android\Sdk` and `adb` is on PATH. Gradle builds, the emulator and adb are all driven from the shell.

## Start the emulator
- AVDs: `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds` -> `Pixel_9` (phone, default), `Pixel_Tablet` (for tablet layout checks).
- Boot (hidden window), then wait until ready:
  ```powershell
  Start-Process "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -ArgumentList '-avd','Pixel_9','-no-snapshot-load','-no-snapshot-save' -WindowStyle Hidden
  adb wait-for-device; do { Start-Sleep 5 } until ((adb shell getprop sys.boot_completed) -match '1')
  ```
  `adb wait-for-device` alone returns before Android has booted; poll `sys.boot_completed`. Boot takes about 1 minute.
- An old Quick Boot snapshot can restore older app data as well as stale activity state. `-no-snapshot-save` alone does not prevent
  loading it. Cold-boot with both flags when preserving current data or recovering instrumentation lifecycle/reporting failures.
- Stop with `adb emu kill` (never kill by process name).
- `adb devices` printing an empty list means no emulator is running; `installDebug` then fails with a generic Gradle error - start the emulator first.

## Install and launch
- `.\gradlew.bat --console=plain -q :app:installDebug` (prints "Installed on 1 device."). Reinstall after matcher or font changes (see the gotchas in `copilot-instructions.md`).
- Launch: `adb shell monkey -p io.github.stopcran.kanji 1` (without `-c`; with a category monkey fails to resolve the launcher intent). Package: `io.github.stopcran.kanji`.
- The app syncs the content repo on launch, so give it a few seconds.
- Preserve emulator learning data before interactive sessions. Stop the app before copying its database and any WAL/SHM
  sidecars; a local SQLite reader can checkpoint a backup and remove those sidecars. Restore the coherent snapshot, not a
  mixture with the device's newer WAL. Use `adb push` to a named temporary file followed by `run-as ... cp`, then remove the
  temporary file and verify the destination hash and learning counts. Large stdin transfers through `adb shell`/`exec-in`
  have truncated despite exit code 0 on Windows; never accept the command's success alone as a restored database.
  Run `adb shell sync` before terminating the emulator, then verify the restored data after a cold boot. An immediate read-back
  can come from the guest's filesystem cache and is not, by itself, proof that restoration survived shutdown.

## Look at the screen
- Screenshot: `cmd /c "adb exec-out screencap -p > $env:TEMP\x.png"` (use `cmd /c`; PowerShell redirection corrupts the PNG), then view the file.
- **Coordinates:** the image viewer may downscale (e.g. 890 px wide for a 1080 px screen). `adb shell wm size` gives the real size (Pixel_9: 1080x2424). Scale every tap/swipe: `$s = 1080 / <image width>; adb shell input tap ([int]($x*$s)) ([int]($y*$s))`. Forgetting this makes taps miss silently.
- Exact bounds instead of guessing: `adb shell uiautomator dump /sdcard/ui.xml; adb pull /sdcard/ui.xml $env:TEMP\ui.xml`, then read `bounds="[l,t][r,b]"` (real pixels, no scaling needed).
- For IME-inset checks, confirm the screenshot shows a docked keyboard. Gboard's "Write in text fields" mode can instead show a
  floating handwriting toolbar/keyboard with no ordinary bottom inset; temporarily disable it in Gboard settings if necessary,
  then restore its original value. UI hierarchy dumps may still list app nodes covered by the keyboard, so inspect their actual visibility.

## Drive the UI
- Tap: `adb shell input tap X Y`. Wait 2-3 s after navigation before the screenshot.
- Draw strokes: `adb shell input swipe x1 y1 x2 y2 250` (250 ms), one per stroke in stroke order, 400 ms apart. Direction and order matter for the matcher, so swipe like a person writes. The pad is a canvas, so uiautomator sees nothing inside it.
- Home -> kanji stack -> Drawing "Start" reaches the Draw screen; which kanji appears depends on what is due or new today, so look before drawing.
- State persists in the emulator between runs (reviews, settings). To retest "first time" flows use `adb shell pm clear io.github.stopcran.kanji` (also wipes synced content and the ML Kit model, so the next start is slow) or pick another kanji.
- Emulator gotcha: "I don't remember" always grades Again, so a session of only give-ups never reaches Done.

## Checks before pushing (what CI runs)
`.\gradlew.bat --console=plain -q detekt :app:testDebugUnitTest :app:lintDebug` (unit tests take about 5 min; add `--tests "*Name"` for a targeted run). Prefer refactoring over the Detekt baseline.

## Grammar roadmap stress check
- Build `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`, then explicitly install both APKs on the chosen emulator
  with `adb -s emulator-5554 install -r`. The test APK is `app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`.
- At default text size, run `adb -s emulator-5554 shell am instrument -w -r -e class io.github.stopcran.kanji.ui.GrammarRoadmapPerformanceTest io.github.stopcran.kanji.test/androidx.test.runner.AndroidJUnitRunner`.
  Require `OK (1 test)`; `am instrument` can return a successful shell exit code despite a failed test.
- This renders 1,000 synthetic nodes using the production diagram, measures warm layout, first render and two-axis interactions,
  and bounds composed-card counts. It does not insert synthetic content or progress into the learner's database.
  Read `cache/roadmap-synthetic-benchmark.json` and its `.png` through the target app's `run-as`; preserve binary output correctly.
- After syncing real chapter content, add `-e roadmapCorpus real` to also time the production screen's overview/largest-chapter
  navigation. It reads accepted content without replacing it; results are in `cache/roadmap-real-benchmark.json`.
  Let cold-boot background work settle before measuring the warm-navigation budget. Observation waits allow diagnostic timing
  collection; they do not replace the tighter measured acceptance assertions. Do not accept stale cache JSON after a failed run.
- Keep real-corpus navigation, source/Back/process restoration, large-text, theme, TalkBack and gesture checks separate.
  A semantic node can be partially clipped even though it exists: inspect screenshots and check the actual visible touch area.
- `uiautomator dump` temporarily suppresses other accessibility services. For TalkBack checks, use direct screenshots and keep
  the service bound. `GrammarRoadmapAccessibilityTest` uses `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` to exercise exported
  Android scroll/focus/click actions with TalkBack enabled; it is not a spoken-output or physical gesture usability audit.
  Traverse the exported virtual-node tree and refresh nodes after layout changes before trusting cached actions/bounds.
  Enable TalkBack before running that class, then restore the original accessibility settings afterward.

## Release APK to a phone (MTP, no adb over USB)
- Build: `.\gradlew.bat --console=plain -q :app:assembleRelease`; copy `app\build\outputs\apk\release\*.apk` under a name that includes the short SHA.
- The phone must be unlocked and set to "File transfer". Use the Shell COM API: item of `NameSpace(17)` (This PC) with `Type -eq 'Mobile Phone'` -> its first storage item -> the `Download` folder -> `GetFolder.CopyHere($src, 16)`.
- Verify with `ExtendedProperty('Size')` on the copied item; Explorer shows 0 KB for a while.
- On the phone, open the file from Downloads and allow install from that source.

## Android Studio
Only needed for what the shell cannot do: Layout/Compose Inspector, Profiler, the Device Manager GUI (create or edit AVDs) and the SDK Manager. New AVDs can also be created with `avdmanager` from `cmdline-tools`.
