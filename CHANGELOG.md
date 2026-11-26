# Changelog

All notable changes to this project are recorded here. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Phase 0 audit documents: project audit, AppHub feasibility, architecture proposal, implementation plan.
- Android project foundation (Phase 1): Gradle 9.9.1 wrapper with pinned checksum, version catalog, AGP 9.5.1 / Kotlin 2.4.21 / KSP / Hilt / Room / Compose build.
- Room schema v1 (`lists`, `items`) with exported schema JSON; DataStore-backed settings (theme, show completed).
- Material 3 light/dark theme, three-destination navigation shell (Lists, Categories, Settings), launcher icon.
- Lists screen with live item counts and a create-list dialog; read-only default categories; settings screen.
- Unit tests (13) and instrumented test sources for the database and the Lists screen.
- Release signing that refuses to build without an upload key.
- Verified on an Android 16 (API 36) emulator: 13 unit tests, 9 instrumented tests, install and launch, list and theme persistence across a process kill.

- Phase 2, guest shopping lists, fully offline: edit, pin, archive, delete (with undo), search and sort lists with colour and emoji; list detail with quick-add ("2 kg milk"), open and completed sections, progress, drag-to-reorder, swipe-to-delete with undo, clear completed, item edit sheet (quantity, unit, category, tags, notes), search and category/tag filters, group by category; category management (rename or recolour the 11 built-ins, custom categories) and tag management.
- Room schema v1 gains categories, tags and item_tags; every update bumps a local revision counter for the later sync.
- 64 unit tests and 62 instrumented tests; docs `DATABASE_SCHEMA.md` and `TESTING.md`.

- Phase 3, optional account and sync: AppHub client (Retrofit, OkHttp, kotlinx.serialization) with typed errors; sign in, create account and password-reset request from Settings, Account (never at launch); secure session (refresh token encrypted with an Android Keystore AES-GCM key, access token in memory, one shared refresh, no logging of credentials); offline-first sync of lists, items, categories and tags (dirty-row tracking, field-level diff, three-way merge with conflict counting, tombstones, backoff, blocked rows with Retry, paced under AppHub's rate limit); WorkManager (periodic, network-constrained retry) driven by a coordinator that follows the session; guest-to-account migration with per-list selection, same-name warnings and no deletion; sign-out that keeps every list as guest data; account switching that never mixes two accounts' data.
- Room schema v2 (`sync_entities`, `sync_state`) with an auto-migration tested from a v1 database that holds data.
- `backend/apphub/`: the cloud app's definition (`schema.sql`, `shoppinglist.config.json`, validator, runbook) and a faithful fake AppHub server for development. **Nothing has been applied to any server.**
- Docs: `APPHUB_INTEGRATION.md`, `SYNC_STRATEGY.md`; `DATABASE_SCHEMA.md` updated.
- The cloud dev app `shoppinglistdev` (schema and config applied through the AppHub admin) and an opt-in on-device test, `CloudDevAppTest`, that runs the real client stack against a real AppHub app.

- Phase 5: a Glance home-screen widget (one list per widget, tick items off, quick-add dialog, tap the title to open the list), per-list timed reminders (once, daily or weekly, optionally only when items are left; inexact alarms, restored after reboot, time-zone and clock change), a per-list ongoing "Shopping mode" notification with Open list and Mark all done, an open-a-list link, and two global switches in Settings. Room schema v3 (`reminders`, `list_prefs`, local only). Verified on an Android 16 emulator: 216 unit tests, 167 instrumented tests, and a hands-on pass with a real alarm, notification and launcher widget. Phase 4 (sharing) is parked on its own branch.

### Changed
- Core library desugaring is enabled (`desugar_jdk_libs` 2.1.5) so `java.time` works on Android 7 and 7.1; `minSdk` stays 24.
- Deleting a tag now also marks the items that carried it as edited, so the change syncs.
- Account tombstones are purged only after the server acknowledged them; guest tombstones as before.
- Debug builds talk to the local fake AppHub (`10.0.2.2:8787`); release builds to the cloud AppHub over HTTPS only. The app now declares the INTERNET and ACCESS_NETWORK_STATE permissions.

### Fixed
- Sign-in and token refresh no longer fail with a false "can't reach the server" after the connection has been idle: the real server closes idle connections, so auth calls now use a fresh connection each time (found against the real cloud app).
- Only the account's own row counts as the override of a built-in category; another row naming the same built-in is ignored.
- The "New list" button had no accessible name for screen readers; it now exposes its label.
- Undo after swiping an item away restored it still swiped away; it now comes back in place.
- The item edit sheet's Delete and Save buttons could be hidden by the keyboard; they are now pinned below the scrolling fields.
