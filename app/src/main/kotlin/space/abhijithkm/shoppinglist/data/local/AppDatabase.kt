package space.abhijithkm.shoppinglist.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local source of truth. Schema JSON is exported to app/schemas and committed. Every schema change
 * needs a migration that is tested on a device (never a destructive fallback).
 *
 *  - v1: lists, items, categories, tags, item_tags (Phases 1 and 2).
 *  - v2: sync bookkeeping, `sync_entities` and `sync_state` (Phase 3). Added tables only, so Room can
 *    generate the migration.
 *  - v3: `reminders` and `list_prefs` (Phase 5, local-only, never synced). Added tables only.
 */
@Database(
    entities = [
        ShoppingListEntity::class,
        ShoppingItemEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        ItemTagCrossRef::class,
        SyncEntityEntity::class,
        SyncStateEntity::class,
        ReminderEntity::class,
        ListPrefsEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun listDao(): ShoppingListDao

    abstract fun itemDao(): ItemDao

    abstract fun categoryDao(): CategoryDao

    abstract fun tagDao(): TagDao

    abstract fun syncDao(): SyncDao

    abstract fun reminderDao(): ReminderDao

    abstract fun listPrefsDao(): ListPrefsDao
}
