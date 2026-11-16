# Implementation Status

Last updated: 2026-11-16. Each phase has its own completion report. A phase is complete only when its exit criteria are verified, not when it compiles.

## Phase 0 — Repository and AppHub audit: complete

Approved by the owner on 2026-11-15. Deliverables: [PROJECT_AUDIT](PROJECT_AUDIT.md), [APPHUB_FEASIBILITY](APPHUB_FEASIBILITY.md), [ARCHITECTURE_PROPOSAL](ARCHITECTURE_PROPOSAL.md), [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md). Nothing was created on the cloud AppHub. Defaults proposed for decisions D1 to D9 were taken as approved, except the backend inputs (app names, operator access, test accounts, SMTP status), which are still open and block only Phases 3 and 4.

## Phase 1 — Android foundation: code complete, device verification pending

### Deliverables

| Deliverable | State | Evidence |
|---|---|---|
| Gradle project, version catalog, pinned wrapper | Done | Gradle 9.9.1 with `distributionSha256Sum`; `assembleDebug` succeeds |
| Toolchain compatibility spike (AGP 9.5.1, Kotlin 2.4.21, KSP 2.3.14, Hilt 2.60.1, Room 2.8.5, Compose BOM 2026.09.00) | Passed | Full build including KSP and Hilt succeeds; no fallback to AGP 8 was needed |
| Hilt wiring | Done | `ShoppingListApp`, `UtilModule`, `DatabaseModule`, `DataStoreModule`, `@HiltViewModel`s compile |
| Room v1 (`lists`, `items`), DAOs, exported schema | Done | `app/schemas/.../1.json` generated and committed; no destructive migration fallback |
| DataStore settings (theme mode, show completed) | Done | 4 unit tests |
| Material 3 theme (light/dark/system), navigation shell | Done (compiled) | Not yet seen on a device |
| Lists screen with counts and create dialog; categories (read-only defaults); settings screen | Done (compiled) | UI tests compile; not yet run |
| Release signing that fails without a key | Done | Build script check; verified by code review only, a release build was not attempted |
| `.gitignore`, `.gitattributes`, README, CHANGELOG | Done | |
| Automated tests | Partly run | See below |

### Verification

| Check | Result |
|---|---|
| `gradlew assembleDebug` | **Pass** |
| `gradlew testDebugUnitTest` | **Pass, 13 of 13** (DefaultCategory 3, UuidGenerator 3, SettingsRepository 4, ListsViewModel 3) |
| `gradlew lintDebug` (`abortOnError = true`) | **Pass**, 0 errors, 3 warnings (see below) |
| `gradlew assembleDebugAndroidTest` | **Pass** (instrumented sources compile) |
| Instrumented tests: 6 Room tests (counts, ordering, hidden rows, cascade, foreign keys) and 3 Compose tests | **Not run** — no emulator or device |
| Install and launch on a device, theme toggle, create a list | **Not done** — same reason |
| Airplane-mode check | Not applicable yet (no network code exists) |

Lint warnings: `OldTargetApi` is intentional (`targetSdk` 36 is the Play requirement; API 37 will be evaluated in Phase 6). `PluralsCandidate` on `"%1$d of %2$d done"` is accepted (two numbers, no plural form). `DataExtractionRules` was addressed by adding explicit rules.

### Deviations from the Phase 0 documents

- **Gradle 9.9.1, not 9.4.1.** AGP 9.5.1 failed on Gradle 9.4.1 with `NoClassDefFoundError: org/gradle/features/binding/ProjectTypeBinding`.
- **`compileSdk` 37, `targetSdk` 36.** Current stable AndroidX libraries (core-ktx 1.19.1, Compose 1.12.1, lifecycle 2.11.0) require compiling against API 37. The audit said 36 for both.
- **`material-icons-core` added explicitly.** `material3` no longer brings it in.
- **`hiltViewModel` import** moved to `androidx.hilt.lifecycle.viewmodel.compose` in hilt-navigation-compose 1.4.0.
- **Source directory** is `src/<set>/kotlin` (supported by AGP 9's built-in Kotlin).
- **Tooling note:** the Gradle daemon cannot start from Git Bash on this machine ("Unable to establish loopback connection"); Gradle is run from PowerShell.

### Known limitations of this phase

- Categories are the 11 built-in defaults, read-only; custom categories, rename and colour are Phase 2.
- The Lists screen can create lists but not rename, delete, archive, pin or search them; items cannot be added yet. These are Phase 2.
- The theme preference loads asynchronously, so a user who chose a theme different from the system theme may see one frame of the system theme at cold start.
- No device-level evidence exists yet that the app launches.

### Open decisions

- **Backup policy.** App data is currently excluded from cloud backup and device transfer. Whether guest lists should survive a phone change (and therefore be backed up, with auth tokens still excluded) is a product decision for the owner.
- **Emulator.** This machine has WHPX available but no emulator system image or `cmdline-tools`. Downloading them needs the owner's approval (size and source stated when asked).

### Exit criteria still to satisfy before Phase 1 is complete

1. App installs and launches on an emulator or device.
2. Instrumented tests (database and UI) pass on it.
3. Theme switching and list creation confirmed on-device, including after a process restart.

## Phases 2 to 6

Not started. Plan: [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md). Backend track B0 (cloud dev app and validation spike) waits for the inputs listed in [PROJECT_AUDIT §8](PROJECT_AUDIT.md).
