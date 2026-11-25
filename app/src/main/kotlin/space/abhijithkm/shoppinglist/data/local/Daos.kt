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

    /** Moves "last updated" forward to at least [timestamp] (an item changed on another device). */
    @Query("UPDATE lists SET updatedAt = :timestamp WHERE id = :id AND updatedAt < :timestamp")
    suspend fun touchAtLeast(id: String, timestamp: Long)

    /** Hard delete (cascades to items). Only for tombstones that are no longer needed. */
    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun purge(id: String)

    @Query("DELETE FROM lists WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND origin = 'LOCAL'")
    suspend fun purgeLocalTombstones(cutoff: Long)

    /** Synced tombstones the server has acknowledged (clean) and whose undo window is over. */
    @Query("DELETE FROM lists WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND origin = 'CLOUD' AND revision <= serverRevision")
    suspend fun purgeSyncedTombstones(cutoff: Long)

    /** Guest list -> account list (migration). Items follow through the list; they have no account column. */
    @Query("UPDATE lists SET accountId = :accountId, origin = 'CLOUD' WHERE id = :id AND accountId IS NULL AND deletedAt IS NULL")
    suspend fun adopt(id: String, accountId: String)

    /** Account lists -> guest lists (sign-out with "keep a copy"). */
    @Query("UPDATE lists SET accountId = NULL, origin = 'LOCAL', serverRevision = 0 WHERE accountId = :accountId")
    suspend fun detachAccount(accountId: String)

    @Query("SELECT * FROM lists WHERE accountId = :accountId AND id != :exceptId")
    suspend fun listsOf(accountId: String, exceptId: String = ""): List<ShoppingListEntity>

    @Query("SELECT * FROM lists WHERE accountId IS NULL AND deletedAt IS NULL AND origin = 'LOCAL' ORDER BY sortKey")
    suspend fun guestLists(): List<ShoppingListEntity>

    @Query("SELECT COUNT(*) FROM lists WHERE accountId IS NULL AND deletedAt IS NULL AND origin = 'LOCAL'")
    fun observeGuestListCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM lists WHERE accountId IS NOT NULL AND accountId != :accountId")
    suspend fun countOfOtherAccounts(accountId: String): Int

    @Query("SELECT DISTINCT accountId FROM lists WHERE accountId IS NOT NULL")
    suspend fun accountIds(): List<String>

    /** The lists whose "Shopping mode" switch is on (deleted and archived ones included: callers decide what to show). */
    @Query("SELECT l.* FROM lists l JOIN list_prefs p ON p.listId = l.id WHERE p.shoppingMode = 1")
    suspend fun shoppingModeLists(): List<ShoppingListEntity>
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

    /** Ticks every open item of a list in one statement ("Mark all done"); already-bought and deleted items are left alone. */
    @Query("UPDATE items SET isBought = 1, boughtAt = :now, updatedAt = :now, revision = revision + 1 WHERE listId = :listId AND deletedAt IS NULL AND isBought = 0")
    suspend fun markAllBought(listId: String, now: Long)

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

    @Query("DELETE FROM items WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND revision <= serverRevision AND listId IN (SELECT id FROM lists WHERE origin = 'CLOUD')")
    suspend fun purgeSyncedTombstones(cutoff: Long)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun purge(id: String)

    @Query("SELECT tagId FROM item_tags WHERE itemId = :itemId")
    suspend fun tagIdsOf(itemId: String): List<String>

    @Query("SELECT COUNT(*) FROM items WHERE listId = :listId AND deletedAt IS NULL")
    suspend fun countLive(listId: String): Int

    /** Removing a tag changes the tag set of every item that carried it, so those items must sync again. */
    @Query("UPDATE items SET updatedAt = :now, revision = revision + 1 WHERE id IN (SELECT itemId FROM item_tags WHERE tagId = :tagId)")
    suspend fun bumpItemsOfTag(tagId: String, now: Long)

    @Query("SELECT * FROM items WHERE listId = :listId")
    suspend fun allOfList(listId: String): List<ShoppingItemEntity>
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

    @Query("DELETE FROM categories WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND accountId IS NOT NULL AND revision <= serverRevision")
    suspend fun purgeSyncedTombstones(cutoff: Long)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun purge(id: String)

    /** Guest vocabulary joins the account at migration. */
    @Query("UPDATE categories SET accountId = :accountId WHERE accountId IS NULL AND deletedAt IS NULL")
    suspend fun adoptGuest(accountId: String)

    @Query("UPDATE categories SET accountId = NULL, serverRevision = 0 WHERE accountId = :accountId")
    suspend fun detachAccount(accountId: String)

    @Query("SELECT COUNT(*) FROM categories WHERE accountId IS NULL AND deletedAt IS NULL")
    suspend fun guestCount(): Int
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

    @Query("DELETE FROM tags WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff AND accountId IS NOT NULL AND revision <= serverRevision")
    suspend fun purgeSyncedTombstones(cutoff: Long)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun purge(id: String)

    @Query("SELECT id FROM tags WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    @Query("UPDATE tags SET accountId = :accountId WHERE accountId IS NULL AND deletedAt IS NULL")
    suspend fun adoptGuest(accountId: String)

    @Query("UPDATE tags SET accountId = NULL, serverRevision = 0 WHERE accountId = :accountId")
    suspend fun detachAccount(accountId: String)

    @Query("SELECT COUNT(*) FROM tags WHERE accountId IS NULL AND deletedAt IS NULL")
    suspend fun guestCount(): Int
}

/**
 * Sync bookkeeping and the dirty-row queries. A row is dirty when it belongs to the account and either
 * `revision > serverRevision` (edited since the last acknowledged push) or has no synced base yet (never
 * reached the server). Items have no account column: they belong to the account of their list.
 */
@Dao
interface SyncDao {
    @Query(
        """SELECT l.* FROM lists l WHERE l.accountId = :accountId AND (l.revision > l.serverRevision OR NOT EXISTS
           (SELECT 1 FROM sync_entities s WHERE s.type = 'list' AND s.id = l.id AND s.base IS NOT NULL)) ORDER BY l.createdAt""",
    )
    suspend fun dirtyLists(accountId: String): List<ShoppingListEntity>

    @Query(
        """SELECT i.* FROM items i JOIN lists l ON l.id = i.listId WHERE l.accountId = :accountId AND (i.revision > i.serverRevision OR NOT EXISTS
           (SELECT 1 FROM sync_entities s WHERE s.type = 'item' AND s.id = i.id AND s.base IS NOT NULL)) ORDER BY i.createdAt""",
    )
    suspend fun dirtyItems(accountId: String): List<ShoppingItemEntity>

    @Query(
        """SELECT c.* FROM categories c WHERE c.accountId = :accountId AND (c.revision > c.serverRevision OR NOT EXISTS
           (SELECT 1 FROM sync_entities s WHERE s.type = 'category' AND s.id = c.id AND s.base IS NOT NULL)) ORDER BY c.createdAt""",
    )
    suspend fun dirtyCategories(accountId: String): List<CategoryEntity>

    @Query(
        """SELECT t.* FROM tags t WHERE t.accountId = :accountId AND (t.revision > t.serverRevision OR NOT EXISTS
           (SELECT 1 FROM sync_entities s WHERE s.type = 'tag' AND s.id = t.id AND s.base IS NOT NULL)) ORDER BY t.createdAt""",
    )
    suspend fun dirtyTags(accountId: String): List<TagEntity>

    /** Number of rows waiting to be pushed (blocked rows included; subtract [observeBlockedCount]). */
    @Query(
        """SELECT
           (SELECT COUNT(*) FROM lists l WHERE l.accountId = :accountId AND (l.revision > l.serverRevision OR NOT EXISTS
              (SELECT 1 FROM sync_entities s WHERE s.type = 'list' AND s.id = l.id AND s.base IS NOT NULL)))
         + (SELECT COUNT(*) FROM items i JOIN lists l ON l.id = i.listId WHERE l.accountId = :accountId AND (i.revision > i.serverRevision OR NOT EXISTS
              (SELECT 1 FROM sync_entities s WHERE s.type = 'item' AND s.id = i.id AND s.base IS NOT NULL)))
         + (SELECT COUNT(*) FROM categories c WHERE c.accountId = :accountId AND (c.revision > c.serverRevision OR NOT EXISTS
              (SELECT 1 FROM sync_entities s WHERE s.type = 'category' AND s.id = c.id AND s.base IS NOT NULL)))
         + (SELECT COUNT(*) FROM tags t WHERE t.accountId = :accountId AND (t.revision > t.serverRevision OR NOT EXISTS
              (SELECT 1 FROM sync_entities s WHERE s.type = 'tag' AND s.id = t.id AND s.base IS NOT NULL)))""",
    )
    fun observeDirtyCount(accountId: String): Flow<Int>

    @Query("UPDATE lists SET serverRevision = :revision WHERE id = :id AND serverRevision < :revision")
    suspend fun markListClean(id: String, revision: Int)

    @Query("UPDATE items SET serverRevision = :revision WHERE id = :id AND serverRevision < :revision")
    suspend fun markItemClean(id: String, revision: Int)

    @Query("UPDATE categories SET serverRevision = :revision WHERE id = :id AND serverRevision < :revision")
    suspend fun markCategoryClean(id: String, revision: Int)

    @Query("UPDATE tags SET serverRevision = :revision WHERE id = :id AND serverRevision < :revision")
    suspend fun markTagClean(id: String, revision: Int)

    @Query("SELECT * FROM sync_entities WHERE type = :type AND id = :id")
    suspend fun entity(type: String, id: String): SyncEntityEntity?

    @Upsert
    suspend fun upsertEntity(entity: SyncEntityEntity)

    @Query("DELETE FROM sync_entities WHERE type = :type AND id = :id")
    suspend fun deleteEntity(type: String, id: String)

    @Query("DELETE FROM sync_entities")
    suspend fun clearEntities()

    /** The bookkeeping of every row that belongs to [accountId]; call before those rows are detached or removed. */
    @Query(
        """DELETE FROM sync_entities WHERE
           (type = 'list' AND id IN (SELECT id FROM lists WHERE accountId = :accountId)) OR
           (type = 'item' AND id IN (SELECT i.id FROM items i JOIN lists l ON l.id = i.listId WHERE l.accountId = :accountId)) OR
           (type = 'category' AND id IN (SELECT id FROM categories WHERE accountId = :accountId)) OR
           (type = 'tag' AND id IN (SELECT id FROM tags WHERE accountId = :accountId))""",
    )
    suspend fun deleteEntitiesOfAccount(accountId: String)

    @Query("SELECT COUNT(*) FROM sync_entities WHERE blocked = 1")
    fun observeBlockedCount(): Flow<Int>

    @Query("UPDATE sync_entities SET blocked = 0, failCount = 0, nextAttemptAt = 0 WHERE blocked = 1")
    suspend fun unblockAll()

    /** Rows purged locally leave no bookkeeping behind. */
    @Query(
        """DELETE FROM sync_entities WHERE
           (type = 'list' AND id NOT IN (SELECT id FROM lists)) OR
           (type = 'item' AND id NOT IN (SELECT id FROM items)) OR
           (type = 'category' AND id NOT IN (SELECT id FROM categories)) OR
           (type = 'tag' AND id NOT IN (SELECT id FROM tags))""",
    )
    suspend fun deleteOrphanEntities()

    /** Every account id that owns data on this device. */
    @Query(
        """SELECT accountId FROM lists WHERE accountId IS NOT NULL
           UNION SELECT accountId FROM categories WHERE accountId IS NOT NULL
           UNION SELECT accountId FROM tags WHERE accountId IS NOT NULL""",
    )
    suspend fun allAccountIds(): List<String>

    @Query("SELECT value FROM sync_state WHERE accountId = :accountId AND `key` = :key")
    suspend fun state(accountId: String, key: String): String?

    @Query("SELECT value FROM sync_state WHERE accountId = :accountId AND `key` = :key")
    fun observeState(accountId: String, key: String): Flow<String?>

    @Upsert
    suspend fun putState(state: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE accountId = :accountId")
    suspend fun clearState(accountId: String)

    @Query("DELETE FROM sync_state WHERE accountId = :accountId AND `key` LIKE 'cursor.%'")
    suspend fun clearCursors(accountId: String)
}
