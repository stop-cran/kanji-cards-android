# Implementation plan

> **Status (October 2026):** milestones 1–8 are done (content foundation with N5 cards, sync, FSRS-5 with per-stack state, quiz, article viewer,
> matcher, drawing mode, settings/About/stacks) plus later additions: brushes, search, queue noise, launch sync, reminders, and the word quiz (both directions, N5 scope with an N4 unlock offer; explicit vocabulary JLPT with hardest-kanji fallback for unlabelled words; separate FSRS state per direction; the other direction only slightly defers ordering). Remaining: real-device
> tuning from the drawing log (after the N4 set), writing-pattern variants ([issue #1](https://github.com/stop-cran/kanji-cards-android/issues/1)),
> and the release work in milestone 9 (see [RELEASE.md](RELEASE.md)). The sections below are the original plan and are kept for context.

EN/RU localization has a separate [checkpoint implementation plan](https://github.com/stop-cran/learning-japanese-ru/blob/main/docs/IMPLEMENTATION-PLAN.md).
The Android implementation now includes locale selection, EN/RU app resources, repository-language validation, and staged source
transitions. The reviewed `ru-pilot-a` package is a bounded integration candidate, not the ordinary RU default or broad release.
Pilot A's bounded C5 integration gate is accepted through Native15, including real pending-validation interruption:
the signed `a7a9e6b3…` build preserves every SQLite table and all10states/13logs/6sessions, restores the official
RU pilot, and removes the app-only fault controls exactly. Prior native observations retain their exact APK scopes.
Those three reviewed branches were merged into their respective mains at the owner's request:
app `30b0719`, RU `98046a9`, and the EN two-parent merge `5f460b8`.
The next [Pilot B](https://github.com/stop-cran/learning-japanese-ru/blob/main/docs/PILOT-B.md)
adds a complete compact grammar set, counter v1/v2 lessons, the required 20-word closure and three reused images.
Its new canonical metadata exposed the renderer-only localization gap tracked in
[Android #128](https://github.com/stop-cran/kanji-cards-android/issues/128).
The 18-label correction and the native counter-reference locale fix have independent review.
The six focused Compose/CounterHome tests, three final public-archive cases, signed release and Android lint pass.
Detekt still reports only the two pre-existing `ComposeUiTest` findings, not a fully green analysis run.
The owned API-29 lab accepted controlled `ru-pilot-b` at `7ce81c7`, content version `24362f736bc17328`.
Actual grammar choice/typing, counter context/writing/reading, both picture directions, article Back,
background question/check phases and source/language return were exercised. Committed controlled-source
history survived both the language roundtrip and process relaunch row-for-row; all original learning rows remain.
The final signed APK is `190561ef…`, superseding `af1d2201…` only for the subsequent counter-fix/native checks.
Counter suffix/legacy whole-phrase typing still needs a second real practice day; new-cohort Word Type
has not earned its separate reverse prerequisite. Neither is counted as a native pass.
Both complete Russian listening packages remain text drafts outside runtime pending fresh package authority.
The original Russian Pilot A source, effective daily limit 10 and N5 selection were restored; the input helper,
421 exact device scratch files, temporary ADB root and owned emulator were cleaned up. Two previously unset
preferences are now explicit defaults, not raw-preference identity. Release acceptance (C7), ordinary RU
activation, physical-phone/Japanese-IME acceptance and broad translation remain separate.
The reviewed Pilot B branches are published and fast-forwarded into main as source integration;
the official Pilot A branch and controlled native package remain pinned, without counting deferred paths as passed.

## 0. Tooling to install on this PC

Already present: Git, Node.js, Python 3.12, winget. Missing:

| Software | Why | Install |
|---|---|---|
| JDK 17 (Temurin) | Gradle/AGP | `winget install EclipseAdoptium.Temurin.17.JDK` |
| Android Studio (latest stable) | IDE, SDK Manager, emulator, Compose preview | `winget install Google.AndroidStudio` |
| Android SDK Platform 35 + Build-Tools + Platform-Tools (adb) | build/run | Android Studio SDK Manager |
| Android emulator + system image (API 35, x86_64) | testing without a phone | Device Manager |
| Hardware acceleration (Windows Hypervisor Platform / Hyper-V enabled) | emulator speed | Windows Features |
| Gradle wrapper | reproducible builds | after JDK: install Gradle once (`winget install Gradle.Gradle`) and run `gradle wrapper`, commit `gradlew*` and `gradle/wrapper` |
| GitHub CLI (optional) | create/push repos | `winget install GitHub.cli` |
| A physical Android phone with USB debugging (recommended) | drawing needs real touch/stylus testing | Developer options |

Later: Play Console account (one-off fee), a release keystore (kept outside git; use Play App Signing).
Env vars: `JAVA_HOME`, `ANDROID_HOME`.

## 1. Milestones

1. **Content foundation** (content repo)
   - Schema + validator script (Python or Node) + CI.
   - Generator from KANJIDIC2/KanjiVG/JMdict producing skeleton cards, `strokes/*.json`, `manifest.json`.
   - ~20 hand-checked cards, then JLPT N5 (~100). Add data attribution/licence file.
2. **App core**
   - Room schema: `Card`, `Word`, `Article`, `ReviewState(repo, kanji, mode, fsrs fields, due)`, `ReviewLog`.
   - Content sync: settings (repo URL + branch, default `Defaults`), download zip, validate `schemaVersion`, diff by manifest hashes, parse front matter. WorkManager periodic sync + manual refresh. Treat content as untrusted.
   - Unit tests for parser and sync.
3. **Scheduling**: FSRS implementation (own port or library after licence check); separate state per mode; queue builder (due first, then new cards with daily limit); unit tests.
4. **Quiz mode**: question state → answer-check state; distractors from `distractors` then same-tag/similar kanji; grade mapping (wrong = Again, right = Good/Easy by time).
5. **Article viewer**: Markdown renderer (no raw HTML), link rewriting to in-app navigation, back stack and back button, "details" from any answer state.
6. **Drawing matcher prototype (pure Kotlin, JVM-testable)** – done before UI polish:
   - Resample/normalize strokes; per-stroke distance (position, direction, endpoints, length).
   - Alignment of drawn vs. reference strokes (detect reversed, swapped, merged, split, missing/extra).
   - Config thresholds; recorded-drawing test fixtures (good and deliberately wrong).
7. **Drawing mode**: Compose `Canvas` capture; ML Kit Digital Ink (Japanese) as recognition gate; matcher for per-stroke feedback; outcomes *not recognized* / *recognized with stroke mistakes* / *clean* → Again / Hard / Good; overlay of reference with offending strokes highlighted; matcher-only fallback when the model is unavailable.
8. **Settings, About/Licences, stacks**: strictness, daily limits, repo URL; attributions; later multiple stacks as tag filters with own state.
9. **Release**: R8 check, signing, Play listing, data-safety form (network fetch of user-configured URL), privacy policy, internal testing track.

## 1a. Review-driven requirements

- **Gradle wrapper first** (blocking): after installing JDK + Gradle, `gradle wrapper --gradle-version 8.9`, commit it; CI uses `./gradlew`; ensure API 35 SDK on CI.
- **Finalize strokes JSON contract** (done; see CONTENT-SCHEMA.md and the content repo's format doc) before generator and matcher work.
- **Sync safety**: app-private storage; reject zip-slip paths; cap compressed/uncompressed size, entry count, per-file size; verify manifest hashes; import into staging and swap atomically; keep last-known-good.
- **Repo identity**: local content-source ID separate from editable URL/branch; canonical GitHub owner/repo; explicit UX for "same corpus / moved" vs "new corpus (reset scheduling)".
- **Licences**: content repo gets `LICENSE`/`NOTICE` with exact upstream attributions, source links, modification notes, CC BY-SA for derived data; mirrored in the About screen.
- **WorkManager**: unique periodic sync with network constraint + separate manual refresh, exponential backoff, visible failure state.
- **Test fixtures**: versioned content fixture repo (Unicode filenames, broken/valid links, bad front matter, missing strokes, migrations).
- **ML Kit**: advisory gate only; defined behaviour when model missing/offline (matcher-only); test first-run/offline.
- **Pre-release**: upgrade AGP/dependencies/targetSdk to the then-current Play requirement and run device regression.

## 2. Key decisions / risks

- Licence Apache-2.0 for code; the default content repo is CC BY-SA 4.0 (`strokes/` stays CC BY-SA 3.0 from KanjiVG). Content is downloaded at runtime and not bundled in the APK; any bundled sample keeps its CC BY-SA notice in separate assets. The About screen shows the CC BY-SA, KanjiVG and EDRDG (KANJIDIC2/JMdict) credits.
- Biggest risk: stroke matcher quality and tolerances; mitigated by prototyping first with fixtures.
- ML Kit gives rank only (no distance); logic distance comes from our matcher.
- Review state must survive content updates and repo switches (stable IDs, repo namespace); consider export/backup.
- Version pins in `gradle/libs.versions.toml` are initial; verify/update them when Android Studio is installed.

## 3. Status

Scaffolded but **not yet built** (no JDK/Android SDK on this machine). First build after tooling install is the first verification step.
