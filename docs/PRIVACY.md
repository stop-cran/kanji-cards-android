# Privacy policy — Ensō Nihongo

_Last updated: October 2026._

Ensō Nihongo is a personal study app. It has no accounts, no advertising, no analytics of its own and no server of its own. Google's ML Kit library, used for handwriting recognition, may send technical diagnostics and usage metrics to Google (see below).

## What stays on your device
- Your review progress (which cards you studied and when), settings and the downloaded cards.
- Grammar's per-repository new-material/browse scopes, focused-introduction switch and successful parser epoch are local settings,
  also included in the settings export. The local focus cursor stores selection counters and introduced exercise/facet/context IDs.
  It is omitted from progress ZIPs but included in ordinary Android database backup. Focus evidence is derived from existing recorded
  answers; the on-device readout shows aggregate counts/reasons, not raw responses. This adds no response collection, telemetry or upload.
- Recorded **Grammar** reviews automatically store the exercise, construction and relationship IDs, choice/typing presentation, your selected
  option ID or normalized typed answer, and correctness, linked to the review's grade and time. This is separate from the opt-in drawing log.
  Give-up stores an empty answer; successful practice-only confirmations are not recorded. The app does not upload these responses.
- If you turn on **"Save my drawings"** (off by default), your checked handwriting is stored in the app's private storage. It is never uploaded. You can export it as a zip file yourself.
- The optional daily reminder is a local notification; nothing is sent anywhere.
- If you turn on **voice input** (off by default; offered only when your device has an on-device English speech recognizer), the app uses the microphone while you press the button. Recognition runs on the device only; audio is never sent anywhere or saved, and there is no online fallback.
- Android's automatic backup may copy this data to your own Google account backup, under your device's backup settings.

## What the app downloads
- **Card content** from the public GitHub repository set in Settings (default `github.com/stop-cran/learning-japanese`), and a tiny request to the GitHub API to see whether the repository changed. GitHub sees your IP address as for any web request, as described in the [GitHub privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement). The app does not send any personal data to GitHub.
- **Handwriting recognition model** from Google (ML Kit Digital Ink Recognition) the first time you use drawing mode. Recognition itself runs on the device; your drawings are not sent to Google. The ML Kit SDK may send diagnostics and usage metrics (such as performance and model-download events) to Google; see the [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [ML Kit terms](https://developers.google.com/ml-kit/terms).

## Your control
- **Settings → Export progress** saves a ZIP of your review states, answer history, settings and Grammar response details to a location you
  choose. It includes typed answers and is not anonymized. A selected cloud-backed document provider may upload that file under its own
  settings. Keep exports private; do not post them in public issues or source repositories.
- The optional desktop Grammar-analysis script works locally without network access and omits response text/general settings from its
  report. The aggregate report still contains source/content IDs and study statistics, so it should also be kept private.
- Uninstalling the app deletes its local data, not copies you exported or backups held elsewhere. Export progress and, separately, saved
  drawings from Settings before uninstalling if you want to retain them. The app does not currently import/restore progress ZIPs.
- Switch off the reminder, drawing log or sync (by removing the repository) at any time.

## Contact
Open an issue at <https://github.com/stop-cran/kanji-cards-android/issues>.