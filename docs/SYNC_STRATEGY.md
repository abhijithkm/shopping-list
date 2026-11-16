# Sync strategy (Phase 3)

How the app keeps a signed-in account's lists, items, categories and tags the same on every device, and how guest lists move into an account. It is written against what AppHub can and cannot do ([APPHUB_FEASIBILITY](APPHUB_FEASIBILITY.md) §4, [APPHUB_INTEGRATION](APPHUB_INTEGRATION.md)). Code: `data/sync/`.

Status: implemented and tested against an in-memory fake AppHub and the faithful Node fake in `backend/apphub/fake-server`. **Not yet verified against the real cloud AppHub** (no cloud dev app exists; see [IMPLEMENTATION_STATUS](IMPLEMENTATION_STATUS.md)).

## 1. Principles

1. **Room is the source of truth.** The UI reads and writes only Room. Sync is a background process that makes Room and AppHub agree; the screens never wait for it.
2. **Guest mode stays pure.** With no account, nothing here runs and no network call is made. Sign-in is requested only when the user chooses a cloud feature (Settings, Account).
3. **Nothing is ever deleted to make sync easier.** Lists are only moved (guest to account, account to guest) never dropped; deletions are tombstones.
4. **Every server write is safe to repeat.** The app can be killed, lose its connection or lose a reply at any point; a retry must never duplicate or lose data.
5. **No server-side help is assumed.** AppHub has no "changed since", no batch, no ETag and no push. Everything below is built from plain list, create and patch calls.

## 2. What is synced

| Local entity | Cloud table | Synced fields | Not synced (sent on create only, or local only) |
|---|---|---|---|
| List (`lists`) | `shopping_lists` | name, description, icon, color, is_archived, is_pinned, sort_key, deleted_at_ms | id, created_at_ms; `updated_at_ms` is sent with every write as display metadata |
| Item (`items`) | `shopping_items` | name, quantity, unit, category_key, notes, is_bought, bought_at_ms, sort_order, tag_ids, deleted_at_ms | id, list_id, created_at_ms, `updated_at_ms` |
| Category (`categories`) | `categories` | name, color, deleted_at_ms | id, builtin_key, created_at_ms |
| Tag (`tags`) | `tags` | name, deleted_at_ms | id, created_at_ms |

* An item's tags travel as the JSON text column `tag_ids` (`["uuid","uuid"]`); there is no `item_tags` table in the cloud. The effective tag set of an item is its non-deleted tags that exist locally; references to deleted tags are ignored when reading.
* A rename or recolour of a **built-in** category is a row whose `builtin_key` is the built-in key (for example `dairy`). Its cloud id is a name-based UUID of (account id, key), because `categories.id` is one primary key shared by every account: two accounts overriding `dairy` must not collide, and a retried create must hit the same id.
* `revision`, `serverRevision`, `origin`, `accountId` and the sync bookkeeping below are local only.

## 3. Local bookkeeping (Room schema v2)

* **Dirty rows.** A row of the account is *dirty*, meaning "has something to push", when `revision > serverRevision` or when it has no sync base (never reached the server). Every repository update already bumps `revision`, so **no hook is needed in any repository method**: the sync engine just reads the dirty rows, and the coordinator watches the dirty-row count to know when to run. (Items have no account column: they belong to the account of their list.)
* **`sync_entities`** (one row per synced entity that has been touched by sync): the **base**, which is the canonical JSON of the synced fields as the server last agreed with us; plus per-row failure state (`failCount`, `nextAttemptAt`, `lastError`, `blocked`, `blockedAtRevision`). The base is the common ancestor of the field-level diff (what to push) and the three-way merge (what to keep when the server changed too).
* **`sync_state`**: per account key/value: the pull cursor per table (`cursor.tag`, `cursor.category`, `cursor.list`, `cursor.item`), `lastPullAt`, `lastSuccessAt` (the last run that finished with no failure), `lastRun` (outcome and counts of the latest run).

## 4. Identity and idempotency

* Every id is a client-generated UUID, used as the primary key on create. A create that succeeded on the server but whose answer was lost is repeated as a create: the server answers **409** (duplicate key), the client then reads the row by id and patches whatever differs. Nothing is ever created twice.
* Updates are `PATCH` of the changed fields only, so repeating one is harmless.
* Success is recorded (base saved, row marked clean) in one transaction that runs non-cancellably right after the answer arrives. If the process dies between the answer and that record, the next run repeats the call, which is idempotent as above.

## 5. A run

Triggers: `APP_START`, `SIGN_IN`, `MANUAL` (Sync now, Retry, migration), `PERIODIC` (every 30 minutes, network required), `LOCAL_CHANGE` (about 2 seconds after the dirty-row count rises). Runs are serialised by one mutex. Order inside a run:

1. Make sure there is an access token (refresh if needed, see [APPHUB_INTEGRATION](APPHUB_INTEGRATION.md) §3). No session means no run.
2. **Pull** every table (tags, categories, lists, items, in that order), unless the trigger is `LOCAL_CHANGE` and a pull happened less than a minute ago.
3. **Push** the dirty rows (tags, categories, lists, items).
4. Record the outcome.

### 5.1 Pull

AppHub offers no "changed since" filter, but `updated_at` is maintained by the database on every real change and the list call can sort by it. So one request per table returns exactly the recent changes first:

* `GET /{table}?sort=updated_at&order=desc&limit=100&page=n`, page by page, until a row is older than **cursor minus a 2-minute overlap**. The cursor is the newest `updated_at` seen. It is a server value compared only with other server values, so the server's time zone and the device clock are irrelevant (`ServerTimestamp` does the arithmetic without `java.time`).
* The overlap re-reads rows written while a previous page fetch was running (offset paging can skip a row if rows are written meanwhile). Applying a row twice is harmless.
* An idle run costs one request per table (four). A first sync reads every page.
* Rows are applied one at a time in a transaction (see merge). A server row that is a **tombstone for an unseen row** is ignored; if it is a list, its items (which stay live on the server) are ignored too instead of waiting for it. An item whose list is simply not on the device *yet* is skipped and **the cursor for that table is not advanced**, so the row is read again next time.
* When a **new tag** arrives, items are read again in full that run: an item pulled earlier was stored without the link to a tag the device did not know yet, and the cursor alone would never revisit it.
* There is no page cap: a run is bounded by its time budget (8 minutes), and the cursor is saved only when a table was read to its end, so an interrupted first sync is simply continued. Runs are paced under AppHub's per-IP rate limit (about 120 requests a minute): at least 520 ms between requests.
* Timestamps are formatted with `Locale.ROOT`, so a device language with non-ASCII digits cannot break the cursor arithmetic.

### 5.2 Merge (conflict detection and resolution)

For each row both sides know, the engine merges the **fields** (not the whole row) with the stored base as the ancestor:

| Local vs base | Server vs base | Result |
|---|---|---|
| unchanged | changed | take the server value |
| changed | unchanged | keep the local value; it is pushed |
| changed | changed, same new value | nothing to do (converged) |
| changed | changed, different values | **conflict**: the local value wins and is pushed (last pusher wins); counted and shown ("merged N conflicting edits") |
| `deleted_at_ms` set on either side | | **delete wins** over an edit: a row deleted on either device stays deleted |

So edits to different fields of one item on two phones both survive; only a simultaneous edit of the *same field* is a conflict, and the one made on the device that syncs last wins. With no base (a row that exists on the server but was never synced here, for example a repeated migration or a list moved back into the account after a sign-out) every differing field counts as a conflict: the local copy wins, unless the server copy carries a later `updated_at_ms`, in which case the server's values win (lists and items; categories and tags have no such stamp, so the local value wins). A delete on either side still wins.

**Known limitation.** AppHub has no revision check, so two devices editing the same field offline can overwrite each other once, and the overwritten edit is lost. For personal lists with a handful of devices this is acceptable. A true compare-and-set (a `revision` column and a MySQL trigger) is part of the Phase 4 server design for shared lists, where concurrent editors are normal.

### 5.3 Push

For each dirty row, in the order tags, categories, lists, items:

1. **Skip** it while it waits out its backoff (`nextAttemptAt`) or is *blocked* (see below).
2. **Deleted while no base exists** (created and deleted before the device recorded it as synced): the delete waits **15 seconds** (the undo window; a quick add-then-delete costs nothing). Then the tombstone is `PATCH`ed, because the server may already have the row (a create whose answer was lost, or a list moved back into the account): a **404** means it never did and the local row is dropped; otherwise the server now has the tombstone and the row is clean. A coordinator re-run follows the wait.
3. **Item whose list is not on the server yet**: wait (lists are pushed first, so this only happens when the list failed).
4. **No base**: `POST` the whole row with its client id. On **409** read the row by id and **merge with it** through the ordinary merge (below), never overwrite it: a delete made elsewhere stays a delete, and when the server copy was edited later than the local one (`updated_at_ms`) its values win the conflicts. Whatever still differs is sent on a second push pass of the same run (this is the idempotent create). If the row does not exist, the 409 was a real refusal and the row is blocked.
5. **Has a base**: compute the field diff against the base, `PATCH` only the changed fields plus `updated_at_ms`. An empty diff sends nothing (the row is simply marked clean). A **404** means the row vanished from the server, so it is created again from the local copy.
6. On success the base becomes *the old base plus exactly the fields just sent* (never the whole returned row: the returned row may contain another device's newer changes that have not been merged yet, and adopting it as the base would push them back as if they were ours).

### 5.4 Failures and backoff

| Situation | What happens |
|---|---|
| No connection (`IOException`) | the run stops with `OFFLINE`; nothing is lost; WorkManager retries with exponential backoff and when the network returns |
| 401 | the authenticator already tried one refresh. If the session ended, the run stops with `NEEDS_SIGN_IN` (local data and pending changes are kept); otherwise it counts as offline |
| 429 | the run stops with `RATE_LIMITED`; `Retry-After` is reported; the run is retried later |
| 502, 503, 504 | the server is down: the run stops with `SERVER_UNAVAILABLE`, to be retried |
| 500 | AppHub reports a database refusal (a CHECK failure for example) as 500, so it may be permanent. The row retries after 5 s, 30 s, 2 min, 10 min, 30 min; after **3 failures** it is blocked. Three 500s in a row (different rows) look like an outage: the run stops with `SERVER_UNAVAILABLE` and **the penalty those rows just received is undone**, so an outage cannot push innocent rows towards being blocked |
| any other 4xx (400, 403, 404 on create, 409 without a row, 422) | the server refused this content; retrying the same bytes cannot help. The row is **blocked at once** with the server's message |

A **blocked** row does not stop anything else. It waits until the user edits it again (its revision rises) or taps **Retry** in Settings, which unblocks all failed rows. Settings shows how many changes could not be synced. The client validates the same limits the server enforces (name lengths, quantity range, notes length) before it ever saves, so a refusal should be a rare, unexpected case.

### 5.5 Scheduling

* `SyncCoordinator` (started in `Application.onCreate`) follows the session. Signed in: it queues the periodic worker, runs a sync now, and runs another about two seconds after every rise in the dirty-row count (debounced). Signed out or session ended: it cancels all background work.
* In-process runs handle the normal case instantly. If a run ends in a retryable way, the coordinator also queues a **network-constrained one-time WorkManager job**, which survives process death and waits for connectivity; the job's result makes WorkManager back off exponentially (30 s base).
* `PERIODIC` runs every 30 minutes with a network constraint as the slow safety net.
* **Full resync** (`SyncRepository.fullResync`; there is no button for it yet) clears the cursors so the next run reads everything: the repair tool for a change that was somehow missed.

## 6. Deletion, tombstones and purging

* A delete sets `deletedAt`; the row stays and is synced as `deleted_at_ms`. AppHub roles have **no delete permission at all** for these tables, so a client bug can never wipe server data.
* Guest tombstones are purged 7 days after the delete (the undo window is long over). Account tombstones are purged on the same schedule **only once the server has acknowledged them** (not dirty). Purged rows also lose their bookkeeping.
* A list delete is one tombstone on the list: its items are not individually marked, and they come back with the list if the delete is undone.
* Undo after a synced delete is simply another edit (`deleted_at_ms` back to null); it syncs like any other.

## 7. Sign-out and account switching

* **Sign out** attempts one last sync (15 seconds at most), ends the session on the server when reachable, then **detaches the account's data on the device**: lists, categories and tags become guest data (no account, local origin, no server revision, no bookkeeping). Nothing is deleted. Changes that never reached the server are reported ("3 changes had not synced and stay only on this device") and remain on the device; if the user signs in to the same account again and moves those lists, they are uploaded.
* **Signing in as a different account** than the one whose data is on the device first detaches the old account's data the same way, so two accounts never mix on one device. The same detach is repeated (it is idempotent) at the start of every sync run, so a sign-in interrupted between publishing the session and detaching is finished by the next run.
* **Session ended** (refresh token rejected): the app keeps everything, shows "Please sign in again", and edits keep queuing. Nothing is detached.

## 8. Guest-to-account migration

After a sign-in, the app syncs once (so the account's existing lists are known) and, if there are guest lists, offers to move them (also available later from Settings). Moving is a local, transactional change of ownership (`accountId`, `origin`) for the selected lists and, optionally, for the guest categories and tags; the upload is then the ordinary push.

* **Never deletes**: unselected lists stay guest lists; moved lists stay on the device.
* **Preserves everything**: the same rows are uploaded, so order (`sort_order`, `sort_key`), colours, icons, categories, tags, notes, quantities, units and bought state are unchanged.
* **Recoverable**: if the app dies or the network drops mid-way, the lists are already the account's locally and show as waiting to sync; the next run finishes the job. The sheet says so when the upload could not finish.
* **No duplicates**: running it twice is harmless (adopted lists are no longer guest lists, and a list that already reached the server is found by its id and only differences are patched). A guest list whose **name matches** an account list is flagged and left unselected by default; it is never merged silently (merging into an existing list is not implemented; the user can upload it as a separate list).

## 9. Known limitations

* Same-field concurrent edits: last device to sync wins (§5.2). Fixed by revision checks with the shared-list work (Phase 4).
* Two devices that create a tag with the same name while offline end up with two tags of that name; the user can delete one.
* A server tombstone for a tag leaves stale tag ids inside the `tag_ids` of items on the server; they are ignored when read.
* Sync has no push notification: another device's change appears on the next run (at most 30 minutes, or immediately on app start, Sync now, or a local change that triggers a pull).
* A device that is signed out and back in as the same account re-uploads its guest lists only when the user moves them again (a deliberate choice: guest data is never uploaded without being selected). When it does, the merge rules above decide, using `updated_at_ms` as the tie-break; categories and tags have no such stamp.
* Guest categories and tags are offered together with guest lists; with no guest list there is nothing to move them with. A rename of a built-in category made as a guest stays local until it is moved or edited again while signed in (editing while signed in makes it the account's).
* A built-in override that exists as guest data on a device keeps the account's override of the same built-in from applying there until the guest one is moved.
* Everything about timing and 429/5xx behaviour was exercised against fakes. The real server's limits, error texts and `updated_at` behaviour must be confirmed in the dev-app verification ([backend/apphub/README.md](../backend/apphub/README.md)).
