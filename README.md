# Shopping List

A native Android shopping-list app: create a list, add items, tick them off. It is **offline-first** and works with **no account**. Cloud sync and shared lists (registered users only) come later and use the owner's own AppHub backend, with the database built **in the cloud only**.

Stack: Kotlin, Jetpack Compose (Material 3), Room (source of truth), Hilt, DataStore; WorkManager, Glance widgets and Retrofit/OkHttp arrive in later phases.

## Status

Phases 0 (audit), 1 (Android foundation) and 2 (guest lists: items, editing, categories, tags, all offline) are complete and verified on an Android 16 emulator, including in airplane mode. Phase 3 (AppHub sign-in, sync and guest-to-account migration) is next and waits on backend inputs. See [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) for what was verified and what was not.

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
  data/      local (Room), repository (UI-facing Room + settings)
  di/        Hilt modules
  feature/   lists, categories, settings (one package per screen)
  ui/        theme and navigation shell
app/schemas/ exported Room schema JSON (committed)
docs/        audit, architecture, AppHub feasibility, plan, status
```

## Documentation

- [Project audit](docs/PROJECT_AUDIT.md)
- [AppHub feasibility](docs/APPHUB_FEASIBILITY.md)
- [Architecture proposal](docs/ARCHITECTURE_PROPOSAL.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)
- [Implementation status](docs/IMPLEMENTATION_STATUS.md)
- [Local database schema](docs/DATABASE_SCHEMA.md)
- [Testing](docs/TESTING.md)
- [Changelog](CHANGELOG.md)

## Privacy and secrets

This repository is public. It must never contain keystores, passwords, tokens, API keys or test-account credentials.
