# Implementation Plan — Shopping List (Phase 0)

Date: 2026-11-15 · Status: **proposal; nothing starts until you approve** · See [PROJECT_AUDIT](PROJECT_AUDIT.md), [ARCHITECTURE_PROPOSAL](ARCHITECTURE_PROPOSAL.md), [APPHUB_FEASIBILITY](APPHUB_FEASIBILITY.md)

## 1. Shape of the work

Two tracks. The backend track exists because collaboration depends on MySQL triggers that must be proven on the cloud MySQL 8.0 before the client relies on them, and because that needs operator access that only you can grant.

```
Track A (Android):  P1 Foundation → P2 Guest lists → P3 Auth + Sync → P4 Collaboration → P5 Widgets + Reminders → P6 QA + Release
Track B (Backend):  B0 Dev app + spike (S1–S14) ───────────────→ B1 Prod app provisioning ──→ (feeds P3 and P4)
```

B0 starts alongside P1 so its results land before P3 needs them. P2 (all local) does not depend on the backend, so a delay in operator access does not block visible progress.

Complexity scale: S ≈ 1–3 days, M ≈ 4–7, L ≈ 8–12, XL ≈ 13+, in focused engineering days. These are rough estimates with low confidence (new toolchain versions, an unproven backend pattern); the two largest uncertainties are called out per phase.

| Phase | Complexity | Rough effort | Main uncertainty |
|---|---|---|---|
| B0 Dev app + spike | M | 4–6 d (plus operator wait) | MySQL 8.0 trigger behaviour; operator access |
| P1 Foundation | S–M | 3–4 d | AGP 9 / Kotlin 2.4 / KSP / Hilt compatibility |
| P2 Guest lists | L | 9–12 d | Drag-and-drop reorder + inline quick-add polish |
| P3 Auth + sync | L–XL | 10–14 d | Idempotent sync under lost responses; refresh rotation without grace |
| P4 Collaboration | XL | 12–16 d | Trigger design correctness; conflict UX |
| P5 Widgets + reminders | M–L | 6–9 d | Glance limits; OEM background restrictions |
| P6 QA + release | M | 6–8 d | Play requirements (data safety, account deletion) |
| **Total** | | **≈ 50–70 d** | |

## 2. Working agreements (from the master prompt)

- One lead (me) owns integration; specialist agents only where work is independent; **each file has one owner at a time**; architectural decisions are shared before implementation; every agent change is reviewed by me before it lands.
- Agent ownership: **Architect** — Gradle, DI, packages, dependency versions. **UI/UX** — `feature/*`, `ui/theme`, `widget` UI. **Database** — `data/local`, repositories, migrations. **Backend** — `backend/apphub/`, `data/remote`, auth, sync contract. **QA** — test plans, fakes, backend scripts, reports.
- Branches: `develop` integration, `feature/*`, `fix/*`, `main` stable. Small commits with meaningful messages; no attribution trailers; **repository-local personal git identity** verified before each commit (`git config user.email`) and confirmed after (`git log -1 --format='%an <%ae> | %cn <%ce>'`).
- Never committed: secrets, tokens, passwords, keystores, test-account credentials, `local.properties`, build output.
- After every significant phase: run the Gradle build and the relevant tests, write a **phase completion report** in `docs/IMPLEMENTATION_STATUS.md`, and update the docs that changed. A phase is not complete because it compiles.
- Always working: the project stays buildable on `develop` at every commit.

## 3. Phases

### B0 — Cloud dev app and validation spike (Track B)

*Needs from you:* app names, permission to create a dev app, operator access path for triggers, two test users (PROJECT_AUDIT §8).

Deliver: `backend/apphub/` (schema, triggers, config, provisioning steps, tests, ledger); the dev app provisioned **in the cloud**; spike results S1–S14 recorded in `docs/APPHUB_INTEGRATION.md` and `docs/DATABASE_SCHEMA.md`.

Exit criteria: S2, S3, S4, S7, S8, S9 pass (or the affected flow is re-planned onto feasibility §10); account-deletion and refresh-grace decisions recorded; test scripts repeatable from a clean dev DB.

### P1 — Android foundation

Deliver: project skeleton; Gradle Kotlin DSL; version catalog; pinned JDK 17 toolchain; Hilt; Room (empty-but-real schema v1 with exported JSON); DataStore; Material 3 theme (light/dark); navigation shell with the three destinations; CI-less local checks (`./gradlew test lint`); `.gitignore`, `.gitattributes`; release signing config that fails without a key; first unit and instrumented tests.

Starts with a **compatibility spike** (AGP × Kotlin × KSP × Hilt × Room × Compose compiler) before any feature code; falls back to AGP 8.x if needed.

Exit criteria: app builds, installs and launches on an emulator; DB opens and migrates; theme toggles; tests green; build output shown, not assumed.

### P2 — Guest shopping lists (no network code at all)

Deliver: everything in product requirements A–F that is local: lists (create/rename/delete/archive/pin/search/sort, counts, colour/icon), items (inline quick-add, edit, delete with undo, bought/unbought, quantity/unit, notes, drag reorder, completed section, hide/show setting, clear completed, progress), categories (11 defaults, custom, rename, colour, filter, group), tags (multi, filter), settings. Room schema v1 final for local entities.

Exit criteria: all P2 behaviours verified on an emulator **in airplane mode**; data survives process death and app restart; DAO/ViewModel unit tests; Compose UI tests for add/undo/reorder/complete; no network dependency compiled into feature code.

### P3 — AppHub authentication, sync, migration

Deliver: `AppHubAuthService` + session manager (Keystore, mutex, rotation rules); register/login/logout/me/access, password recovery UI (works only if SMTP is confirmed); outbox, push/pull engines, conflict resolver, `SyncWorker`, sync status UI; guest→account migration; personal cloud lists syncing; production app provisioning (B1) after spike sign-off.

Exit criteria: against the **cloud dev app**: register/login/logout; expired access token refresh; **lost refresh response** handled without data loss; offline edit then sync; duplicate prevention on retried creates; fault-injected "fail before/after" tests green; migration of several lists with a forced mid-way failure and resume; second-device pull; guest data intact when auth fails.

### P4 — Collaborative lists

Gate: B0 spike passed. Deliver: sharing UI; email lookup and invitations; join codes and join requests; owner approval; member removal, leave, revoke, regenerate; role-aware UI (server enforces; UI only reflects); shared editing with revision conflicts; local notifications for invitations and shared activity; account-switching behaviour.

Exit criteria (security verified **before** enabling in the UI): full authorization matrix script green on the cloud dev app (non-member read/insert, editor list edits, `list_id` moves, self-grant, forged commands, wrong-user accept, revoked member); simultaneous modification test; disconnect-during-edit test; invitation expiry; code brute-force throttle and log-scan (no plaintext code in any log). Two real accounts on two devices/emulators.

### P5 — Widgets and reminders

Deliver: Glance widget with per-instance list selection, check-off, quick-add activity, open-list; update after local and sync changes (debounced, no periodic refresh); scheduled and incomplete-list reminders; notification channels and permission flow; per-category notification toggles; reschedule on boot / time-zone / time change.

Exit criteria: multiple widget instances with different lists; toggle works in airplane mode and survives reboot; widgets update after app changes and after sync; reminder create/edit/cancel/reboot/permission-denied/time-zone matrix passes on an emulator (and ideally one physical device).

### P6 — QA and release

Deliver: full test run, performance pass (cold start, long lists, Room indices, battery: no extra workers), accessibility pass, final docs (`README`, `CHANGELOG`, `ARCHITECTURE`, `DATABASE_SCHEMA`, `APPHUB_INTEGRATION`, `SYNC_STRATEGY`, `IMPLEMENTATION_STATUS`, `TESTING`), privacy policy and account-deletion page content, Play data-safety notes, signed **release AAB/APK** with signature verified (`apksigner verify --print-certs`), tagged release on `main`.

Exit criteria: Definition of Done checklist (master prompt §14) item by item, with evidence; final report (features, architecture, AppHub details, schema, tests and results, known limitations, artifact location, commit history). Re-check Play's target-API policy and data-safety form at this point.

## 4. Test plan summary

| Area | Where it runs | Mechanism |
|---|---|---|
| Guest mode, persistence, airplane mode | Emulator/device | Instrumented + manual script |
| Auth, expired token, network failure | Unit + cloud dev app | MockWebServer fake + real dev-app smoke |
| Sync idempotency, lost responses, conflicts | Unit/JVM | Fake AppHub with fault injection; two simulated clients |
| Migration and duplicates | Instrumented + dev app | Forced failure and resume |
| Collaboration authorization | **Cloud dev app only** | Node scripts (`backend/apphub/tests`) with two users |
| Throttles / log-scan | Cloud dev app (operator-assisted for server logs) | Scripts + operator checks |
| Widgets | Emulator | Instrumented + manual; Glance logic via unit tests |
| Reminders | Emulator/device | Manual matrix + scheduler unit tests |
| Performance | Mid-range emulator profile | Startup and scroll benchmarks, no extra analytics |

## 5. Risks to the plan

1. Operator access for triggers is on your side; B0 and P4 wait on it. P1–P2 do not.
2. If the spike shows MySQL 8.0 behaves differently from what AppHub's source implies, collaboration moves to the §10 core extension, which adds PHP work and a redeploy of shared infrastructure. That is a re-plan and needs your approval.
3. Toolchain versions are new (AGP 9, Kotlin 2.4); the P1 spike protects the schedule.
4. Local notifications for shared activity are delayed by the sync interval; this is a documented product limitation, not a defect.
5. AppHub's per-IP limit (120/min) on shared mobile NATs: the sync design keeps traffic low, but heavy bulk migrations are throttled and resumed.

## 6. What I need from you to start

1. Approve (or change) the four Phase 0 documents.
2. Answer the open decisions D1–D9 in ARCHITECTURE §13, especially **D3 (device-generated join codes)**, **D2 (applicationId)** and **D9 (toolchain fallback)**.
3. Provide: cloud app names and permission to create a dev app; the operator path for triggers; two test accounts; SMTP status — see PROJECT_AUDIT §8.
4. Confirm that P1–P2 may begin immediately while B0 waits for operator access.
