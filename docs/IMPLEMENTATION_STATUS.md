# Implementation Status

Last updated: 2026-11-16 (Phase 3 implemented, awaiting cloud verification). Each phase has its own completion report. A phase is complete only when its exit criteria are verified, not when it compiles.

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

## Phase 2 — Guest shopping lists: complete (2026-11-16)

Everything in product requirements A to F works with no account and no network. It was verified in **airplane mode** on an Android 16 emulator, including after killing the process.

### Deliverables

| Requirement | State | Notes |
|---|---|---|
| Lists: create, rename, edit colour and emoji icon, pin, archive and unarchive, delete with undo, search, sort (recent, name, newest; saved), counts, last updated | Done | "Shared" indicator arrives with collaboration (Phase 4) |
| List detail: inline quick-add, edit item, delete with undo (swipe or edit sheet), bought and unbought, progress, completed section with a show/hide setting, clear completed with undo | Done | Quick-add parses `2 kg milk`, `500g sugar`, `Milk 2 litres`, `3 mangoes` and leaves `7up`, `2% milk` alone |
| Quantity and unit (optional), notes (optional) | Done | Unit suggestions in the edit sheet |
| Drag-and-drop ordering | Done | `sh.calvin.reorderable` 3.1.0; a drag rewrites one row (renumbering only when a gap is used up) |
| Categories: 11 defaults, rename and recolour any of them (with reset), custom create and delete, assign, filter, group by category | Done | Built-ins stay fixed keys; renames and colours are override rows |
| Tags: create inline, several per item, filter, rename and delete with duplicate protection | Done | Tag management lives on the Categories screen |
| Search and filter items (name, category, tag) | Done | |
| Settings: theme, show completed, list sort, group by category | Done | |
| Local reminders, Android widgets | **Not in this phase** | Phase 5, as planned |

### Verification (2026-11-16)

| Check | Result |
|---|---|
| `gradlew testDebugUnitTest` | **Pass, 64 of 64** |
| `gradlew connectedDebugAndroidTest` (Android 16 emulator) | **Pass, 62 of 62** (database 6, repository 18, Lists screen 10, list detail 17, categories and tags 11) |
| `gradlew lintDebug` | **Pass**, 0 errors; one warning, the intentional `OldTargetApi` |
| Manual device pass in airplane mode | **Pass**: edit list (name, colour, emoji); quick-add parsing; toggle to Completed; real drag reorder; real swipe delete and Undo; edit sheet with keyboard; grouped view; custom category; kill and relaunch with everything intact |

### Defects found by verification and fixed

- **Undo after a swipe-delete did not restore the item in place.** `rememberSwipeToDismissBoxState` is saved per lazy-list key, so the restored row came back already swiped away. Found on the device, reproduced by a new Compose regression test that failed first, fixed by resetting the swipe state once the delete is issued, and confirmed on the device with tracing (since removed).
- **The edit sheet's Delete and Save buttons could be hidden by the keyboard.** They are now pinned below the scrolling fields.
- Earlier in the phase: Compose v2 test-rule timing needed `waitForIdle()`; Windows DataStore tests allow one write per store; a stale on-disk database crashed app start after the schema changed (expected before release, and the startup cleanup is now guarded so it can never crash the app).
- A test pass also caught an accessibility defect in Phase 1 (unnamed FAB), and the lesson was applied here: the Lists and Categories floating buttons use the content-slot overload.

### Deviations and decisions

- **Built-in category renames and colours are stored as override rows** (`categories.id` = the built-in key). The architecture doc said built-ins are never rows; they are still fixed keys, the row only carries the user's changes. Documented in [DATABASE_SCHEMA](DATABASE_SCHEMA.md).
- **Room schema v1 was edited in place** (new tables and columns) because nothing has shipped; the exported schema JSON was regenerated. After the first public release every change needs a tested migration.
- **Guest tombstones are purged after 7 days** at app start; cloud tombstones are kept until sync acknowledges them (Phase 3).
- **List icons are emoji** from a small picker (no assets, works offline).
- **Delete list lives on the Lists screen only**; the detail screen offers Archive (reversible), so there is no second place to delete a whole list without undo.
- New docs: [DATABASE_SCHEMA](DATABASE_SCHEMA.md) and [TESTING](TESTING.md).

### Known limitations

- Deleting a category or a tag has no undo (the dialogs say what will happen). Item and list deletion do.
- Drag handles have no keyboard or TalkBack alternative yet ("move up/down" actions); the accessibility pass in Phase 6 should add them. Swipe-delete has an accessible alternative (the edit sheet).
- The quick-add parser knows English units only; strings are English only.
- "Updated 5 minutes ago" does not tick while the screen stays open.
- No automated drag-and-drop test; very large lists, cold-start time and battery are Phase 6 measurements.
- Backup remains excluded (open decision from Phase 1).

### Dev notes

- Install `outputs/apk/debug/app-debug.apk` only after `assembleDebug`; the connected-test task does not refresh it (I lost time testing a stale build).
- The emulator is shut down when idle; restarting it takes about 2 minutes.

## Phase 3 — AppHub sign-in, sync and guest-to-account migration: complete, with listed gaps (2026-11-16)

**Status in one sentence:** everything Phase 3 asked for is built, tested against a fake AppHub (in the tests and on an emulator) **and against the real cloud dev app `shoppinglistdev`**, where the real client stack passed its end-to-end test and the check found one production bug that no fake could (fixed). The gaps that remain are listed under "What is NOT verified"; the production app `shoppinglist` has not been created.

**Cloud dev app (2026-11-16).** The owner's AppHub admin showed no Shopping List app, so with the owner's go-ahead the dev app was created through the admin panel in the owner's signed-in Chrome: database `shoppinglistdev` with the five tables of `schema.sql` (the first run of that file on the real MySQL, no errors), the app registered, `shoppinglist.config.json` saved through Settings, Advanced, health check 12 of 14. With the owner's go-ahead two throwaway accounts were then registered on it (example.com addresses, generated passwords kept outside the repo) and the client was verified against it. The ledger in [backend/apphub/README.md](../backend/apphub/README.md) has the details and the differences found; the two accounts still have to be deleted in the panel (the roles cannot delete).

### Deliverables

| Requirement | State | Where and evidence |
|---|---|---|
| Register, sign in, sign out, token refresh, account info | Done | `data/remote`, `data/session`; Settings, Account; 18 wire tests, 34 session and encryption tests; on the device: account created, signed in, kill and relaunch with no prompt (refresh used), sign-out |
| Password recovery "if supported" | Request is Done; completing it is not | `auth/forgot` is called and a 404 ("not set up") is shown kindly. The reset link AppHub mails points to a page in the app's own web site, and the server's mail is unconfirmed, so the app has no screen for entering the reset token |
| Secure session management | Done | refresh token AES-256-GCM in the Android Keystore (4 on-device tests), access token memory only, one mutex, persist-before-use, no redirects, redacted `toString`s, never auto-retried refresh; rules in [APPHUB_INTEGRATION §3](APPHUB_INTEGRATION.md) |
| Auth requested only when a cloud feature is used | Done | nothing asks at launch; sign-in lives in Settings, Account; guest mode makes no network call |
| Cloud sync with WorkManager | Done | in-process runs 2 s after a change, a network-constrained WorkManager retry when a run fails, a 30-minute periodic run; on the device: offline edits reached the server about 10 s after the connection returned with no interaction |
| Pending operation tracking, sync status, last successful sync time | Done | dirty-row tracking (no separate outbox, see deviations); Settings shows "Up to date", "N changes are waiting to sync", "N could not be synced" with Retry, "Last synced 5 minutes ago", "merged N conflicting edits" |
| Retries with exponential backoff | Done | per row 5 s, 30 s, 2 min, 10 min, 30 min then blocked after 3 failures (4xx blocks at once); run level: offline, 429 (`Retry-After`), 5xx stop the run; WorkManager backoff on top; an outage undoes the penalty its rows received |
| Idempotency and duplicate prevention | Done | client UUIDs, 409 then fetch then merge, deterministic per-account ids for built-in category overrides, PATCH of fields only; tests: lost create answer, repeated migration |
| Conflict detection and resolution | Done | field-level three-way merge against a stored base; same-field conflicts counted and shown, local wins (the server wins when its `updated_at_ms` is later and there is no base), delete always wins; limitation documented ([SYNC_STRATEGY §5.2](SYNC_STRATEGY.md)) |
| Tombstones | Done | deletes sync as `deleted_at_ms`; undo syncs; guest tombstones purge after 7 days, account tombstones only once acknowledged; AppHub roles have no delete permission |
| Guest-to-account migration | Done | per-list selection, name clashes flagged (never merged silently), optional custom categories and tags, nothing deleted, order, colours, tags, notes and bought state preserved, offline and repeated runs recoverable, a list moved back after a sign-out never overwrites newer server data or resurrects a server delete |
| Sign-out and account switching | Done | sign-out keeps every list as guest data; a different account never sees the previous account's data (detached at sign-in and again at the start of each run) |
| Docs | Done | [APPHUB_INTEGRATION](APPHUB_INTEGRATION.md), [SYNC_STRATEGY](SYNC_STRATEGY.md), [DATABASE_SCHEMA](DATABASE_SCHEMA.md) updated, README, CHANGELOG, [TESTING](TESTING.md) |

### Verification (2026-11-16)

| Check | Result |
|---|---|
| `gradlew testDebugUnitTest` | **Pass, 140 of 140** (the 64 from Phase 2, plus AppHub client 18, session and encryption 34, sync core 24) |
| `gradlew connectedDebugAndroidTest` (Android 16 emulator) | **Pass, 122 of 122** (Phase 2: 62; new: sync engine 25, migration 9, account screens 21, Keystore 4, Room v1 to v2 migration 1) plus 3 opt-in cloud tests that are skipped without credentials (they passed against the cloud dev app, below) |
| `gradlew lintDebug` | **Pass**, 0 errors, one warning (the intentional `OldTargetApi`) |
| Fake server self-test (`node backend/apphub/fake-server/smoke.mjs`) | **245 passed, 0 failed**; the config passes AppHub's own validator |
| Hands-on pass on the emulator against the Node fake | **Pass**: guest list created offline; account created and signed in from the app; first sync; migration sheet offered the list and moved it (list, two items, quantity, unit and order verified on the server); another device simulated through the API (rename, new item, new list) and pulled with Sync now; hard kill and relaunch stayed signed in (`auth/refresh` 200) and pushed the next edit; airplane mode: three edits showed "3 changes are waiting to sync", then reached the server about 10 s after the network returned, untouched; sign-out kept both lists as guest data |
| Two independent reviews (security of the session code; correctness of the sync engine) | Both read-only; findings below, all fixed and covered by tests |
| **Real cloud dev app**, protocol script (`shoppinglistdev`, two throwaway accounts) | **43 checks passed**: sign-up rules, login, refresh rotation and reuse detection, column types, PATCH semantics, 422/409/403, paging and sorting, account isolation, database refusals are 500; the 6 early "failures" were the real auth rate limit (429) |
| **Real cloud dev app**, `CloudDevAppTest` on the emulator (real client stack) | **3 of 3 passed**: upload, pull on a second device, field merge, same-field conflict, delete and undo, a real refresh-token rotation, guest-list migration |

### Defects found by verification and review, and fixed

* **`MigrationRepository` read the session before it was restored** (3 instrumented tests failed): it now restores first.
* **Found only against the real cloud: idle keep-alive connections.** The server closes idle connections after a few seconds, and the auth client has no automatic retry (a resend of a one-time refresh token could end the session), so the first sign-in or refresh after a pause failed as a false "can't reach the server". Auth calls now use a fresh connection each time (`AppHubHttp.authClient`); data calls retry dropped pooled connections (idempotent). A second real-server finding: an account can hold two override rows for one built-in category (made by other software); the client now trusts only the account's deterministic id.
* **The emulator was still in airplane mode** from the Phase 2 offline check, so the first on-device sign-in correctly said "can't reach the server" (an unplanned check of that error path).
* **The debug base URL was wrong** (`/apphub/` prefix only exists on the cloud server): found by the first real on-device request, fixed and documented.
* Security review: a bare 401/403 from a proxy ended the session (now only AppHub's own error envelope does); a cancelled first `restore()` latched "signed out" for the process; failed token writes were ignored (now retried before the next handout, and a sign-in that cannot be stored clears the previous account's session); redirects could resend a password or one-time token; the authenticator could starve its own refresh call (own dispatcher); secrets in `toString` and in an exception cause; a temporary Keystore error was read as a lost key; double-tap started two logins; display name was not capped; a half-finished account switch is now finished at the start of every sync run.
* Sync review (one high): **moving a stale detached list back into the account could overwrite newer server edits and resurrect a server delete**; now merged by the ordinary rules with `updated_at_ms` as the tie-break. Also: a created-then-deleted row is no longer purged without telling the server and Undo works for 15 seconds; items of a list deleted before a device arrived no longer pin the pull cursor; a tag that arrives late restores the links of items stored earlier; timestamps no longer depend on the device language; no page cap that could skip old rows; an outage that answers 500 no longer pushes rows towards "blocked"; editing a built-in category while signed in makes it the account's.

### Deviations from the Phase 0 documents

* **Dirty-row tracking instead of a `pending_ops` outbox.** A row is dirty when `revision > serverRevision` or it has no sync base; a `sync_entities` row stores the last-agreed server snapshot. Coalescing, field-level diffs and merge come free, and no repository method can forget to enqueue.
* **No `item_tags` table in the cloud**: an item's tags travel as the JSON text column `tag_ids`.
* **Two additions to the planned cloud contract**, made before anything was applied: `categories.builtin_key` (a global primary key made built-in overrides collide between accounts) and `updated_at_ms` on lists and items (cross-device "last updated").
* **Debug builds point at the local fake server**, release builds at the cloud app; the app declares INTERNET and ACCESS_NETWORK_STATE.
* **Room schema v2** by auto-migration, tested from a v1 database with data (the "edit v1 in place" shortcut of Phase 2 ended with the first sync tables).

### What is NOT verified

* **The screens driven by hand against the cloud.** The real client stack was tested against the cloud dev app through `CloudDevAppTest` (no UI), and the screens by hand against the fake; typing a password into the Account sheet against the cloud was not done.
* The **production app** `shoppinglist` (not created), the real server's mail (password reset answers 404, so completing a reset and email verification are untested), and the real server's behaviour under a lost refresh answer (tested against the fake with `drop_after`).
* A physical device, an API 24 to 28 device, a release (R8) build, a very large account (thousands of rows), battery use of the periodic job.

### Known limitations

* Two devices editing the **same field** offline: the last to sync wins and the other edit is lost once (counted and shown). Revision checks arrive with shared lists (Phase 4).
* Same-named tags created on two devices stay two tags until one is deleted. A rename of a built-in category made as a guest stays local until moved.
* No push: another device's change shows on the next run (app start, Sync now, a local change, or within 30 minutes).
* There is no button for a full resync yet (`SyncRepository.fullResync` exists) and no banner on the Lists screen inviting guests to sign in; both are small follow-ups.
* AppHub has no account deletion endpoint, so the app cannot offer "delete my account".

### Left for the owner

1. With your MySQL admin login (the panel's Tables, Run SQL asks for it each time; Claude does not have it), run on `shoppinglistdev`: `ALTER TABLE users DROP CHECK chk_users_status; ALTER TABLE users ADD CONSTRAINT chk_users_status CHECK (status IN ('active','inactive','suspended'));` (a defect found while cleaning up: AppHub's **Suspend account** writes `suspended`, which the first version of `schema.sql` refused with a 500; `schema.sql` is fixed, the live dev database is not), then `DELETE FROM users WHERE email LIKE 'slverify-%@example.com';` to remove the two throwaway accounts (`slverify-a-75794793@example.com`, `slverify-b-75794793@example.com`; the foreign keys cascade their rows). Both accounts are already signed out everywhere; the panel has no delete-user action and the app roles cannot delete.
2. Say whether the server can send mail (password reset and email verification depend on it) and when to create the production app `shoppinglist` from the same files.
3. Optionally try the app by hand against the dev app: a debug build with `-PSL_APPHUB_BASE_URL=https://132-226-190-156.sslip.io/apphub/` and a throwaway account of your own.

## Phases 4 to 6

Not started. Plan: [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md). Phase 4 (collaboration) additionally needs the validation spike of [APPHUB_FEASIBILITY §9](APPHUB_FEASIBILITY.md) and an operator path for installing database triggers.
