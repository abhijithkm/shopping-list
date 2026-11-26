-- Shopping List database for AppHub (Phase 3: personal lists, items, categories and tags; Phase 4: shared lists, see the end).
-- Written, NOT applied to any server: see README.md (the ledger there says what has been applied where).
-- This file is the FRESH-install definition. A database that already holds the Phase 3 tables gets the Phase 4 changes from
-- migrate-phase4.sql (ALTER TABLE has no IF NOT EXISTS for columns in MySQL 8); the triggers are in triggers.sql.
-- Target: MySQL 8.0.16 or newer (CHECK constraints are only enforced from 8.0.16), InnoDB, strict mode (the default).
-- One database per AppHub app: `shoppinglistdev` (dev/test) first, then `shoppinglist` (production), from this SAME file.
--
-- HOW TO RUN: this file names no database on purpose. The operator creates the database and chooses it on the command line,
-- so the very same file runs on a throwaway dry-run database, on the dev database and on the production one:
--     mysql <database> < schema.sql
-- (README.md has the full runbook.) The file never contains CREATE DATABASE, DROP or USE.
--
-- Design rules (the Android client is written against exactly these):
--   * every exposed table has ONE single-column primary key `id` (an AppHub requirement). Data-table ids are client-generated
--     UUID strings (CHAR(36)) that the client sends as the primary key on create, so a retried create answers 409 and is
--     then fetched (idempotent create).
--   * `user_id` is the owner column AppHub scopes by (`own`): a person reads and writes only the rows whose user_id is theirs,
--     AppHub fills the column in itself on create and refuses it in a request body.
--   * `created_at_ms` is the client's clock (epoch milliseconds). `deleted_at_ms` is the app-level tombstone: NULL means
--     live, a value means deleted. AppHub's built-in soft_delete and trash are NOT used, and clients get no DELETE right
--     (they PATCH deleted_at_ms instead), so a tombstone stays in the normal list and reaches the other devices.
--   * `updated_at_ms` (shopping_lists and shopping_items only) is the CLIENT's clock of the last user edit, epoch milliseconds, written by
--     the client like any other column (DEFAULT 0 when a client does not send it). It is NOT the server-managed `updated_at` below, is
--     not filterable or sortable, and changing it counts as a change (so it moves `updated_at`).
--   * `updated_at DATETIME(3)` is managed by MySQL itself (DEFAULT and ON UPDATE CURRENT_TIMESTAMP(3)): never writable
--     through the API, it is the sync cursor (clients read newest first with sort=updated_at&order=desc). It moves only when a
--     column value actually changes, so a PATCH that changes nothing leaves it alone. No trigger maintains it. (Exception since
--     Phase 4: a trigger on shopping_items changes `revision` on every UPDATE, so even a PATCH that changes nothing moves it there.)
--     It follows the MySQL session time zone: the server must run in UTC (provision step: NOW() must equal UTC_TIMESTAMP()).
--   * DECIMAL comes back through the API as a STRING ("2.500"); TINYINT(1) as 0/1; BIGINT as a number; DATETIME(3) as the
--     string 'YYYY-MM-DD HH:MM:SS.mmm' (UTC).
--   * item.tag_ids is a JSON array string of tag ids (TEXT): there is no item_tags table in the cloud.
--   * categories.id is always a client-generated UUID. An override of a built-in category (for example 'dairy') is a row with
--     `builtin_key` set and a deterministic per-user UUID as its id, so the global primary key never makes two accounts collide.
--   * collation is explicit (the server default is utf8mb4_0900_ai_ci); the CHAR(36) key columns of parent and child tables
--     share it, which a foreign key requires.
--   * CHECK constraints sit only on columns that carry no foreign-key referential action (MySQL 8 forbids both on one column).
--     A violated CHECK is an HTTP 500 from AppHub (not 409/422), so the clients must never send such values: they validate
--     first (the same limits the local database already enforces).
--   * AppHub creates its own apphub_* tables when the app is registered (tenant.php add): do not create them here.
--   * to keep the file readable by tools (the fake server in fake-server/ reads it), every column, key and constraint is
--     written on ONE line, and a CHECK uses only comparisons, IN, IS NULL, AND, OR, CHAR_LENGTH and TRIM.
--   * CREATE TABLE IF NOT EXISTS makes a re-run harmless. It does NOT alter an existing table: a later schema change is a
--     reviewed ALTER run by the operator (and a change to this file), never an edit of this file alone.

-- AppHub's auth table: email, password (hash), role, status. `display_name` is the only profile column (register_fields).
CREATE TABLE IF NOT EXISTS users (
    id                INT UNSIGNED NOT NULL AUTO_INCREMENT,
    email             VARCHAR(254) NOT NULL,
    password          VARCHAR(255) NOT NULL,
    role              VARCHAR(20)  NOT NULL DEFAULT 'user',
    status            VARCHAR(20)  NOT NULL DEFAULT 'active',
    email_verified_at DATETIME     NULL,
    display_name      VARCHAR(60)  NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email),
    CONSTRAINT chk_users_role CHECK (role IN ('user', 'admin')),
    CONSTRAINT chk_users_status CHECK (status IN ('active', 'inactive', 'suspended'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A shopping list. `sort_key` is reserved for manual list order; `color` is a signed ARGB integer.
CREATE TABLE IF NOT EXISTS shopping_lists (
    id            CHAR(36)     NOT NULL,
    user_id       INT UNSIGNED NOT NULL,
    name          VARCHAR(80)  NOT NULL,
    description   VARCHAR(255) NULL,
    icon          VARCHAR(16)  NULL,
    color         INT          NULL,
    is_archived   TINYINT(1)   NOT NULL DEFAULT 0,
    is_pinned     TINYINT(1)   NOT NULL DEFAULT 0,
    sort_key      DOUBLE       NOT NULL DEFAULT 0,
    created_at_ms BIGINT       NOT NULL,
    updated_at_ms BIGINT       NOT NULL DEFAULT 0,
    deleted_at_ms BIGINT       NULL,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_shopping_lists_user_updated (user_id, updated_at),
    CONSTRAINT fk_shopping_lists_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_shopping_lists_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_shopping_lists_flags CHECK (is_archived IN (0, 1) AND is_pinned IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One item of a list. `category_key` is a built-in key or a custom category id; `quantity` is a DECIMAL (a string on the wire).
-- `list_id` is a foreign key, and since Phase 4 a trigger (triggers.sql) also checks that the caller owns the list or is an
-- editor of it. `user_id` is the creator (the owner column AppHub scopes by).
-- Phase 4 columns, all set by the triggers and never writable except base_revision:
--   `revision`      starts at 1 and goes up by 1 on every UPDATE (conflict detection).
--   `updated_by`    the user (AppHub id) who made the last change.
--   `base_revision` transient: the revision the client's edit was based on; the trigger compares it with `revision`
--                   (a mismatch is `sl:conflict`) and then stores NULL.
CREATE TABLE IF NOT EXISTS shopping_items (
    id            CHAR(36)      NOT NULL,
    list_id       CHAR(36)      NOT NULL,
    user_id       INT UNSIGNED  NOT NULL,
    name          VARCHAR(120)  NOT NULL,
    quantity      DECIMAL(12,3) NULL,
    unit          VARCHAR(20)   NULL,
    category_key  VARCHAR(40)   NULL,
    notes         VARCHAR(500)  NULL,
    is_bought     TINYINT(1)    NOT NULL DEFAULT 0,
    bought_at_ms  BIGINT        NULL,
    sort_order    DOUBLE        NOT NULL DEFAULT 0,
    tag_ids       TEXT          NULL,
    created_at_ms BIGINT        NOT NULL,
    updated_at_ms BIGINT        NOT NULL DEFAULT 0,
    deleted_at_ms BIGINT        NULL,
    revision      INT           NOT NULL DEFAULT 1,
    updated_by    INT UNSIGNED  NULL,
    base_revision INT           NULL,
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_shopping_items_user_updated (user_id, updated_at),
    KEY idx_shopping_items_list (list_id),
    CONSTRAINT fk_shopping_items_list FOREIGN KEY (list_id) REFERENCES shopping_lists (id) ON DELETE CASCADE,
    CONSTRAINT fk_shopping_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_shopping_items_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_shopping_items_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_shopping_items_bought CHECK (is_bought IN (0, 1)),
    CONSTRAINT chk_shopping_items_quantity CHECK (quantity IS NULL OR (quantity > 0 AND quantity <= 1000000))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Custom categories AND overrides of the built-ins. `id` is ALWAYS a client-generated UUID and never a built-in key, because the
-- primary key is one column shared by every account. A row with `builtin_key` set (for example 'dairy') is a rename and/or recolour
-- OVERRIDE of that built-in category: the client uses a deterministic per-user UUID as its id (so two accounts never collide and a
-- retried create is idempotent: the duplicate answers 409 and the row is fetched by id). `builtin_key` is NULL for a custom category.
-- An override may carry only a colour or only a name, so `name` and `color` are nullable, but a row must carry at least one of them.
-- (`builtin_key` is not unique per account: the client's deterministic id is what keeps one override per built-in per account.)
CREATE TABLE IF NOT EXISTS categories (
    id            CHAR(36)     NOT NULL,
    user_id       INT UNSIGNED NOT NULL,
    builtin_key   VARCHAR(40)  NULL,
    name          VARCHAR(40)  NULL,
    color         INT          NULL,
    created_at_ms BIGINT       NOT NULL,
    deleted_at_ms BIGINT       NULL,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_categories_user_updated (user_id, updated_at),
    KEY idx_categories_user_builtin (user_id, builtin_key),
    CONSTRAINT fk_categories_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_categories_name CHECK (name IS NULL OR CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_categories_content CHECK (name IS NOT NULL OR color IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tags. Uniqueness of a tag name among live tags is the client's job (a tombstoned tag may keep its name).
CREATE TABLE IF NOT EXISTS tags (
    id            CHAR(36)     NOT NULL,
    user_id       INT UNSIGNED NOT NULL,
    name          VARCHAR(30)  NOT NULL,
    created_at_ms BIGINT       NOT NULL,
    deleted_at_ms BIGINT       NULL,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_tags_user_updated (user_id, updated_at),
    CONSTRAINT fk_tags_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_tags_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================================================================
-- Phase 4: collaboration (collab-contract.md is the agreed surface; triggers.sql is the only writer of these tables' state).
--   * Every table below carries a server-set `created_at_ms` (the trigger's UTC clock in epoch milliseconds, NOT a client clock)
--     and the MySQL-managed `updated_at`. Columns a trigger fills have a DEFAULT or are NULL, so no insert depends on the order in
--     which MySQL checks NOT NULL and runs a BEFORE INSERT trigger.
--   * `user_id` is AppHub's owner column (forced to the caller on create); each table's comment says whose id that is.
--   * Plaintext secrets (an email in a lookup, a join code) are columns only so that the client can SEND them: the BEFORE INSERT
--     trigger sets them to NULL, so no stored row ever holds one. Config marks them hidden as well.
-- =====================================================================================================================

-- Operator-only secrets (the code pepper). NOT listed in the config, so every API call on it answers 404. seed.sql inserts a
-- placeholder that the triggers refuse (sl:server_not_ready) until the operator replaces it with a random value.
CREATE TABLE IF NOT EXISTS app_secrets (
    `name`     VARCHAR(40)  NOT NULL,
    `value`    VARCHAR(255) NOT NULL,
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The grant table. One row per (list, person). `user_id` is the LIST OWNER (so the owner reads every row), `member_id` the person.
-- role: owner (the list's creator, inserted by a trigger when the list is created), editor, removed. `removal` says how a removed
-- person left: removed (by the owner) or left (by themselves). A removed person keeps their row, so their app can notice it, and
-- re-inviting them re-activates the same row. Written only by triggers: clients have no write rule on it.
CREATE TABLE IF NOT EXISTS list_members (
    id            CHAR(36)     NOT NULL,
    list_id       CHAR(36)     NOT NULL,
    user_id       INT UNSIGNED NOT NULL,
    member_id     INT UNSIGNED NOT NULL,
    role          VARCHAR(10)  NOT NULL DEFAULT 'editor',
    removal       VARCHAR(10)  NULL,
    display_name  VARCHAR(60)  NULL,
    created_at_ms BIGINT       NOT NULL DEFAULT 0,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_list_members_list_member (list_id, member_id),
    KEY idx_list_members_member_list (member_id, list_id),
    KEY idx_list_members_user_updated (user_id, updated_at),
    KEY idx_list_members_member_updated (member_id, updated_at),
    CONSTRAINT fk_list_members_list FOREIGN KEY (list_id) REFERENCES shopping_lists (id) ON DELETE CASCADE,
    CONSTRAINT fk_list_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_list_members_member FOREIGN KEY (member_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_list_members_role CHECK (role IN ('owner', 'editor', 'removed')),
    CONSTRAINT chk_list_members_removal CHECK (removal IS NULL OR removal IN ('removed', 'left'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Method 1, step 1: "does this email belong to a registered person?". `user_id` is the asker. The client sends `email`; the trigger
-- nulls it and fills status, a masked display_hint and the hidden target_id. Both outcomes are stored, so both count for the throttle.
CREATE TABLE IF NOT EXISTS user_lookups (
    id            CHAR(36)     NOT NULL,
    user_id       INT UNSIGNED NOT NULL,
    email         VARCHAR(254) NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'not_found',
    display_hint  VARCHAR(20)  NULL,
    target_id     INT UNSIGNED NULL,
    created_at_ms BIGINT       NOT NULL DEFAULT 0,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_user_lookups_user_created (user_id, created_at_ms),
    CONSTRAINT fk_user_lookups_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_user_lookups_status CHECK (status IN ('found', 'not_found'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Method 1, step 2: an invitation. `user_id` is the inviter (the list owner), `invitee_id` the invited person (a members column, so
-- the invitee reads it without any access to the list). Status pending / accepted / rejected / revoked; "expired" is derived from
-- expires_at and never stored. `lookup_id` is checked by the trigger and deliberately has no foreign key (a lookup row is throwaway).
-- `display_hint` is the masked hint of the lookup, so the inviter can tell invited people apart without ever seeing an email.
CREATE TABLE IF NOT EXISTS list_invitations (
    id            CHAR(36)     NOT NULL,
    user_id       INT UNSIGNED NOT NULL,
    list_id       CHAR(36)     NOT NULL,
    lookup_id     CHAR(36)     NOT NULL,
    invitee_id    INT UNSIGNED NULL,
    role          VARCHAR(10)  NOT NULL DEFAULT 'editor',
    status        VARCHAR(10)  NOT NULL DEFAULT 'pending',
    list_name     VARCHAR(80)  NULL,
    inviter_name  VARCHAR(60)  NULL,
    display_hint  VARCHAR(40)  NULL,
    expires_at    DATETIME(3)  NULL,
    created_at_ms BIGINT       NOT NULL DEFAULT 0,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_list_invitations_list_status (list_id, status),
    KEY idx_list_invitations_invitee_status (invitee_id, status),
    KEY idx_list_invitations_user_created (user_id, created_at_ms),
    KEY idx_list_invitations_user_updated (user_id, updated_at),
    KEY idx_list_invitations_invitee_updated (invitee_id, updated_at),
    CONSTRAINT fk_list_invitations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_list_invitations_list FOREIGN KEY (list_id) REFERENCES shopping_lists (id) ON DELETE CASCADE,
    CONSTRAINT fk_list_invitations_invitee FOREIGN KEY (invitee_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_list_invitations_role CHECK (role IN ('editor')),
    CONSTRAINT chk_list_invitations_status CHECK (status IN ('pending', 'accepted', 'rejected', 'revoked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Method 2, owner side: a join code. `user_id` is the list owner. The client sends `code`; the trigger stores only
-- code_hash = SHA2(CONCAT(pepper, code), 256) and nulls `code`. There is deliberately NO unique key on code_hash: a duplicate-key
-- answer would tell a probing owner that a guessed code exists. Status active / revoked / used; "expired" is derived; a code that a
-- newer code of the same list replaced is "superseded" by derivation too (MySQL refuses a trigger that updates its own table, so
-- the older row keeps `active`; see README.md, limitations).
CREATE TABLE IF NOT EXISTS join_codes (
    id            CHAR(36)     NOT NULL,
    user_id       INT UNSIGNED NOT NULL,
    list_id       CHAR(36)     NOT NULL,
    code          VARCHAR(64)  NULL,
    code_hash     CHAR(64)     NULL,
    status        VARCHAR(12)  NOT NULL DEFAULT 'active',
    expires_at    DATETIME(3)  NULL,
    created_at_ms BIGINT       NOT NULL DEFAULT 0,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_join_codes_hash (code_hash),
    KEY idx_join_codes_list_created (list_id, created_at_ms),
    KEY idx_join_codes_user_updated (user_id, updated_at),
    CONSTRAINT fk_join_codes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_join_codes_list FOREIGN KEY (list_id) REFERENCES shopping_lists (id) ON DELETE CASCADE,
    CONSTRAINT chk_join_codes_status CHECK (status IN ('active', 'superseded', 'revoked', 'used'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Method 2, joiner preview: "what list does this code open?". `user_id` is the joiner. `invalid` is stored too (it counts for the
-- throttle) and is the one outcome for unknown, expired, used, revoked and superseded codes. `code_id` is hidden.
CREATE TABLE IF NOT EXISTS code_lookups (
    id            CHAR(36)     NOT NULL,
    user_id       INT UNSIGNED NOT NULL,
    code          VARCHAR(64)  NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'invalid',
    list_name     VARCHAR(80)  NULL,
    inviter_name  VARCHAR(60)  NULL,
    code_id       CHAR(36)     NULL,
    created_at_ms BIGINT       NOT NULL DEFAULT 0,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_code_lookups_user_status_created (user_id, status, created_at_ms),
    CONSTRAINT fk_code_lookups_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_code_lookups_status CHECK (status IN ('found', 'invalid'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Method 2, step 2: a request to join. `user_id` is the requester, `owner_id` the list owner (a members column, so the owner reads it).
-- `lookup_id` has no foreign key for the same reason as in list_invitations.
CREATE TABLE IF NOT EXISTS join_requests (
    id             CHAR(36)     NOT NULL,
    user_id        INT UNSIGNED NOT NULL,
    lookup_id      CHAR(36)     NOT NULL,
    owner_id       INT UNSIGNED NULL,
    list_id        CHAR(36)     NULL,
    list_name      VARCHAR(80)  NULL,
    requester_name VARCHAR(60)  NULL,
    status         VARCHAR(10)  NOT NULL DEFAULT 'pending',
    created_at_ms  BIGINT       NOT NULL DEFAULT 0,
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_join_requests_user_list_status (user_id, list_id, status),
    KEY idx_join_requests_user_created (user_id, created_at_ms),
    KEY idx_join_requests_owner_updated (owner_id, updated_at),
    KEY idx_join_requests_list_status (list_id, status),
    CONSTRAINT fk_join_requests_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_join_requests_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_join_requests_list FOREIGN KEY (list_id) REFERENCES shopping_lists (id) ON DELETE CASCADE,
    CONSTRAINT chk_join_requests_status CHECK (status IN ('pending', 'approved', 'rejected', 'cancelled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Insert-only commands. `user_id` is the ACTOR (forced to the caller by AppHub). The whole command is validated and executed in the
-- BEFORE INSERT trigger; a refusal rolls the insert back, so a refused command leaves no row. `command` has no CHECK on purpose: the
-- trigger answers an unknown command with a readable token, a CHECK would answer an opaque 500.
CREATE TABLE IF NOT EXISTS list_commands (
    id            CHAR(36)     NOT NULL,
    user_id       INT UNSIGNED NOT NULL,
    command       VARCHAR(20)  NOT NULL,
    target_id     CHAR(36)     NOT NULL,
    created_at_ms BIGINT       NOT NULL DEFAULT 0,
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_list_commands_user_updated (user_id, updated_at),
    CONSTRAINT fk_list_commands_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
