package space.abhijithkm.shoppinglist.surfaces

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity

class OpenListResolveTest {
    private fun list(id: String = "l1", archived: Boolean = false, deletedAt: Long? = null) =
        ShoppingListEntity(id = id, name = id, isArchived = archived, createdAt = 1L, updatedAt = 1L, deletedAt = deletedAt)

    @Test
    fun aLiveListIsShown() = runTest {
        assertEquals(OpenListOutcome.Show("l1"), resolveOpenList("l1") { list() })
    }

    @Test
    fun anArchivedListIsStillShown() = runTest {
        assertEquals(OpenListOutcome.Show("l1"), resolveOpenList("l1") { list(archived = true) })
    }

    @Test
    fun aDeletedListIsUnavailable() = runTest {
        assertEquals(OpenListOutcome.Unavailable, resolveOpenList("l1") { list(deletedAt = 5L) })
    }

    @Test
    fun anUnknownListIsUnavailable() = runTest {
        assertEquals(OpenListOutcome.Unavailable, resolveOpenList("nope") { null })
    }
}
