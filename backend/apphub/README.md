# Shopping List on AppHub: the files that define the backend (Phase 3)

AppHub (the owner's own REST-over-MySQL server) is the only cloud backend of the Shopping List app. These files are the whole
definition of the `shoppinglistdev` (dev/test) and `shoppinglist` (production) apps on it. They are source code: change them
here, never by hand on the server. The design and the reasons are in `docs/APPHUB_FEASIBILITY.md` (sections 3 to 5 are the
facts this phase relies on); this page is the operator's runbook and the ledger of what has actually been applied.
It follows the method of `C:\MyApps\ReadRhythm\apphub\` (`README.md`, `provision.sh`, `validate-config.php`).

## The rule: cloud only, nothing applied yet

* **The real database lives only in the cloud AppHub** (`https://132-226-190-156.sslip.io/apphub`). The local XAMPP AppHub
  (`C:\xampp\htdocs\apphub`) is a read-only reference for its source and `docs/API.md`: never a place to create this database.
* **Nothing in this folder has been applied to any server, any database or any app.** Every file here is written for a human
  operator to review and apply later. The ledger below says "not applied" until a row is filled in with the date and who did it.
* Every step below that touches the cloud server waits for the owner's go-ahead in chat at that moment (same rule as ReadRhythm).
* No credential is ever stored in this folder, in git or in chat: see "What the operator must provide".
* The Android client is developed against the **fake server** in `fake-server/` until a cloud dev app exists and has been verified.

| File | What it is |
|---|---|
| `schema.sql` | The five tables: `users` (AppHub's sign-in table, plus `display_name`), `shopping_lists`, `shopping_items`, `categories`, `tags`. MySQL 8.0.16 or newer, InnoDB, `utf8mb4_unicode_ci`. Names **no database**: the operator creates the database and picks it on the command line, so one file serves a throwaway dry-run database, `shoppinglistdev` and `shoppinglist` |
| `shoppinglist.config.json` | AppHub's own settings for the app (roles and permissions, sign-in and password rules, token lifetimes). It has nothing app-specific in it: the same file is imported into `shoppinglistdev` and then into `shoppinglist` (the app name is the slug given to `tenant.php import`) |
| `validate-config.php` | Runs the config through AppHub's own validator (`App::normalize`) with dummy database and JWT values, without any database, and checks this app's Phase 3 contract (no delete for anyone, no wildcard role, owner columns, `updated_at` never writable): `php validate-config.php [apphub checkout] [config.json] [app]` |
| `fake-server/` | `server.mjs`: an in-memory fake of the AppHub API for local development and testing of the Android client (reads `schema.sql` and the config, so it cannot drift). `smoke.mjs`: the scripted scenario that proves it. `README.md` there: how to run it and every known difference from real AppHub |

## Ledger: what has been applied where

Update this table whenever something is applied, with the date and who did it. Nothing is applied until a row says so.

Observed 2026-11-16 before anything was applied (the owner's screenshot of the cloud AppHub admin, `/apphub/admin/#/apps`): five apps were registered (Fuel Tracker, Leave Tracker, ReadRhythm, Sample Shop, SubmitFlow) and neither `shoppinglistdev` nor `shoppinglist` existed.

**How the dev app was created on 2026-11-16.** Not with `tenant.php` over SSH as the runbook below describes, but through the AppHub admin panel in the owner's own signed-in Chrome (Claude drove the browser with the owner's go-ahead; the owner signed in themselves, no password or other secret was typed by Claude): *Add app*, app name `shoppinglistdev`, display name "Shopping List (dev)", *Create a new database* `shoppinglistdev`, *Empty* start, importing a copy of `schema.sql` with only its comment lines removed (the same five `CREATE TABLE` statements), then *Settings, Advanced* with `shoppinglist.config.json` pasted and saved. The panel asked for no MySQL admin login. The SSH runbook remains the way to do the production app and to install the Phase 4 triggers.

| Item | Where | State | When and by whom | Checked by |
|---|---|---|---|---|
| Dry run of `schema.sql` on a throwaway database | cloud MySQL | skipped for the dev app: the panel creates the database and removes it again if the import fails | | |
| Database `shoppinglistdev` and its five tables (`schema.sql`) | cloud MySQL | **applied**: "Database shoppinglistdev created with 5 tables" (so the file runs on the cloud MySQL, CHECKs and foreign keys included) | 2026-11-16 16:00 UTC, Claude in the owner's Chrome, owner's go-ahead | the panel's own confirmation and health check |
| App `shoppinglistdev` registered with AppHub | cloud AppHub | **applied** (MySQL user `apphub_shoppinglistdev`, key version 1) | same | panel Overview |
| Config (`shoppinglist.config.json`) in `shoppinglistdev` | cloud AppHub | **applied** through Settings, Advanced ("Settings saved. They apply to the API immediately.") | 2026-11-16, same | panel validation; `POST auth/register {}` answers 422 "A valid email is required" (public sign-up is on) |
| Health check, config export and diff | cloud AppHub | health check done: 12 of 14 pass, the one warning is "no users yet", the other note is "no CORS origins" (not needed by the Android app); export and diff not done | 2026-11-16 | panel |
| Client verified against `shoppinglistdev` (register, sign in, create, sync, refresh) | cloud AppHub, through its public API | **done** with two throwaway accounts (`slverify-a-75794793@example.com`, `slverify-b-75794793@example.com`, generated passwords kept outside the repo): a protocol check script and, on an emulator, the real client stack (`CloudDevAppTest`, opt-in) | 2026-11-16, Claude, owner's go-ahead ("Yes, register them") | results below |
| Removal of the two throwaway accounts | cloud AppHub, Users tab | **pending**: the roles have no delete permission, so the accounts and their rows stay until an admin deletes the users in the panel (rows cascade) | | |

### What the real dev app showed (2026-11-16)

Checked by a script against the public API (43 checks passed) and by `CloudDevAppTest` on an Android 16 emulator (3 tests passed: upload, pull on a second device, field merge, same-field conflict, delete and undo, a real refresh-token rotation, guest-list migration).

* **Matches what the client assumes:** JSON types (DECIMAL as a string `"2.500"`, TINYINT 0/1, DOUBLE and BIGINT as numbers, negative INT colour, emoji and umlauts round trip); `updated_at` is `YYYY-MM-DD HH:MM:SS.mmm` and the database session is **UTC** (offset 0 s from this PC); a PATCH that changes nothing leaves `updated_at` alone and a real change moves it; explicit `null` clears a nullable column and `null` on a NOT NULL column is 409; a non-writable field (`user_id`, `updated_at`, `role`, `email`) is 422 "These fields cannot be written" and nothing is applied; duplicate id is 409 and a GET then returns the row; DELETE is 403 "Your role cannot delete ..."; paging (`limit` 1 to 100, 101 is 400, `meta.pages`), `sort=updated_at`, an equality filter on `list_id`; an unknown sort column or filter is 400; refresh rotates the pair, an old token is 401 "already used; all sessions from it were signed out", and the new token of that family is dead too; logout kills the refresh token; two accounts can each store a `dairy` override (different ids) and cannot see or patch each other's rows (404); `forgot` answers 404 (no mail is set up, as assumed).
* **Database refusals are HTTP 500** (blank name, 81-character name, missing `created_at_ms`, quantity 0, a category with neither name nor colour, a 31-character tag): confirmed, so the client validates before saving. A reference to a list that does not exist is 409.
* **Auth rate limit is real:** about 10 auth calls a minute per IP, then 429 "Too many requests" (a burst of registrations and logins hit it); data calls carry `x-ratelimit-limit: 120`.
* **Known hazard confirmed:** account B can create an item whose `list_id` is account A's list (201); A cannot see it (404), so it is harmless for personal lists.
* **Rows with a non-UUID id are accepted** (an id with spaces was stored), though such a row cannot be addressed by URL later. The app always generates UUIDs.
* **A second `dairy` override row with another id can exist for one account.** The client now only trusts the account's deterministic id for a built-in override and ignores any other row naming the same built-in.
* **Found only against the real server: idle keep-alive connections.** The server closes idle connections after a few seconds. With automatic retry off (needed for the one-time refresh token) the first auth call after a pause failed as a false "can't reach the server". Fixed: auth calls now use a fresh connection per call (`AppHubHttp.authClient`), data calls retry dropped pooled connections (they are idempotent).
* Registering returns 201 with `user` and `verification_email_sent` false. Signing in returns `expires_in` 900 and `refresh_expires_in`.
| Database `shoppinglist` and its five tables (`schema.sql`, same file) | cloud MySQL | not applied | | |
| App `shoppinglist` registered, config imported, `check.php`, export and diff | cloud AppHub | not applied | | |

## Tests that need no server

```bash
node --check fake-server/server.mjs                     # syntax
php validate-config.php C:\xampp\htdocs\apphub          # AppHub accepts the config (read-only source checkout, no database, no server)
node fake-server/smoke.mjs                              # the fake server's scenario (starts its own fake on a random localhost port)
```

What these prove, and what they do not:

* **Proved:** AppHub's own validator accepts the config and the Phase 3 contract holds in it; `schema.sql` and the config agree
  column by column (the fake reads both and warns on any drift; the smoke test requires zero warnings); the fake behaves as
  the AppHub PHP source says it should (`smoke: N passed, 0 failed`; a mutation test confirmed the checks do fail when the
  fake is broken).
* **Not proved:** that `schema.sql` loads on the cloud MySQL (no MySQL was touched while writing it: the dry run below is its first
  real test), and that the real server behaves like the fake. Every behaviour the fake takes from reading source rather than from
  observing the server is listed in `fake-server/README.md`.

## What the operator must provide (never in the repo, never in chat)

| What | Why | How it is handled |
|---|---|---|
| SSH access to the AppHub server (the user and key the owner already uses for ReadRhythm) | to upload the files and run `mysql` and `php bin/tenant.php` there | use the owner's own agent/key; Windows OpenSSH in PowerShell (Git Bash's `ssh` cannot see the agent) |
| The MySQL admin login | `tenant.php add` creates the app's own MySQL user and AppHub's `apphub_*` tables | read **on the server** and handed to AppHub on standard input, never as a command-line argument or an environment variable (they show in `ps` and in the sudo log), exactly as `ReadRhythm/apphub/provision.sh` step 5 does |
| The owner's go-ahead for each server step | nothing here is applied without it | said in chat at that moment |
| Throwaway test addresses for the verification step | registering creates real accounts on the cloud app | `+tag` addresses of the owner's own mailbox; removed afterwards (`DELETE FROM users WHERE email IN (...)` on the app's database: the foreign keys cascade) |
| Decisions: open sign-up on production, no password recovery until mail is set up | see "Things to know" | owner says yes or no |

Nothing else is needed: the app's MySQL password and JWT secret are derived by AppHub from its master key and are never part of
the config (`tenant.php import` refuses a config that contains `jwt.secret` or `database`).

## Operator runbook: the dev app first, then production, from the SAME files

Commands are the real ones from `bin/tenant.php`, `SETUP.md` and `ReadRhythm/apphub/provision.sh`; the server paths
(`/var/www/html/apphub`, `/root/mysql-admin.txt`, the `setup_admin` MySQL user) are the ones that script uses on this server.
Do **not** run anything until the owner has said go for that step. Do the whole list for `shoppinglistdev` and verify it
before starting the list again for `shoppinglist`. Use the files unchanged: if something must change, change it in git first.

0. **Check the files and look at the server, read only.**
   ```bash
   # on the PC
   php validate-config.php C:\xampp\htdocs\apphub        # must end "contract: ok"
   node fake-server/smoke.mjs                            # must end "0 failed"
   # on the server
   sudo mysql -N -e "SELECT VERSION(), @@global.time_zone, @@system_time_zone, NOW(), UTC_TIMESTAMP(), @@GLOBAL.sql_mode"
   ```
   MySQL must be 8.0.16 or newer (earlier versions ignore CHECK constraints). `NOW()` must equal `UTC_TIMESTAMP()`: `updated_at`
   is the sync cursor and follows the MySQL clock, and the app reads it as UTC. If not, set `default-time-zone = '+00:00'` under
   `[mysqld]` and restart MySQL first. `sql_mode` should include `STRICT_TRANS_TABLES` (the default). Stop and ask if
   `shoppinglistdev` already exists (`sudo mysql -N -e "SHOW DATABASES LIKE 'shoppinglistdev'"`) or the app does
   (`cd /var/www/html/apphub && sudo -u www-data php bin/tenant.php list`).

1. **Upload the files** (LF line endings: the repository's `.gitattributes` keeps text files as LF in git, but check the copy you upload).
   ```bash
   scp schema.sql shoppinglist.config.json ubuntu@<host>:/tmp/
   ssh ubuntu@<host> "tr -cd '\r' < /tmp/schema.sql | wc -c"     # must print 0
   ssh ubuntu@<host> "grep -inE '^[[:space:]]*(USE|(CREATE|DROP|ALTER)[[:space:]]+(DATABASE|SCHEMA))[[:space:]]' /tmp/schema.sql"   # must print nothing
   ```

2. **Dry run on a throwaway database.** `schema.sql` names no database, so it is loaded into one the operator names. A
   throwaway name proves the file on this exact MySQL without touching anything real:
   ```bash
   T="sl_dryrun_$$"
   sudo mysql -e "CREATE DATABASE \`$T\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
   sudo mysql "$T" < /tmp/schema.sql
   sudo mysql -N -e "SHOW TABLES FROM \`$T\`"                                                        # 5 tables
   sudo mysql -N -e "SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = '$T'"                     # 10
   sudo mysql -N -e "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = '$T' AND CONSTRAINT_TYPE = 'FOREIGN KEY'"   # 5
   sudo mysql "$T" < /tmp/schema.sql && echo "second run is harmless (IF NOT EXISTS)"
   sudo mysql -e "DROP DATABASE \`$T\`"
   ```
   Stop on any error: fix `schema.sql` in git, never on the server.

3. **Create the database and load the schema.**
   ```bash
   sudo mysql -e "CREATE DATABASE shoppinglistdev CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
   sudo mysql shoppinglistdev < /tmp/schema.sql
   sudo mysql -N -e "SHOW TABLES FROM shoppinglistdev"
   ```

4. **Add the app** (creates the app's MySQL user limited to that database, AppHub's own `apphub_*` tables in it, and a starter
   config that only admins can use; the MySQL admin password goes in on standard input):
   ```bash
   cd /var/www/html/apphub
   P=$(sudo sed -n 's/^password: //p' /root/mysql-admin.txt)
   printf '%s\n' "$P" | sudo -u www-data env APPHUB_DB_ADMIN_USER=setup_admin php bin/tenant.php add shoppinglistdev shoppinglistdev "Shopping List (dev)"
   unset P
   ```
   (`add <slug> <database> ["Name"]`.) Do not create `apphub_*` tables by hand.

5. **Import the config**, which replaces the starter config completely:
   ```bash
   sudo -u www-data php bin/tenant.php import shoppinglistdev /tmp/shoppinglist.config.json
   ```

6. **Check** (AppHub's own read-only check: config valid, users table matches the auth settings, every table and column exists):
   ```bash
   sudo -u www-data php bin/check.php shoppinglistdev
   ```

7. **Export and diff the config afterwards.** AppHub has been seen to widen a role after schema changes (ReadRhythm README), so
   prove what is stored is what is in git:
   ```bash
   sudo -u www-data php bin/tenant.php export shoppinglistdev /tmp/exported.json
   NORM='function k(&$x){if(is_array($x)){ksort($x);foreach($x as &$v)k($v);}} $a=json_decode(file_get_contents($argv[1]),true,512,JSON_THROW_ON_ERROR); k($a); echo json_encode($a,JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES),"\n";'
   diff <(php -r "$NORM" /tmp/shoppinglist.config.json) <(php -r "$NORM" /tmp/exported.json) && echo IDENTICAL
   ```
   They must be identical: only a `user` role, no `*` entry, no `delete` anywhere, no `shared` or `all` scope.

8. **Verify through the public API** (the owner or an operator, with throwaway addresses; see "How to verify"), then record
   everything in the ledger, including what failed or was skipped.

9. **Production.** Only after the dev app has been verified and the owner says go: repeat steps 0 to 7 with `shoppinglist` as the
   database and the app, name "Shopping List", from the same, unchanged `schema.sql` and `shoppinglist.config.json`. Remove the
   uploaded files from `/tmp` afterwards (`rm -f /tmp/schema.sql /tmp/shoppinglist.config.json /tmp/exported.json`; they hold no secrets).

If a step after the dry run fails halfway, a half-built database or app may remain, and a rerun will refuse because it exists:
look at what was created (`SHOW TABLES FROM shoppinglistdev`, `php bin/tenant.php list`), `php bin/tenant.php remove <app>` unregisters
an app and drops its MySQL user but keeps the database (drop it yourself), then rerun from the corrected files.

## How to verify

* **Now, with no server:** the three commands under "Tests that need no server". For the Android client, run the fake server
  (`node fake-server/server.mjs --port 8787`, see `fake-server/README.md`) and point the debug build at it.
* **After step 6, against the real dev app only** (never production first): from a PC, with the base URL
  `https://132-226-190-156.sslip.io/apphub/shoppinglistdev/` and throwaway addresses, in this order: register (201; sending `role`
  or `user_id` must be refused), sign in, `GET auth/access` (the four data tables show `list/read/create/update` as `own`, no
  `delete`; `create_fields` has `id` and no `user_id` or `updated_at`), create a list and an item with client UUIDs (201), repeat
  the create (409) and fetch it by id (200), `PATCH` a field (200, `updated_at` moves) and `PATCH` the same value again
  (`updated_at` does not move), list with `sort=updated_at&order=desc` and paging, `PATCH deleted_at_ms` (the row stays in the
  list), `DELETE` (403), a second account reading the first one's row (404), refresh rotation and refresh reuse (401, session
  dead), a name of 81 characters (500). Each of these is a check in `fake-server/smoke.mjs`; any difference between the real
  answer and the fake's is a bug in the fake (fix `server.mjs` and its README), not in the client.
  A script that runs those checks against a URL (modelled on `ReadRhythm/apphub/isolation-test.mjs`) is not written yet.

## The contract a client codes against

**Columns the client may write** (everything else is server-managed): on create, every column of the table except `user_id` and
`updated_at`; on update, the same minus `id`. `user_id` is filled in by AppHub (the caller) and `updated_at` by MySQL.

| Table | Writable on create | Writable on update (extra rule) |
|---|---|---|
| `shopping_lists` | `id`, `name`, `description`, `icon`, `color`, `is_archived`, `is_pinned`, `sort_key`, `created_at_ms`, `updated_at_ms`, `deleted_at_ms` | all but `id` |
| `shopping_items` | `id`, `list_id`, `name`, `quantity`, `unit`, `category_key`, `notes`, `is_bought`, `bought_at_ms`, `sort_order`, `tag_ids`, `created_at_ms`, `updated_at_ms`, `deleted_at_ms` | all but `id` |
| `categories` | `id`, `builtin_key`, `name`, `color`, `created_at_ms`, `deleted_at_ms` | all but `id` |
| `tags` | `id`, `name`, `created_at_ms`, `deleted_at_ms` | all but `id` |
| `users` | none (register takes `email`, `password`, `display_name`) | `display_name` only |

* **`updated_at_ms`** (`shopping_lists` and `shopping_items` only; `BIGINT NOT NULL DEFAULT 0`) is the client's clock of the last user
  edit. It is an ordinary writable column, **not** the server-managed `updated_at`, and is neither filterable nor sortable. Omitted on create it
  is stored as `0`; an explicit `null` is refused (409). Changing it moves `updated_at`, like any change.
* **Sync cursor:** `updated_at` (server). `sort` is allowed on `id`, `updated_at`, `created_at_ms`; filters (equality only): `id` on every
  table, `list_id` on items, `builtin_key` on categories.

**Exact JSON types in answers** (verified against the fake, which follows the source; see `fake-server/README.md` for what is inferred):

| Column type | JSON | Example |
|---|---|---|
| `CHAR`, `VARCHAR`, `TEXT` | string | `"Milk"`, `"[\"t1\"]"` |
| `INT`, `INT UNSIGNED`, `BIGINT` | number, no fraction | `-16777216`, `1767225600000`, `user_id` `1` |
| `TINYINT(1)` | number `0` or `1` (never `true`/`false`; a request may send either) | `1` |
| `DOUBLE` (`sort_key`, `sort_order`) | number WITH a fraction | `2048.0` (a client must accept `2048` too) |
| `DECIMAL(12,3)` (`quantity`) | **string** with all three decimals | `"2.000"`, `"2.500"` (a request may send `2.5` or `"2.5"`) |
| `DATETIME(3)` (`updated_at`) | string `YYYY-MM-DD HH:MM:SS.mmm`, UTC, no `T`, no zone | `"2026-11-16 14:23:04.969"` |
| `DATETIME` (`users.created_at`) | string `YYYY-MM-DD HH:MM:SS` | `"2026-11-16 14:23:04"` |
| SQL `NULL` | `null` | `"notes":null` |

**PATCH (and PUT, which behaves identically) is a partial update, with these exact rules:**

* A field **omitted** from the body is left unchanged.
* A field sent as an explicit JSON **`null`** on a nullable column **sets it to NULL**. On a `NOT NULL` column (`name` of lists, items and tags,
  `list_id`, `created_at_ms`, `updated_at_ms`, `is_*`, `sort_*`) it is refused with **409**.
* A column that is **not writable** (`user_id`, `updated_at`, `id` on update, and any name that is not a column) is **never silently ignored**:
  the whole request answers **422** `These fields cannot be written`, with the offending names in `error.details.fields` and the allowed ones
  in `error.details.writable`, and **nothing** in the body is applied. The same 422 answers a create that sends `user_id` or `updated_at`.
* An empty body `{}` answers 422 `No writable fields were provided`; a row that is not yours (or does not exist) answers 404 before the fields are checked.
* A PATCH that changes no value returns the row with an **unchanged** `updated_at`; any real change, `updated_at_ms` included, moves it.
  Values are compared after the database converts them, so `2.5` over a stored `"2.500"` is a no-op.

## Things to know

* **`item.list_id` is not owner-checked.** AppHub scopes a row by its owner column only: nothing stops an account from creating an
  item whose `list_id` is a list owned by somebody else (only a foreign key says the list exists). The other account never sees that
  item (its `user_id` is the creator's), but a client must not depend on the server refusing it. This is a known limitation of
  Phase 3, fixed in Phase 4 with a database trigger. **ReadRhythm installs its triggers over SSH** as the MySQL admin
  (`ReadRhythm/apphub/triggers.sql`): AppHub's panel refuses `CREATE TRIGGER`, and neither lists nor backs up triggers.
* **Built-in category overrides (the old "global primary key" hazard is fixed).** AppHub's primary key is one column shared by all
  accounts, so `categories.id` is always a client-generated UUID and never a built-in key. A rename or recolour of a built-in
  category (for example `dairy`) is a row with `builtin_key = 'dairy'` and a **deterministic per-user UUID** as its id, so two
  accounts never collide and a retried create is idempotent (409, then `GET` by id). `builtin_key` is NULL for a custom
  category, is writable and filterable (`GET categories?builtin_key=dairy`), and is not unique per account: one override per
  built-in per account is the client's job (the deterministic id). `name` and `color` are both nullable, but a row must carry at
  least one of them (a CHECK: otherwise HTTP 500).
* **Database refusals are mostly HTTP 500, not 422.** AppHub turns only a duplicate key, a foreign-key failure and an explicit
  NULL in a NOT NULL column into 409. A violated CHECK, a value longer than its column (`name` over 80 characters, `display_name`
  over 60, `builtin_key` over 40), a non-numeric quantity, a category with neither `name` nor `color`, or a missing required field
  (`created_at_ms`) is a generic 500 with no detail. The clients must
  validate first and must not retry a 500 forever.
* **The default list order is `id DESC`, which is random for UUIDs.** A client must always send `sort=updated_at&order=desc`.
  There is no `since` filter; paging is offset based without a tie-break (feasibility F4).
* **DELETE answers 403** (`Your role cannot delete <table>`), not 405: the route exists and the role has no delete right.
  Clients tombstone with `PATCH deleted_at_ms`.
* **No email.** `reset_url`, `verify_url` and `verified_column` are not configured, so `auth/forgot`, `reset`, `verify` and
  `resend-verification` answer 404 and there is no password recovery. Sign-up is open and unverified. Turning email on needs working
  SMTP on the server and an Android App Link for the link (see `docs/API.md`, "Password reset and email verification").
* **No account deletion endpoint** and no `delete` right on `users` (feasibility F8).
* **Only the `user` role exists.** The `users.role` CHECK allows `admin` (as ReadRhythm's does), but the config gives `admin` no
  rules, so an operator-promoted admin could do nothing through the API.
* **`updated_at` has millisecond resolution and no tie-break:** two changes in the same millisecond share a value. The delta
  pull must overlap (feasibility 4).
* **Hosting:** the Android app needs no CORS entry (`cors_origins` is empty) and no API key: the only public values are the base
  URL and the app name.

## Changing the schema later

AppHub has no migrations. A change is a change to `schema.sql` (and the config when it is affected), a reviewed `ALTER` run by
the operator on the dev database first, then `check.php`, an export and diff of the config, and the ledger. `CREATE TABLE IF NOT EXISTS`
does not alter an existing table. When the first trigger arrives (Phase 4) it gets its own `triggers.sql`, applied by the operator
over SSH, and a shape comparison like ReadRhythm's `shape-compare.sh`.
