package space.abhijithkm.shoppinglist.data.repository

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.abhijithkm.shoppinglist.core.model.Category
import space.abhijithkm.shoppinglist.core.model.DefaultCategory
import space.abhijithkm.shoppinglist.core.model.Item
import space.abhijithkm.shoppinglist.core.model.ItemUpdate
import space.abhijithkm.shoppinglist.core.model.Limits
import space.abhijithkm.shoppinglist.core.model.ListOrigin
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.core.model.Tag
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.core.util.IdGenerator
import space.abhijithkm.shoppinglist.core.util.QuickAddParser
import space.abhijithkm.shoppinglist.core.util.SortOrder
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.local.CategoryEntity
import space.abhijithkm.shoppinglist.data.local.ItemTagCrossRef
import space.abhijithkm.shoppinglist.data.local.ItemWithTags
import space.abhijithkm.shoppinglist.data.local.ListWithCounts
import space.abhijithkm.shoppinglist.data.local.ShoppingItemEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity
import space.abhijithkm.shoppinglist.data.local.TagEntity

/** Colour given to a new custom category when the user does not pick one (ARGB). */
const val DEFAULT_CUSTOM_CATEGORY_COLOR: Int = 0xFF607D8B.toInt()

/**
 * UI-facing repository: reads and writes Room only. It never touches the network (sync uses a
 * separate remote repository). Every mutation bumps `revision`/`updatedAt` through the DAOs, and
 * deletions are tombstones so the UI can offer undo and the sync engine can later propagate them.
 */
@Singleton
class LocalShoppingRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val accountScope: AccountScope = AccountScope.Guest,
) {
    private val lists = db.listDao()
    private val items = db.itemDao()
    private val categories = db.categoryDao()
    private val tags = db.tagDao()
    private val sync = db.syncDao()

    // ---------------------------------------------------------------- lists

    fun observeLists(archived: Boolean = false): Flow<List<ListSummary>> =
        lists.observeWithCounts(archived).map { rows -> rows.map { it.toSummary() } }

    fun observeList(id: String): Flow<ListSummary?> =
        lists.observeOneWithCounts(id).map { it?.toSummary() }

    /** How many lists exist only on this device (not in any account). */
    fun observeGuestListCount(): Flow<Int> = lists.observeGuestListCount()

    /** Creates a list and returns its id: a guest list while signed out, an account list (synced) while signed in. */
    suspend fun createList(name: String, color: Int? = null, icon: String? = null): String {
        val cleaned = requireName(name, Limits.LIST_NAME, "List name")
        val now = clock.nowMillis()
        val id = idGenerator.newId()
        val account = accountScope.activeAccountId()
        lists.upsert(
            ShoppingListEntity(
                id = id,
                accountId = account,
                origin = if (account != null) ListOrigin.CLOUD else ListOrigin.LOCAL,
                name = cleaned,
                icon = cleanIcon(icon),
                color = color,
                sortKey = now.toDouble(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    suspend fun renameList(id: String, name: String) =
        lists.rename(id, requireName(name, Limits.LIST_NAME, "List name"), clock.nowMillis())

    suspend fun setListPinned(id: String, pinned: Boolean) = lists.setPinned(id, pinned, clock.nowMillis())

    suspend fun setListArchived(id: String, archived: Boolean) = lists.setArchived(id, archived, clock.nowMillis())

    suspend fun setListAppearance(id: String, color: Int?, icon: String?) =
        lists.setAppearance(id, color, cleanIcon(icon), clock.nowMillis())

    /** Soft delete: the list disappears but can be restored (undo) until the tombstone is purged. */
    suspend fun deleteList(id: String) = lists.softDelete(id, clock.nowMillis())

    suspend fun restoreList(id: String) = lists.restore(id, clock.nowMillis())

    // ---------------------------------------------------------------- items

    /** All live items of a list in manual order, bought ones included; the UI splits the sections. */
    fun observeItems(listId: String): Flow<List<Item>> =
        items.observeForList(listId).map { rows -> rows.map { it.toModel() } }

    /**
     * Quick add: parses "2 kg milk" style input and appends the item to the open items.
     * Returns the new id, or null when [input] is blank.
     */
    suspend fun addItem(listId: String, input: String): String? {
        val parsed = QuickAddParser.parse(input) ?: return null
        val now = clock.nowMillis()
        val id = idGenerator.newId()
        db.withTransaction {
            val order = SortOrder.after(items.maxOpenSortOrder(listId))
            items.upsert(
                ShoppingItemEntity(
                    id = id,
                    listId = listId,
                    name = parsed.name.take(Limits.ITEM_NAME),
                    quantity = parsed.quantity,
                    unit = parsed.unit?.take(Limits.UNIT),
                    sortOrder = order,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            lists.touch(listId, now)
        }
        return id
    }

    suspend fun updateItem(update: ItemUpdate) {
        val name = requireName(update.name, Limits.ITEM_NAME, "Item name")
        val now = clock.nowMillis()
        db.withTransaction {
            val current = items.getById(update.id) ?: return@withTransaction
            items.upsert(
                current.copy(
                    name = name,
                    quantity = update.quantity?.takeIf { it > 0.0 && it <= Limits.MAX_QUANTITY },
                    unit = update.unit?.trim()?.takeIf { it.isNotEmpty() }?.take(Limits.UNIT),
                    categoryKey = update.categoryKey?.takeIf { isKnownCategory(it) },
                    notes = update.notes?.trim()?.takeIf { it.isNotEmpty() }?.take(Limits.ITEM_NOTES),
                    updatedAt = now,
                    revision = current.revision + 1,
                ),
            )
            items.deleteTagLinks(current.id)
            val validTagIds = tags.existingIds(update.tagIds.toList())
            items.insertTagLinks(validTagIds.map { ItemTagCrossRef(current.id, it, current.listId) })
            lists.touch(current.listId, now)
        }
    }

    suspend fun setItemBought(id: String, bought: Boolean) {
        val now = clock.nowMillis()
        db.withTransaction {
            val current = items.getById(id) ?: return@withTransaction
            if (current.isBought == bought) return@withTransaction
            items.setBought(id, bought, if (bought) now else null, now)
            lists.touch(current.listId, now)
        }
    }

    suspend fun deleteItem(id: String) {
        val now = clock.nowMillis()
        db.withTransaction {
            val current = items.getById(id) ?: return@withTransaction
            items.softDelete(id, now)
            lists.touch(current.listId, now)
        }
    }

    suspend fun restoreItem(id: String) {
        val now = clock.nowMillis()
        db.withTransaction {
            val current = items.getById(id) ?: return@withTransaction
            items.restore(id, now)
            lists.touch(current.listId, now)
        }
    }

    /**
     * Places [itemId] between [aboveId] and [belowId], the open items that now surround it (either
     * may be null at the ends). Only the moved item's row changes unless the gap between its
     * neighbours is exhausted, in which case the open items are renumbered once.
     */
    suspend fun moveItem(itemId: String, aboveId: String?, belowId: String?) {
        val now = clock.nowMillis()
        db.withTransaction {
            val moved = items.getById(itemId) ?: return@withTransaction
            var above = aboveId?.let { items.getById(it)?.sortOrder }
            var below = belowId?.let { items.getById(it)?.sortOrder }
            var target = SortOrder.between(above, below)
            if (target == null) {
                renumberOpenItems(moved.listId, now)
                above = aboveId?.let { items.getById(it)?.sortOrder }
                below = belowId?.let { items.getById(it)?.sortOrder }
                target = SortOrder.between(above, below) ?: SortOrder.after(items.maxOpenSortOrder(moved.listId))
            }
            items.setSortOrder(itemId, target, now)
            lists.touch(moved.listId, now)
        }
    }

    /** Deletes all bought items of a list and returns their ids so the UI can offer undo. */
    suspend fun clearCompleted(listId: String): List<String> {
        val now = clock.nowMillis()
        return db.withTransaction {
            val doneIds = items.boughtIds(listId)
            if (doneIds.isNotEmpty()) {
                items.softDeleteMany(doneIds, now)
                lists.touch(listId, now)
            }
            doneIds
        }
    }

    suspend fun restoreItems(itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        val now = clock.nowMillis()
        db.withTransaction {
            items.restoreMany(itemIds, now)
            items.listIdsOf(itemIds).forEach { lists.touch(it, now) }
        }
    }

    // ----------------------------------------------------------- categories

    /** The 11 built-ins (with the user's renames/colours applied) followed by custom categories. */
    fun observeCategories(): Flow<List<Category>> = categories.observeAll().map { rows ->
        val byId = rows.associateBy { it.id }
        val builtIns = DefaultCategory.entries.map { built ->
            val override = byId[built.key]
            Category(
                key = built.key,
                name = override?.name,
                color = override?.color ?: built.colorArgb.toInt(),
                defaultCategory = built,
                isCustomized = override != null && (override.name != null || override.color != null),
            )
        }
        val custom = rows
            .filter { DefaultCategory.fromKey(it.id) == null }
            .map {
                Category(
                    key = it.id,
                    name = it.name.orEmpty(),
                    color = it.color ?: DEFAULT_CUSTOM_CATEGORY_COLOR,
                    defaultCategory = null,
                    isCustomized = false,
                )
            }
        builtIns + custom
    }

    suspend fun createCategory(name: String, color: Int? = null): String {
        val cleaned = requireName(name, Limits.CATEGORY_NAME, "Category name")
        val now = clock.nowMillis()
        val id = idGenerator.newId()
        categories.upsert(
            CategoryEntity(
                id = id,
                accountId = accountScope.activeAccountId(),
                name = cleaned,
                color = color ?: DEFAULT_CUSTOM_CATEGORY_COLOR,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    /**
     * Renames and/or recolours a category; a null argument leaves that attribute as it is. For a
     * built-in category this stores an override row, so built-ins stay identical on every device.
     */
    suspend fun updateCategory(id: String, name: String? = null, color: Int? = null) {
        val cleanedName = name?.let { requireName(it, Limits.CATEGORY_NAME, "Category name") }
        val now = clock.nowMillis()
        db.withTransaction {
            val existing = categories.getById(id)?.takeIf { it.deletedAt == null }
            if (DefaultCategory.fromKey(id) == null && existing == null) return@withTransaction
            val base = existing ?: CategoryEntity(
                id = id,
                accountId = accountScope.activeAccountId(),
                createdAt = now,
                updatedAt = now,
            )
            categories.upsert(
                base.copy(
                    // Editing while signed in makes the category the account's, so the edit is not left stranded as guest data.
                    accountId = accountScope.activeAccountId() ?: base.accountId,
                    name = cleanedName ?: existing?.name,
                    color = color ?: existing?.color,
                    updatedAt = now,
                    revision = (existing?.revision ?: 0) + 1,
                    deletedAt = null,
                ),
            )
        }
    }

    /**
     * Deletes a custom category (its items become uncategorised) or resets a built-in one to its
     * original name and colour.
     */
    suspend fun deleteCategory(id: String) {
        val now = clock.nowMillis()
        db.withTransaction {
            categories.softDelete(id, now)
            if (DefaultCategory.fromKey(id) == null) items.clearCategory(id, now)
        }
    }

    // ----------------------------------------------------------------- tags

    fun observeTags(): Flow<List<Tag>> = tags.observeAll().map { rows -> rows.map { Tag(it.id, it.name) } }

    /** Creates a tag, or returns the id of the existing tag with the same name (case-insensitive). */
    suspend fun createTag(name: String): String {
        val cleaned = requireName(name, Limits.TAG_NAME, "Tag name")
        val now = clock.nowMillis()
        return db.withTransaction {
            tags.findByName(cleaned)?.id ?: idGenerator.newId().also { id ->
                tags.upsert(
                    TagEntity(
                        id = id,
                        accountId = accountScope.activeAccountId(),
                        name = cleaned,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    /** Returns false (and changes nothing) when another tag already has that name. */
    suspend fun renameTag(id: String, name: String): Boolean {
        val cleaned = requireName(name, Limits.TAG_NAME, "Tag name")
        val now = clock.nowMillis()
        return db.withTransaction {
            val clash = tags.findByName(cleaned)
            if (clash != null && clash.id != id) {
                false
            } else {
                tags.rename(id, cleaned, now)
                true
            }
        }
    }

    suspend fun deleteTag(id: String) {
        val now = clock.nowMillis()
        db.withTransaction {
            tags.softDelete(id, now)
            // The tag set of every item that carried it changes, so those items sync again.
            items.bumpItemsOfTag(id, now)
            items.deleteLinksForTag(id)
        }
    }

    // ---------------------------------------------------------- housekeeping

    /**
     * Permanently removes tombstones older than [cutoffMillis] (the undo window is long over). Guest tombstones go
     * as soon as they expire; account tombstones only once the server has acknowledged them (not dirty), so a
     * delete that has not synced yet is never forgotten.
     */
    suspend fun purgeExpiredTombstones(cutoffMillis: Long) {
        db.withTransaction {
            items.purgeLocalTombstones(cutoffMillis)
            lists.purgeLocalTombstones(cutoffMillis)
            categories.purgeTombstones(cutoffMillis)
            tags.purgeTombstones(cutoffMillis)
            items.purgeSyncedTombstones(cutoffMillis)
            lists.purgeSyncedTombstones(cutoffMillis)
            categories.purgeSyncedTombstones(cutoffMillis)
            tags.purgeSyncedTombstones(cutoffMillis)
            sync.deleteOrphanEntities()
        }
    }

    // -------------------------------------------------------------- helpers

    private suspend fun renumberOpenItems(listId: String, now: Long) {
        val open = items.openItemsInOrder(listId)
        SortOrder.renumbered(open.size).forEachIndexed { index, order ->
            items.setSortOrder(open[index].id, order, now)
        }
    }

    private suspend fun isKnownCategory(key: String): Boolean {
        if (DefaultCategory.fromKey(key) != null) return true
        val row = categories.getById(key)
        return row != null && row.deletedAt == null
    }

    private fun requireName(value: String, max: Int, what: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "$what must not be blank" }
        return trimmed.take(max)
    }

    private fun cleanIcon(icon: String?): String? =
        icon?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ICON_LENGTH }

    private companion object {
        const val MAX_ICON_LENGTH = 16
    }
}

private fun ListWithCounts.toSummary() = ListSummary(
    id = list.id,
    name = list.name,
    color = list.color,
    icon = list.icon,
    isPinned = list.isPinned,
    isArchived = list.isArchived,
    role = list.role,
    totalCount = totalCount,
    boughtCount = boughtCount,
    createdAt = list.createdAt,
    updatedAt = list.updatedAt,
)

private fun ItemWithTags.toModel() = Item(
    id = item.id,
    listId = item.listId,
    name = item.name,
    quantity = item.quantity,
    unit = item.unit,
    categoryKey = item.categoryKey,
    notes = item.notes,
    isBought = item.isBought,
    boughtAt = item.boughtAt,
    sortOrder = item.sortOrder,
    tags = tags.filter { it.deletedAt == null }.map { Tag(it.id, it.name) }.sortedBy { it.name.lowercase() },
)
