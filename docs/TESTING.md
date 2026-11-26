# Testing

Status: reflects Phase 3 (2026-11-16). Counts are from the last full run: **140 unit tests and 122 instrumented tests, all passing, plus 3 opt-in cloud tests (skipped without credentials); lint 0 errors**. The fake AppHub server has its own 245-check self-test (`node backend/apphub/fake-server/smoke.mjs`).

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

## Against a real AppHub app (opt-in)

`CloudDevAppTest` runs the real client stack (session, refresh, sync engine, merge, migration) on the emulator against an AppHub app. It is skipped unless credentials are passed, so no secret is ever in the repository. Use a **throwaway account on a dev app**: the roles have no delete permission, so the rows stay until an admin deletes the user.

```powershell
.\gradlew.bat connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=space.abhijithkm.shoppinglist.data.sync.CloudDevAppTest" `
  "-Pandroid.testInstrumentationRunnerArguments.cloudBaseUrl=https://132-226-190-156.sslip.io/apphub/" `
  "-Pandroid.testInstrumentationRunnerArguments.cloudApp=shoppinglistdev" `
  "-Pandroid.testInstrumentationRunnerArguments.cloudEmail=<throwaway address>" `
  "-Pandroid.testInstrumentationRunnerArguments.cloudPassword=<its password>"
```

AppHub allows about 10 auth calls a minute per IP (429 beyond that), so the test waits seven seconds before every sign-in and refresh. Read the password from a local file into a variable instead of typing it in a command that ends up in a transcript.

## Manual device checklist for sign-in and sync (Phase 3)

Run the fake server first (`node backend/apphub/fake-server/server.mjs --port 8787`), airplane mode off, and a debug build (it points at `10.0.2.2:8787`). Then:

1. Create a guest list with a couple of items, with no account. Settings shows the account section and "1 list is only on this device".
2. Create an account from Settings; the migration sheet should offer the list; move it; check the rows on `GET /__state?dump=1`.
3. Change the data through the API (log in with curl; PATCH an item, POST a new item and list); tap Sync now; the app shows them.
4. Force-stop the app and relaunch: still signed in, no prompt; the next edit reaches the server (the log shows `auth/refresh`).
5. Airplane mode on, add items: Settings says "N changes are waiting to sync"; airplane mode off, do not touch the app: they reach the server within a few seconds.
6. Sign out: lists stay, Settings offers sign-in again.

Gotcha: an emulator left in airplane mode by an earlier check makes every request fail with "can't reach the server" (`adb shell settings get global airplane_mode_on`).

## Phase 5: widget, reminders, shopping mode

Automated: `ReminderMathTest` (once, daily, weekly, DST gap and overlap, time-zone change, the boot catch-up boundaries), `ReminderSchedulerTest` (cancel for a deleted, archived or disabled list, re-arm on restore, skip rules, catch-up within 24 h, the global switch), `ShoppingModeContentTest` and `ShoppingModeRefresherTest`, `SurfaceUpdaterTest` (a burst of writes refreshes once; fake change source), `WidgetRowsTest` (incl. the `widgetModels` flow), `OpenListResolveTest`, `ReminderViewModelTest`, `SettingsRepositoryTest`; instrumented: `SurfaceDaoTest`, the Room v2 to v3 migration, `MarkAllBoughtTest`, `SurfaceUpdaterWritePathsTest` (any Room writer triggers a refresh), `SurfaceNotifierTest` (waits for the system's asynchronous posts), `ReminderAlarmsTest` (a `PendingIntent` with `FLAG_NO_CREATE` proves an alarm exists), `OpenListIntentTest`, `NotificationsUiTest`.

Not automated, because there is no Hilt test setup and a `GlanceId` exists only for a widget hosted by a launcher: the widget itself (chooser, tick, quick-add) and the receivers. Check them by hand on an emulator:

1. Install the debug build, grant `android.permission.POST_NOTIFICATIONS` (`adb shell pm grant`), launch once.
2. Seed a list (a debug build lets `run-as` read the database): stop the app, then with `adb shell run-as space.abhijithkm.shoppinglist sqlite3 databases/shoppinglist.db` insert a row into `lists`, a few into `items`, `list_prefs (listId, shoppingMode) = (id, 1)` and a `reminders` row with `oneTimeAtMillis` 90 s ahead; start the app. Room does not see writes from another process, so restart the app after each seeding.
3. `adb shell dumpsys notification --noredact` shows the ongoing silent notification; `adb shell dumpsys alarm | grep ReminderReceiver` shows the alarm; wait for it to fire (inexact: possibly a minute late) and check the reminder notification and that the one-time row is disabled.
4. Pull the notification shade down, expand the shopping-mode notification, tap Mark all done: items become bought, the notification goes away.
5. `adb shell am start -n space.abhijithkm.shoppinglist/.MainActivity --es open_list <id>` opens the list; an unknown id shows the "not available" message on the Lists screen.
6. Long-press the home screen, Widgets, Shopping List, Add: the chooser appears; choose the list; the widget shows the list. Tick an item (the row and the widget change at once); tap "+" and add "2 kg rice".
7. `adb reboot`, do not open the app: about a minute after boot `dumpsys alarm` shows the reminder alarm again.

Gotchas: typing with `adb shell input text` into a dialog fails if the soft keyboard moved it (screenshot first); `adb shell settings put secure stylus_handwriting_enabled 0` stops the keyboard's stylus tutorial swallowing text.

## Not covered yet

- Real drag and swipe gestures are checked by hand on a device (the swipe-then-undo path also has a Compose regression test); there is no automated drag-and-drop test.
- Instrumented tests that go through Hilt and the real database from the UI (end-to-end) do not exist yet.
- Performance with very large lists, cold-start time and battery are Phase 6 items.
- **The real cloud AppHub.** Sync and authentication are tested against fakes only (see [IMPLEMENTATION_STATUS](IMPLEMENTATION_STATUS.md), Phase 3).
- Collaboration is parked (Phase 4); its test plan is in [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md) and [APPHUB_FEASIBILITY §9](APPHUB_FEASIBILITY.md).
- Phase 5 on a physical device, Android 7 to 12, a release (R8) build, a real time-zone change, the system permission dialog, and TalkBack are not covered (see [IMPLEMENTATION_STATUS](IMPLEMENTATION_STATUS.md)).
