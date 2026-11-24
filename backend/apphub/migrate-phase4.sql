-- Phase 4 migration for a database that ALREADY holds the Phase 3 tables (the dev database `shoppinglistdev`, created 2026-11-16).
-- A fresh database does NOT need this file: schema.sql already contains everything below. WRITTEN, NOT APPLIED (README.md ledger).
--
-- Why a separate file: MySQL 8 has no ADD COLUMN IF NOT EXISTS, and CREATE TABLE IF NOT EXISTS never alters an existing table.
-- Order for an already-provisioned database (provision.md has the full runbook):
--     1. sudo mysql <database> < schema.sql             creates ONLY the new tables (the Phase 3 ones are skipped: IF NOT EXISTS)
--     2. sudo mysql <database> < migrate-phase4.sql     section A: the ALTERs (once)
--     3. sudo mysql <database> < triggers.sql           the rules (repeatable)
--     4. seed.sql, then the real pepper (provision.md)
--     5. section B again, ALONE (it is repeatable; a list created between step 2 and step 3 has no owner row yet):
--            sed -n '/^-- B\./,$p' migrate-phase4.sql | sudo mysql <database>
-- The whole file runs ONCE, at step 2 (A, then B). Running the whole file a second time stops at the ALTER of section A (the mysql
-- client stops at the first error), so B would never run: that is why step 5 pipes section B alone.
--
-- GUARD (run it first, read-only): the ALTER below must run only while this prints 0. It fails with "Duplicate column name" when
-- run twice (and then changes nothing: one ALTER statement is atomic), so a second run is harmless but noisy.
--     SELECT COUNT(*) FROM information_schema.COLUMNS
--      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shopping_items' AND COLUMN_NAME IN ('revision', 'updated_by', 'base_revision');
--
-- Existing rows: every item gets revision 1 (the column default), updated_by NULL, base_revision NULL. The ALTER does not touch
-- updated_at (ON UPDATE CURRENT_TIMESTAMP fires on an UPDATE of a row, not on a table definition change), so the sync cursor of the
-- clients does not move. It does not fire the triggers either (there are none yet at step 2).

-- ---------------------------------------------------------------------------------------------------------------------------
-- A. Column changes. ONCE, at step 2 (before triggers.sql: the items trigger reads these columns).
-- ---------------------------------------------------------------------------------------------------------------------------
ALTER TABLE shopping_items
    ADD COLUMN revision      INT          NOT NULL DEFAULT 1   AFTER deleted_at_ms,
    ADD COLUMN updated_by    INT UNSIGNED NULL                 AFTER revision,
    ADD COLUMN base_revision INT          NULL                 AFTER updated_by,
    ADD CONSTRAINT fk_shopping_items_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------------------------------------------------------
-- B. The owner's own list_members row for every list that existed before the triggers did. REPEATABLE: the NOT EXISTS makes a
--    second run insert nothing. Run it at step 2 with the file and AGAIN alone after triggers.sql (step 5), so a list created in
--    between is covered too. The owner also reaches their lists through AppHub's owner column, so a missing row never
--    locks anybody out; the row is what lets the app list the members of a list and what the other triggers look up.
--    created_at_ms is the server clock in epoch milliseconds (the same expression the triggers use); display_name is a copy of the
--    owner's users.display_name at this moment.
-- ---------------------------------------------------------------------------------------------------------------------------
INSERT INTO list_members (id, list_id, user_id, member_id, role, removal, display_name, created_at_ms)
SELECT UUID(), l.id, l.user_id, l.user_id, 'owner', NULL, u.display_name,
       TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(3)) DIV 1000
  FROM shopping_lists l
  JOIN users u ON u.id = l.user_id
 WHERE NOT EXISTS (SELECT 1 FROM list_members m WHERE m.list_id = l.id AND m.member_id = l.user_id);

-- Check after B (read-only, must print 0): lists without their owner row.
--     SELECT COUNT(*) FROM shopping_lists l WHERE NOT EXISTS (SELECT 1 FROM list_members m WHERE m.list_id = l.id AND m.member_id = l.user_id AND m.role = 'owner');
