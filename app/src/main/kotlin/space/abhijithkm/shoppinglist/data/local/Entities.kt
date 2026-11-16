package space.abhijithkm.shoppinglist.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import space.abhijithkm.shoppinglist.core.model.ListOrigin
import space.abhijithkm.shoppinglist.core.model.MemberRole

/*
 * Conventions shared by all synced entities: ids are client-generated UUIDs, timestamps are epoch
 * milliseconds, `revision` counts local edits and `serverRevision` is the last revision AppHub
 * acknowledged (0 for guest data), and `deletedAt` is a tombstone (rows are hard-deleted only after
 * the tombstone is no longer needed).
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
    /** A single emoji, or null. */
    val icon: String? = null,
    /** ARGB, or null for the default look. */
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
    /** A built-in category key (e.g. "dairy") or the id of a custom category; null means none. */
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

/**
 * A custom category, or an override of a built-in one: when [id] is a built-in key (e.g. "dairy")
 * the row only carries the user's rename and/or colour, and deleting it resets the built-in.
 * [listId] is null for guest data; shared lists use list-scoped categories later.
 */
@Entity(tableName = "categories", indices = [Index(value = ["accountId"])])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val accountId: String? = null,
    val listId: String? = null,
    val name: String? = null,
    val color: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Int = 0,
    val serverRevision: Int = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "tags", indices = [Index(value = ["accountId"])])
data class TagEntity(
    @PrimaryKey val id: String,
    val accountId: String? = null,
    val listId: String? = null,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Int = 0,
    val serverRevision: Int = 0,
    val deletedAt: Long? = null,
)

/**
 * Sync bookkeeping for one synced row (docs/SYNC_STRATEGY.md). [base] is the canonical JSON of the synced
 * fields as the server last agreed on them: it is the common ancestor for the field-level diff (what to
 * push) and the three-way merge (what to keep when the server changed too). A null [base] means the row
 * has never reached the server (a create is pending). Rows are *dirty* when `revision > serverRevision`
 * or when they belong to an account but have no [base].
 */
@Entity(tableName = "sync_entities", primaryKeys = ["type", "id"])
data class SyncEntityEntity(
    /** One of [SyncType]. */
    val type: String,
    /** The local id of the list, item, category or tag. */
    val id: String,
    val base: String? = null,
    val failCount: Int = 0,
    /** Epoch ms before which the engine does not retry this row (per-row backoff). */
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    /** Failed too often for the same content: waits until the row is edited again or the user taps Retry. */
    val blocked: Boolean = false,
    /** The row's revision when it was blocked; a higher revision means a new edit, so it is tried again. */
    val blockedAtRevision: Int = 0,
)

/** Small key-value store for sync cursors and the last run summary, per account. */
@Entity(tableName = "sync_state", primaryKeys = ["accountId", "key"])
data class SyncStateEntity(
    val accountId: String,
    val key: String,
    val value: String,
)

/** Values of [SyncEntityEntity.type]. */
object SyncType {
    const val LIST = "list"
    const val ITEM = "item"
    const val CATEGORY = "category"
    const val TAG = "tag"
}

/** Many-to-many link between items and tags. [listId] is denormalised for list-scoped sync later. */
@Entity(
    tableName = "item_tags",
    primaryKeys = ["itemId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = ShoppingItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tagId"])],
)
data class ItemTagCrossRef(
    val itemId: String,
    val tagId: String,
    val listId: String,
)
