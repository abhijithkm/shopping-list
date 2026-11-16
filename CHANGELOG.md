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
