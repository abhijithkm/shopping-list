# Fake AppHub (local development and testing of the Android client)

`server.mjs` is an in-memory fake of the AppHub API. It exists so the Android client (Phase 3: auth, sync, refresh, retries) can be
written and tested before any cloud app exists. **It is not AppHub**: no MySQL, no HTTPS, no CORS, no email, no persistence unless
you ask for it, and a deliberately weak password hash. It makes no network request, reads no credential, and listens on
localhost only (`127.0.0.1`, and `::1` when the machine has it). Node 18 or newer, no npm dependencies (written and tested on Node 26).

Its behaviour comes from reading the AppHub PHP source (`Kernel`, `CrudController`, `AuthController`, `TokenService`, `Passwords`,
`RateLimiter`, `Request`, `HttpException`, `docs/API.md`), not from observing the cloud server. Section "Known differences" lists
every difference, and "Not verified against the real server" every place where the source was unclear and a guess was made.

## Run it

```bash
node server.mjs --port 8787 [--access-ttl 900] [--refresh-ttl 1209600] [--data file.json] [--rate-limit 0]
node smoke.mjs                      # the scripted scenario (starts its own fake on a random port); exits 1 on any failed check
```

| Option | Default | Meaning |
|---|---|---|
| `--port N` | 8787 | listen port (`0` = a random free port) |
| `--apps a,b` | `shoppinglistdev,shoppinglist` | app names served; each is a separate "database" with its own users, rows and JWT secret. Any other name answers 401, like AppHub |
| `--access-ttl S` | config (900) | access token lifetime in seconds. Any value >= 1 (real AppHub clamps to 60..3600) |
| `--refresh-ttl S` | config (1209600) | refresh token lifetime in seconds. Any value >= 1 (real: 3600..7776000) |
| `--jwt-leeway S` | 30 | seconds an access token is still accepted after `exp` (`Jwt::LEEWAY`). `0` makes a short `--access-ttl` bite at once |
| `--data FILE` | none | keep the state in this JSON file (loaded at start, written after changes and on exit). Holds password hashes and the fake's JWT secret: it is gitignored (`*.data.json`, `*.local.json`, `state*.json`), keep it out of git anyway |
| `--rate-limit N` | 0 (off) | requests per window per IP per app; over it: 429 + `Retry-After`. Real AppHub: 120 |
| `--rate-window S` | 60 | the window in seconds for both limiters |
| `--auth-rate-limit N` | 0 (off) | auth calls (`login`, `register`, `refresh`, `forgot`, `reset`, `verify`, `resend-verification`, `password`) per window per IP per app. Real: 10 |
| `--login-lockout N` | 0 (off) | N wrong passwords lock the (email, IP) pair for `--lockout-seconds` (429). Real: 5 |
| `--lockout-seconds S` | 900 | the lock time |
| `--mail` | off | turns on password reset and email verification (the real config has none, so by default these answer 404). The "emails" go to an outbox you read at `GET /__outbox` |
| `--require-verified` | off | sign-in refused (403) until the address is verified (needs `--mail`) |
| `--config FILE`, `--schema FILE` | `../shoppinglist.config.json`, `../schema.sql` | the contract files |
| `--quiet` | off | no request log |

The log prints `METHOD path -> status ms` only: never a body, a token or a password.

### Pointing the Android app at it

* Emulator: `http://10.0.2.2:8787/shoppinglistdev/` (the host machine is `10.0.2.2`). Plain HTTP needs a **debug-only** network security
  config that allows cleartext for `10.0.2.2` and `localhost` only (`docs/API.md`, "Android apps").
* Real phone over USB: `adb reverse tcp:8787 tcp:8787`, then `http://localhost:8787/shoppinglistdev/`.
* The base URL, then the app name, then the path, exactly like the cloud: `https://132-226-190-156.sslip.io/apphub/shoppinglistdev/auth/login`.

## The contract comes from the files, not from the fake

At start the fake reads `../schema.sql` (column types, NOT NULL, defaults, `AUTO_INCREMENT`, `ON UPDATE CURRENT_TIMESTAMP`, unique keys,
foreign keys and the CHECK constraints, which it evaluates with a small SQL expression evaluator) and `../shoppinglist.config.json`
(owner column, `writable`, `sortable`, `filterable`, roles per table and action, auth settings). It then warns (`DRIFT WARNING`, and
`drift_warnings` in `GET /__state`) when the config names a column the schema lacks, or the schema has a client-writable column the
config forgot. `smoke.mjs` requires zero warnings. A change to either file changes the fake with it.

## What it mirrors (from the source)

* **Envelope:** `{ok:true,data[,meta]}` / `{ok:false,error:{status,message[,details],request_id}}`; `X-Request-Id` (16 hex) on every answer, equal to
  `error.request_id`; the same security headers (`Cache-Control: no-store`, `X-Content-Type-Options`, ...); `WWW-Authenticate: Bearer` on 401;
  `Allow` on 405; `Retry-After` on 429; `X-RateLimit-Limit/Remaining` when `--rate-limit` is on.
* **Routing:** `POST /{app}/auth/{register,login,refresh,logout,forgot,reset,verify,resend-verification,password}` (`google` answers 404, as it
  does for an app without `auth.google`), `GET /{app}/auth/me`, `GET /{app}/auth/access`, `GET|POST /{app}/{table}`,
  `GET|PUT|PATCH|DELETE /{app}/{table}/{id}`. Body rules: JSON only (415), empty body 400, invalid JSON 400, must be an object 400, 1 MiB (413).
  The body is read before table and permission checks, as in `Kernel`. Ids in the URL: `[A-Za-z0-9_-]{1,64}`.
* **Auth:** HS256 access tokens (`iss apphub`, `aud app`, `sub`, `role`, `fp` = password fingerprint, `iat/nbf/exp`, 30 s leeway); a password change
  invalidates old access tokens. Refresh tokens `{app}.{43 url-safe chars}`, stored hashed, one-time, rotated inside one family. **Reuse of a
  revoked refresh token revokes the whole family and answers 401 "Refresh token was already used; all sessions from it were signed out"**;
  unknown or foreign-app token 401 "Invalid refresh token"; expired 401 "Refresh token expired". There is no grace window (feasibility F2): a lost refresh
  response followed by a retry kills the session. Register: 201, role forced to `default_role`, only `email`, `password` and `register_fields` accepted, 409
  "This email is already registered" (any letter case), password policy of `Passwords::assertAcceptable` (min 10, max 128, common list, fewer than 4
  distinct characters, contains the email name when it has 4+ characters). Login: the same 401 for unknown email, wrong password and inactive account.
* **Tables:** owner scope `own` (another user's row answers 404 on read, update; list only shows own rows); the owner column is forced to the caller
  on create and **refused if sent** (422 "These fields cannot be written", `details.fields` and `details.writable`); only `writable` columns accepted;
  the primary key is accepted on create only; duplicate key 409 "The data conflicts with existing records (duplicate value or invalid reference)"
  (also for a foreign-key failure and an explicit NULL in a NOT NULL column); list parameters `page` (1 to 1000000), `limit` (1 to 100, default 20),
  `sort` and `order` (only sortable columns; default `id DESC`), equality filters on filterable columns, unknown parameter 400 with
  `details.params`, bad sort 400 with `details.sortable`; `meta {page,limit,total,pages}`.
* **Types on the wire:** DECIMAL as a **string** (`"2.500"`), TINYINT as 0/1, BIGINT and INT as numbers, DOUBLE with a fraction (`1024.0`, from
  `JSON_PRESERVE_ZERO_FRACTION`), DATETIME(3) as `'YYYY-MM-DD HH:MM:SS.mmm'` in UTC, TEXT as a string.
* **`updated_at`** is set on create and moved on update **only when a column value really changes** (MySQL `ON UPDATE CURRENT_TIMESTAMP(3)`
  semantics): a PATCH with identical values, or `quantity: 2.5` over `"2.500"`, leaves it alone. Two changes in one millisecond share a value.
* **Database refusals** are classified like AppHub does: duplicate key, foreign key and NOT NULL are 409; everything else the database rejects (a
  violated CHECK, a value too long for its column, a non-numeric number, a required column left out) is a generic **500**
  "Internal server error". The fake adds a dev-only response header `X-Fake-Db-Error` with the reason so you can see why.
* **DELETE** on a data table answers **403 "Your role cannot delete <table>"**: the route exists and the role has no delete right. (The Phase 3
  brief expected 405; the source says 403, so the fake follows the source. `PUT`/`POST` on the wrong URL shape do answer 405.)

## Dev-only control endpoints (outside the app namespace, never rate limited, never faulted)

| Call | Does |
|---|---|
| `POST /__reset` | wipes every app's data, secrets (so all tokens die), faults, limiter counters and the virtual clock |
| `GET /__state[?dump=1]` | row counts per table, active refresh tokens, pending faults, drift warnings, the options; `dump=1` lists the rows (never password hashes) |
| `POST /__clock` `{"advance_seconds":N}` or `{"reset":true}` | moves the fake's clock (JWT expiry, refresh expiry, rate windows, lockout, `updated_at`) so token expiry can be tested without waiting |
| `POST /__fault` | adds a fault rule (below); `GET /__fault` lists; `DELETE /__fault` clears |
| `GET /__outbox[?app=name]` | the "emails" sent under `--mail`: `{at,to,purpose,token,link}` |

### Faults

```bash
curl -X POST localhost:8787/__fault -H 'content-type: application/json' \
  -d '{"mode":"drop_after","count":1,"match":"POST /shoppinglistdev/shopping_items$"}'
```

| Field | Meaning |
|---|---|
| `mode` | `drop_after`: the server **processes** the request, then closes the connection without answering (a lost response). `drop_before`: closes without processing (a lost request). `status`: answers an error immediately without processing. `delay`: waits `ms`, then processes normally |
| `count` | how many matching requests it applies to (default 1; `-1` until cleared) |
| `match` | a JavaScript regular expression tested against `METHOD /path` (no query string, **with** the app prefix, for example `POST /shoppinglistdev/auth/refresh$`); omitted = every request |
| `status`, `retry_after` | for `status`: the code (default 503, message "Service temporarily unavailable" for 503, "Internal server error" for 500, "Too many requests" for 429) and an optional `Retry-After` header |
| `ms` | for `delay`: milliseconds (default 500, at most 60000) |

Typical uses: `drop_after` on `POST .../shopping_items` then retry the same UUID (409, then `GET` by id: 200); `drop_after` on `POST .../auth/refresh`
(the retry is token reuse and kills the session: why the client must never auto-retry a refresh); `status` 503 with `retry_after` to test backoff;
`delay` to test timeouts.

## State between runs

Without `--data` everything is lost on exit and tokens die with the process (a new random secret each start), like an AppHub `rotate`. With `--data`
the accounts, rows, refresh tokens and the secret survive a restart. The file holds scrypt hashes, never a plaintext password.

## Smoke test

`node smoke.mjs` starts the fake on a random localhost port and runs the Phase 3 scenario: register, login, `me`, `access`; create list and item
with client UUIDs; duplicate 409 then GET by id; sorted and paged lists; `updated_at` bumped by a change and not by a no-op; tombstone through
`deleted_at_ms`; cross-user 404; non-writable and owner fields 422; refresh rotation and reuse killing the family; access and refresh expiry with
the virtual clock; logout; password change; `drop_after`, `drop_before`, `status` and `delay` faults and the idempotent retry; the 429 paths (request
limit, auth limit, lockout); password reset and verification with `--mail`; persistence with `--data`. It exits 1 on any failed check and prints
`smoke: N passed, M failed`. It also covers `updated_at_ms` (a JSON number, default 0, writable, not sortable or filterable) and the exact PATCH rules
(omitted field unchanged, explicit `null` sets NULL, a non-writable column answers 422 and applies nothing; see `../README.md`, "The contract a client
codes against"), plus per-account built-in category overrides (`builtin_key`, two accounts with the same built-in and different
ids, filter, idempotent retry, PATCH, the name-or-color CHECK, `sort=updated_at`). One check is labelled **KNOWN**: it pins real behaviour that is a
hazard for the client (the unchecked `list_id`); see `../README.md`, "Things to know".

## Known differences from real AppHub

1. **No MySQL.** Rows live in memory. The database's refusals are emulated by rules read from `schema.sql`; MySQL's exact error texts, rounding corner
   cases, collation folding (the fake lower-cases for `utf8mb4_unicode_ci`, with no accent folding), the `AUTO_INCREMENT` counter (a failed insert does not
   consume an id in the fake, it does in MySQL) and integers above 2^53 are not reproduced.
2. **No HTTPS, no HSTS, no CORS.** `OPTIONS` answers 204 without CORS headers. The real server requires HTTPS for non-local clients.
3. **No email.** `forgot`, `reset`, `verify`, `resend-verification` answer 404 (the real config has no `reset_url`/`verify_url`) unless `--mail`, which writes
   to an outbox instead of sending.
4. **Passwords** are scrypt (N=1024) instead of Argon2id: fast and weak on purpose. The bcrypt 72-byte rule is not applied. The email check is an
   approximation of PHP's `FILTER_VALIDATE_EMAIL`.
5. **Token lifetimes** accept any value (`--access-ttl 2` works); the real server clamps them.
6. **Rate limits are off by default** (the real ones are always on: 120 requests per minute per IP per app, 10 auth calls per minute, 5 wrong passwords
   lock for 15 minutes). The IP is the socket's address, or the `X-Fake-Client-Ip` header (dev-only) to simulate another client; real AppHub takes it from
   the trusted proxy's `X-Forwarded-For`. The counters are fixed windows, like the real file-based limiter, but in memory.
7. **Not implemented** (they answer what the real server answers when the feature is off, or 404): tenant-style URLs (`/auth/login` with `tenant` in the body),
   API keys, Google sign-in, file uploads and downloads, public reads, the admin panel, console, docs and SDK routes, the `shared` scope, delete rights and the
   trash, `q` search (no table is searchable, so `q` answers 400 "This resource does not support search"). `GET /` always answers the JSON health check.
8. **Ties** in a sorted list keep insertion order; MySQL leaves them undefined. Paging is offset based on a snapshot of the current rows.
9. **Concurrency:** requests are handled one at a time, so there is no race. Real AppHub has none of the guards either (no transaction around create/update).
   A burst of a few hundred simultaneous new connections can be refused by the operating system's listen backlog (seen at about 230 on Windows);
   120 at once is fine.
10. **Dev-only extras** that do not exist on AppHub: the `/__*` endpoints, `X-Fake-Db-Error`, `X-Fake-Client-Ip`, `--mail`'s outbox, the virtual clock.
11. **Documentation drift:** `docs/API.md` shows the 422 message as "These fields cannot be written: price"; the source (and so the fake) uses
    "These fields cannot be written" with the names in `details.fields`.
12. **Speed:** no network latency, no TLS, no 1-vCPU server behind a tunnel. Use `delay` faults and `--rate-limit` to approximate bad conditions.

## Not verified against the real server (guesses and inferences)

Nothing here was observed on the cloud AppHub; each item is derived from source or from general MySQL/PHP knowledge and should be confirmed by the
first verification run against `shoppinglistdev`:

* DOUBLE columns come back as JSON numbers with a fraction (`1024.0`): inferred from PDO native prepared statements returning PHP floats and
  `JSON_PRESERVE_ZERO_FRACTION` in `Response::json`. A client must parse either form.
* Which database errors are 409 and which are 500 beyond SQLSTATE 23000 (the source) relies on MySQL's SQLSTATEs (CHECK violation 3819, data too long 1406,
  incorrect integer 1366, field has no default 1364, out of range 1264 are all non-23000, so 500). ReadRhythm's README records CHECK and duplicate refusals
  observed as 500 and 409 on the real server; the other cases are inferred.
* The real server's password hash is Argon2id (ReadRhythm's `schema.sql` comment says so); if the cloud PHP has no Argon2id, bcrypt's 72-byte limit applies there.
* PHP's handling of unusual query strings (`a[]=1`, `a[b]=1`, keys with dots) is approximated: any key with `[` becomes an array value.
* `Retry-After` on 429 is `max(1, seconds until the window resets)`, from `RateLimiter` and `HttpException::tooManyRequests`; the cloud limiter is file-based
  and per IP behind a proxy, so shared-NAT clients see the limit sooner than a single client does.
* The messages of the injected `status` faults other than 429, 500 and 503 ("Bad gateway", "Gateway timeout", "Injected fault") are invented; a proxy in
  front of the real server may answer with its own HTML.
* Unknown app names answer 401 "Authentication required" (from `Kernel::resolveTenant`; the feasibility audit saw 401 for every name on the live server).
* Text and key limits: a text value over 65,535 bytes answers 422 (source); a `VARCHAR` column's limit is counted in characters (code points), as MySQL does.
