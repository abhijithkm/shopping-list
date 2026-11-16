package space.abhijithkm.shoppinglist.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A list row plus live item counts (deleted items excluded). */
data class ListWithCounts(
    @Embedded val list: ShoppingListEntity,
    val totalCount: Int,
    val boughtCount: Int,
)

@Dao
interface ShoppingListDao {
    @Query(
        """
        SELECT l.*,
          (SELECT COUNT(*) FROM items i WHERE i.listId = l.id AND i.deletedAt IS NULL) AS totalCount,
          (SELECT COUNT(*) FROM items i WHERE i.listId = l.id AND i.deletedAt IS NULL AND i.isBought = 1) AS boughtCount
        FROM lists l
        WHERE l.deletedAt IS NULL AND l.isArchived = 0
        ORDER BY l.isPinned DESC, l.updatedAt DESC
        """,
    )
    fun observeActiveWithCounts(): Flow<List<ListWithCounts>>

    @Upsert
    suspend fun upsert(list: ShoppingListEntity)

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun getById(id: String): ShoppingListEntity?

    /** Hard delete (cascades to items). Only for purging a tombstone after the server acknowledged it, or a local list after undo expired. */
    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun purge(id: String)
}

@Dao
interface ItemDao {
    /** Open items first, then bought; each group by manual order. */
    @Query("SELECT * FROM items WHERE listId = :listId AND deletedAt IS NULL ORDER BY isBought ASC, sortOrder ASC")
    fun observeForList(listId: String): Flow<List<ShoppingItemEntity>>

    @Upsert
    suspend fun upsert(item: ShoppingItemEntity)
}
