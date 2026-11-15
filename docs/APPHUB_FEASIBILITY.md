# AppHub Feasibility — Shopping List (Phase 0)

Date: 2026-11-15 · Status: **audit only; no cloud app, table, trigger or user was created** · See also [PROJECT_AUDIT](PROJECT_AUDIT.md), [ARCHITECTURE_PROPOSAL](ARCHITECTURE_PROPOSAL.md), [IMPLEMENTATION_PLAN](IMPLEMENTATION_PLAN.md)

Evidence tags: **[guide]** public cloud guide · **[src]** local AppHub source `eb6aa36` (cloud guide and SDK are byte-identical to the local checkout, see PROJECT_AUDIT §5) · **[self]** confirmed by me directly in source during this audit · **[unverified]** must be proven by the spike in §9 before it is relied on.

Standing rule: the database is built **in the cloud AppHub only**. The local AppHub (`C:\xampp\htdocs\apphub`) is a read-only reference.

---

## 1. Verdict

| Question | Answer |
|---|---|
| Can AppHub be the only cloud backend for guest-less sync of personal lists? | **Yes**, with a client UUID primary key, an app-maintained `updated_at`, and client-side delta logic. |
| Does AppHub's ordinary "own rows" permission support shared lists? | **No.** `own` shows a user only rows they own. Sharing needs the separate `shared` scope plus a grant table. |
| Can AppHub enforce membership authorization **server-side** for shared lists? | **Yes, but only by combining three things:** the `shared` scope (read/update), MySQL **triggers** (create-membership checks, transitions, conflict detection), and trigger-only writes to the grant table. Row scopes alone are not enough: `create` and `delete` can never be `shared` [src]. |
| Can both invitation methods (email lookup, join code) be enforced server-side? | **Yes without changing AppHub's core**, using insert-only "command" tables and triggers (§6). Two deviations/weaknesses need your decision: the join code is **generated on the device** (server hashes and validates it), and per-user brute-force throttling answers **422, not 429**. |
| Is a custom server-side extension required? | **Not strictly.** A small AppHub core extension (§10) would give server-generated codes and real 429s, but it touches the platform shared by five other apps and still does not remove the need for triggers. Recommended: start with triggers; keep §10 as the fallback. |
| Blocking prerequisite | Installing triggers needs **MySQL admin access over SSH** on the cloud server. The AppHub panel cannot create triggers [self]. |
| Push notifications / realtime | **Not supported** [src]. Notifications will be local, driven by sync polling. |

Nothing here is invented: every capability is tagged to the guide or to source. Items I could not prove are in §9.

---

## 2. What was audited

- Public guide (`/apphub/docs/`), SDK (`apphub.js`, `apphub.d.ts`), home page — read in full.
- Local source (107 commits): routing (`Kernel.php`), CRUD and scopes (`CrudController.php`), config validation (`App.php`), schema tooling (`Registry/SchemaAdmin.php`, `TenantAdmin.php`), tokens (`Auth/TokenService.php`), actor variables (`Database.php`), tests (`tests/shared.php`, `tests/actor.php`, `tests/emails.php`), `PROJECT_STATUS.md`, `docs/API.md`.
- Live cloud: `GET /apphub/` health and unauthenticated `GET` probes. Anonymous probes cannot tell whether an app exists (all names return 401).
- ReadRhythm's `apphub/` folder, the working example of provisioning an app on this server.

Parity: guide hash `8e5b9b57a0b9184a` and SDK hashes match the local checkout exactly, so source behaviour is a reliable proxy. Differences that remain: cloud DB is **MySQL 8.0** (AppHub's trigger tests ran on MariaDB 10.4), and the cloud mail transport was `log` as of the status doc [src].

---

## 3. Authentication

Endpoints [guide][src] (all under `/{app}/auth/`, JSON envelope `{ok,data}` / `{ok:false,error{status,message,details,request_id}}`):

| Need | Endpoint | Notes |
|---|---|---|
| Registration | `POST register` `{email,password,...register_fields}` | Role forced to the app's default role [src]. Does not sign in. 409 if email exists (enumeration oracle). Password min length ≥ 8 (default 10), max 128, common-password and 4-distinct-character rules [src] |
| Login | `POST login` `{email,password}` → `access_token, refresh_token, expires_in, token_type, user` | 10 auth calls/min/IP; 5 wrong passwords lock the (email, IP) pair for 15 min [src] |
| Refresh | `POST refresh` `{refresh_token}` | One-time use, rotation, see below |
| Logout | `POST logout` `{refresh_token}` | |
| Account info | `GET me`, `GET access` | `access` returns the tables/actions/fields this user may use — used to gate UI |
| Password change / recovery | `POST password`, `POST forgot`, `POST reset` | Recovery and verification need working mail (§8, R5) |
| Email verification | `POST verify`, `POST resend-verification` | Off unless `verify_url`/`verified_column` are configured |
| Account deletion | **none** | A config `users.delete = own` would expose `DELETE /users/{id}` by code reading but is untested [src] |

Token facts: access token 900 s default (clamped 60–3600 s); refresh token 14 days default (1 h–90 d) [src]. Refresh tokens look like `{app}.{43 chars}`.

**Critical — no refresh grace window [self].** `TokenService::rotate` revokes the presented token inside a transaction; presenting an already-revoked token revokes the **entire token family** (`token.reuse_detected`) and answers 401. If the response to a refresh call is lost (tunnel, airplane mode, process death between server rotation and local write), the retry with the old token destroys the session. Client rules (details in ARCHITECTURE §5): one global mutex, persist the new refresh token (Keystore-encrypted) **before** using it, never auto-retry the refresh request, treat an ambiguous failure as "session needs re-login" while keeping all local data. Optional server hardening (small change): accept the immediately previous token for ~60 s and return the same new pair.

The SDK in the guide is JavaScript. Android uses plain Retrofit/OkHttp; the guide documents the pattern (synchronized `Authenticator`, plain client for refresh, compare sent vs stored token) and we follow it. No API key ever ships in the APK; public values only are base URL and app name.

---

## 4. Data API and sync primitives

| Capability | Finding |
|---|---|
| CRUD | `GET/POST /{app}/{table}`, `GET/PATCH(PUT)/DELETE /{app}/{table}/{id}`; PATCH returns the row [guide][src] |
| Paging | `page` ≥ 1, `limit` 1–100, `meta{page,limit,total,pages}`; offset-based, no tie-break, so concurrent writes can skip or repeat rows [src] |
| Filters | **Equality only** on `filterable` columns, values ≤ 255 chars; no `gt/gte/in/since/range`; unknown parameters → 400 [src] |
| Sort | One `sortable` column, `order=asc|desc` [guide] |
| Search | `q` over `searchable` columns |
| Batch / transactions | **None** [src] |
| ETag / If-Match / revision | **None built in.** Only the documented trigger pattern (`base_revision` vs `revision`, `SIGNAL` → 422 `conflict`) [guide] |
| Timestamps | `created_at`/`updated_at` are **not managed by the API**; they exist only if the DB declares them (e.g. `ON UPDATE CURRENT_TIMESTAMP`, 1-second DATETIME, MySQL session time zone, none set by AppHub) [src]. We maintain `updated_at DATETIME(3)` in triggers with `UTC_TIMESTAMP(3)` |
| Primary keys | Client-supplied string PK is allowed on create if the PK column is in `writable`; never writable on update; URL id regex `[A-Za-z0-9_-]{1,64}`, so UUIDs work [src]. **The `users` table needs AUTO_INCREMENT** |
| Duplicate key | `409` with fixed text and no details; same 409 also covers FK and NOT NULL violations [src]. Retry pattern: on 409 `GET /{table}/{uuid}`; 200 means "already created" |
| Types | ints → JSON numbers; **DECIMAL → string**; TINYINT(1) → 0/1; DATETIME → `"YYYY-MM-DD HH:MM:SS"` with no zone; JSON columns are strings [src] |
| Limits | body ≤ 1 MiB, text value ≤ 65,535 bytes; ~120 requests/min per **IP** per app (counted before auth; file-based limiter); `429` + `Retry-After` [guide][src] |
| Soft delete | Built-in `soft_delete` column: `DELETE` hides the row; `?deleted=only` lists the trash using the caller's **delete** scope; live and trashed rows cannot be listed together; non-owners cannot see tombstones because delete is never shared [src] |
| Hidden columns | Never returned; removed from filter/search/sort. **A column may be both `hidden` and `writable`** — there is no cross-check [self] |
| Files | Not needed in v1 (product images are a future feature). Note: downloads are not row-authorised (any signed-in user with the random name) [src] |

Consequences for sync (design in ARCHITECTURE §6):

1. **Delta pull without a `since` filter.** Because the `shared` scope returns every row the caller may see, one request per entity gives the whole stream: `GET /shopping_items?sort=updated_at&order=desc&limit=100`. Page until a row is older than `cursor − overlap` (overlap ≈ 2 min). An idle sync costs one request per entity type (about 6), well under the 120/min budget.
2. **Deletes are app-level tombstones**, not AppHub's `soft_delete`: `deleted_at` set by `PATCH` (update *is* shareable; delete is not), so a tombstone appears in the normal stream for all members.
3. **Loss of access is detectable** by keeping a membership row visible to the removed user (members column, role `removed`), so the client purges the list instead of guessing.
4. **Idempotent creates** via client UUID PK.
5. **Concurrency** via `revision`/`base_revision` triggers (needs `actor_in_sql: true`, §5).

---

## 5. Authorization audit — what AppHub can and cannot enforce

### 5.1 Row scopes [guide][src]

| Scope | list/read | update | create | delete |
|---|---|---|---|---|
| `all` | everything | everything | yes | yes |
| `own` | owner column = caller | owner | owner column forced to caller | owner |
| `shared` | owner, **or** a `members` column = caller (read only), **or** an `EXISTS` match in a grant table with a level in `read` | owner, or a grant level in `update` | **never** | **never** |

Confirmed in source: invisible rows answer **404**; totals/search/filter/sort use the same condition; owner and members columns are never writable under `own` or `shared`; update read-back uses the same condition; file attach follows the update rule; `shared` is refused at config-save time for create/delete, the users table, and as a default; no batch, include/expand or join routes exist, so no side door was found.

### 5.2 Consequences (the "do not assume" check)

- **`own` is not enough** for sharing: only the creator would ever see an item an editor adds. The `shared` scope with a grant table is required.
- **Editors cannot create items through a row scope.** `create` stays `own`, so *any* signed-in user could insert an item with an arbitrary `list_id`. Without a server rule this is a privilege hole. → a `BEFORE INSERT` trigger must verify active membership of `NEW.list_id`.
- **Editors cannot delete others' items.** → deletion is a `PATCH deleted_at` (tombstone).
- **The grant table's `create` must not be given to clients**, otherwise anyone can add themselves. AppHub only reads the grant table; who may write it is the app's job [guide][src]. → clients get no write rule on `list_members`; only triggers write it (the trigger's DEFINER is the MySQL admin, so it is not subject to the runtime user's narrower rights).
- **`shared_via.on` column** (here `list_id`) decides which grants apply and must not be freely re-pointable → trigger blocks `list_id` changes on update.
- **Shared-scope rows owned by the creator.** Editor-created items are owned by the editor (owner column is forced to the caller on create). Reads and updates by the list owner and other members work through the grant, not ownership.
- **Revoking access is immediate**: the grant is evaluated on every request, so changing a member's role to `removed` takes effect on their next call [guide].

### 5.3 actor_in_sql and triggers

- Opt-in config `actor_in_sql: true` sets `@apphub_user_id`, `@apphub_role`, `@apphub_app` on the request's PDO connection; new non-persistent connection per request, so no leakage between users [src]. Variables are `NULL` for sign-in/register/refresh/public reads/panel SQL/CLI [src].
- With it on, `SIGNAL SQLSTATE '45000'` surfaces as **HTTP 422 with the trigger's text** (≤ 200 chars). With it off a SIGNAL is an opaque 500 [src]. We turn it on.
- Because AppHub forces the owner column to the caller on every `own` create, `NEW.user_id` is a trustworthy caller id inside `BEFORE INSERT` triggers even without the actor variables; `@apphub_user_id` is needed in `BEFORE UPDATE` triggers.
- **Triggers cannot be created from the panel.** Run SQL accepts only `CREATE TABLE`, `ALTER TABLE`, `CREATE INDEX`, `INSERT … VALUES`; `DELIMITER` is refused [self, `SchemaAdmin.php:110-169`]. The .sql import at database-creation time also refuses `DELIMITER` and runs as a temporary user that is dropped afterwards, so a trigger would get an orphaned DEFINER (inference [unverified]). ReadRhythm installs triggers over SSH as the MySQL admin; so will we.
- The runtime app MySQL user has only `SELECT, INSERT, UPDATE, DELETE` [src]; triggers run with their DEFINER's privileges.
- Triggers are **not captured** by the panel's dump/backup; they must live in git and in a `mysqldump --routines --triggers` runbook step.

---

## 6. Collaboration design (Method 1 email, Method 2 code)

### 6.1 Approach: insert-only command tables, trigger-authorized

All sensitive state (memberships, invitation status, code state) is changed **only by triggers**, in response to rows the client inserts. Clients never get write rules on `list_members`. Each insert-only table uses `own` create so the owner column is forced to the authenticated caller, which the trigger uses as the actor.

Logical cloud tables (names indicative; final schema in Phase 3/4 and `docs/DATABASE_SCHEMA.md`):

| Table | Client rules | Written by | Purpose |
|---|---|---|---|
| `shopping_lists` | create/update **own**; read **shared** (grant: `list_members` on `id`) | client (owner) | Owner-only list row; editors have no update right on it |
| `shopping_items` | create **own** + membership trigger; read/update **shared** (grant on `list_id`); no delete | client | Items; `deleted_at` tombstone; `revision`/`base_revision` |
| `list_categories`, `list_tags`, `item_tags` | same pattern as items, each carrying `list_id` | client | List-scoped taxonomy so every member sees names/colours |
| `list_members` | read: **members** column (`member_id`) + owner; **no create/update/delete** | triggers only | Grant table. Role `owner`/`editor`/`removed`. Owner column overridden to the list owner so the owner can read all members |
| `user_lookups` | create **own**, read own | client → trigger | M1: exact-email lookup. `email` is hidden+writable and nulled after use; returns only `found` and a masked display hint; hidden `target_id` |
| `list_invitations` | create **own**; read own (inviter) + **members** column (invitee) | client → trigger | M1: invitation. Trigger resolves the invitee from the caller's recent lookup, denormalizes `list_name`/`inviter_name` so the invitee never needs read access to the list |
| `join_codes` | create **own**; read own | client → trigger | M2 owner side. Stores `code_hash`, `expires_at`, `status`; plaintext column is nulled |
| `code_lookups` | create **own**; read own | client → trigger | M2 joiner preview: returns `found`/`invalid`, `list_name`, `inviter_name`; hidden `code_id` |
| `join_requests` | create **own**; read own (requester) + **members** column (list owner) | client → trigger | M2 request to join |
| `list_commands` | create **own**; read own | client → trigger | Insert-only commands: `accept_invite`, `reject_invite`, `approve_join`, `reject_join`, `revoke_invite`, `revoke_code`, `remove_member`, `leave_list` |
| `app_secrets` | **not listed in config** (API answers 404) | operator | Pepper for code hashing; readable by triggers only |

Config highlights: `actor_in_sql: true`; `users` readable `own` only (no directory; `GET users` is 403 [src]); `list_members`, `app_secrets` have no client write rules; `updated_at`, `revision`, `created_by`, `updated_by` are trigger-maintained and not writable; `id` is writable on create only; `base_revision` is the only transient writable control column on items.

### 6.2 Method 1 — invite by registered email

1. Owner taps Share → enters an exact email. Client inserts `user_lookups {id, email}`.
2. `BEFORE INSERT` trigger: caller must own at least one list; throttle (see 6.4); exact case-insensitive match against `users.email` (no `LIKE`, no partial); caller ≠ target; writes `found`, `display_hint` (first letter of the display name plus masking, e.g. `A•••• K.`) and hidden `target_id`; **nulls `email`**. Every outcome is persisted (no SIGNAL for "not found") so the throttle can count it.
3. App shows the hint; the owner confirms. Client inserts `list_invitations {id, list_id, lookup_id}`.
4. Trigger: list owned by caller; lookup is the caller's, `found`, < 10 min old; target not already a member and no live invitation; sets `invitee_id`, role `editor`, `status pending`, `expires_at` (7 days), denormalized names.
5. Invitee's next sync reads the invitation (members column) and raises a local notification. Invitee inserts `list_commands {accept_invite | reject_invite, target_id}`.
6. Command trigger: `NEW.user_id = invitation.invitee_id`, status `pending`, not expired → inserts the `list_members` grant (role `editor`) and marks the invitation `accepted`; reject marks `rejected`. Unique key per `(command type, target)` makes it idempotent.

Privacy: the users table is never exposed; the lookup reveals only existence plus a masked display name to a signed-in list owner, throttled. Email addresses are never returned. (Registration's 409 is already an existence oracle, so this adds a throttled, masked signal rather than a new class of leak.)

### 6.3 Method 2 — invitation code

1. Owner taps Generate sharing code. **Client generates** an 8-character code from the 32-symbol alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no `0 O 1 I`) with `SecureRandom` (keyspace 2^40 ≈ 1.1×10^12). Inserts `join_codes {id, list_id, code}`.
2. Trigger: caller owns the list; `code` matches `^[A-HJ-NP-Z2-9]{8}$` (validated **before** any type conversion so a bad value cannot reach an error message); stores `code_hash = SHA2(CONCAT(pepper, code), 256)`; **sets `code = NULL`**; `expires_at = UTC_TIMESTAMP(3) + 24 h`; status `active`. A newer active code supersedes older ones (validators accept only the newest non-revoked, unexpired code per list). The owner's app shows the code once and keeps it only in memory; it is never logged.
3. Owner shares it out of band. Joiner opens Join a List and enters it → client inserts `code_lookups {id, code}`.
4. Trigger: throttle; hash; match on an active, unexpired, unused, non-superseded code. Writes `status found` with `list_name`/`inviter_name` and hidden `code_id`, or persists `status invalid`; nulls `code`.
5. Joiner confirms → inserts `join_requests {id, lookup_id}`. Trigger: lookup is the caller's, `found`, < 10 min old, code still valid, caller not a member and has no pending request. It creates the request (owner reads it via the members column) and **consumes the code** (single use).
6. Owner's sync shows the request. Owner inserts `list_commands {approve_join | reject_join, target_id}`; the trigger requires `NEW.user_id` = list owner, then inserts the `editor` grant or rejects. A rejected request spends the code; the owner generates a new one.

### 6.4 Brute-force and abuse limits

- Keyspace 2^40; at the per-IP ceiling of 120 requests/min the whole space would take thousands of years from one address; the code lives 24 h, is single-use, and joining still needs **owner approval**.
- Per-user throttle in the trigger: persisted `invalid` attempts in the last 15 min ≥ 5 → `SIGNAL` → **422** "too many attempts". (A SIGNAL rolls back its own statement, so failures are persisted as ordinary rows and the *next* attempt is refused.) Lookups: ≥ 10 per hour per user.
- No per-endpoint HTTP 429 exists in AppHub [src]; only the generic per-IP limits apply. Sign-up is open, so a determined attacker can create accounts; the per-user throttle plus owner approval keeps impact low. Option B (§10) gives a true 429 and per-IP/per-user counters.

### 6.5 Requirement-by-requirement

| Requirement | Status |
|---|---|
| Collaboration only for registered users | ✅ every collaboration table is behind AppHub auth; guests never call it |
| Search by registered email; privacy-conscious lookup; no directory/emails exposed | ✅ exact match, masked hint, throttled, `users` not readable by others |
| Invitation created; recipient sees it in-app; accept/reject; becomes Editor | ✅ via sync + commands. **In-app, delivered at next sync**, not instantly (no push) |
| Code: 8 chars, unambiguous uppercase alphanumerics | ✅ 32-symbol alphabet, trigger-validated |
| Code: cryptographically secure random generation | ⚠️ **device `SecureRandom`**, not server. Server enforces format only |
| Code: "backend generates" | ❌ in the trigger design; ✅ with §10. **Decision needed** |
| Store only a secure hash server-side | ✅ peppered SHA-256, plaintext column nulled. Verify binary/general logs in spike S9 |
| 24 h expiry, single use, server-side validation, revocable, regenerable | ✅ |
| Brute-force rate limiting | ⚠️ per-user throttle (422) + per-IP generic limit; no true 429. ✅ with §10 |
| Never expose codes in logs | ⚠️ AppHub logs no bodies [src]; code is only ever in POST bodies, never in URL, pk or filter; app never logs it. Residual: MySQL logs (S9) and 500-error messages (mitigated by regex pre-validation) |
| One-time invitation records, no permanent public codes | ✅ |
| Safe preview (list name + inviter), request access, owner approves/rejects | ✅ |
| Owner: view pending, view members, approve/reject, remove, revoke, regenerate | ✅ |
| Recipient: accept/reject, enter code, request, view pending, leave | ✅ |
| Owner full control; Editor add/edit/delete/mark bought | ✅ delete = tombstone |
| Server-side enforcement, not Android-only | ✅ scopes + triggers; Android checks are UX only |
| Transfer ownership | ❌ **not in v1** (needs owner-column rewrite across tables; spec says "if implemented safely") |

### 6.6 Threat model (server side)

| Attack | Control |
|---|---|
| Unauthenticated access | 401 on every route except auth/public |
| Read a list by knowing its UUID | Non-member read → 404 (shared scope) |
| Insert an item into someone else's list | `BEFORE INSERT` membership trigger → 422 |
| Move an item to another list / change creator | Trigger rejects `list_id`/`user_id`/`created_by` changes |
| Editor edits or deletes the list itself | `shopping_lists` update is `own` only; no delete rule |
| Self-grant membership / escalate role | No client write rule on `list_members` |
| Forge a command as another user | Owner column forced to the caller by AppHub; trigger checks it against the invitation/list owner |
| Accept someone else's invitation | Trigger: `NEW.user_id = invitee_id` |
| Enumerate users | Exact match only, masked output, throttle, no directory |
| Guess join codes | 2^40 space, 24 h, single use, per-user throttle, per-IP limit, owner approval |
| Steal hashes from a DB dump | Peppered hash; plaintext never stored; code useless after expiry or use |
| Removed member keeps reading | Role set to `removed`; next request returns 404 for the list's rows |
| Replay/duplicate submits | Client UUID PKs and unique keys → 409 |
| Lost-update between two editors | `revision`/`base_revision` trigger → 422 `conflict`, client merges (ARCHITECTURE §6) |

---

## 7. Notifications

No push, webhook, SSE or websocket exists in AppHub [src]; "not planned". Event emails need working SMTP and are sent synchronously with a 10 s timeout, limits 3/address/h, 30/user/day, 300/app/day [src]. Cloud transport status is unconfirmed (R5).

Design: all notifications are **local**. A periodic WorkManager sync (≥ 15 min, system-controlled) and foreground refresh discover new invitations, join requests and shared activity, then post local notifications. Latency is "next sync", not real time; Doze can delay further. Firebase is excluded by the master prompt; self-hosted push (UnifiedPush/ntfy) would add a server component outside AppHub and is rejected for v1. Optional later: an event email on invitation (`emails.on_create`), which also requires a trigger-filled hidden recipient column and working SMTP.

---

## 8. Risks and limitations

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| F1 | Triggers need operator SSH/MySQL-admin; not in panel backups | High | `backend/apphub/` in git, runbook, `--triggers` dumps |
| F2 | Refresh has no grace window; lost response kills the session [self] | High | Client rules in §3; optional server patch |
| F3 | Trigger behaviour on MySQL 8.0 unproven (AppHub tests ran on MariaDB) | High | Spike §9 gates Phase 4 |
| F4 | No `since`, batch, ETag; offset paging unstable; 120 req/min per IP on shared NAT | High | Single stream per entity, overlap window, coalescing, cap 2 concurrent, backoff on 429 |
| F5 | Join-code generation is client-side; throttle is 422 | Medium | Decision in §6.5; Option B |
| F6 | Enumeration oracles (register 409; lookup found/not-found) | Medium | Masking, throttling, uniform outcomes |
| F7 | Mail transport may be `log` only | Medium | Core flows never depend on email |
| F8 | No account deletion endpoint (Play requirement) | Medium | `users.delete = own` spike S13, or small AppHub change |
| F9 | `updated_at` is only as reliable as our trigger | Medium | `DATETIME(3)` set in triggers, `UTC_TIMESTAMP(3)`, index; revision as the real conflict guard |
| F10 | File downloads not row-authorised | Low (unused in v1) | Do not use AppHub files in v1 |
| F11 | Shared 1 vCPU server; panel dump omits triggers | Medium | Light sync; separate dev app; manual routine dumps |
| F12 | Sign-up is open | Low/Med | Password policy, per-user throttles; consider email verification once SMTP works |

---

## 9. Validation spike (gate before Phase 4; can start in parallel with Phase 1)

Preconditions: a separate cloud dev app (`shoppinglistdev`) and a dev MySQL database created by the operator; two test users; operator access to run `triggers.sql`. **Nothing runs against production or any existing app.** All checks run against the **cloud** dev app, because MySQL 8.0 behaviour is what matters.

| # | Check | Pass criterion |
|---|---|---|
| S1 | Install triggers as MySQL admin; API writes through the runtime user fire them | Trigger side effects visible after an API `POST` |
| S2 | `SIGNAL 45000` → 422 with text; other-table writes in the same statement are rolled back | HTTP 422 + message; no partial rows |
| S3 | `BEFORE INSERT` owner-column override is honoured by later scope checks | Overridden owner can read/update via `own` |
| S4 | `email`/`code` columns `hidden` + `writable`: client can send, never receives; trigger can read and null them | Response lacks the column; stored value is NULL |
| S5 | 201 read-back includes trigger-set visible columns | `found`, `list_name` etc. present |
| S6 | `shared_via` on lists/items; grant maintained only by triggers; role→`removed` | Next request after removal returns 404; removed user can still read own membership row |
| S7 | Authorization matrix: non-member read, non-member item insert, editor list update, `list_id` change | 404 / 422 / 404 or 403 / 422 as designed |
| S8 | Revision conflict race with two clients | Second writer gets 422 `conflict`; no lost update |
| S9 | Code never persisted or logged: AppHub audit/app logs, Apache access log, MySQL general log, binlog row images, slow log | No plaintext occurrence in any of them |
| S10 | Throttles: 5 invalid code lookups → 6th refused; lookup limit | 422 with expected text; counter survives the SIGNAL |
| S11 | Delta pull with `updated_at DATETIME(3)` desc + overlap, under concurrent writes | No missed row across 1,000 randomized interleavings |
| S12 | Client UUID PK + duplicate 409 + `GET` by id | Idempotent re-send |
| S13 | Refresh with a lost response; optional grace patch; `users.delete = own` and cascade policy for shared lists | Documented behaviour; decision recorded |
| S14 | 120/min limiter behaviour and `Retry-After` | Client backs off correctly |

Output: `backend/apphub/tests/*.mjs` (repeatable), and findings in `docs/APPHUB_INTEGRATION.md`. If S2, S3, S4 or S9 fail, the design switches to §10 for the affected flow.

---

## 10. Option B — minimum core extension (fallback / hardening)

Only if the spike fails or you want server-generated codes and true 429s. It modifies AppHub core, which serves five other apps (`fueltracker`, `leavetracker`, `shop`, `submitflow`, `readrhythm`) and is deployed with `deploy/update-server.ps1`. AppHub has **no plugin or per-app handler mechanism**; every route is in `Kernel::route()` [src]. The repo's own pattern for features is an opt-in config key validated in `App::normalize` (LeaveTracker's unbuilt R8 `/auth/invite` was designed that way).

Minimum surface, all behind a new opt-in config key `invitations` (off for every other app):

| Route | Behaviour |
|---|---|
| `POST /{app}/x/user-lookup` | exact email, masked result, per-user + per-IP counters, 429 |
| `POST /{app}/x/codes` | server `random_bytes()` → 8 chars; stores `hash_hmac` with an app secret derived from the master key; **returns plaintext once**; revokes older codes in one transaction |
| `POST /{app}/x/codes/preview`, `POST /{app}/x/codes/join` | validate, preview, create request; consume code atomically |
| `POST /{app}/x/members/{respond,remove,leave}` | authorize against `list_members` in a transaction |

It still would **not** remove triggers: `create: shared` does not exist, so item-create membership checks and `revision` conflict detection still need two small triggers. Effort is larger (PHP code, tests in AppHub's own suite, regression risk on shared core, cloud redeploy) and it needs your approval. Not recommended for the first release.

---

## 11. Repository layout and operator runbook (proposed, not created yet)

```
backend/apphub/
  README.md                 ledger of what was applied to which app, when
  schema.sql                tables, FKs, UNIQUE, CHECK (MySQL >= 8.0.16), indexes
  triggers.sql              all triggers (applied by the operator)
  shoppinglist.config.json  roles, scopes, shared_via, actor_in_sql
  seed.sql                  app_secrets pepper placeholder (real value never committed)
  provision.md              steps: dry-run DB -> create DB -> add app -> import config -> triggers -> check
  tests/                    node scripts: isolation, authz matrix, conflicts, throttles, log scan
```

- Applies to **cloud only**; the dev app is used for every destructive or trigger test; production is provisioned from the same files after the spike passes.
- Every change goes through the files, then the operator applies it and records it in the ledger; `shape-compare` after each schema change; config is re-exported and diffed because AppHub was once seen widening the admin role after schema changes (ReadRhythm README).
- Secrets (pepper, admin credentials) are never committed or put in chat.
