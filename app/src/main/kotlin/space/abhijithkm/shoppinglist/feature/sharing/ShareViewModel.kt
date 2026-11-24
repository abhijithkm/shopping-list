package space.abhijithkm.shoppinglist.feature.sharing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.core.model.ListSummary
import space.abhijithkm.shoppinglist.data.collab.CollabCommand
import space.abhijithkm.shoppinglist.data.collab.CollabFailure
import space.abhijithkm.shoppinglist.data.collab.CollabResult
import space.abhijithkm.shoppinglist.data.collab.CollabState
import space.abhijithkm.shoppinglist.data.collab.CollaborationRepository
import space.abhijithkm.shoppinglist.data.collab.UserLookup
import space.abhijithkm.shoppinglist.data.local.InvitationEntity
import space.abhijithkm.shoppinglist.data.local.JoinRequestEntity
import space.abhijithkm.shoppinglist.data.local.MemberEntity
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository
import space.abhijithkm.shoppinglist.data.session.AccountInfo

/** One person on the list. [id] is the membership row, which the owner uses to remove an editor. */
data class MemberUi(val id: String, val name: String, val isOwner: Boolean, val isMe: Boolean)

/** An invitation the owner sent that has not been answered. [hint] is a masked name, never an address. */
data class InvitationUi(val id: String, val hint: String?, val expiresAt: Long?)

data class RequestUi(val id: String, val requesterName: String?)

/** The list's join code as this screen knows it. The text of a code exists only in [Issued], in memory. */
sealed interface CodeUi {
    data object None : CodeUi

    /** The server holds a live code; its text is not available any more (only a hash is kept). */
    data class Active(val expiresAt: Long?) : CodeUi

    class Issued(val code: String, val expiresAt: Long?) : CodeUi {
        override fun toString(): String = "Issued(<hidden>)"
    }
}

sealed interface LookupUi {
    data object Idle : LookupUi

    data object Busy : LookupUi

    data class Found(val lookup: UserLookup) : LookupUi

    data object NotFound : LookupUi
}

/** A short, one-time message for a snackbar. */
sealed interface ShareMessage {
    data class Failed(val failure: CollabFailure) : ShareMessage

    data class Done(val kind: DoneKind) : ShareMessage
}

enum class DoneKind { InviteSent, InvitationRevoked, CodeRevoked, RequestApproved, RequestRejected, MemberRemoved }

data class ShareUiState(
    val loading: Boolean = true,
    /** Null once loading has finished means the list is gone (deleted, or this account lost access). */
    val list: ListSummary? = null,
    val account: AccountInfo? = null,
    val members: List<MemberUi> = emptyList(),
    val invitations: List<InvitationUi> = emptyList(),
    val requests: List<RequestUi> = emptyList(),
    val code: CodeUi = CodeUi.None,
    val lookup: LookupUi = LookupUi.Idle,
    val busy: Boolean = false,
    val message: ShareMessage? = null,
) {
    val signedIn: Boolean get() = account != null
    val isOwner: Boolean get() = list?.canManage ?: false

    /** Sharing needs the account and a list that belongs to it. */
    val canShare: Boolean get() = signedIn && list?.isInAccount == true
}

/** The state that only this screen owns; the rest is read from the database. */
private data class ShareLocal(
    val code: CodeUi = CodeUi.None,
    val lookup: LookupUi = LookupUi.Idle,
    val busy: Boolean = false,
    val message: ShareMessage? = null,
)

@HiltViewModel
class ShareViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val collab: CollaborationRepository,
    private val state: CollabState,
    private val repository: LocalShoppingRepository,
) : ViewModel() {
    private val listId: String = checkNotNull(savedState["listId"])
    private val local = MutableStateFlow(ShareLocal())
    private var activeCodeId: String? = null

    val uiState: StateFlow<ShareUiState> = combine(
        repository.observeList(listId),
        state.account,
        combine(state.members(listId), state.invitationsForList(listId), state.requestsForList(listId)) { m, i, r -> Triple(m, i, r) },
        local,
    ) { list, account, (members, invitations, requests), own ->
        ShareUiState(
            loading = false,
            list = list,
            account = account,
            members = members.map { it.toUi(account?.userId) },
            invitations = invitations.map(InvitationEntity::toUi),
            requests = requests.map(JoinRequestEntity::toUi),
            code = own.code,
            lookup = own.lookup,
            busy = own.busy,
            message = own.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShareUiState())

    init {
        refresh()
    }

    /** Reads the server's current membership, invitation and request state, and the owner's live code. */
    fun refresh() {
        viewModelScope.launch {
            collab.refresh()
            loadActiveCode()
        }
    }

    fun findPerson(email: String) {
        if (local.value.busy) return
        local.update { it.copy(lookup = LookupUi.Busy, busy = true) }
        viewModelScope.launch {
            when (val result = collab.lookupUser(email)) {
                is CollabResult.Ok -> local.update {
                    it.copy(lookup = if (result.value.found) LookupUi.Found(result.value) else LookupUi.NotFound, busy = false)
                }
                is CollabResult.Failed -> local.update { it.copy(lookup = LookupUi.Idle, busy = false, message = ShareMessage.Failed(result.failure)) }
            }
        }
    }

    fun clearLookup() {
        local.update { it.copy(lookup = LookupUi.Idle) }
    }

    fun invite() {
        val found = (local.value.lookup as? LookupUi.Found)?.lookup ?: return
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = collab.invite(listId, found)) {
                is CollabResult.Ok -> local.update { it.copy(busy = false, lookup = LookupUi.Idle, message = ShareMessage.Done(DoneKind.InviteSent)) }
                is CollabResult.Failed -> local.update { it.copy(busy = false, message = ShareMessage.Failed(result.failure)) }
            }
        }
    }

    fun createCode() {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = collab.generateCode(listId)) {
                is CollabResult.Ok -> {
                    local.update { it.copy(code = CodeUi.Issued(result.value.code, result.value.expiresAtMs), busy = false) }
                    loadActiveCodeId()
                }
                is CollabResult.Failed -> local.update { it.copy(busy = false, message = ShareMessage.Failed(result.failure)) }
            }
        }
    }

    /** Called when the person leaves the screen or hides the code: the text is dropped, the server code stays active. */
    fun hideCode() {
        local.update { current -> (current.code as? CodeUi.Issued)?.let { current.copy(code = CodeUi.Active(it.expiresAt)) } ?: current }
    }

    fun revokeCode() {
        val id = activeCodeId ?: return
        viewModelScope.launch {
            if (command(CollabCommand.RevokeCode, id, DoneKind.CodeRevoked)) {
                activeCodeId = null
                local.update { it.copy(code = CodeUi.None) }
            }
        }
    }

    fun approve(requestId: String) = act(CollabCommand.ApproveJoin, requestId, DoneKind.RequestApproved)

    fun reject(requestId: String) = act(CollabCommand.RejectJoin, requestId, DoneKind.RequestRejected)

    fun revokeInvitation(invitationId: String) = act(CollabCommand.RevokeInvite, invitationId, DoneKind.InvitationRevoked)

    fun removeMember(memberRowId: String) = act(CollabCommand.RemoveMember, memberRowId, DoneKind.MemberRemoved)

    /** An editor leaves. The list disappears from this device with the next sync, which the command already triggers. */
    fun leave() {
        val mine = uiState.value.members.firstOrNull { it.isMe } ?: return
        act(CollabCommand.LeaveList, mine.id, null)
    }

    fun dismissMessage() {
        local.update { it.copy(message = null) }
    }

    // ------------------------------------------------------------------------------------------ plumbing

    private fun act(command: CollabCommand, targetId: String, done: DoneKind?) {
        viewModelScope.launch { command(command, targetId, done) }
    }

    private suspend fun command(command: CollabCommand, targetId: String, done: DoneKind?): Boolean {
        if (local.value.busy) return false
        local.update { it.copy(busy = true) }
        return when (val result = collab.send(command, targetId)) {
            is CollabResult.Ok -> {
                local.update { it.copy(busy = false, message = done?.let(ShareMessage::Done)) }
                true
            }
            is CollabResult.Failed -> {
                local.update { it.copy(busy = false, message = ShareMessage.Failed(result.failure)) }
                false
            }
        }
    }

    private suspend fun loadActiveCode() {
        val result = collab.activeCode(listId)
        if (result is CollabResult.Ok) {
            val active = result.value
            activeCodeId = active?.id
            local.update { current ->
                when {
                    // A code made on this screen keeps showing its text.
                    current.code is CodeUi.Issued && active != null -> current
                    active != null -> current.copy(code = CodeUi.Active(active.expiresAtMs))
                    else -> current.copy(code = CodeUi.None)
                }
            }
        }
    }

    private suspend fun loadActiveCodeId() {
        (collab.activeCode(listId) as? CollabResult.Ok)?.value?.let { activeCodeId = it.id }
    }

    override fun onCleared() {
        // The code text never outlives the screen.
        local.update { current -> (current.code as? CodeUi.Issued)?.let { current.copy(code = CodeUi.Active(it.expiresAt)) } ?: current }
    }
}

private fun MemberEntity.toUi(me: String?) = MemberUi(
    id = id,
    name = displayName?.takeIf { it.isNotBlank() } ?: "—",
    isOwner = role == "owner",
    isMe = memberId == me,
)

private fun InvitationEntity.toUi() = InvitationUi(id = id, hint = inviteeHint, expiresAt = expiresAt)

private fun JoinRequestEntity.toUi() = RequestUi(id = id, requesterName = requesterName)
