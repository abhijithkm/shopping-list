# Testing

Status: reflects Phase 3 (2026-11-16). Counts are from the last full run: **137 unit tests and 121 instrumented tests, all passing; lint 0 errors**. The fake AppHub server has its own 245-check self-test (`node backend/apphub/fake-server/smoke.mjs`).

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
| Screens | `src/androidTest` (Compose, stateless content with fake actions) | Lists, list detail, edit sheet, Categories and tags, **Account section, sign-in form, migration sheet**: rendering, callbacks, dialogs, undo snackbars, accessibility names |
| AppHub client | `src/test` (MockWebServer) | wire format, envelope and error mapping (`Http`, `Network`, `Protocol`), `Retry-After`, PATCH bodies with explicit nulls, row decoding of DECIMAL strings and 0/1 booleans |
| Session | `src/test` (MockWebServer, fake storage, real DataStore, software AES key) | one shared refresh, new refresh token stored before the access token is usable, rejected versus unavailable refresh, authenticator single retry, sign-out, encryption round trip, tamper and wrong-key rejection |
| Sync core | `src/test` | server timestamp arithmetic, three-way merge rules, field mapping (entity and server row compare equal), base serialisation, backoff |
| Sync engine and migration | `src/androidTest` (real Room, `FakeAppHub` over MockWebServer, two simulated devices) | upload, pull across pages, field-level merge, same-field conflict, delete and undo, idempotent create after a lost reply, offline then recovery, blocked and retried rows, backoff, rate limit, ended session, tag deletion, sign-out keeps data, guest-to-account migration (selection, name clash, offline, repeat, partial earlier upload) |
| Room migration | `src/androidTest` (`MigrationTestHelper`) | a v1 database with data is upgraded to v2 without losing a row |

## Conventions that matter

- **Compose UI tests use `androidx.compose.ui.test.junit4.v2.createComposeRule`.** It queues composition on a `StandardTestDispatcher`, so call `composeRule.waitForIdle()` after `setContent`, and assert state (`assertIsDisplayed`, `assertIsEnabled`) before an action that depends on a just-typed value.
- **Find controls by their visible text or content description** wherever possible. This doubles as an accessibility check; it caught a Floating Action Button with no accessible name.
- **Content composables are stateless** (`ListsScreenContent`, `ListDetailContent`, `CategoriesScreenContent`) and take an actions object whose defaults are no-ops, so tests wire only what they check and need no Hilt.
- **Never block forever in a test.** `MockWebServer.takeRequest()` waits without limit; the HTTP tests use a helper with a 5 s timeout, because a missing request once hung the whole unit-test run. Code that uses `withTimeoutOrNull` (sign-out) is tested on `Dispatchers.Default`, since `runTest` virtual time would fire the timeout at once.
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

## Manual device checklist for sign-in and sync (Phase 3)

Run the fake server first (`node backend/apphub/fake-server/server.mjs --port 8787`), airplane mode off, and a debug build (it points at `10.0.2.2:8787`). Then:

1. Create a guest list with a couple of items, with no account. Settings shows the account section and "1 list is only on this device".
2. Create an account from Settings; the migration sheet should offer the list; move it; check the rows on `GET /__state?dump=1`.
3. Change the data through the API (log in with curl; PATCH an item, POST a new item and list); tap Sync now; the app shows them.
4. Force-stop the app and relaunch: still signed in, no prompt; the next edit reaches the server (the log shows `auth/refresh`).
5. Airplane mode on, add items: Settings says "N changes are waiting to sync"; airplane mode off, do not touch the app: they reach the server within a few seconds.
6. Sign out: lists stay, Settings offers sign-in again.

Gotcha: an emulator left in airplane mode by an earlier check makes every request fail with "can't reach the server" (`adb shell settings get global airplane_mode_on`).

## Not covered yet

- Real drag and swipe gestures are checked by hand on a device (the swipe-then-undo path also has a Compose regression test); there is no automated drag-and-drop test.
- Instrumented tests that go through Hilt and the real database from the UI (end-to-end) do not exist yet.
- Performance with very large lists, cold-start time and battery are Phase 6 items.
- **The real cloud AppHub.** Sync and authentication are tested against fakes only (see [IMPLEMENTATION_STATUS](IMPLEMENTATION_STATUS.md), Phase 3).
- Collaboration, widgets and reminders arrive with their phases; their test plans are in [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md) and [APPHUB_FEASIBILITY §9](APPHUB_FEASIBILITY.md).
