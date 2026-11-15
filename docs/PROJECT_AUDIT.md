# Project Audit — Shopping List (Phase 0)

Date: 2026-11-15 · Status: **audit only, nothing implemented, no cloud writes** · Related: [APPHUB_FEASIBILITY](APPHUB_FEASIBILITY.md), [ARCHITECTURE_PROPOSAL](ARCHITECTURE_PROPOSAL.md), [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md)

## 1. Scope and method

Examined, read-only:

| Source | What was done |
|---|---|
| GitHub repo `abhijithkm/shopping-list` | Inspected local clone, remote state via `gh` |
| Local machine toolchain | JDK, Android SDK, Gradle, emulator availability |
| Cloud AppHub public guide, SDK (`apphub.js`, `apphub.d.ts`) | Downloaded with `GET`, read in full |
| Cloud AppHub live server | Unauthenticated `GET` probes only (health, app-existence). No sign-in, no writes |
| Local AppHub source `C:\xampp\htdocs\apphub` | **Reference only.** Audited by source reading (107 commits, `main` @ `eb6aa36`). Not run, not used as a database |
| Reference app `C:\MyApps\ReadRhythm` | Read `apphub/`, `mobile/src/{lib,offline}`, docs, changelog. `.env` files were not opened |
| Maven Central / Google Maven | Current stable library versions (fetched 2026-11-15) |

Not done, by design: no AppHub tables, apps, users or config were created or changed. No Android project was initialised. Nothing was committed or pushed.

Project rule recorded from the user: **the database is built in the cloud AppHub only.** The local AppHub is a reference for server behaviour and is never used to host data.

Evidence tags used in these documents:

- **[guide]** stated in the public cloud guide fetched today
- **[src]** confirmed by reading local AppHub source (`eb6aa36`)
- **[self]** confirmed by me directly in source, in this audit
- **[unverified]** inferred or untested — must be proven by a spike on a cloud dev app before it is relied on

## 2. Repository state

- `abhijithkm/shopping-list` is **public** and **empty**: no commits, no branches on `origin`. Local clone has only `.git`.
- Local branch `main`. Remote `origin` = `https://github.com/abhijithkm/shopping-list.git`. `gh` is authenticated as `abhijithkm`.
- Git identity: the repo belongs to the personal GitHub account, so the **repository-local** git identity was set to the owner's personal name and address. The global identity (work address) is untouched.
- Because the repo is public, everything committed, including these docs, is world-readable. Rules: no keystores, no passwords, no tokens, no test-account credentials. The AppHub base URL and app name are public values (the guide itself is public).
- Branching proposal from the master prompt is kept: `main` (stable), `develop`, `feature/*`, `fix/*`. The first commit will be these docs on `develop` after approval, then a merge to `main` only at release.
- Required before the first code commit: `.gitignore` (Android, Gradle, `local.properties`, `*.jks`, `*.keystore`, `*.p12`, `.env*`, `secrets/`, `build/`, IDE files), plus a rule that signing secrets live only in `~/.gradle/gradle.properties`.

## 3. Local toolchain

| Item | Finding | Consequence |
|---|---|---|
| OS | Windows 11 | Add `.gitattributes` (`* text=auto eol=lf`, `*.bat eol=crlf`) |
| `JAVA_HOME` | JDK 17 (Temurin) | OK for AGP 8.x/9.x |
| `java` on `PATH` | **1.8.0_501** | Gradle must use `JAVA_HOME`/toolchain 17; do not rely on `PATH`. Phase 1 pins `org.gradle.java.home` or a Gradle toolchain |
| Android SDK | `ANDROID_HOME` set; platforms `android-36`, `36.1`, `37.0`; build-tools 35–37; `emulator`, `platform-tools`, `cmake`, `ndk` present | Can compile and target API 36 now |
| Gradle | not installed system-wide | Use the Gradle wrapper (generated in Phase 1) |
| Android Studio | installed | Bundled JBR can be used as JDK |
| AVDs | none found in `%USERPROFILE%\.android\avd` | Need an emulator image (API 36 plus one API 24–26 image for min-SDK checks) or a physical device for widget, notification and reboot tests |

## 4. Reference application: ReadRhythm

Expo / React Native app by the same author on the same AppHub. The points that matter for this project:

1. **Backend as files, not clicks.** `apphub/` holds `schema.sql`, `triggers.sql`, `*.config.json`, `provision.sh`, `db-test.sh`, `shape-compare.sh`, `isolation-test.mjs`. Triggers are installed **over SSH**, because AppHub's panel refuses `CREATE TRIGGER`. We adopt the same discipline in `backend/apphub/`.
2. **Conventions to reuse:** `id INT UNSIGNED AUTO_INCREMENT` (or a client-supplied string key, see feasibility doc), owner column `user_id`, UTC `created_at`/`updated_at`, `ON DELETE CASCADE`, CHECK constraints (MySQL ≥ 8.0.16), UNIQUE keys that surface as 409.
3. **ReadRhythm is not offline-first.** The server is its source of truth; only one write kind is queued; no conflict handling; no idempotency key (it uses a "find the row after a lost answer" heuristic it calls "a guard, not a guarantee"). We must not copy that model. We copy only the outbox mechanics: persist each step before the next, reset `syncing` to `pending` at start, backoff 5 s / 30 s / 2 min / 10 min, honour 429 `Retry-After`, stop on 401, keep failed items visible to the user.
4. **Auth lessons:** one process-wide mutex around refresh; persist the rotated refresh token before using it; never sign out on a network failure (status 0).
5. **UI tokens** (indigo palette, 48 dp touch targets, offline banner with "N waiting to sync", "Saved on this phone" wording) are a good starting point for a Material 3 theme but are not mandatory.
6. **Release process:** release keystore read from `~/.gradle/gradle.properties`, never committed; a pitfall where a missing key silently fell back to the debug key (we make the build fail instead); 16 KB page alignment, R8, `allowBackup=false`, blocked unused permissions, Play data-safety, privacy page and account-deletion page prepared early. Package id convention: `space.abhijithkm.<app>`.
7. **Hygiene warning:** ReadRhythm's `mobile/` was never committed. Here, commit from day one.
8. The server already hosts the apps `fueltracker`, `leavetracker`, `shop`, `submitflow`, `readrhythm`. **Do not reuse `shop`** (it is the guide's example name).

## 5. Cloud AppHub: what was verified directly

| Check | Result |
|---|---|
| `GET /apphub/` | `200 {"ok":true,"data":{"status":"ok"}}`; Apache/2.4.58 (Ubuntu), HSTS on |
| `GET /apphub/{app}/auth/access` with no credentials | `401 Authentication required` for **every** name tried (`readrhythm`, `shoppinglist`, `shopping-list`, `shopping_list`, `shop`, `health`). App existence therefore cannot be probed anonymously |
| Guide hash vs local | `docs/API.md sha256 8e5b9b57a0b9184a` is **identical** in the cloud guide page and the local `ui/guide/index.html` |
| SDK hash vs local | `apphub.js` source sha256 prefix `8fa9feb2b4ce51fc` matches the local `ui/sdk/src/apphub.js`; both SDK files' md5 match the local build's ETags |

Conclusion: the cloud deployment's documented surface and SDK are byte-identical to the local checkout, which makes the local PHP source a trustworthy reference for **behaviour**. This is evidence, not proof, that the PHP core matches; the dev-app spike ([APPHUB_FEASIBILITY §9](APPHUB_FEASIBILITY.md)) closes that gap. The cloud database is **MySQL 8.0**, whereas the local tests ran on **MariaDB 10.4**: anything trigger-related must be proven on the cloud dev app, not locally.

## 6. Dependency baseline (stable, fetched 2026-11-15)

| Library | Stable | Notes |
|---|---|---|
| Compose BOM | 2026.09.00 | Material 3 comes via the BOM (`material3` 1.4.0 stable; 1.5.0 is RC) |
| Room | 2.8.5 | KSP |
| Glance (appwidget) | 1.2.0 | 1.3.0 is alpha, so stay on 1.2.0 |
| WorkManager | 2.12.0 | |
| DataStore | 1.3.0 | |
| Navigation Compose | 2.10.2 | |
| Activity Compose | 1.13.0 | 1.14 is alpha |
| Lifecycle (viewmodel-compose) | 2.11.0 | 2.12 is alpha |
| Hilt / hilt-navigation-compose / hilt-work | 2.60.1 / 1.4.0 / 1.4.0 | |
| KSP | 2.3.14 | |
| Kotlin | 2.4.21 | 2.5 is RC |
| AGP | 9.5.1 stable | **Risk:** AGP 9 changed built-in Kotlin/KSP handling. Phase 1 starts with a throw-away compatibility spike (AGP × Kotlin × KSP × Hilt × Room). Fallback: latest AGP 8.x |
| Retrofit / OkHttp | 3.0.0 / 5.5.1 | Retrofit 3's first-party kotlinx-serialization converter to be confirmed in the spike |
| kotlinx-serialization-json / coroutines | 1.13.0 / 1.11.0 | |
| `sh.calvin.reorderable` | 3.1.0 | Third-party; needed because LazyColumn has no stable reorder API. The only non-Google/Square/JetBrains UI library proposed |
| Test: MockK / Turbine / MockWebServer | 1.14.12 / 1.2.1 / 5.5.1 | |

Deliberately **not** used: `androidx.security:security-crypto` (reported deprecated; AppHub's own Android guide recommends DataStore plus a Keystore key) and Firebase of any kind.

SDK levels: `minSdk 24` (spec); `compileSdk` and `targetSdk` **36**. Google Play's policy page lists Android 16 (API 36) as the target for new apps and updates from 2026-08-31. Re-check the Play Console policy at release time (Phase 6).

## 7. Risk register (project level)

Backend-specific risks are in [APPHUB_FEASIBILITY §8](APPHUB_FEASIBILITY.md). Ranked:

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | Collaboration authorization needs MySQL triggers; AppHub's panel cannot create them; they need SSH plus MySQL admin on the cloud server | High | Repo-managed `backend/apphub/` SQL and provisioning script; user-approved operator step; dev app first |
| R2 | Refresh token rotation has **no grace window**; a lost refresh response followed by a retry revokes the whole session [self] | High | Persist-before-use, single mutex, never auto-retry the refresh call, soft re-login without data loss; optional AppHub patch (grace window) |
| R3 | No delta/`since` filter, no batch endpoint, no ETag; equality filters only; 120 req/min per IP shared across mobile NAT | High | One stream per entity sorted by a trigger-maintained `updated_at`; overlap window; coalesced outbox; concurrency cap 2 |
| R4 | Trigger behaviour on MySQL 8.0 is untested by AppHub (its tests ran on MariaDB 10.4) | High | Spike on a cloud dev app before Phase 4; automated trigger test script |
| R5 | Cloud mail transport may be `log` only, so no verification/reset/invitation emails | Medium | Confirm SMTP; core flows must not depend on email |
| R6 | No account-deletion endpoint; Play requires in-app and web deletion | Medium | Config `users.delete = own` (untested) or a small AppHub change; decide before release |
| R7 | One shared 1 vCPU server hosts five other apps | Medium | Keep sync chatty-minimal; dev app separate; no load tests against prod |
| R8 | AGP 9 / Kotlin 2.4 / KSP / Hilt compatibility unknown until built | Medium | Phase 1 spike; fallback versions |
| R9 | Public repo | Low | Secret-scanning pre-commit check; no credentials in docs |
| R10 | Triggers are not captured by AppHub's panel dump/backup | Medium | Triggers live in git; manual `mysqldump --routines` in the runbook |

## 8. Inputs needed from the user (blocking for Phases 1, 3, 4)

1. **Approval** of the proposals in the four Phase 0 docs.
2. **App names** on the cloud AppHub: production (proposed `shoppinglist`) and a separate dev/test app (proposed `shoppinglistdev`), and permission to create both.
3. **Operator access path** for what the panel cannot do (installing triggers): either an SSH tunnel with MySQL admin rights, or you run the provided SQL yourself. Credentials are never to be placed in chat or in the repo.
4. **Test accounts**: at least two registered users on the dev app (owner and editor) for collaboration tests, supplied through a git-ignored local file or environment variables.
5. **Decision on invitation-code generation** (client-generated and server-hashed, versus an AppHub core change). See [APPHUB_FEASIBILITY §6](APPHUB_FEASIBILITY.md).
6. **Android `applicationId`** (proposed `space.abhijithkm.shoppinglist`, matching ReadRhythm). It cannot change after the first Play release.
7. **SMTP status** of the cloud AppHub (invitation and reset emails).
