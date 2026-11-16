# Testing

Status: reflects Phase 2 (2026-11-16). Counts are from the last full run: **64 unit tests and 62 instrumented tests, all passing; lint 0 errors**.

## How to run

Run Gradle from PowerShell or cmd on Windows (not Git Bash). JDK 17 must be in `JAVA_HOME`.

```powershell
.\gradlew.bat testDebugUnitTest                 # JVM tests, no device
.\gradlew.bat lintDebug                         # lint, fails on errors
.\gradlew.bat connectedDebugAndroidTest         # needs an emulator or device
.\gradlew.bat assembleDebug                     # refresh app/build/outputs/apk/debug/app-debug.apk before installing by hand
```

`connectedDebugAndroidTest` installs its own APKs and does **not** refresh `outputs/apk/debug/app-debug.apk`; always run `assembleDebug` before installing that file manually, or you will test an old build. Gradle can report `BUILD SUCCESSFUL` even when the instrumentation run crashed, so read the result XML under `app/build/outputs/androidTest-results` (and `app/build/test-results` for unit tests) instead of trusting the exit status alone.

## Layers

| Layer | Where | What it covers |
|---|---|---|
| Pure logic | `src/test` | quick-add parser, sort-order arithmetic, quantity formatting, default categories, id generator, list and detail state building (filter, sections, grouping, reorder rules) |
| ViewModels | `src/test` (MockK, Turbine-style `first {}`) | state mapping, delegation to repositories, trimming and blank-input rules |
| Settings | `src/test` (real DataStore in a temp folder) | defaults, persistence, unknown stored values |
| Database and repository | `src/androidTest` (in-memory Room, fake clock and ids) | DAO queries, counts, ordering, foreign keys, cascade; every repository operation including undo, reorder with renumbering, tags, built-in category overrides, tombstone purging |
| Screens | `src/androidTest` (Compose, stateless content with fake actions) | Lists, list detail, edit sheet, Categories and tags: rendering, callbacks, dialogs, undo snackbars, accessibility names |

## Conventions that matter

- **Compose UI tests use `androidx.compose.ui.test.junit4.v2.createComposeRule`.** It queues composition on a `StandardTestDispatcher`, so call `composeRule.waitForIdle()` after `setContent`, and assert state (`assertIsDisplayed`, `assertIsEnabled`) before an action that depends on a just-typed value.
- **Find controls by their visible text or content description** wherever possible. This doubles as an accessibility check; it caught a Floating Action Button with no accessible name.
- **Content composables are stateless** (`ListsScreenContent`, `ListDetailContent`, `CategoriesScreenContent`) and take an actions object whose defaults are no-ops, so tests wire only what they check and need no Hilt.
- **DataStore tests perform at most one write per store.** On Windows the atomic file rename fails when it must replace a file DataStore still holds open (a JVM test-environment quirk, not an app issue).
- **Time and ids are injected** (`Clock`, `IdGenerator`), so repository tests control timestamps and ids.
- Schema changes in-place before release make an installed debug app's on-disk database incompatible: uninstall or clear data on the emulator, otherwise the app crashes at start with a Room identity-hash error.

## Manual device checklist (run for each phase, in airplane mode when offline behaviour matters)

1. Fresh install, create a list, add items with the quick-add field (`2 kg milk`, `12 pieces eggs`), check the parsed quantity and unit.
2. Tick an item (it moves to Completed, progress updates); untick it (it returns to its old position).
3. Drag an item by its handle to a new position; leave the screen and come back.
4. Swipe an item away, tap Undo within the snackbar window, confirm it returns in place.
5. Open the edit sheet with the keyboard visible; Delete and Save must stay visible.
6. Kill the process (`adb shell am force-stop`), relaunch, confirm everything persisted.
7. Group by category, create a custom category, switch theme.

Emulator notes (stylus tutorial, memory, `adb` path conversion in Git Bash) are in [IMPLEMENTATION_STATUS](IMPLEMENTATION_STATUS.md).

## Not covered yet

- Real drag and swipe gestures are checked by hand on a device (the swipe-then-undo path also has a Compose regression test); there is no automated drag-and-drop test.
- Instrumented tests that go through Hilt and the real database from the UI (end-to-end) do not exist yet.
- Performance with very large lists, cold-start time and battery are Phase 6 items.
- Sync, authentication, collaboration, widgets and reminders arrive with their phases; their test plans are in [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md) and [APPHUB_FEASIBILITY §9](APPHUB_FEASIBILITY.md).
