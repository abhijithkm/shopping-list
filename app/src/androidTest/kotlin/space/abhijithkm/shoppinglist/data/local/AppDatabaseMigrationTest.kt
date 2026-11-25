package space.abhijithkm.shoppinglist.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Upgrades a real version-1 database (the Phase 2 schema, with data in it) to the current schema and checks that
 * nothing was lost. This is the test that keeps "no destructive migration" honest.
 */
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun version1ToVersion2KeepsEveryRowAndAddsTheSyncTables() {
        helper.createDatabase(DB_NAME, 1).apply {
            execSQL(
                "INSERT INTO lists (id, accountId, origin, name, isArchived, isPinned, sortKey, role, createdAt, updatedAt, revision, serverRevision) " +
                    "VALUES ('l1', NULL, 'LOCAL', 'Groceries', 0, 1, 5.0, 'OWNER', 10, 20, 3, 0)",
            )
            execSQL(
                "INSERT INTO items (id, listId, name, quantity, unit, isBought, sortOrder, createdAt, updatedAt, revision, serverRevision) " +
                    "VALUES ('i1', 'l1', 'Milk', 2.0, 'kg', 1, 1024.0, 11, 21, 2, 0)",
            )
            execSQL("INSERT INTO tags (id, name, createdAt, updatedAt, revision, serverRevision) VALUES ('t1', 'Organic', 12, 22, 0, 0)")
            execSQL("INSERT INTO item_tags (itemId, tagId, listId) VALUES ('i1', 't1', 'l1')")
            execSQL(
                "INSERT INTO categories (id, name, color, createdAt, updatedAt, revision, serverRevision) VALUES ('dairy', 'Milk and cheese', NULL, 13, 23, 1, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 2, true)

        db.query("SELECT name, isPinned, revision FROM lists WHERE id = 'l1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Groceries", it.getString(0))
            assertEquals(1, it.getInt(1))
            assertEquals(3, it.getInt(2))
        }
        db.query("SELECT name, quantity, unit, isBought FROM items WHERE id = 'i1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Milk", it.getString(0))
            assertEquals(2.0, it.getDouble(1), 0.0)
            assertEquals("kg", it.getString(2))
            assertEquals(1, it.getInt(3))
        }
        db.query("SELECT COUNT(*) FROM item_tags").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT name FROM categories WHERE id = 'dairy'").use {
            it.moveToFirst()
            assertEquals("Milk and cheese", it.getString(0))
        }
        // The new tables exist and start empty.
        db.query("SELECT COUNT(*) FROM sync_entities").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sync_state").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        // Existing guest rows are not dirty: they have no account.
        db.query("SELECT COUNT(*) FROM lists WHERE accountId IS NOT NULL").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun version2ToVersion3KeepsListsAndItemsAndAddsEmptyReminderTables() {
        helper.createDatabase(DB_NAME, 2).apply {
            execSQL(
                "INSERT INTO lists (id, accountId, origin, name, isArchived, isPinned, sortKey, role, createdAt, updatedAt, revision, serverRevision) " +
                    "VALUES ('l1', NULL, 'LOCAL', 'Groceries', 0, 0, 5.0, 'OWNER', 10, 20, 1, 0)",
            )
            execSQL(
                "INSERT INTO items (id, listId, name, isBought, sortOrder, createdAt, updatedAt, revision, serverRevision) " +
                    "VALUES ('i1', 'l1', 'Milk', 0, 1024.0, 11, 21, 1, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true)

        db.query("SELECT name FROM lists WHERE id = 'l1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Groceries", it.getString(0))
        }
        db.query("SELECT name FROM items WHERE id = 'i1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Milk", it.getString(0))
        }
        db.query("SELECT COUNT(*) FROM reminders").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM list_prefs").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }

    private companion object {
        const val DB_NAME = "migration-test"
    }
}
