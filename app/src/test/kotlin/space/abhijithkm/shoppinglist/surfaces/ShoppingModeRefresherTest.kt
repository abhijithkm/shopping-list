package space.abhijithkm.shoppinglist.surfaces

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.abhijithkm.shoppinglist.data.local.ItemDao
import space.abhijithkm.shoppinglist.data.local.ShoppingItemEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingListDao
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity
import space.abhijithkm.shoppinglist.data.repository.NotificationPrefs

class ShoppingModeRefresherTest {
    private val lists = mockk<ShoppingListDao>()
    private val items = mockk<ItemDao>()
    private val notifier = mockk<SurfaceNotifier>(relaxed = true)
    private var shoppingModeOn = true

    private val prefs = object : NotificationPrefs {
        override suspend fun remindersEnabled() = true

        override suspend fun shoppingModeEnabled() = shoppingModeOn
    }

    private fun refresher() = ShoppingModeRefresher(lists, items, notifier, prefs)

    private fun list(id: String = "l1", deletedAt: Long? = null) =
        ShoppingListEntity(id = id, name = "List $id", createdAt = 1L, updatedAt = 1L, deletedAt = deletedAt)

    private fun item(listId: String = "l1", bought: Boolean = false) =
        ShoppingItemEntity(id = "i-$listId-$bought", listId = listId, name = "Milk", isBought = bought, createdAt = 1L, updatedAt = 1L)

    private fun given(vararg rows: Pair<ShoppingListEntity, List<ShoppingItemEntity>>, canNotify: Boolean = true) {
        coEvery { lists.shoppingModeLists() } returns rows.map { it.first }
        rows.forEach { (l, its) -> coEvery { items.allOfList(l.id) } returns its }
        every { notifier.canNotify() } returns canNotify
    }

    @Test
    fun postsEachListThatHasItemsLeftAndKeepsOnlyThose() = runTest {
        given(list("a") to listOf(item("a")), list("b") to listOf(item("b")))

        refresher().refresh()

        verify { notifier.postShoppingMode(match { it.listId == "a" }) }
        verify { notifier.postShoppingMode(match { it.listId == "b" }) }
        verify { notifier.cancelShoppingModeExcept(setOf("a", "b")) }
    }

    @Test
    fun aListWithNothingLeftIsNotPostedAndItsNotificationIsCancelled() = runTest {
        given(list("a") to listOf(item("a", bought = true)))

        refresher().refresh()

        verify(exactly = 0) { notifier.postShoppingMode(any()) }
        verify { notifier.cancelShoppingModeExcept(emptySet()) }
    }

    @Test
    fun aDeletedListIsNotWanted() = runTest {
        given(list("a", deletedAt = 5L) to listOf(item("a")))

        refresher().refresh()

        verify(exactly = 0) { notifier.postShoppingMode(any()) }
        verify { notifier.cancelShoppingModeExcept(emptySet()) }
    }

    @Test
    fun theGlobalSwitchOffClearsEverything() = runTest {
        shoppingModeOn = false
        given(list("a") to listOf(item("a")))

        refresher().refresh()

        verify(exactly = 0) { notifier.postShoppingMode(any()) }
        verify { notifier.cancelShoppingModeExcept(emptySet()) }
    }

    @Test
    fun deniedNotificationPermissionClearsEverything() = runTest {
        given(list("a") to listOf(item("a")), canNotify = false)

        refresher().refresh()

        verify(exactly = 0) { notifier.postShoppingMode(any()) }
        verify { notifier.cancelShoppingModeExcept(emptySet()) }
    }
}
