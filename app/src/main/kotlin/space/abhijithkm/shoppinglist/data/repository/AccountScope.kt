package space.abhijithkm.shoppinglist.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import space.abhijithkm.shoppinglist.data.session.SessionManager

/**
 * Which account new rows belong to. Signed out (guest) it is null and rows stay purely local; while signed in
 * (or when the session needs a new sign-in, which does not change whose data this is) it is the account id,
 * and new lists, categories and tags are created as account data that the sync engine pushes.
 */
fun interface AccountScope {
    fun activeAccountId(): String?

    companion object {
        /** Always signed out: used by tests and previews. */
        val Guest = AccountScope { null }
    }
}

@Singleton
class SessionAccountScope @Inject constructor(private val session: SessionManager) : AccountScope {
    override fun activeAccountId(): String? = session.account?.userId
}
