-- Shopping List: the Phase 4 (collaboration) rules AppHub cannot express as permissions. collab-contract.md is the agreed surface;
-- this file implements its sections 1 to 6. WRITTEN, NOT APPLIED to any server: README.md has the ledger, provision.md the runbook.
--
-- HOW TO RUN (operator only, as the MySQL admin, on the database that already holds schema.sql and migrate-phase4.sql):
--     sudo mysql <database> < triggers.sql
-- The AppHub panel cannot run this (it refuses CREATE TRIGGER and DELIMITER, apphub src/Registry/SchemaAdmin.php). The file names no
-- database on purpose. Every trigger is DROPped IF EXISTS before it is created, so a re-run is harmless, but there is a gap of a
-- few milliseconds per trigger in which the rule does not exist: run it when nobody is using the app. AppHub neither lists, backs up
-- nor audits triggers (a dropped table drops its triggers too), so THIS FILE is their only source; mysqldump needs --triggers.
-- Target: MySQL 8.0.16 or newer (REGEXP_LIKE needs 8.0.4, CHECK enforcement 8.0.16).
--
-- The rules every trigger below follows (provision.md says how each is verified):
--   * Needs `actor_in_sql: true` in the config: then a SIGNAL SQLSTATE '45000' answers HTTP 422 with the message text
--     (apphub src/Database.php ruleMessage, src/Kernel.php). The text is ALWAYS one of the contract's `sl:` tokens and nothing else:
--     no id, no value and no user input is ever put into MESSAGE_TEXT. Every SIGNAL below uses a literal.
--   * No dynamic SQL (triggers cannot use it anyway). Values are only compared and stored, never concatenated into a statement.
--   * A secret (a join code) is format-checked BEFORE it is used for anything else, and nulled before the row is stored.
--   * Time is UTC_TIMESTAMP(3) (expiries) or its epoch milliseconds (created_at_ms, throttle windows), never the session zone.
--   * Anything decided by a status takes `SELECT ... INTO ... FOR UPDATE` on the row being decided and checks the status AFTER the
--     lock, so two accepts, two approves or two uses of one code cannot both win. A command that changes a list's membership also
--     locks the list row first, so the member and invitation limits cannot be passed by two parallel requests.
--     Lock order is always: the row being decided, then the list row.
--   * EVERY read that a limit, a throttle or an "already a member / already pending" rule depends on is a LOCKING read
--     (`SELECT COUNT(*) INTO ... FOR UPDATE`, `FOR SHARE` for membership checks of item writes). A plain read would use the snapshot
--     of the first read of the transaction (the duplicate-id check at the top of each trigger), which cannot see a request that
--     committed while this one waited for a lock: under REPEATABLE READ two parallel requests would both count 9 editors. The
--     throttles first lock the CALLER's users row, so one account's parallel attempts run one after the other.
--   * A command trigger that sets @sl_transfer declares an EXIT HANDLER that clears it again and re-raises, so a failed statement
--     (lock wait timeout, deadlock) can never leave the bypass set on a connection.
--   * A refusal that would tell a stranger whether an id exists answers sl:not_found, not sl:forbidden: a row that is not the
--     caller's to see is "not found" (collab-contract.md section 1).
--   * Authorization comparisons use the NULL-safe <=> operator, so a NULL id can never satisfy or skip a check.
--   * Local variables are all named v_*, so they can never shadow a column. String variables carry the table collation explicitly
--     (a variable gets the DATABASE's default collation otherwise, and mixing it with a column raises error 1267).
--   * A BEFORE INSERT trigger runs BEFORE MySQL checks the primary key, so a retried insert (same client UUID) would be judged by the
--     rules again and answer a confusing 422 (the code is already used, the command already done). Every insert-only table therefore
--     starts with a duplicate-id check that raises SQLSTATE 23000 / error 1062, which AppHub answers as 409 exactly like a real
--     duplicate key: "retry with the same UUID, then GET it" keeps working.
--   * MySQL error 1442: a trigger may READ the table it is on, but may not MODIFY it (join_codes cannot mark its own older rows).
--
-- Session variables the triggers read:
--   @apphub_user_id  set by AppHub for every signed-in request (NULL for sign-in, register, the panel, the CLI: "system or operator").
--   @sl_transfer     set to 1 and back to NULL only by trg_list_commands_bi around the re-owning of a removed member's items.
--   @sl_operator     an operator may SET it to 1 in their own mysql session to update shopping_items by hand (see trg_shopping_items_bu).
-- A client cannot set any of these: AppHub opens a new connection per request and runs only its own prepared statements. (Not
-- verified on the real server; if AppHub ever pools connections, the exit handler on @sl_transfer below is what keeps it safe.)
--
-- Limits of what a trigger can do: foreign-key actions (ON DELETE CASCADE / SET NULL) fire no trigger; an operator with admin
-- rights can still change anything; display_name copies in list_members, list_invitations, join_requests are snapshots and are not
-- refreshed when a person renames themselves.

DELIMITER $$

-- ---------------------------------------------------------------------------------------------------------------------------
-- shopping_lists
-- ---------------------------------------------------------------------------------------------------------------------------

-- Contract section 2 (shopping_lists): the creator gets their own list_members row, role owner. The grant table is what AppHub's
-- `shared` scope reads, and the other triggers look the owner up there. The owner also matches through AppHub's owner column,
-- so a missing row (a list that predates migrate-phase4.sql's backfill) never locks the owner out.
DROP TRIGGER IF EXISTS trg_shopping_lists_ai$$
CREATE TRIGGER trg_shopping_lists_ai AFTER INSERT ON shopping_lists FOR EACH ROW
BEGIN
    INSERT INTO list_members (id, list_id, user_id, member_id, role, removal, display_name, created_at_ms)
    VALUES (
        UUID(), NEW.id, NEW.user_id, NEW.user_id, 'owner', NULL,
        (SELECT u.display_name FROM users u WHERE u.id = NEW.user_id),
        TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(3)) DIV 1000
    );
END$$

-- ---------------------------------------------------------------------------------------------------------------------------
-- shopping_items
-- ---------------------------------------------------------------------------------------------------------------------------

-- Contract section 2 (shopping_items, BEFORE INSERT): AppHub's `create` scope is `own` (never shared), so any signed-in user could
-- insert an item with ANY list_id. This refuses unless NEW.user_id (forced to the caller by AppHub) is the list's owner or an
-- active editor. The check runs before the foreign key, so an unknown list answers the same sl:not_a_member (not a 409).
-- Sets revision 1 and updated_by; base_revision is a transient control column and is never stored.
DROP TRIGGER IF EXISTS trg_shopping_items_bi$$
CREATE TRIGGER trg_shopping_items_bi BEFORE INSERT ON shopping_items FOR EACH ROW
BEGIN
    DECLARE v_ok INT DEFAULT 0;

    -- FOR SHARE: waits for a concurrent remove_member / leave_list (which holds the row exclusively) and then sees role removed.
    SELECT COUNT(*) INTO v_ok FROM list_members m WHERE m.list_id = NEW.list_id AND m.member_id = NEW.user_id AND m.role IN ('owner', 'editor') FOR SHARE;
    IF v_ok = 0 THEN
        SELECT COUNT(*) INTO v_ok FROM shopping_lists l WHERE l.id = NEW.list_id AND l.user_id = NEW.user_id;
    END IF;
    IF v_ok = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_a_member';
    END IF;
    -- An editor may not add to a list its owner has deleted (the owner may: their own unsent items still have to land).
    SELECT COUNT(*) INTO v_ok FROM shopping_lists l WHERE l.id = NEW.list_id AND l.deleted_at_ms IS NOT NULL AND l.user_id <> NEW.user_id;
    IF v_ok > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
    END IF;
    SET NEW.revision = 1;
    SET NEW.updated_by = NEW.user_id;
    SET NEW.base_revision = NULL;
END$$

-- Contract section 2 (shopping_items, BEFORE UPDATE) and section 5 (revision rule):
--   * list_id and user_id may not change (sl:forbidden). list_id is AppHub's shared_via.on column: moving an item would re-point
--     which grants apply to it. user_id is the creator (only the member-removal re-owning below may change it).
--   * the caller (@apphub_user_id) must still be an active member of the item's list (sl:not_a_member). AppHub's own scope lets a
--     creator through even after removal (its owner term is always OR-ed in), so this is what stops a removed editor writing.
--   * base_revision, when sent, must equal the stored revision, else sl:conflict and nothing changes. When it is not sent
--     (NULL) no check is made: that is a "last writer wins" PATCH, the client's choice.
--   * revision goes up by 1 on EVERY update (also one that changes nothing else), updated_by is the caller, base_revision is
--     stored as NULL.
-- Fail closed: a request with no actor (@apphub_user_id NULL) is refused, because with actor_in_sql switched off every request
-- would look like that and every check above would silently vanish. An operator fixing a row by hand runs
-- `SET @sl_operator = 1;` first in their own session (then only the list_id/user_id rule and the revision bump still apply).
-- @sl_transfer is the one bypass the triggers themselves use (re-owning items when a member leaves); it changes nothing else.
DROP TRIGGER IF EXISTS trg_shopping_items_bu$$
CREATE TRIGGER trg_shopping_items_bu BEFORE UPDATE ON shopping_items FOR EACH ROW
BEGIN
    DECLARE v_ok INT DEFAULT 0;

    IF @sl_transfer IS NULL THEN
        IF @apphub_user_id IS NULL AND @sl_operator IS NULL THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:forbidden';
        END IF;
        IF NOT (NEW.list_id <=> OLD.list_id) OR NOT (NEW.user_id <=> OLD.user_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:forbidden';
        END IF;
        IF @apphub_user_id IS NOT NULL THEN
            SELECT COUNT(*) INTO v_ok FROM list_members m WHERE m.list_id = OLD.list_id AND m.member_id = @apphub_user_id AND m.role IN ('owner', 'editor') FOR SHARE;
            IF v_ok = 0 THEN
                SELECT COUNT(*) INTO v_ok FROM shopping_lists l WHERE l.id = OLD.list_id AND l.user_id = @apphub_user_id;
            END IF;
            IF v_ok = 0 THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_a_member';
            END IF;
            SELECT COUNT(*) INTO v_ok FROM shopping_lists l WHERE l.id = OLD.list_id AND l.deleted_at_ms IS NOT NULL AND l.user_id <> @apphub_user_id;
            IF v_ok > 0 THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
            END IF;
            IF NEW.base_revision IS NOT NULL AND NEW.base_revision <> OLD.revision THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:conflict';
            END IF;
        END IF;
        SET NEW.revision = OLD.revision + 1;
        SET NEW.updated_by = @apphub_user_id;
        SET NEW.base_revision = NULL;
    END IF;
END$$

-- ---------------------------------------------------------------------------------------------------------------------------
-- user_lookups (Method 1, step 1)
-- ---------------------------------------------------------------------------------------------------------------------------

-- Contract section 3 (user_lookups): the caller must own at least one live list (sl:forbidden); at most 10 lookups per hour
-- (sl:too_many_attempts; found and not_found are both stored, so both count, and the refused attempt is rolled back, so the
-- count comes from the rows of earlier statements); EXACT email match, case-insensitive (the column's collation is
-- case-insensitive, and an extra binary comparison of the lower-cased values makes it exact: no LIKE, no partial match, no
-- accent folding); the caller is never "found"; an inactive account is not found either. The email is NULLed and never returned.
-- display_hint is the masked name ("A. K." from "Abhijith KM", "A." from a one-word name, "?" when there is no name).
DROP TRIGGER IF EXISTS trg_user_lookups_bi$$
CREATE TRIGGER trg_user_lookups_bi BEFORE INSERT ON user_lookups FOR EACH ROW
BEGIN
    DECLARE v_now BIGINT;
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_target INT UNSIGNED DEFAULT NULL;
    DECLARE v_name VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_lock INT UNSIGNED DEFAULT NULL;

    IF EXISTS (SELECT 1 FROM user_lookups x WHERE x.id = NEW.id) THEN
        SIGNAL SQLSTATE '23000' SET MESSAGE_TEXT = 'sl:duplicate', MYSQL_ERRNO = 1062;
    END IF;
    SET v_now = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(3)) DIV 1000;

    IF NOT EXISTS (SELECT 1 FROM shopping_lists l WHERE l.user_id = NEW.user_id AND l.deleted_at_ms IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:forbidden';
    END IF;

    -- Serialise this caller's attempts (their users row), then count with a locking read: parallel requests cannot all see 0.
    SELECT u.id INTO v_lock FROM users u WHERE u.id = NEW.user_id FOR UPDATE;
    SELECT COUNT(*) INTO v_count FROM user_lookups x WHERE x.user_id = NEW.user_id AND x.created_at_ms > v_now - 3600000 FOR UPDATE;
    IF v_count >= 10 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:too_many_attempts';
    END IF;

    SET v_email = TRIM(NEW.email);
    IF v_email IS NOT NULL AND CHAR_LENGTH(v_email) > 0 THEN
        SELECT u.id, u.display_name INTO v_target, v_name
          FROM users u
         WHERE u.email = v_email
           AND LOWER(u.email) COLLATE utf8mb4_bin = LOWER(v_email) COLLATE utf8mb4_bin
           AND u.id <> NEW.user_id
           AND u.status = 'active'
         LIMIT 1;
    END IF;

    IF v_target IS NULL THEN
        SET NEW.status = 'not_found';
        SET NEW.display_hint = NULL;
        SET NEW.target_id = NULL;
    ELSE
        SET NEW.status = 'found';
        SET NEW.display_hint = CASE
            WHEN v_name IS NULL OR CHAR_LENGTH(TRIM(v_name)) = 0 THEN '?'
            WHEN LOCATE(' ', TRIM(v_name)) = 0 THEN CONCAT(UPPER(LEFT(TRIM(v_name), 1)), '.')
            ELSE CONCAT(UPPER(LEFT(TRIM(v_name), 1)), '. ', UPPER(LEFT(SUBSTRING_INDEX(TRIM(v_name), ' ', -1), 1)), '.')
        END;
        SET NEW.target_id = v_target;
    END IF;
    SET NEW.created_at_ms = v_now;
    SET NEW.email = NULL;
END$$

-- ---------------------------------------------------------------------------------------------------------------------------
-- list_invitations (Method 1, step 2)
-- ---------------------------------------------------------------------------------------------------------------------------

-- Contract section 3 (list_invitations): the caller owns the live list (sl:forbidden for someone else's, sl:not_found for an unknown
-- or deleted one); the lookup is the caller's, status found (sl:not_found) and younger than 10 minutes (sl:expired); at most 30
-- invitations per hour (sl:too_many_attempts); the target is not an active member (sl:already_member); no live pending invitation
-- for (list, target) (sl:already_pending); fewer than 20 live pending invitations and fewer than 10 editors (sl:limit_reached).
-- It fills invitee_id, role editor, status pending, list_name, inviter_name, display_hint (copied from the caller's lookup),
-- expires_at (now + 7 days UTC) and created_at_ms. The list row is locked first, so two parallel invitations cannot pass a limit.
DROP TRIGGER IF EXISTS trg_list_invitations_bi$$
CREATE TRIGGER trg_list_invitations_bi BEFORE INSERT ON list_invitations FOR EACH ROW
BEGIN
    DECLARE v_now BIGINT;
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_lk_status VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_lk_target INT UNSIGNED DEFAULT NULL;
    DECLARE v_lk_created BIGINT DEFAULT NULL;
    DECLARE v_lk_hint VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_list_owner INT UNSIGNED DEFAULT NULL;
    DECLARE v_list_name VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_list_deleted BIGINT DEFAULT NULL;
    DECLARE v_inviter_name VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_lock INT UNSIGNED DEFAULT NULL;

    IF EXISTS (SELECT 1 FROM list_invitations x WHERE x.id = NEW.id) THEN
        SIGNAL SQLSTATE '23000' SET MESSAGE_TEXT = 'sl:duplicate', MYSQL_ERRNO = 1062;
    END IF;
    SET v_now = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(3)) DIV 1000;

    -- Authorise with a plain read FIRST (shopping_lists.user_id never changes), so a stranger who knows a list id can neither take
    -- the list lock nor tell "not yours" from "unknown": both answer sl:not_found.
    SELECT l.user_id, l.name INTO v_list_owner, v_list_name FROM shopping_lists l WHERE l.id = NEW.list_id;
    IF v_list_owner IS NULL OR NOT (v_list_owner <=> NEW.user_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
    END IF;
    SELECT l.deleted_at_ms INTO v_list_deleted FROM shopping_lists l WHERE l.id = NEW.list_id FOR UPDATE;
    IF v_list_deleted IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
    END IF;

    SELECT k.status, k.target_id, k.created_at_ms, k.display_hint INTO v_lk_status, v_lk_target, v_lk_created, v_lk_hint
      FROM user_lookups k WHERE k.id = NEW.lookup_id AND k.user_id = NEW.user_id;
    IF v_lk_status IS NULL OR v_lk_status <> 'found' OR v_lk_target IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
    END IF;
    IF v_lk_created < v_now - 600000 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:expired';
    END IF;

    -- Lock order: list row (above), then the caller's users row, then the counted rows. All reads below are locking reads.
    SELECT u.id INTO v_lock FROM users u WHERE u.id = NEW.user_id FOR UPDATE;
    SELECT COUNT(*) INTO v_count FROM list_invitations x WHERE x.user_id = NEW.user_id AND x.created_at_ms > v_now - 3600000 FOR UPDATE;
    IF v_count >= 30 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:too_many_attempts';
    END IF;

    SELECT COUNT(*) INTO v_count FROM list_members m WHERE m.list_id = NEW.list_id AND m.member_id = v_lk_target AND m.role IN ('owner', 'editor') FOR UPDATE;
    IF v_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:already_member';
    END IF;
    SELECT COUNT(*) INTO v_count FROM list_invitations i WHERE i.list_id = NEW.list_id AND i.invitee_id = v_lk_target AND i.status = 'pending' AND i.expires_at > UTC_TIMESTAMP(3) FOR UPDATE;
    IF v_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:already_pending';
    END IF;
    SELECT COUNT(*) INTO v_count FROM list_invitations i WHERE i.list_id = NEW.list_id AND i.status = 'pending' AND i.expires_at > UTC_TIMESTAMP(3) FOR UPDATE;
    IF v_count >= 20 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:limit_reached';
    END IF;
    SELECT COUNT(*) INTO v_count FROM list_members m WHERE m.list_id = NEW.list_id AND m.role = 'editor' FOR UPDATE;
    IF v_count >= 10 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:limit_reached';
    END IF;

    SELECT u.display_name INTO v_inviter_name FROM users u WHERE u.id = NEW.user_id;

    SET NEW.invitee_id = v_lk_target;
    SET NEW.role = 'editor';
    SET NEW.status = 'pending';
    SET NEW.list_name = v_list_name;
    SET NEW.inviter_name = v_inviter_name;
    SET NEW.display_hint = v_lk_hint;
    SET NEW.expires_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 7 DAY);
    SET NEW.created_at_ms = v_now;
END$$

-- ---------------------------------------------------------------------------------------------------------------------------
-- join_codes (Method 2, owner side)
-- ---------------------------------------------------------------------------------------------------------------------------

-- Contract section 3 (join_codes): the code is checked against ^[A-HJ-NP-Z2-9]{8}$ CASE-SENSITIVELY (flag 'c') and for an exact
-- length of 8 (ICU's $ would also accept a trailing line break) BEFORE anything else uses it, else sl:invalid_code. The pepper
-- comes from app_secrets row code_pepper; a missing, short or still-placeholder value is sl:server_not_ready. The caller must own
-- the live list (sl:forbidden / sl:not_found). Stores code_hash = SHA2(CONCAT(pepper, code), 256), status active, expires_at now +
-- 24 h, and sets `code` to NULL: the plaintext exists only for the duration of this statement.
-- DEVIATION from the contract: "marks the list's older active codes superseded" is impossible here (error 1442: a trigger may not
-- update its own table). Supersession is DERIVED instead: trg_code_lookups_bi and trg_join_requests_bi accept a code only while no
-- newer code row exists for the same list, and the client shows an older `active` row as superseded.
DROP TRIGGER IF EXISTS trg_join_codes_bi$$
CREATE TRIGGER trg_join_codes_bi BEFORE INSERT ON join_codes FOR EACH ROW
BEGIN
    DECLARE v_now BIGINT;
    DECLARE v_pepper VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_list_owner INT UNSIGNED DEFAULT NULL;
    DECLARE v_list_deleted BIGINT DEFAULT NULL;

    IF EXISTS (SELECT 1 FROM join_codes x WHERE x.id = NEW.id) THEN
        SIGNAL SQLSTATE '23000' SET MESSAGE_TEXT = 'sl:duplicate', MYSQL_ERRNO = 1062;
    END IF;
    IF NEW.code IS NULL OR CHAR_LENGTH(NEW.code) <> 8 OR NOT REGEXP_LIKE(NEW.code, '^[A-HJ-NP-Z2-9]{8}$', 'c') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:invalid_code';
    END IF;
    SELECT s.`value` INTO v_pepper FROM app_secrets s WHERE s.`name` = 'code_pepper';
    IF v_pepper IS NULL OR CHAR_LENGTH(v_pepper) < 32 OR v_pepper LIKE 'REPLACE%' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:server_not_ready';
    END IF;
    SET v_now = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(3)) DIV 1000;

    -- Plain read and owner check before any lock; not-yours and unknown both answer sl:not_found (no oracle, no lock stalling).
    SELECT l.user_id INTO v_list_owner FROM shopping_lists l WHERE l.id = NEW.list_id;
    IF v_list_owner IS NULL OR NOT (v_list_owner <=> NEW.user_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
    END IF;
    SELECT l.deleted_at_ms INTO v_list_deleted FROM shopping_lists l WHERE l.id = NEW.list_id FOR UPDATE;
    IF v_list_deleted IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
    END IF;

    SET NEW.code_hash = SHA2(CONCAT(v_pepper, NEW.code), 256);
    SET NEW.status = 'active';
    SET NEW.expires_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 24 HOUR);
    SET NEW.created_at_ms = v_now;
    SET NEW.code = NULL;
END$$

-- ---------------------------------------------------------------------------------------------------------------------------
-- code_lookups (Method 2, joiner preview)
-- ---------------------------------------------------------------------------------------------------------------------------

-- Contract section 3 (code_lookups) and section 6: format check (sl:invalid_code, not stored, so it does not count), pepper
-- (sl:server_not_ready), throttle (5 stored `invalid` lookups in 15 minutes, then sl:too_many_attempts), hash, match against a code
-- that is active, not expired and the newest row of its list (derived supersession, see trg_join_codes_bi) on a live list.
-- Stores status found (+ list_name, inviter_name = the owner's display name, hidden code_id) or status invalid. Unknown, expired,
-- used, revoked and superseded codes all give the same `invalid`, so a probe learns nothing. `code` is NULLed.
DROP TRIGGER IF EXISTS trg_code_lookups_bi$$
CREATE TRIGGER trg_code_lookups_bi BEFORE INSERT ON code_lookups FOR EACH ROW
BEGIN
    DECLARE v_now BIGINT;
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_pepper VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_hash CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_code_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_code_list CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_list_name VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_owner_name VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_lock INT UNSIGNED DEFAULT NULL;

    IF EXISTS (SELECT 1 FROM code_lookups x WHERE x.id = NEW.id) THEN
        SIGNAL SQLSTATE '23000' SET MESSAGE_TEXT = 'sl:duplicate', MYSQL_ERRNO = 1062;
    END IF;
    IF NEW.code IS NULL OR CHAR_LENGTH(NEW.code) <> 8 OR NOT REGEXP_LIKE(NEW.code, '^[A-HJ-NP-Z2-9]{8}$', 'c') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:invalid_code';
    END IF;
    SELECT s.`value` INTO v_pepper FROM app_secrets s WHERE s.`name` = 'code_pepper';
    IF v_pepper IS NULL OR CHAR_LENGTH(v_pepper) < 32 OR v_pepper LIKE 'REPLACE%' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:server_not_ready';
    END IF;
    SET v_now = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(3)) DIV 1000;

    -- Serialise this caller's guesses (their users row) and count with a locking read: N parallel guesses cannot all see 0.
    SELECT u.id INTO v_lock FROM users u WHERE u.id = NEW.user_id FOR UPDATE;
    SELECT COUNT(*) INTO v_count FROM code_lookups x WHERE x.user_id = NEW.user_id AND x.status = 'invalid' AND x.created_at_ms > v_now - 900000 FOR UPDATE;
    IF v_count >= 5 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:too_many_attempts';
    END IF;

    SET v_hash = SHA2(CONCAT(v_pepper, NEW.code), 256);
    SELECT c.id, c.list_id INTO v_code_id, v_code_list
      FROM join_codes c
     WHERE c.code_hash = v_hash
       AND c.status = 'active'
       AND c.expires_at > UTC_TIMESTAMP(3)
       AND NOT EXISTS (SELECT 1 FROM join_codes n WHERE n.list_id = c.list_id AND (n.created_at_ms > c.created_at_ms OR (n.created_at_ms = c.created_at_ms AND n.id > c.id)))
     ORDER BY c.created_at_ms DESC
     LIMIT 1;
    IF v_code_id IS NOT NULL THEN
        SELECT l.name, u.display_name INTO v_list_name, v_owner_name
          FROM shopping_lists l JOIN users u ON u.id = l.user_id
         WHERE l.id = v_code_list AND l.deleted_at_ms IS NULL;
    END IF;

    IF v_code_id IS NOT NULL AND v_list_name IS NOT NULL THEN
        SET NEW.status = 'found';
        SET NEW.list_name = v_list_name;
        SET NEW.inviter_name = v_owner_name;
        SET NEW.code_id = v_code_id;
    ELSE
        SET NEW.status = 'invalid';
        SET NEW.list_name = NULL;
        SET NEW.inviter_name = NULL;
        SET NEW.code_id = NULL;
    END IF;
    SET NEW.created_at_ms = v_now;
    SET NEW.code = NULL;
END$$

-- ---------------------------------------------------------------------------------------------------------------------------
-- join_requests (Method 2, step 2)
-- ---------------------------------------------------------------------------------------------------------------------------

-- Contract section 3 (join_requests) and section 6: the lookup is the caller's (sl:not_found), status found (sl:invalid_code),
-- younger than 10 minutes (sl:expired); at most 10 requests per hour (sl:too_many_attempts); the code row is locked and then
-- judged: still active and the newest of its list (sl:invalid_code), not expired (sl:expired), list live; the caller is not an
-- active member (sl:already_member; this also stops an owner using their own code) and has no pending request for the list
-- (sl:already_pending). Then the code is CONSUMED (status used: single use) and the row gets owner_id, list_id, list_name,
-- requester_name, status pending and created_at_ms. Everything that can refuse comes before the one UPDATE.
DROP TRIGGER IF EXISTS trg_join_requests_bi$$
CREATE TRIGGER trg_join_requests_bi BEFORE INSERT ON join_requests FOR EACH ROW
BEGIN
    DECLARE v_now BIGINT;
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_lk_status VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_lk_code CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_lk_created BIGINT DEFAULT NULL;
    DECLARE v_code_list CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_code_status VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_code_exp DATETIME(3) DEFAULT NULL;
    DECLARE v_code_created BIGINT DEFAULT NULL;
    DECLARE v_code_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_list_owner INT UNSIGNED DEFAULT NULL;
    DECLARE v_list_name VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_list_deleted BIGINT DEFAULT NULL;
    DECLARE v_requester_name VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_lock INT UNSIGNED DEFAULT NULL;

    IF EXISTS (SELECT 1 FROM join_requests x WHERE x.id = NEW.id) THEN
        SIGNAL SQLSTATE '23000' SET MESSAGE_TEXT = 'sl:duplicate', MYSQL_ERRNO = 1062;
    END IF;
    SET v_now = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(3)) DIV 1000;

    SELECT k.status, k.code_id, k.created_at_ms INTO v_lk_status, v_lk_code, v_lk_created
      FROM code_lookups k WHERE k.id = NEW.lookup_id AND k.user_id = NEW.user_id;
    IF v_lk_status IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
    END IF;
    IF v_lk_status <> 'found' OR v_lk_code IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:invalid_code';
    END IF;
    IF v_lk_created < v_now - 600000 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:expired';
    END IF;

    SELECT u.id INTO v_lock FROM users u WHERE u.id = NEW.user_id FOR UPDATE;
    SELECT COUNT(*) INTO v_count FROM join_requests x WHERE x.user_id = NEW.user_id AND x.created_at_ms > v_now - 3600000 FOR UPDATE;
    IF v_count >= 10 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:too_many_attempts';
    END IF;

    SELECT c.id, c.list_id, c.status, c.expires_at, c.created_at_ms INTO v_code_id, v_code_list, v_code_status, v_code_exp, v_code_created
      FROM join_codes c WHERE c.id = v_lk_code FOR UPDATE;
    IF v_code_id IS NULL OR v_code_status <> 'active' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:invalid_code';
    END IF;
    IF v_code_exp IS NULL OR v_code_exp <= UTC_TIMESTAMP(3) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:expired';
    END IF;
    IF EXISTS (SELECT 1 FROM join_codes n WHERE n.list_id = v_code_list AND (n.created_at_ms > v_code_created OR (n.created_at_ms = v_code_created AND n.id > v_code_id))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:invalid_code';
    END IF;

    SELECT l.user_id, l.name, l.deleted_at_ms INTO v_list_owner, v_list_name, v_list_deleted FROM shopping_lists l WHERE l.id = v_code_list;
    IF v_list_owner IS NULL OR v_list_deleted IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:invalid_code';
    END IF;

    SELECT COUNT(*) INTO v_count FROM list_members m WHERE m.list_id = v_code_list AND m.member_id = NEW.user_id AND m.role IN ('owner', 'editor') FOR UPDATE;
    IF v_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:already_member';
    END IF;
    SELECT COUNT(*) INTO v_count FROM join_requests r WHERE r.user_id = NEW.user_id AND r.list_id = v_code_list AND r.status = 'pending' FOR UPDATE;
    IF v_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:already_pending';
    END IF;

    SELECT u.display_name INTO v_requester_name FROM users u WHERE u.id = NEW.user_id;

    UPDATE join_codes SET status = 'used' WHERE id = v_code_id;

    SET NEW.owner_id = v_list_owner;
    SET NEW.list_id = v_code_list;
    SET NEW.list_name = v_list_name;
    SET NEW.requester_name = v_requester_name;
    SET NEW.status = 'pending';
    SET NEW.created_at_ms = v_now;
END$$

-- ---------------------------------------------------------------------------------------------------------------------------
-- list_commands (insert-only; every membership and status change)
-- ---------------------------------------------------------------------------------------------------------------------------

-- Contract section 3 (list_commands). NEW.user_id (forced to the caller by AppHub) is the actor. The command is validated and
-- executed here; a refusal rolls the whole statement back, so a refused command changes nothing and stores no row. Order inside every
-- branch: find and lock the row (sl:not_found; also when the row is not the actor's to see, so ids cannot be probed. Only a member
-- of the list who lacks the right for remove_member / leave_list is told sl:forbidden) -> status after the lock
-- (sl:not_pending) -> expiry (sl:expired) -> list row lock, list live (sl:not_found), already a member (sl:already_member),
-- editor limit 10 (sl:limit_reached) -> the changes. An unknown command is sl:forbidden. Expiry blocks accept only; a pending but
-- expired invitation can still be rejected or revoked so that it can be tidied away.
--   accept_invite   invitee only. Upserts the editor member row (a removed person's row is re-activated), invitation accepted.
--   reject_invite   invitee only. Invitation rejected.
--   revoke_invite   inviter (list owner) only. Pending invitation revoked.
--   approve_join    list owner only. Request approved, editor member row upserted.
--   reject_join     list owner only. Request rejected.
--   cancel_request  requester only. Pending request cancelled.
--   revoke_code     list owner only. Active code revoked.
--   remove_member   list owner only, never the owner row. Member role removed / removal removed.
--   leave_list      the member themself (their own row), never the owner. Role removed / removal left.
-- remove_member and leave_list also re-own the leaving person's items to the list owner (see "DEVIATION" below), and, like
-- accept_invite and approve_join, close every other pending invitation / join request of that person for the list, so a stale
-- invitation can never bring a removed person back in without the owner (see the security review of 2026-11-20).
-- DEVIATION from the contract (items keep their creator in user_id): AppHub's `shared` scope ALWAYS lets a row's owner through (an OR
-- on the owner column), so a removed editor could keep reading, through `user_id = me`, the items they created and every later
-- edit of them by others. Re-owning those items to the list owner closes that; the set @sl_transfer lets trg_shopping_items_bu allow
-- exactly that one change and nothing else, and the person's membership row (role removed) is what tells their app.
DROP TRIGGER IF EXISTS trg_list_commands_bi$$
CREATE TRIGGER trg_list_commands_bi BEFORE INSERT ON list_commands FOR EACH ROW
BEGIN
    DECLARE v_now BIGINT;
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_status VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_exp DATETIME(3) DEFAULT NULL;
    DECLARE v_u1 INT UNSIGNED DEFAULT NULL;
    DECLARE v_u2 INT UNSIGNED DEFAULT NULL;
    DECLARE v_list CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_list_owner INT UNSIGNED DEFAULT NULL;
    DECLARE v_list_deleted BIGINT DEFAULT NULL;
    DECLARE v_mid CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_role VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE v_name VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    -- Whatever fails below (a refusal, a lock wait timeout, a deadlock), the items bypass is switched off again before it propagates.
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET @sl_transfer = NULL;
        RESIGNAL;
    END;

    IF EXISTS (SELECT 1 FROM list_commands x WHERE x.id = NEW.id) THEN
        SIGNAL SQLSTATE '23000' SET MESSAGE_TEXT = 'sl:duplicate', MYSQL_ERRNO = 1062;
    END IF;
    -- Fail closed, like trg_shopping_items_bu: no actor (with actor_in_sql off every request would look like that) is refused.
    IF @apphub_user_id IS NULL OR NOT (@apphub_user_id <=> NEW.user_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:forbidden';
    END IF;
    SET v_now = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(3)) DIV 1000;

    IF NEW.command = 'accept_invite' THEN
        SELECT i.status, i.invitee_id, i.list_id, i.expires_at INTO v_status, v_u1, v_list, v_exp
          FROM list_invitations i WHERE i.id = NEW.target_id FOR UPDATE;
        IF v_status IS NULL OR NOT (v_u1 <=> NEW.user_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
        END IF;
        IF v_status <> 'pending' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_pending';
        END IF;
        IF v_exp IS NULL OR v_exp <= UTC_TIMESTAMP(3) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:expired';
        END IF;
        SELECT l.user_id, l.deleted_at_ms INTO v_list_owner, v_list_deleted FROM shopping_lists l WHERE l.id = v_list FOR UPDATE;
        IF v_list_owner IS NULL OR v_list_deleted IS NOT NULL THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
        END IF;
        SELECT m.id, m.role INTO v_mid, v_role FROM list_members m WHERE m.list_id = v_list AND m.member_id = NEW.user_id FOR UPDATE;
        IF v_role IN ('owner', 'editor') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:already_member';
        END IF;
        SELECT COUNT(*) INTO v_count FROM list_members m WHERE m.list_id = v_list AND m.role = 'editor' FOR UPDATE;
        IF v_count >= 10 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:limit_reached';
        END IF;
        SELECT u.display_name INTO v_name FROM users u WHERE u.id = NEW.user_id;
        IF v_mid IS NULL THEN
            INSERT INTO list_members (id, list_id, user_id, member_id, role, removal, display_name, created_at_ms)
            VALUES (UUID(), v_list, v_list_owner, NEW.user_id, 'editor', NULL, v_name, v_now);
        ELSE
            UPDATE list_members SET role = 'editor', removal = NULL, display_name = v_name, created_at_ms = v_now WHERE id = v_mid;
        END IF;
        UPDATE list_invitations SET status = 'accepted' WHERE id = NEW.target_id;
        -- The person is in now: nothing else that could let them in (or back in later) may stay pending for this list.
        UPDATE list_invitations SET status = 'revoked' WHERE list_id = v_list AND invitee_id = NEW.user_id AND status = 'pending' AND id <> NEW.target_id;
        UPDATE join_requests SET status = 'cancelled' WHERE list_id = v_list AND user_id = NEW.user_id AND status = 'pending';

    ELSEIF NEW.command = 'reject_invite' THEN
        SELECT i.status, i.invitee_id INTO v_status, v_u1 FROM list_invitations i WHERE i.id = NEW.target_id FOR UPDATE;
        IF v_status IS NULL OR NOT (v_u1 <=> NEW.user_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
        END IF;
        IF v_status <> 'pending' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_pending';
        END IF;
        UPDATE list_invitations SET status = 'rejected' WHERE id = NEW.target_id;

    ELSEIF NEW.command = 'revoke_invite' THEN
        SELECT i.status, i.user_id INTO v_status, v_u1 FROM list_invitations i WHERE i.id = NEW.target_id FOR UPDATE;
        IF v_status IS NULL OR NOT (v_u1 <=> NEW.user_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
        END IF;
        IF v_status <> 'pending' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_pending';
        END IF;
        UPDATE list_invitations SET status = 'revoked' WHERE id = NEW.target_id;

    ELSEIF NEW.command = 'approve_join' THEN
        SELECT r.status, r.owner_id, r.user_id, r.list_id INTO v_status, v_u1, v_u2, v_list
          FROM join_requests r WHERE r.id = NEW.target_id FOR UPDATE;
        IF v_status IS NULL OR NOT (v_u1 <=> NEW.user_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
        END IF;
        IF v_status <> 'pending' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_pending';
        END IF;
        SELECT l.user_id, l.deleted_at_ms INTO v_list_owner, v_list_deleted FROM shopping_lists l WHERE l.id = v_list FOR UPDATE;
        IF v_list_owner IS NULL OR v_list_deleted IS NOT NULL THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
        END IF;
        IF NOT (v_list_owner <=> NEW.user_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:forbidden';
        END IF;
        SELECT m.id, m.role INTO v_mid, v_role FROM list_members m WHERE m.list_id = v_list AND m.member_id = v_u2 FOR UPDATE;
        IF v_role IN ('owner', 'editor') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:already_member';
        END IF;
        SELECT COUNT(*) INTO v_count FROM list_members m WHERE m.list_id = v_list AND m.role = 'editor' FOR UPDATE;
        IF v_count >= 10 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:limit_reached';
        END IF;
        SELECT u.display_name INTO v_name FROM users u WHERE u.id = v_u2;
        IF v_mid IS NULL THEN
            INSERT INTO list_members (id, list_id, user_id, member_id, role, removal, display_name, created_at_ms)
            VALUES (UUID(), v_list, v_list_owner, v_u2, 'editor', NULL, v_name, v_now);
        ELSE
            UPDATE list_members SET role = 'editor', removal = NULL, display_name = v_name, created_at_ms = v_now WHERE id = v_mid;
        END IF;
        UPDATE join_requests SET status = 'approved' WHERE id = NEW.target_id;
        -- The person is in now: no other pending request or invitation for this list may stay behind.
        UPDATE join_requests SET status = 'cancelled' WHERE list_id = v_list AND user_id = v_u2 AND status = 'pending' AND id <> NEW.target_id;
        UPDATE list_invitations SET status = 'revoked' WHERE list_id = v_list AND invitee_id = v_u2 AND status = 'pending';

    ELSEIF NEW.command = 'reject_join' THEN
        SELECT r.status, r.owner_id INTO v_status, v_u1 FROM join_requests r WHERE r.id = NEW.target_id FOR UPDATE;
        IF v_status IS NULL OR NOT (v_u1 <=> NEW.user_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
        END IF;
        IF v_status <> 'pending' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_pending';
        END IF;
        UPDATE join_requests SET status = 'rejected' WHERE id = NEW.target_id;

    ELSEIF NEW.command = 'cancel_request' THEN
        SELECT r.status, r.user_id INTO v_status, v_u1 FROM join_requests r WHERE r.id = NEW.target_id FOR UPDATE;
        IF v_status IS NULL OR NOT (v_u1 <=> NEW.user_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
        END IF;
        IF v_status <> 'pending' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_pending';
        END IF;
        UPDATE join_requests SET status = 'cancelled' WHERE id = NEW.target_id;

    ELSEIF NEW.command = 'revoke_code' THEN
        SELECT c.status, c.user_id INTO v_status, v_u1 FROM join_codes c WHERE c.id = NEW.target_id FOR UPDATE;
        IF v_status IS NULL OR NOT (v_u1 <=> NEW.user_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
        END IF;
        IF v_status <> 'active' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_pending';
        END IF;
        UPDATE join_codes SET status = 'revoked' WHERE id = NEW.target_id;

    ELSEIF NEW.command = 'remove_member' THEN
        SELECT m.list_id, m.user_id, m.member_id, m.role INTO v_list, v_list_owner, v_u2, v_role
          FROM list_members m WHERE m.id = NEW.target_id FOR UPDATE;
        IF v_role IS NULL THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
        END IF;
        IF NOT (v_list_owner <=> NEW.user_id) THEN
            -- A member of that list may be told "forbidden"; a stranger only learns "not found".
            SELECT COUNT(*) INTO v_count FROM list_members m WHERE m.list_id = v_list AND m.member_id = NEW.user_id AND m.role IN ('owner', 'editor');
            IF v_count = 0 THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
            END IF;
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:forbidden';
        END IF;
        IF v_role = 'owner' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:forbidden';
        END IF;
        IF v_role <> 'editor' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_a_member';
        END IF;
        SET @sl_transfer = 1;
        UPDATE shopping_items SET user_id = v_list_owner WHERE list_id = v_list AND user_id = v_u2;
        SET @sl_transfer = NULL;
        UPDATE list_members SET role = 'removed', removal = 'removed' WHERE id = NEW.target_id;
        -- Nothing that could let the removed person back in without the owner may stay pending.
        UPDATE list_invitations SET status = 'revoked' WHERE list_id = v_list AND invitee_id = v_u2 AND status = 'pending';
        UPDATE join_requests SET status = 'cancelled' WHERE list_id = v_list AND user_id = v_u2 AND status = 'pending';

    ELSEIF NEW.command = 'leave_list' THEN
        SELECT m.list_id, m.user_id, m.member_id, m.role INTO v_list, v_list_owner, v_u2, v_role
          FROM list_members m WHERE m.id = NEW.target_id FOR UPDATE;
        IF v_role IS NULL THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
        END IF;
        IF NOT (v_u2 <=> NEW.user_id) THEN
            SELECT COUNT(*) INTO v_count FROM list_members m WHERE m.list_id = v_list AND m.member_id = NEW.user_id AND m.role IN ('owner', 'editor');
            IF v_count = 0 THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_found';
            END IF;
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:forbidden';
        END IF;
        IF v_role = 'owner' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:forbidden';
        END IF;
        IF v_role <> 'editor' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:not_a_member';
        END IF;
        SET @sl_transfer = 1;
        UPDATE shopping_items SET user_id = v_list_owner WHERE list_id = v_list AND user_id = v_u2;
        SET @sl_transfer = NULL;
        UPDATE list_members SET role = 'removed', removal = 'left' WHERE id = NEW.target_id;
        UPDATE list_invitations SET status = 'revoked' WHERE list_id = v_list AND invitee_id = v_u2 AND status = 'pending';
        UPDATE join_requests SET status = 'cancelled' WHERE list_id = v_list AND user_id = v_u2 AND status = 'pending';

    ELSE
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sl:forbidden';
    END IF;

    SET NEW.created_at_ms = v_now;
END$$

DELIMITER ;
