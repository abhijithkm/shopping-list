package space.abhijithkm.shoppinglist.surfaces

import space.abhijithkm.shoppinglist.data.local.ShoppingItemEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity

/** What the "Shopping mode" notification of one list shows. "Open" means not bought yet. */
data class ShoppingModeContent(
    val listId: String,
    val listName: String,
    val openCount: Int,
    val totalCount: Int,
    val nextItems: List<String>,
)

/**
 * The notification content for [list], or null when there should be no notification: the list is missing, deleted
 * or archived, or nothing is left to buy (an empty list counts as nothing left). Deleted items are ignored; the
 * shown names are the open items in manual order, at most [maxItems].
 */
fun shoppingModeContent(list: ShoppingListEntity?, items: List<ShoppingItemEntity>, maxItems: Int = 5): ShoppingModeContent? {
    if (list == null || list.deletedAt != null || list.isArchived) return null
    val live = items.filter { it.deletedAt == null }
    val open = live.filter { !it.isBought }.sortedBy { it.sortOrder }
    if (open.isEmpty()) return null
    return ShoppingModeContent(
        listId = list.id,
        listName = list.name,
        openCount = open.size,
        totalCount = live.size,
        nextItems = open.take(maxItems).map { it.name },
    )
}
