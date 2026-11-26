package space.abhijithkm.shoppinglist.surfaces

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import space.abhijithkm.shoppinglist.data.local.ShoppingListDao

/** Lets the navigation host ask whether a list from a widget or notification can still be opened. */
@HiltViewModel
class OpenListViewModel @Inject constructor(
    private val lists: ShoppingListDao,
) : ViewModel() {
    suspend fun resolve(listId: String): OpenListOutcome = resolveOpenList(listId, lists::getById)
}
