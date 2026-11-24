package space.abhijithkm.shoppinglist.data.collab

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.local.InvitationEntity
import space.abhijithkm.shoppinglist.data.local.JoinRequestEntity
import space.abhijithkm.shoppinglist.data.local.MemberEntity
import space.abhijithkm.shoppinglist.data.session.AccountInfo
import space.abhijithkm.shoppinglist.data.session.SessionManager
import space.abhijithkm.shoppinglist.data.session.SessionState

/**
 * What the screens show about sharing, always for the account that is signed in (or whose session needs a new sign-in:
 * the cached state is still theirs). Read-only: the rows are mirrored from the server by the sync engine.
 */
@Singleton
class CollabState @Inject constructor(
    db: AppDatabase,
    private val session: SessionManager,
    private val clock: Clock,
) {
    private val dao = db.collabDao()

    /** The account the cached state belongs to; null for a guest. */
    val account: Flow<AccountInfo?> = session.state.map { state ->
        when (state) {
            is SessionState.SignedIn -> state.account
            is SessionState.NeedsReauth -> state.account
            else -> null
        }
    }.distinctUntilChanged()

    /** Invitations to the account plus join requests to its lists: what needs an answer. */
    fun inboxCount(): Flow<Int> = forAccount(0) { dao.observeInboxCount(it, clock.nowMillis()) }

    fun members(listId: String): Flow<List<MemberEntity>> = forAccount(emptyList()) { dao.observeActiveMembers(it, listId) }

    fun invitationsToMe(): Flow<List<InvitationEntity>> = forAccount(emptyList()) { dao.observeInvitationsToMe(it, clock.nowMillis()) }

    fun invitationsForList(listId: String): Flow<List<InvitationEntity>> =
        forAccount(emptyList()) { dao.observeInvitationsForList(it, listId, clock.nowMillis()) }

    fun requestsForList(listId: String): Flow<List<JoinRequestEntity>> = forAccount(emptyList()) { dao.observeRequestsForList(it, listId) }

    fun myRequests(): Flow<List<JoinRequestEntity>> = forAccount(emptyList()) { dao.observeMyRequests(it) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> forAccount(none: T, block: (accountId: String) -> Flow<T>): Flow<T> =
        account.flatMapLatest { info -> if (info == null) flowOf(none) else block(info.userId) }
}
