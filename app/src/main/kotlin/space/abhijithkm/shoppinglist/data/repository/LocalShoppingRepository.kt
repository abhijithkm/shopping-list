package space.abhijithkm.shoppinglist.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.core.util.IdGenerator
import space.abhijithkm.shoppinglist.data.local.ListWithCounts
import space.abhijithkm.shoppinglist.data.local.ShoppingListDao
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity

/** UI-facing repository: reads and writes Room only. It never touches the network. */
@Singleton
class LocalShoppingRepository @Inject constructor(
    private val listDao: ShoppingListDao,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    fun observeLists(): Flow<List<ListSummary>> =
        listDao.observeActiveWithCounts().map { rows -> rows.map { it.toSummary() } }

    /** Creates a local (guest) list and returns its id. */
    suspend fun createList(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "List name must not be blank" }
        val now = clock.nowMillis()
        val id = ids.newId()
        listDao.upsert(
            ShoppingListEntity(
                id = id,
                name = trimmed,
                sortKey = now.toDouble(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }
}

private fun ListWithCounts.toSummary() = ListSummary(
    id = list.id,
    name = list.name,
    color = list.color,
    isPinned = list.isPinned,
    role = list.role,
    totalCount = totalCount,
    boughtCount = boughtCount,
    updatedAt = list.updatedAt,
)
