# Implementation Status

Last updated: 2026-11-16. Each phase has its own completion report. A phase is complete only when its exit criteria are verified, not when it compiles.

## Phase 0 — Repository and AppHub audit: complete

Approved by the owner on 2026-11-15. Deliverables: [PROJECT_AUDIT](PROJECT_AUDIT.md), [APPHUB_FEASIBILITY](APPHUB_FEASIBILITY.md), [ARCHITECTURE_PROPOSAL](ARCHITECTURE_PROPOSAL.md), [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md). Nothing was created on the cloud AppHub. Defaults proposed for decisions D1 to D9 were taken as approved, except the backend inputs (app names, operator access, test accounts, SMTP status), which are still open and block only Phases 3 and 4.

## Phase 1 — Android foundation: complete (2026-11-16)

### Deliverables

| Deliverable | State | Evidence |
|---|---|---|
| Gradle project, version catalog, pinned wrapper | Done | Gradle 9.9.1 with `distributionSha256Sum`; `assembleDebug` succeeds |
| Toolchain compatibility spike (AGP 9.5.1, Kotlin 2.4.21, KSP 2.3.14, Hilt 2.60.1, Room 2.8.5, Compose BOM 2026.09.00) | Passed | Full build including KSP and Hilt succeeds; no fallback to AGP 8 was needed |
| Hilt wiring | Done | App, modules and `@HiltViewModel`s run on a device |
| Room v1 (`lists`, `items`), DAOs, exported schema | Done | `app/schemas/.../1.json` committed; 6 on-device DAO tests; no destructive migration fallback |
| DataStore settings (theme mode, show completed) | Done | 4 unit tests; theme persisted across a process kill on a device |
| Material 3 theme (light/dark/system), navigation shell | Done | Verified on a device in light and dark |
| Lists screen with counts and create dialog; categories (read-only defaults); settings screen | Done | Verified on a device; 3 Compose UI tests |
| Release signing that fails without a key | Done | Build script check; a release build was not attempted (no upload key exists yet) |
| `.gitignore`, `.gitattributes`, README, CHANGELOG | Done | |

### Verification (all run on 2026-11-16)

| Check | Result |
|---|---|
| `gradlew assembleDebug` | **Pass** |
| `gradlew testDebugUnitTest` | **Pass, 13 of 13** (DefaultCategory 3, UuidGenerator 3, SettingsRepository 4, ListsViewModel 3) |
| `gradlew connectedDebugAndroidTest` on an Android 16 (API 36) emulator | **Pass, 9 of 9** (AppDatabase 6, ListsScreenContent 3) |
| `gradlew lintDebug` (`abortOnError = true`) | **Pass**, 0 errors, 2 accepted warnings |
| Install and cold launch | **Pass** (`am start -W` Status: ok) |
| Create a list, force-stop the process, relaunch | **Pass**: the list "Weekend" with "No items yet" was still there |
| Switch to Dark in Settings, force-stop, relaunch | **Pass**: dark theme restored from DataStore |
| Categories screen | **Pass**: shows the 11 defaults |

Lint warnings: `OldTargetApi` is intentional (`targetSdk` 36 is the Play requirement; API 37 will be evaluated in Phase 6). `PluralsCandidate` on `"%1$d of %2$d done"` is accepted (two numbers, no plural form).

Not verified, by design: airplane mode (no network code exists yet), release/R8 build (needs an upload key), a physical device, an API 24 to 26 device.

### Defect found and fixed by this verification

The Lists screen's "New list" floating button had **no accessible name**. The icon-and-text overload of `ExtendedFloatingActionButton` wraps its label in `clearAndSetSemantics`, so the button's merged semantics node had role Button but no text (a UI test could not find it; a UI Automator dump showed an unnamed clickable view). TalkBack would have announced only "Button". Fixed by using the content-slot overload, which keeps the label in the button's semantics; confirmed in the test and in the on-device dump. Lesson for later phases: prefer the content-slot overloads, and keep UI tests that find controls by their visible text, because they double as accessibility checks.

### Deviations from the Phase 0 documents

- **Gradle 9.9.1, not 9.4.1.** AGP 9.5.1 failed on Gradle 9.4.1 with `NoClassDefFoundError: org/gradle/features/binding/ProjectTypeBinding`.
- **`compileSdk` 37, `targetSdk` 36.** Current stable AndroidX libraries (core-ktx 1.19.1, Compose 1.12.1, lifecycle 2.11.0) require compiling against API 37. The audit said 36 for both.
- **`material-icons-core` added explicitly.** `material3` no longer brings it in.
- **`hiltViewModel` import** moved to `androidx.hilt.lifecycle.viewmodel.compose` in hilt-navigation-compose 1.4.0.
- **Compose UI tests** use the v2 `createComposeRule` (the old one is deprecated). It queues composition on a `StandardTestDispatcher`, so tests must call `waitForIdle()` after `setContent`.
- **Source directory** is `src/<set>/kotlin` (supported by AGP 9's built-in Kotlin).

### Development environment (this machine)

- Run Gradle from PowerShell or cmd. In Git Bash the Gradle daemon cannot start ("Unable to establish loopback connection"). `JAVA_HOME` must point to JDK 17; the `java` on `PATH` is 1.8.
- Android SDK command-line tools were installed (`cmdline-tools/latest`, checksum verified against Google's manifest) and the system image `system-images;android-36;google_apis;x86_64` (4.3 GB unpacked). The emulator is the AVD `shoppinglist_api36` (Pixel 6). The `sdkmanager.bat` shim mangles package ids that contain semicolons; use `cmdline-tools/latest/bin/android.exe sdk install "<package>"` instead.
- The AVD's Gboard shows a "Try out your stylus" tutorial over text fields, which swallows typed text in automated runs. Stylus handwriting was switched off on the emulator (`settings put secure stylus_handwriting_enabled 0`). This affects only the disposable AVD.
- Free RAM got tight with the emulator (about 4 GB) and Gradle daemons (about 5 GB) running together; `gradlew --stop` before heavy emulator work.

### Known limitations of this phase

- Categories are the 11 built-in defaults, read-only; custom categories, rename and colour are Phase 2.
- The Lists screen can create lists but not rename, delete, archive, pin or search them; items cannot be added yet. These are Phase 2.
- The theme preference loads asynchronously, so a user whose chosen theme differs from the system theme may see one frame of the system theme at cold start; the Android 12+ system splash also follows the system theme.
- Cold start measured 5.0 to 5.3 s on the memory-constrained emulator. That is not representative of a device, but startup time is a Phase 6 performance item (baseline profile, measured on a mid-range profile).

### Open decisions

- **Backup policy.** App data is currently excluded from cloud backup and device transfer. Whether guest lists should survive a phone change (and therefore be backed up, with auth tokens still excluded) is a product decision for the owner.

## Phases 2 to 6

Not started. Plan: [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md). Backend track B0 (cloud dev app and validation spike) waits for the inputs listed in [PROJECT_AUDIT §8](PROJECT_AUDIT.md).
