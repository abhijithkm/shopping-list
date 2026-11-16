package space.abhijithkm.shoppinglist.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import space.abhijithkm.shoppinglist.core.model.ListOrigin
import space.abhijithkm.shoppinglist.core.model.MemberRole

/**
 * A shopping list. Timestamps are epoch milliseconds. `revision` counts local edits;
 * `serverRevision` is the last revision acknowledged by AppHub (0 for guest lists). Both are used
 * by the sync engine from Phase 3 on. `deletedAt` is a tombstone.
 */
@Entity(
    tableName = "lists",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["isArchived", "isPinned", "updatedAt"]),
    ],
)
data class ShoppingListEntity(
    @PrimaryKey val id: String,
    val accountId: String? = null,
    val origin: ListOrigin = ListOrigin.LOCAL,
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val color: Int? = null,
    val isArchived: Boolean = false,
    val isPinned: Boolean = false,
    val sortKey: Double = 0.0,
    val role: MemberRole = MemberRole.OWNER,
    val ownerName: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Int = 0,
    val serverRevision: Int = 0,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = ShoppingListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["listId", "deletedAt", "isBought", "sortOrder"])],
)
data class ShoppingItemEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val categoryKey: String? = null,
    val notes: String? = null,
    val isBought: Boolean = false,
    val boughtAt: Long? = null,
    val sortOrder: Double = 0.0,
    val createdBy: String? = null,
    val updatedBy: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Int = 0,
    val serverRevision: Int = 0,
    val deletedAt: Long? = null,
)
