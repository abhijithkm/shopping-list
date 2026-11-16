# Database Schema — local (Room)

Status: reflects Phase 3 (schema version 2). The authoritative, machine-generated definition is the committed Room export `app/schemas/space.abhijithkm.shoppinglist.data.local.AppDatabase/2.json` (`1.json` is the Phase 2 schema and is the starting point of the tested migration); this page explains it. The **cloud** schema for personal lists is `backend/apphub/schema.sql` (see [APPHUB_INTEGRATION](APPHUB_INTEGRATION.md) §4); the collaboration tables and triggers of Phase 4 are still only designed in [APPHUB_FEASIBILITY §6](APPHUB_FEASIBILITY.md). Nothing has been created on the cloud AppHub.

## Conventions

- **Ids** are client-generated UUID strings. They double as AppHub primary keys later, which makes creates idempotent (a retried create answers 409 and is then fetched).
- **Timestamps** are epoch milliseconds (`Long`). On the wire they become UTC `DATETIME(3)` strings.
- **`revision`** counts local edits (every DAO `UPDATE` adds 1). **`serverRevision`** is the revision the server last acknowledged; it is `0` for guest data. A row of an account is **dirty** (has something to push) when `revision > serverRevision` or when it has no sync base yet; no repository method needs a sync hook because of this ([SYNC_STRATEGY](SYNC_STRATEGY.md) §3).
- **`deletedAt`** is a tombstone. Deleting never removes a row; the UI hides rows with a tombstone, undo clears it, and `LocalShoppingRepository.purgeExpiredTombstones` hard-deletes **guest** tombstones older than 7 days at app start. Account tombstones (`accountId` set) are purged on the same schedule only once the server has acknowledged them.
- **`accountId` / `origin`** separate guest data (`accountId = null`, `origin = LOCAL`) from account data (`origin = CLOUD`). Guest data never leaves the device without the explicit migration flow. Items have no account column: they belong to the account of their list.
- **Schema history.** v1 (Phases 1 and 2); v2 adds `sync_entities` and `sync_state` (Phase 3) through a Room auto-migration, tested on a device with a v1 database that has data in it. After the first public release every change keeps needing a tested migration, never a destructive fallback.

## Tables

### `lists`

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID |
| `accountId` | TEXT? | null for guest lists |
| `origin` | TEXT | `LOCAL` or `CLOUD` |
| `name` | TEXT | 1 to 80 characters (trimmed) |
| `description` | TEXT? | reserved |
| `icon` | TEXT? | one emoji from the picker (at most 16 characters) |
| `color` | INTEGER? | ARGB |
| `isArchived`, `isPinned` | INTEGER (bool) | archived lists are hidden from the main view; pinned sort first |
| `sortKey` | REAL | reserved for manual list order |
| `role` | TEXT | `OWNER` or `EDITOR` (always `OWNER` for guest lists) |
| `ownerName` | TEXT? | shared lists |
| `createdAt`, `updatedAt` | INTEGER | `updatedAt` also moves when items inside change ("last updated", recent-first sort) |
| `revision`, `serverRevision` | INTEGER | see conventions |
| `deletedAt` | INTEGER? | tombstone |

Indexes: `(accountId)`, `(isArchived, isPinned, updatedAt)`.

### `items`

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID |
| `listId` | TEXT FK → `lists.id` | `ON DELETE CASCADE` |
| `name` | TEXT | 1 to 120 characters |
| `quantity` | REAL? | greater than 0 and at most 1,000,000; optional |
| `unit` | TEXT? | at most 20 characters; optional |
| `categoryKey` | TEXT? | a built-in key (`dairy`, …) or a custom category id; null means none |
| `notes` | TEXT? | at most 500 characters |
| `isBought` | INTEGER (bool) | |
| `boughtAt` | INTEGER? | set when bought, cleared when unbought; the completed section shows most recently bought first |
| `sortOrder` | REAL | gap-based manual order (see below) |
| `createdBy`, `updatedBy` | TEXT? | user ids on shared lists |
| `createdAt`, `updatedAt`, `revision`, `serverRevision`, `deletedAt` | | as above |

Index: `(listId, deletedAt, isBought, sortOrder)` (leading column serves the foreign key).

**Ordering.** New items get `sortOrder = max(open items) + 1024`. A drag changes only the moved item's row to the midpoint of its new neighbours. When two neighbours are closer than `2e-6`, the open items are renumbered `1024, 2048, …` once and the move is retried. An unbought item keeps its old `sortOrder`, so it returns to where it was.

### `categories`

Custom categories **and overrides of the 11 built-ins**. The built-ins are fixed keys in code (`DefaultCategory`), so they are identical on every device; a row whose `id` equals a built-in key only stores the user's rename and/or colour, and deleting that row resets the built-in.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID for custom, the built-in key for an override |
| `accountId`, `listId` | TEXT? | `listId` is for list-scoped categories on shared lists (Phase 4) |
| `name` | TEXT? | null on a colour-only override |
| `color` | INTEGER? | ARGB; null on a name-only override |
| `createdAt`, `updatedAt`, `revision`, `serverRevision`, `deletedAt` | | |

Deleting a custom category tombstones it and sets `items.categoryKey = NULL` for its items.

### `tags` and `item_tags`

`tags`: `id` PK, `accountId?`, `listId?`, `name` (case-insensitive collation, 1 to 30 characters), plus the shared bookkeeping columns. Uniqueness among live tags is enforced by the repository (`createTag` returns the existing id; `renameTag` refuses a clash).

`item_tags`: composite PK `(itemId, tagId)`, both `ON DELETE CASCADE`, plus `listId` denormalised for list-scoped sync. Index `(tagId)`.

### `sync_entities` (v2)

One row per synced entity that sync has touched. Primary key `(type, id)` where `type` is `list`, `item`, `category` or `tag` and `id` is the local id.

| Column | Type | Notes |
|---|---|---|
| `base` | TEXT? | canonical JSON of the synced fields as the server last agreed on them: the ancestor of the field diff and the three-way merge. Null means the row has never reached the server (a create is pending) |
| `failCount` | INTEGER | consecutive failures of this row |
| `nextAttemptAt` | INTEGER | epoch ms before which the engine does not retry the row (backoff) |
| `lastError` | TEXT? | the server's message, at most 200 characters |
| `blocked` | INTEGER (bool) | the row failed too often or was refused; waits for a new edit or Retry |
| `blockedAtRevision` | INTEGER | the row's `revision` when it was blocked; a higher revision unblocks it |

### `sync_state` (v2)

Primary key `(accountId, key)`, `value` TEXT. Keys: `cursor.tag`, `cursor.category`, `cursor.list`, `cursor.item` (newest server `updated_at` seen per table), `lastPullAt`, `lastSuccessAt`, `lastRun` (JSON of the latest run's outcome and counts).

## Main queries (contracts the UI relies on)

- Lists screen: lists with `deletedAt IS NULL` and the requested `isArchived`, with live `totalCount` and `boughtCount` (deleted items excluded), pinned first then by `updatedAt`; the ViewModel then re-sorts by the chosen order.
- List detail: all live items of a list by `sortOrder` with their tags; open/completed split, filtering and grouping happen in a pure, unit-tested function (`buildListDetailState`).
- Every mutation runs in a transaction that also touches the parent list's `updatedAt`.

## Cloud mapping

| Local | Cloud (`backend/apphub/schema.sql`) | Differences |
|---|---|---|
| `lists` | `shopping_lists` | `accountId`, `origin`, `role`, `ownerName`, `revision`, `serverRevision`, `createdAt` and `updatedAt` stay local; the cloud has `created_at_ms`, `updated_at_ms` (client clock) and the server-managed `updated_at` |
| `items` | `shopping_items` | the tag links travel as the JSON text column `tag_ids`; there is **no `item_tags` table in the cloud** (a decision of Phase 3: fewer requests, no join table to keep consistent) |
| `categories` | `categories` | a built-in override carries `builtin_key` and a deterministic per-account UUID as its cloud id (the local id is the bare key) |
| `tags` | `tags` | |
| `item_tags`, `sync_entities`, `sync_state` | none | local only |

Collaboration (Phase 4) adds `list_members`, `list_invitations`, `join_codes`, `join_requests`, `user_lookups`, `code_lookups` and `list_commands`, with membership enforced by AppHub's `shared` scope and MySQL triggers. See [APPHUB_FEASIBILITY §6](APPHUB_FEASIBILITY.md). Input limits above are chosen to fit the cloud columns, and the client enforces them before saving because a database refusal comes back as HTTP 500.
