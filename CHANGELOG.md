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

### Fixed
- The "New list" button had no accessible name for screen readers; it now exposes its label.
- Undo after swiping an item away restored it still swiped away; it now comes back in place.
- The item edit sheet's Delete and Save buttons could be hidden by the keyboard; they are now pinned below the scrolling fields.
