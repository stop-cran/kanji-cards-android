---
name: android-crash-triage
description: Investigate Android startup crashes and Room migration failures using private phone reports, schema lineage, and data-preserving emulator checks.
---

# Android crash triage

Use this only for crash investigations. Reports may contain sensitive device details, paths, exception text or other private
information. Keep them private: never publish reports or their contents to GitHub, external services or repository files.
Share only the minimum non-sensitive cause and schema identifiers needed to coordinate a fix.

## Retrieve and identify

- On Android 10+, the app exports reports to `Download\Enso Nihongo\Crash reports\`. The app also keeps private reports;
  public export is best effort, and earlier Android versions do not export without storage permission.
- For a phone without USB debugging, unlock it, select File transfer and use Windows Shell COM (`Shell.Application`,
  `NameSpace(17)`, the mobile phone, `Internal storage`, then the report folder). Copy the relevant reports to a private
  session directory over MTP; confirm the copy's name and size before reading. Never remove the originals as part of triage.
  If storage is unavailable or hangs, reconnect and unlock the phone rather than assuming an empty folder.
- Match report time and embedded app commit to the attempted APK. Read the exception and, for database failures,
  `Database user_version` and `Room identity_hash`; table/column metadata describes schema, not user records.
  Missing reports or metadata are not evidence of a particular schema.

## Trace and reproduce safely

- Check `docs\DB-VERSIONS.md` and the checked-out Room exports in `app\schemas\io.github.stopcran.kanji.data.AppDatabase`.
  Search committed history and other branches for the exact identity, then investigate relevant local worktrees and
  untracked generated schemas through their owning sessions where needed. A version number alone is not unique.
  Compare the complete schema, including defaults, keys and indexes. Inspect preserved APKs for the identity and verify
  the APK's actual source and hash; an available APK is not proof it was installed on the phone.
- Obtain appropriate emulator/resource approval first and follow the `android-emulator` skill. On an owned disposable
  API 29 emulator, verify the AVD name, API and serial before app commands. Seed a synthetic database matching the
  identified schema and identity; include representative review history and affected feature data. Never install fixtures
  onto a user's phone or replace an existing emulator database without explicit authorization.
- Do not uninstall, clear data, wipe a database, use destructive migration fallback, or use Android's deleting default
  corruption handlers. If the schema is unknown, fail closed and collect schema-only diagnostics before proposing a migration.
  Open the phone database read-only for diagnostics, outside Room, without exporting user records.
- Exercise the signed release migration and reopen; verify version, identity, integrity and the seeded data, including
  unmanaged tables and indexes. Stop the app before capturing the database and all present WAL/SHM sidecars as one coherent
  set; verify each copied file's hash against the device before inspecting. A seeded fixture is not proof of phone success.

## Delivery

- Run focused migration and failure-path tests, static checks and the relevant build; report exact evidence and limitations.
  Preserve the matching release mapping and verify the release APK's signer against the installed release.
- Follow `android-emulator` for MTP delivery. Use a unique commit/hash suffix; explicitly check the destination for that
  name and stop if it exists before calling `CopyHere`. Verify the transferred size and report the filename and local
  SHA-256. Do not mark the phone fixed until the user confirms launch.
