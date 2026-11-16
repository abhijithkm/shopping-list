# AppHub integration (Phase 3)

How the Android app talks to AppHub, and the contract the cloud app must provide. The *definition* of that cloud app (schema and AppHub settings) lives in [`backend/apphub/`](../backend/apphub/README.md); the reasons are in [APPHUB_FEASIBILITY](APPHUB_FEASIBILITY.md). Code: `data/remote/`, `data/session/`, `di/NetworkModule.kt`.

**Database rule:** the real database is built **only on the cloud AppHub**. The local XAMPP AppHub is a read-only reference. Nothing in `backend/apphub/` has been applied to any server yet (the README there keeps the ledger).

## 1. Configuration (public values, no secrets)

| Value | Debug build | Release build |
|---|---|---|
| Base URL | `http://10.0.2.2:8787/` (the host machine from the emulator: the local fake server, which serves `/{app}/...` with no `/apphub` prefix) or `-PSL_APPHUB_BASE_URL=...` (a cloud dev app would be `https://132-226-190-156.sslip.io/apphub/`) | `https://132-226-190-156.sslip.io/apphub/` |
| App name | `shoppinglistdev` or `-PSL_APPHUB_APP=...` | `shoppinglist` |

* They reach the app as `BuildConfig.APPHUB_BASE_URL` and `APPHUB_APP`; the Retrofit base is `{base}/{app}/`.
* No key, password or token is in the APK or the repository. AppHub's SDK is JavaScript; Android uses plain Retrofit and OkHttp as the AppHub guide describes.
* Release builds allow HTTPS only (the platform default for target 36). Debug builds add a network security config that permits cleartext **only** to `10.0.2.2` and `localhost`, so the emulator can reach the fake server.

## 2. Endpoints used

All answers are the envelope `{"ok":true,"data":...,"meta":{...}}` or `{"ok":false,"error":{"status","message","details","request_id"}}`. The client reads `error.message` (shown to people as is), `error.details.reason` and the `Retry-After` header; unknown fields are ignored.

| Purpose | Call | Notes |
|---|---|---|
| Register | `POST auth/register` `{email,password,display_name?}` | does not sign in; the app then calls login. 409 if the address exists; password rules are the server's |
| Sign in | `POST auth/login` `{email,password}` | answer: `access_token`, `refresh_token`, `expires_in`, `user`. 403 with `reason=email_not_verified` when verification is on |
| Refresh | `POST auth/refresh` `{refresh_token}` | one-time token with rotation, see §3 |
| Sign out | `POST auth/logout` `{refresh_token}` | best effort |
| Password reset | `POST auth/forgot` `{email}` | always the same answer; the mail needs working SMTP on the server (not confirmed) |
| Lists, items, categories, tags | `GET/POST {table}`, `GET/PATCH {table}/{id}` | never DELETE (the roles have no delete permission) |

Not used in Phase 3: `auth/me`, `auth/access`, `auth/password` (declared in the code for later), email verification calls, Google sign-in, files, sharing.

Errors become typed exceptions (`AppHubException`): `Http(status, message, reason, requestId, retryAfterSeconds)`, `Network` (no answer), `Protocol` (an answer that is not the envelope). Callers decide by type, never by message text.

## 3. Session and token handling

AppHub's refresh token is **one-time with rotation, and presenting an old token revokes the whole token family** (no grace window). The rules that follow, all in `SessionManager` and tested:

1. The **access token lives in memory only**. The **refresh token** is stored encrypted: Android Keystore AES-256-GCM (non-exportable key, fresh IV per value, authenticated) in a DataStore file that is excluded from backup. If the key is lost the app treats the session as ended (sign in again) and keeps all lists.
2. **One mutex** serialises sign-in, sign-out and refresh, so two callers never present the same refresh token. Concurrent 401s share one refresh.
3. A refreshed pair is **persisted before the new access token is handed out**, and the whole refresh runs non-cancellably, so a rotated token is never lost to a cancelled coroutine. A write that fails (disk, Keystore) is remembered and retried before the next token is handed out; the write cannot be made atomic with the server's rotation, so a process death in the instant between the two still costs the session (the user signs in again; no list is touched). Signing in as someone else clears the previous account's stored session if the new one cannot be written.
4. The refresh call uses a **plain OkHttp client with `retryOnConnectionFailure(false)` and no redirect following** and is never repeated within one call (a redirect would resend a password or a one-time token to wherever it points). Only the signed-in client has the authenticator, and it has its own dispatcher, so a wrong password (401 on login) can never trigger a refresh, a failed refresh can never loop, and a blocked authenticator thread cannot starve the refresh call.
5. Only a definitive rejection ends the session: **401 or 403 on refresh whose body is AppHub's own error envelope**. A bare 401/403 (a proxy, a firewall), offline, 429 and 5xx keep it: the same token is tried again later (if the server did rotate before the reply was lost, that next attempt fails and the user signs in again; the local data is untouched either way). A temporary Keystore failure is not treated as a lost key either; only an undecryptable value is.
6. The authenticator makes one retry per request; a second 401 is final. Concurrent 401s, including requests that carried no token yet, share one refresh.
7. **Nothing logs bodies or headers.** There is no logging interceptor; `TokenPair`, `StoredSession`, `RefreshResult.Token` and every request type that carries a secret print without it, and a malformed auth answer becomes an error that does not quote the body.
8. Sign-in and register actions ignore a second tap while one is running; the password field is not saved with the screen's state.

The UI never asks for sign-in at launch. Settings, Account is where the user chooses to sign in; guest mode never touches the network.

## 4. The cloud contract

Source of truth: `backend/apphub/schema.sql` and `shoppinglist.config.json`. What the client relies on:

| Table | Columns the client reads or writes |
|---|---|
| `users` | AppHub's sign-in table; `display_name VARCHAR(60)` is the only profile column |
| `shopping_lists` | `id CHAR(36)`, `name`, `description`, `icon`, `color INT`, `is_archived`, `is_pinned`, `sort_key DOUBLE`, `created_at_ms`, `updated_at_ms`, `deleted_at_ms`, server-managed `updated_at DATETIME(3)` |
| `shopping_items` | `id`, `list_id`, `name`, `quantity DECIMAL(12,3)`, `unit`, `category_key`, `notes`, `is_bought`, `bought_at_ms`, `sort_order DOUBLE`, `tag_ids TEXT` (JSON array), `created_at_ms`, `updated_at_ms`, `deleted_at_ms`, `updated_at` |
| `categories` | `id`, `builtin_key`, `name` (nullable), `color` (nullable, at least one of the two), `created_at_ms`, `deleted_at_ms`, `updated_at` |
| `tags` | `id`, `name`, `created_at_ms`, `deleted_at_ms`, `updated_at` |

* Every data table has the owner column `user_id`; the role `user` may list, read, create and update **its own** rows (`own` scope) and has **no delete**. Server-side authorization is therefore AppHub's own row scoping; the Android app enforces nothing about ownership.
* Ids are client UUIDs sent as the primary key on create. `updated_at` is never writable and is the pull cursor; the database must run in UTC.
* JSON types the client accepts: DECIMAL as a string (`"2.500"`) or number, TINYINT as `0/1` or a boolean, DOUBLE as `1024.0` or `1024`, ids as strings or numbers. It **sends** booleans as 0/1, numbers as numbers, and explicit `null` to clear a nullable column.
* A PATCH with a non-writable field answers 422 and applies nothing; the client never sends `id`, `user_id` or `updated_at` in a PATCH.

Hazards the fake documents and the client designs around (details in `backend/apphub/fake-server/README.md`):

1. **A database refusal (CHECK, over-long text) is HTTP 500, not 409 or 422**, so the client validates before saving and treats a repeated 500 on one row as a block, not an outage ([SYNC_STRATEGY](SYNC_STRATEGY.md) §5.4).
2. The default list order is `id DESC`, which is random for UUIDs: the client always sends `sort=updated_at`.
3. `shopping_items.list_id` is only a foreign key: AppHub cannot check that the list belongs to the caller, so a hostile client could attach an item to another user's list. This is harmless for personal lists (the item is invisible to everyone else) and is one of the reasons shared lists need server-side authorization of their own (Phase 4).
4. `categories.id` is one global key: a built-in override uses a deterministic per-account UUID plus `builtin_key`.

## 5. Where it is tested

| Level | What |
|---|---|
| JVM, MockWebServer | wire format, envelope and error mapping, the session rules in §3 (refresh sharing, persistence order, rejected versus unavailable, authenticator single retry) |
| Instrumented, in-memory AppHub | the sync engine on real Room with two simulated devices: upload, pull, field merge, conflict, delete and undo, idempotent create after a lost reply, offline, blocked rows, rate limit, ended session, migration |
| Node fake server (`backend/apphub/fake-server`, 245 checks) | a faithful stand-in for AppHub, read from AppHub's PHP source; the emulator runs the debug build against it |
| **Real cloud AppHub** | **not done yet.** The dev app `shoppinglistdev` exists (created 2026-11-16 through the admin panel: schema loaded, config saved, health check 12 of 14), but the client needs two throwaway test accounts to be run against it |

## 6. Needs the owner before real-server verification

From [PROJECT_AUDIT §8](PROJECT_AUDIT.md), still open: permission to register two throwaway test accounts on the dev app (the app names `shoppinglistdev` and `shoppinglist` are confirmed and the dev app exists); an operator path for installing database triggers (needed only for Phase 4); two test accounts; whether the server can send mail (password recovery and email verification depend on it); whether a lost refresh reply should be made recoverable on the server (a short grace window for the previous token) rather than only on the client.
