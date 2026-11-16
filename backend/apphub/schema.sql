-- Shopping List database for AppHub (Phase 3: personal lists, items, categories and tags, one owner per row).
-- Written, NOT applied to any server: see README.md (the ledger there says what has been applied where).
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
--     column value actually changes, so a PATCH that changes nothing leaves it alone. No triggers are needed or used.
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
    CONSTRAINT chk_users_status CHECK (status IN ('active', 'inactive'))
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
-- `list_id` is a foreign key only: AppHub cannot check that the list belongs to the caller (README.md, known limitations).
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
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_shopping_items_user_updated (user_id, updated_at),
    KEY idx_shopping_items_list (list_id),
    CONSTRAINT fk_shopping_items_list FOREIGN KEY (list_id) REFERENCES shopping_lists (id) ON DELETE CASCADE,
    CONSTRAINT fk_shopping_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
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
