package space.abhijithkm.shoppinglist.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local source of truth. Schema JSON is exported to app/schemas and committed. The schema stays at
 * version 1 until the first public release; after that every change needs a tested migration
 * (never a destructive fallback).
 */
@Database(
    entities = [
        ShoppingListEntity::class,
        ShoppingItemEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        ItemTagCrossRef::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun listDao(): ShoppingListDao

    abstract fun itemDao(): ItemDao

    abstract fun categoryDao(): CategoryDao

    abstract fun tagDao(): TagDao
}
