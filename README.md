# Shopping List

A native Android shopping-list app: create a list, add items, tick them off. It is **offline-first** and works with **no account**. Signing in (optional) syncs lists across devices. A home-screen widget, per-list timed reminders and an ongoing "Shopping mode" notification work on the device without a network. Shared lists are parked for a later version. The only backend is the owner's own AppHub, with the database built **in the cloud only**.

Stack: Kotlin, Jetpack Compose (Material 3), Room (source of truth), Hilt, DataStore, Retrofit/OkHttp with kotlinx.serialization, WorkManager, Glance widget.

## Status

Phases 0 (audit), 1 (Android foundation), 2 (guest lists, all offline) and 3 (AppHub sign-in, sync and guest-to-account migration) are implemented. Phase 3 is verified against fake AppHub servers (an in-memory one in the tests and a faithful Node one for the emulator) and against the real cloud dev app `shoppinglistdev` (created 2026-11-16; schema, config and the checks are in the ledger in [backend/apphub/README.md](backend/apphub/README.md)). The production app `shoppinglist` has not been created. Phase 5 (widget, timed reminders, shopping mode) is implemented on `feature/phase-5-widgets-reminders` and was run by hand on an Android 16 emulator; Phase 4 (sharing) is parked; Phase 6 (QA, release build) is next. See [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) for exactly what was verified and what was not.

## Requirements

- JDK 17 or newer in `JAVA_HOME` (Gradle uses it; the `java` on `PATH` may be older).
- Android SDK with platform 37 (`compileSdk`), `ANDROID_HOME` set.
- Windows: run Gradle from PowerShell or cmd. In Git Bash on the development machine the Gradle daemon failed with "Unable to establish loopback connection".

## Build and test

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat connectedDebugAndroidTest   # needs an emulator or a device
```

## Running against the fake AppHub

Debug builds talk to `http://10.0.2.2:8787/` (the host machine as seen from the Android emulator; the fake serves `/{app}/...` with no `/apphub` prefix, which only the cloud server has), app `shoppinglistdev`. Start the fake server on the host first; it needs only Node 18 or newer and has no dependencies:

```powershell
node backend/apphub/fake-server/server.mjs --port 8787
node backend/apphub/fake-server/smoke.mjs        # its own self-test
```

Point a debug build somewhere else with `-PSL_APPHUB_BASE_URL=... -PSL_APPHUB_APP=...`. Release builds use the cloud AppHub over HTTPS only.

## Release signing

Release builds are refused unless all four Gradle properties are set, so an unsigned or debug-signed release can never be produced by accident. Put them in `~/.gradle/gradle.properties` (never in this repository):

```
SL_UPLOAD_STORE_FILE=...
SL_UPLOAD_STORE_PASSWORD=...
SL_UPLOAD_KEY_ALIAS=...
SL_UPLOAD_KEY_PASSWORD=...
```

## Layout

```
app/src/main/kotlin/space/abhijithkm/shoppinglist/
  core/      model types, small utilities
  data/      local (Room), repository (UI-facing Room + settings),
             remote (AppHub client), session (tokens), sync (engine, merge, migration, WorkManager)
  di/        Hilt modules
  feature/   lists, categories, settings, account (one package per screen)
  ui/        theme and navigation shell
app/schemas/ exported Room schema JSON (committed)
backend/apphub/  the cloud app's definition (schema.sql, config) and the fake AppHub server; NOT applied anywhere
docs/        audit, architecture, AppHub feasibility and integration, sync strategy, plan, status
```

## Documentation

- [Project audit](docs/PROJECT_AUDIT.md)
- [AppHub feasibility](docs/APPHUB_FEASIBILITY.md)
- [Architecture proposal](docs/ARCHITECTURE_PROPOSAL.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)
- [Implementation status](docs/IMPLEMENTATION_STATUS.md)
- [AppHub integration](docs/APPHUB_INTEGRATION.md)
- [Sync strategy](docs/SYNC_STRATEGY.md)
- [Local database schema](docs/DATABASE_SCHEMA.md)
- [Testing](docs/TESTING.md)
- [Changelog](CHANGELOG.md)

## Privacy and secrets

This repository is public. It must never contain keystores, passwords, tokens, API keys or test-account credentials.
