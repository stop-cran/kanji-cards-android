# Release and Google Play checklist

## Signing
- `keystore.properties` (git-ignored, repo root) points to the upload keystore: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
- The upload keystore lives **outside the repo** (`~/.kanji-release/upload.jks`). Back it up together with its password; enrol in **Play App Signing** so a lost upload key can be reset.
- Without `keystore.properties` release builds are unsigned (CI-safe).
- Build: `.\gradlew.bat bundleRelease` → `app/build/outputs/bundle/release/app-release.aab` (upload to Play); `assembleRelease` for a sideloadable APK.
- R8 + resource shrinking are on; the release APK was smoke-tested on the emulator (sync, Room, serialization, browse).

## Before each release
1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. `.\gradlew.bat testDebugUnitTest bundleRelease`.
3. Check the target SDK still meets Google Play's current requirement.
4. Smoke-test the release APK on a real device (drawing mode, ML Kit model download).

## Store listing draft
- **Title:** Kanji Cards
- **Short description:** Learn kanji by meaning and by drawing, with spaced repetition and your own GitHub-hosted notes.
- **Full description:** Guess meanings, draw kanji with stroke-order checks, review on an FSRS schedule, browse and search cards, and read articles about similar kanji and words. Content is synced from a public GitHub repository you can fork. No account, no ads.
- **Category:** Education. **Contains ads:** No.
- Needs: 512×512 icon, 1024×500 feature graphic, ≥2 phone screenshots, privacy policy URL (`docs/PRIVACY.md` on GitHub).

## Data safety form (draft)
- Data collected / shared: **none** (no server, no analytics, no ads).
- Network use: downloads public content from GitHub and the ML Kit handwriting model from Google; sends no personal data.
- Optional permissions: `POST_NOTIFICATIONS` for the reminder; `RECORD_AUDIO` for opt-in on-device voice input (audio never leaves the device or is stored, so it is not "collected"; re-check this answer against the current form).
- On-device data: progress and optional drawing log; Android Auto Backup is enabled (user's own Google backup).
- Content rating: questionnaire answers are all "no".

## Account notes
- New personal developer accounts must run a closed test (currently 12+ testers for 14 days) before production; check Play Console for the current rule.
