package space.abhijithkm.shoppinglist.widget

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import space.abhijithkm.shoppinglist.core.util.formatQuantityAndUnit
import space.abhijithkm.shoppinglist.data.local.ShoppingItemEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity

/** One line of the widget: an item with its tick state. */
data class WidgetRow(val itemId: String, val label: String, val bought: Boolean)

/** What one widget instance shows. */
sealed interface WidgetModel {
    data class Content(
        val listId: String,
        val listName: String,
        val openCount: Int,
        val totalCount: Int,
        val rows: List<WidgetRow>,
    ) : WidgetModel

    /** The list is deleted, archived or gone: the widget says so and lets the owner choose another. */
    data object Unavailable : WidgetModel

    /** No list was chosen for this widget yet. */
    data object NotConfigured : WidgetModel
}

/**
 * The widget content for [list]: open items first, then bought ones, each group in manual order, at most [maxRows] lines.
 * The counts cover every live item, not only the shown rows.
 */
fun buildWidgetModel(list: ShoppingListEntity?, items: List<ShoppingItemEntity>, maxRows: Int = 30): WidgetModel {
    if (list == null || list.deletedAt != null || list.isArchived) return WidgetModel.Unavailable
    val live = items.filter { it.deletedAt == null }
    val ordered = live.filter { !it.isBought }.sortedBy { it.sortOrder } + live.filter { it.isBought }.sortedBy { it.sortOrder }
    return WidgetModel.Content(
        listId = list.id,
        listName = list.name,
        openCount = live.count { !it.isBought },
        totalCount = live.size,
        rows = ordered.take(maxRows).map { WidgetRow(it.id, label(it), it.isBought) },
    )
}

/**
 * The widget model of one list as a flow, so a widget that is already showing follows the data. Glance keeps a widget's
 * composition alive for a while and only recomposes it on update, so the model must be read inside the composition from a
 * flow, never computed once before it.
 */
fun widgetModels(
    list: Flow<ShoppingListEntity?>,
    items: Flow<List<ShoppingItemEntity>>,
    maxRows: Int = 30,
): Flow<WidgetModel> = combine(list, items) { l, i -> buildWidgetModel(l, i, maxRows) }

private fun label(item: ShoppingItemEntity): String =
    listOfNotNull(formatQuantityAndUnit(item.quantity, item.unit), item.name).joinToString(" ")
