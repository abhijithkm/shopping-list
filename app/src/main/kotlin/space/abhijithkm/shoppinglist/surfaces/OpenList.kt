package space.abhijithkm.shoppinglist.surfaces

import android.content.Context
import android.content.Intent
import space.abhijithkm.shoppinglist.MainActivity
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity

/** Intent extra naming the list the app should open. Set by the widget, the reminder and both notification buttons. */
const val EXTRA_OPEN_LIST = "open_list"

/** A request to open a list. [token] makes a repeated request for the same list a new request again. */
data class OpenListRequest(val listId: String, val token: Long)

/** An explicit intent that brings the app to the front and asks it to open [listId]. */
fun openListIntent(context: Context, listId: String): Intent =
    Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_OPEN_LIST, listId)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/** The request carried by this intent, or null when it has none (a normal launch). */
fun Intent.toOpenListRequest(token: Long): OpenListRequest? =
    getStringExtra(EXTRA_OPEN_LIST)?.takeIf { it.isNotBlank() }?.let { OpenListRequest(it, token) }

sealed interface OpenListOutcome {
    data class Show(val listId: String) : OpenListOutcome

    /** The list is gone (deleted or never existed): the app stays on the Lists screen and says so. */
    data object Unavailable : OpenListOutcome
}

/** A live list opens (an archived one too, it can still be read); a deleted or unknown one does not. */
suspend fun resolveOpenList(listId: String, find: suspend (String) -> ShoppingListEntity?): OpenListOutcome {
    val list = find(listId)
    return if (list != null && list.deletedAt == null) OpenListOutcome.Show(listId) else OpenListOutcome.Unavailable
}
