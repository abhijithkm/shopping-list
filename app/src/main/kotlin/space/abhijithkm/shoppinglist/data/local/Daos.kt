package space.abhijithkm.shoppinglist.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A list row plus live item counts (deleted items excluded). */
data class ListWithCounts(
    @Embedded val list: ShoppingListEntity,
    val totalCount: Int,
    val boughtCount: Int,
)

/** An item with its (non-deleted) tags; callers filter tombstoned tags. */
data class ItemWithTags(
    @Embedded val item: ShoppingItemEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(value = ItemTagCrossRef::class, parentColumn = "itemId", entityColumn = "tagId"),
    )
    val tags: List<TagEntity>,
)

private const val LIST_WITH_COUNTS = """
    SELECT l.*,
      (SELECT COUNT(*) FROM items i WHERE i.listId = l.id AND i.deletedAt IS NULL) AS totalCount,
      (SELECT COUNT(*) FROM items i WHERE i.listId = l.id AND i.deletedAt IS NULL AND i.isBought = 1) AS boughtCount
    FROM lists l
"""

// Every UPDATE below bumps `revision` (the sync engine's local-edit counter) and `updatedAt`.
@Dao
interface ShoppingListDao {
    @Query(LIST_WITH_COUNTS + " WHERE l.deletedAt IS NULL AND l.isArchived = :archived ORDER BY l.isPinned DESC, l.updatedAt DESC")
    fun observeWithCounts(archived: Boolean): Flow<List<ListWithCounts>>

    @Query(LIST_WITH_COUNTS + " WHERE l.id = :id AND l.deletedAt IS NULL")
    fun observeOneWithCounts(id: String): Flow<ListWithCounts?>

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun getById(id: String): ShoppingListEntity?

    @Upsert
    suspend fun upsert(list: ShoppingListEntity)

    @Query("UPDATE lists SET name = :name, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE lists SET isPinned = :pinned, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long)

    @Query("UPDATE lists SET isArchived = :archived, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, now: Long)

    @Query("UPDATE lists SET color = :color, icon = :icon, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun setAppearance(id: String, color: Int?, icon: String?, now: Long)

    @Query("UPDATE lists SET deletedAt = :now, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE lists SET deletedAt = NULL, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    /** Records that something inside the list changed (for "last updated" and recent-first sorting). */
    @Query("UPDATE lists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    /** Hard delete (cascades to items). Only for tombstones that are no longer needed. */
    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun purge(id: String)

    @Query("DELETE FROM lists WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND origin = 'LOCAL'")
    suspend fun purgeLocalTombstones(cutoff: Long)
}

@Dao
interface ItemDao {
    /** All live items of a list in manual order; callers split open and bought. */
    @Transaction
    @Query("SELECT * FROM items WHERE listId = :listId AND deletedAt IS NULL ORDER BY sortOrder ASC")
    fun observeForList(listId: String): Flow<List<ItemWithTags>>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getById(id: String): ShoppingItemEntity?

    @Upsert
    suspend fun upsert(item: ShoppingItemEntity)

    @Query("SELECT MAX(sortOrder) FROM items WHERE listId = :listId AND deletedAt IS NULL AND isBought = 0")
    suspend fun maxOpenSortOrder(listId: String): Double?

    @Query("SELECT * FROM items WHERE listId = :listId AND deletedAt IS NULL AND isBought = 0 ORDER BY sortOrder ASC")
    suspend fun openItemsInOrder(listId: String): List<ShoppingItemEntity>

    @Query("UPDATE items SET sortOrder = :sortOrder, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Double, now: Long)

    @Query("UPDATE items SET isBought = :bought, boughtAt = :boughtAt, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun setBought(id: String, bought: Boolean, boughtAt: Long?, now: Long)

    @Query("UPDATE items SET deletedAt = :now, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE items SET deletedAt = NULL, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    @Query("SELECT id FROM items WHERE listId = :listId AND deletedAt IS NULL AND isBought = 1")
    suspend fun boughtIds(listId: String): List<String>

    @Query("UPDATE items SET deletedAt = :now, updatedAt = :now, revision = revision + 1 WHERE id IN (:ids)")
    suspend fun softDeleteMany(ids: List<String>, now: Long)

    @Query("UPDATE items SET deletedAt = NULL, updatedAt = :now, revision = revision + 1 WHERE id IN (:ids)")
    suspend fun restoreMany(ids: List<String>, now: Long)

    @Query("SELECT listId FROM items WHERE id IN (:ids) GROUP BY listId")
    suspend fun listIdsOf(ids: List<String>): List<String>

    @Query("UPDATE items SET categoryKey = NULL, updatedAt = :now, revision = revision + 1 WHERE categoryKey = :categoryKey AND deletedAt IS NULL")
    suspend fun clearCategory(categoryKey: String, now: Long)

    @Query("DELETE FROM item_tags WHERE itemId = :itemId")
    suspend fun deleteTagLinks(itemId: String)

    @Query("DELETE FROM item_tags WHERE tagId = :tagId")
    suspend fun deleteLinksForTag(tagId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagLinks(links: List<ItemTagCrossRef>)

    @Query("DELETE FROM items WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND listId IN (SELECT id FROM lists WHERE origin = 'LOCAL')")
    suspend fun purgeLocalTombstones(cutoff: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Query("UPDATE categories SET deletedAt = :now, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("DELETE FROM categories WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND accountId IS NULL")
    suspend fun purgeTombstones(cutoff: Long)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags WHERE deletedAt IS NULL ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name AND deletedAt IS NULL LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Query("SELECT id FROM tags WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun existingIds(ids: List<String>): List<String>

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Query("UPDATE tags SET name = :name, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE tags SET deletedAt = :now, updatedAt = :now, revision = revision + 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("DELETE FROM tags WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND accountId IS NULL")
    suspend fun purgeTombstones(cutoff: Long)
}
