# Collaboration contract (Phase 4)

Status: **written, not applied to any server**. This is the single agreed surface between the server artifacts
(`schema-collab.sql`, `triggers.sql`, the config, the fake server) and the Android client. The design and its reasons are in
[docs/APPHUB_FEASIBILITY.md](../../docs/APPHUB_FEASIBILITY.md) sections 5, 6 and 10; this file fixes the exact names, columns,
commands and error tokens. A change here is a change to both sides.

Principle: **every authorization decision is made on the server** (AppHub row scopes plus MySQL triggers). The Android
client only reflects what the server answers. Memberships, invitation status and code state change **only inside triggers**,
in answer to rows the client inserts. The client holds no write rule on `list_members`.

## 1. Error vocabulary

A trigger refuses with `SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:<token>'` (needs `actor_in_sql: true`; AppHub then
answers HTTP 422 and puts the text in `error.message`). The text is **exactly one of these tokens and nothing else** (no ids,
no values, no user input is ever echoed):

| Token | Meaning |
|---|---|
| `sl:forbidden` | The caller may not do this (not the owner, not the invitee, `list_id` move, forged field) |
| `sl:not_a_member` | The caller is not an active member (owner or editor) of the list |
| `sl:not_found` | The target row does not exist or is not visible to the caller. A list or command target that belongs to someone else also answers this, never `sl:forbidden`, so ids cannot be probed (security review 2026-11-20) |
| `sl:not_pending` | The invitation / request was already handled, revoked or cancelled |
| `sl:expired` | The invitation, code or lookup is past its expiry |
| `sl:invalid_code` | Malformed code (fails the format check) |
| `sl:already_member` | The target is already an active member |
| `sl:already_pending` | A live invitation / request already exists for this target and list |
| `sl:limit_reached` | Member or pending-invitation limit of the list reached (10 editors, 20 pending invitations) |
| `sl:too_many_attempts` | Throttle (section 6) |
| `sl:conflict` | `base_revision` does not match the stored `revision` |
| `sl:server_not_ready` | The pepper row is missing (operator has not seeded `app_secrets`) |

## 2. Changes to the Phase 3 tables

### `shopping_lists`
- Read (`list`, `read`) scope `shared`: the owner, or an active member of the list (grant table `list_members`, `member_id` =
  caller, role in `owner`/`editor`). `create` and `update` stay `own`; no delete. An editor can therefore **never** change the
  list row. Rows carry the owner column `user_id` (the owner's AppHub id).
- `AFTER INSERT` trigger inserts the owner's own `list_members` row (role `owner`).

### `shopping_items`
- Read and update scope `shared` (grant on `list_id`); `create` stays `own`; no delete (tombstone `deleted_at_ms`).
- New columns: `revision INT NOT NULL DEFAULT 1` (trigger-maintained, +1 on every UPDATE, never writable),
  `updated_by INT UNSIGNED NULL` (trigger-maintained from `@apphub_user_id`, never writable) and `base_revision INT NULL`
  (writable, transient: the trigger compares it and then stores NULL). `user_id` stays the creator.
- `BEFORE INSERT`: `NEW.list_id` must be a list the caller (`NEW.user_id`) owns or is an active editor of, else
  `sl:not_a_member`.
- `BEFORE UPDATE`: `list_id` and `user_id` may not change (`sl:forbidden`); the caller must still be an active member of the
  row's list (`sl:not_a_member`); `base_revision IS NOT NULL AND base_revision <> OLD.revision` is `sl:conflict`. `revision`
  and `updated_by` are set by the trigger.

### `categories`, `tags`
Unchanged: personal to their owner. **v1 limitation (documented): there is no list-scoped taxonomy.** In a shared list an item's
`category_key` that is a built-in key is understood by everyone; a custom category or tag of another member is shown by the
client as "Other" / not at all, and is never altered.

## 3. New tables

All ids are client UUIDs (CHAR(36)) unless said otherwise; `user_id` is the AppHub owner column (forced to the caller on create).
Every table has `updated_at DATETIME(3)` managed by MySQL like the Phase 3 tables.

### `list_members` (read only for clients; written only by triggers)
`id` CHAR(36) (UUID() by the trigger), `list_id`, `user_id` (= the list owner, so the owner reads every row), `member_id`
INT UNSIGNED, `role` (`owner` | `editor` | `removed`), `removal` NULL | `removed` | `left`, `display_name` VARCHAR(60)
(snapshot of the member's `users.display_name`), `created_at_ms` BIGINT (server time, ms), `updated_at`.
`UNIQUE (list_id, member_id)`: re-inviting a removed person re-activates the same row. Visible to: the owner (all rows of
their lists), the member themself (their own row, **including after removal**, so the app can notice it), and, if AppHub's
`shared` scope can express it, every active member of the list (a grant on `list_id` against this same table). If it cannot,
the fallback is owner + own row only and the README says so; the client treats "the rows I can see" as the truth.

### `user_lookups` (Method 1, step 1)
Client writes `id`, `email` (hidden + writable; the trigger nulls it after use). The trigger returns `status`
(`found` | `not_found`), `display_hint` (masked, e.g. `A. K.`), hidden `target_id`, and `created_at_ms`. Requirements: caller
owns at least one live list, else `sl:forbidden`; exact case-insensitive match on `users.email` (no LIKE, no partial);
the caller is never "found"; `not_found` and `found` are both persisted (they count towards the throttle). The email is never
returned.

### `list_invitations` (Method 1, step 2)
Client writes `id`, `list_id`, `lookup_id`. The trigger checks: caller owns the (live) list; the lookup is the caller's,
`found`, younger than 10 minutes; target not an active member (`sl:already_member`); no live pending invitation for
(list, target) (`sl:already_pending`); limits. It fills `user_id` (inviter, forced), `invitee_id`, `role` (`editor`),
`status` (`pending`), `list_name`, `inviter_name`, `display_hint` (VARCHAR(40) NULL, copied from the caller's
`user_lookups.display_hint`, the masked hint, so the inviter's "pending invitations" list can tell invited people apart without
an email; not writable), `expires_at` (UTC now + 7 days), `created_at_ms`. Statuses: `pending`,
`accepted`, `rejected`, `revoked`; "expired" is derived from `expires_at`, never stored. Visible to the inviter (owner column)
and to the invitee (members column `invitee_id`), so the invitee never needs access to the list.

### `join_codes` (Method 2, owner side)
Client writes `id`, `list_id`, `code` (hidden + writable; **the trigger nulls it**). Format `^[A-HJ-NP-Z2-9]{8}$` checked
case-sensitively **before** any use, else `sl:invalid_code`. The trigger stores `code_hash = SHA2(CONCAT(pepper, code), 256)`
(hidden, not writable), `expires_at` = UTC now + 24 h, `status` = `active`, marks the list's older active codes `superseded`,
and refuses a non-owner with `sl:forbidden`. Statuses: `active`, `superseded`, `revoked`, `used`; "expired" is derived. The
plaintext is never stored, never returned, never in any column after the INSERT statement ends.

### `code_lookups` (Method 2, joiner preview)
Client writes `id`, `code` (hidden + writable, nulled). The trigger validates the format (`sl:invalid_code`), applies the
throttle, hashes, and matches an `active`, unexpired code. It writes `status` (`found` | `invalid`; `invalid` is persisted so
it counts), `list_name`, `inviter_name` (owner's display name) and hidden `code_id`. The same outcome (`invalid`) is returned
for unknown, expired, used, revoked and superseded codes, so a probe learns nothing.

### `join_requests` (Method 2, step 2)
Client writes `id`, `lookup_id`. The trigger checks: lookup is the caller's, `found`, younger than 10 minutes, its code still
`active` and unexpired; caller is not a member (`sl:already_member`) and has no pending request for the list
(`sl:already_pending`). It then **consumes the code** (`status` = `used`: single use), and fills `user_id` (requester, forced),
`owner_id` (the list owner; members column, so the owner reads it), `list_id`, `list_name`, `requester_name`, `status`
(`pending`), `created_at_ms`. Statuses: `pending`, `approved`, `rejected`, `cancelled`. Visible to the requester and the owner.

### `list_commands` (insert-only)
Client writes `id`, `command`, `target_id` (CHAR(36)). `user_id` is forced to the caller and is the **actor**. The row is
validated and executed in the `BEFORE INSERT` trigger; a refusal rolls the statement back and answers 422 with a token.
Readable by the actor only (an audit trail); never updatable.

| `command` | `target_id` is | Allowed actor | Effect |
|---|---|---|---|
| `accept_invite` | invitation id | the invitee | invitation not pending/expired is refused; else upsert the `editor` member row, invitation `accepted` |
| `reject_invite` | invitation id | the invitee | invitation `rejected` |
| `revoke_invite` | invitation id | the inviter (list owner) | pending invitation `revoked` |
| `approve_join` | join request id | the list owner | request `approved`, upsert `editor` member row |
| `reject_join` | join request id | the list owner | request `rejected` |
| `cancel_request` | join request id | the requester | pending request `cancelled` |
| `revoke_code` | join code id | the list owner | code `revoked` |
| `remove_member` | `list_members.id` | the list owner (never the owner row) | member `role = removed`, `removal = removed` |
| `leave_list` | `list_members.id` of the actor's own row | the member (never the owner) | `role = removed`, `removal = left` |

Command ids are the idempotency key (a retried insert is HTTP 409). Rows are never reused as commands.

### `app_secrets` (operator only)
`name` VARCHAR(40) PK, `value` VARCHAR(255). **Not listed in the config** (every API call answers 404). `seed.sql` carries a
placeholder; the real pepper (`code_pepper`) is generated by the operator and never committed or sent in chat.

## 4. Visibility summary (what a client may read)

| Table | Who reads | Client write |
|---|---|---|
| `shopping_lists`, `shopping_items` | owner, active members | owner (lists), owner + editors (items) |
| `list_members` | owner; the member; (preferably) every active member | none |
| `user_lookups`, `join_codes`, `code_lookups`, `list_commands` | the caller | insert only |
| `list_invitations` | inviter, invitee | insert (inviter) |
| `join_requests` | requester, list owner | insert (requester) |

Non-members get **404** for rows they may not see; a member who loses access gets 404 on the list's rows on their next call.

## 5. Revision rule

`revision` starts at 1 and increases by 1 on every UPDATE of an item. A client PATCH may send `base_revision`; if it differs
from the stored `revision` the answer is `422 sl:conflict` and nothing changes. On `sl:conflict` the client GETs the row,
three-way merges against its base snapshot, and PATCHes again with the new `base_revision`.

## 6. Throttles (per caller, persisted rows, counted inside the triggers; a SIGNAL rolls back its own statement, so refused
attempts are counted from the rows persisted by *earlier* statements)

- `user_lookups`: 10 per hour.
- `code_lookups` with `status = 'invalid'`: 5 per 15 minutes.
- `list_invitations`: 30 per hour.
- `join_requests`: 10 per hour.
Beyond a limit the next attempt is `sl:too_many_attempts`. (There is no per-endpoint HTTP 429 in AppHub; the generic
120/min/IP limit still applies.)

## 7. Never-log rule

A code appears only in the request body of `join_codes` / `code_lookups` inserts. It is never in a URL, a filter, a primary key,
an error message, a log line or a column value after the statement ends. Verification steps for the operator's logs (MySQL
general log, binlog, slow log) are in `provision.md`.
