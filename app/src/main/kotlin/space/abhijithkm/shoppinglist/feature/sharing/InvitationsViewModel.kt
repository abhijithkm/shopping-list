package space.abhijithkm.shoppinglist.feature.sharing

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
import space.abhijithkm.shoppinglist.data.collab.CodePreview
import space.abhijithkm.shoppinglist.data.collab.CollabCommand
import space.abhijithkm.shoppinglist.data.collab.CollabFailure
import space.abhijithkm.shoppinglist.data.collab.CollabResult
import space.abhijithkm.shoppinglist.data.collab.CollabState
import space.abhijithkm.shoppinglist.data.collab.CollaborationRepository
import space.abhijithkm.shoppinglist.data.local.InvitationEntity
import space.abhijithkm.shoppinglist.data.local.JoinRequestEntity
import space.abhijithkm.shoppinglist.data.session.AccountInfo

data class ReceivedInvitationUi(val id: String, val listName: String?, val inviterName: String?, val expiresAt: Long?)

data class MyRequestUi(val id: String, val listName: String?)

/** What a code opens, as the server answered it. */
sealed interface PreviewUi {
    data object Idle : PreviewUi

    data object Busy : PreviewUi

    data class Found(val preview: CodePreview) : PreviewUi

    /** Unknown, expired, used, revoked or malformed: the server does not say which, on purpose. */
    data object Invalid : PreviewUi
}

sealed interface InvitationsMessage {
    data class Failed(val failure: CollabFailure) : InvitationsMessage

    data object Joined : InvitationsMessage

    data object Declined : InvitationsMessage

    data object RequestSent : InvitationsMessage

    data object RequestCancelled : InvitationsMessage
}

data class InvitationsUiState(
    val account: AccountInfo? = null,
    val invitations: List<ReceivedInvitationUi> = emptyList(),
    val requests: List<MyRequestUi> = emptyList(),
    val preview: PreviewUi = PreviewUi.Idle,
    val busy: Boolean = false,
    val message: InvitationsMessage? = null,
) {
    val signedIn: Boolean get() = account != null
}

private data class InvitationsLocal(
    val preview: PreviewUi = PreviewUi.Idle,
    val busy: Boolean = false,
    val message: InvitationsMessage? = null,
)

@HiltViewModel
class InvitationsViewModel @Inject constructor(
    private val collab: CollaborationRepository,
    state: CollabState,
) : ViewModel() {
    private val local = MutableStateFlow(InvitationsLocal())

    val uiState: StateFlow<InvitationsUiState> = combine(
        state.account,
        state.invitationsToMe(),
        state.myRequests(),
        local,
    ) { account, invitations, requests, own ->
        InvitationsUiState(
            account = account,
            invitations = invitations.map(InvitationEntity::toUi),
            requests = requests.map(JoinRequestEntity::toUi),
            preview = own.preview,
            busy = own.busy,
            message = own.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InvitationsUiState())

    init {
        viewModelScope.launch { collab.refresh() }
    }

    fun refresh() {
        viewModelScope.launch { collab.refresh() }
    }

    fun accept(invitationId: String) = answer(CollabCommand.AcceptInvite, invitationId, InvitationsMessage.Joined)

    fun decline(invitationId: String) = answer(CollabCommand.RejectInvite, invitationId, InvitationsMessage.Declined)

    fun cancelRequest(requestId: String) = answer(CollabCommand.CancelRequest, requestId, InvitationsMessage.RequestCancelled)

    /** Asks the server what [rawCode] opens. The code is not kept: only the answer is. */
    fun previewCode(rawCode: String) {
        if (local.value.busy) return
        local.update { it.copy(preview = PreviewUi.Busy, busy = true) }
        viewModelScope.launch {
            when (val result = collab.previewCode(rawCode)) {
                is CollabResult.Ok -> local.update { it.copy(preview = PreviewUi.Found(result.value), busy = false) }
                is CollabResult.Failed -> local.update {
                    when (result.failure) {
                        CollabFailure.InvalidCode -> it.copy(preview = PreviewUi.Invalid, busy = false)
                        else -> it.copy(preview = PreviewUi.Idle, busy = false, message = InvitationsMessage.Failed(result.failure))
                    }
                }
            }
        }
    }

    fun clearPreview() {
        local.update { it.copy(preview = PreviewUi.Idle) }
    }

    fun requestAccess() {
        val found = (local.value.preview as? PreviewUi.Found)?.preview ?: return
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = collab.requestAccess(found)) {
                is CollabResult.Ok -> local.update { it.copy(preview = PreviewUi.Idle, busy = false, message = InvitationsMessage.RequestSent) }
                is CollabResult.Failed -> local.update { it.copy(busy = false, message = InvitationsMessage.Failed(result.failure)) }
            }
        }
    }

    fun dismissMessage() {
        local.update { it.copy(message = null) }
    }

    private fun answer(command: CollabCommand, targetId: String, done: InvitationsMessage) {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = collab.send(command, targetId)) {
                is CollabResult.Ok -> local.update { it.copy(busy = false, message = done) }
                is CollabResult.Failed -> local.update { it.copy(busy = false, message = InvitationsMessage.Failed(result.failure)) }
            }
        }
    }
}

private fun InvitationEntity.toUi() = ReceivedInvitationUi(id, listName, inviterName, expiresAt)

private fun JoinRequestEntity.toUi() = MyRequestUi(id, listName)
