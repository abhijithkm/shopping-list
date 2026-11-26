package space.abhijithkm.shoppinglist.feature.sharing

import android.content.Intent
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.data.collab.CollabFailure
import space.abhijithkm.shoppinglist.data.collab.JoinCode
import space.abhijithkm.shoppinglist.ui.NoInsets

/** Everything the Share screen can ask for. Defaults are no-ops so tests only wire what they check. */
data class ShareActions(
    val onFindPerson: (email: String) -> Unit = {},
    val onClearLookup: () -> Unit = {},
    val onInvite: () -> Unit = {},
    val onCreateCode: () -> Unit = {},
    val onHideCode: () -> Unit = {},
    val onRevokeCode: () -> Unit = {},
    val onApprove: (requestId: String) -> Unit = {},
    val onReject: (requestId: String) -> Unit = {},
    val onRevokeInvitation: (invitationId: String) -> Unit = {},
    val onRemoveMember: (memberRowId: String) -> Unit = {},
    val onLeave: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

@Composable
fun ShareScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(), // wired to Hilt; the content below is stateless
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The list is gone (deleted, or this account was removed from it): nothing left to manage.
    LaunchedEffect(state.loading, state.list) {
        if (!state.loading && state.list == null) onBack()
    }
    val actions = remember(viewModel) {
        ShareActions(
            onFindPerson = viewModel::findPerson,
            onClearLookup = viewModel::clearLookup,
            onInvite = viewModel::invite,
            onCreateCode = viewModel::createCode,
            onHideCode = viewModel::hideCode,
            onRevokeCode = viewModel::revokeCode,
            onApprove = viewModel::approve,
            onReject = viewModel::reject,
            onRevokeInvitation = viewModel::revokeInvitation,
            onRemoveMember = viewModel::removeMember,
            onLeave = viewModel::leave,
            onDismissMessage = viewModel::dismissMessage,
        )
    }
    ShareContent(state = state, actions = actions, onBack = onBack, onOpenSettings = onOpenSettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareContent(
    state: ShareUiState,
    actions: ShareActions,
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    nowMillis: Long = System.currentTimeMillis(),
) {
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { messageText(it) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            actions.onDismissMessage()
        }
    }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    val listName = state.list?.name.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_title, listName), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = NoInsets,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.loading -> Unit
                !state.signedIn -> Gate(R.string.share_sign_in_needed, onOpenSettings)
                !state.canShare -> Gate(R.string.share_move_first, onOpenSettings)
                else -> {
                    MembersSection(state, onRemove = { removing = it }, onLeave = { leaving = true })
                    if (state.isOwner) {
                        HorizontalDivider()
                        InviteSection(state, actions)
                        if (state.invitations.isNotEmpty()) PendingInvitations(state, actions, nowMillis)
                        HorizontalDivider()
                        CodeSection(state, actions, listName, nowMillis)
                        if (state.requests.isNotEmpty()) RequestsSection(state, actions)
                    } else {
                        Text(
                            text = stringResource(R.string.editor_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    val toRemove = state.members.firstOrNull { it.id == removing }
    if (toRemove != null) {
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.member_remove_title, toRemove.name)) },
            text = { Text(stringResource(R.string.member_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onRemoveMember(toRemove.id)
                        removing = null
                    },
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.leave_title, listName)) },
            text = { Text(stringResource(R.string.leave_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onLeave()
                        leaving = false
                    },
                ) { Text(stringResource(R.string.leave)) }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Gate(@StringRes textRes: Int, onOpenSettings: () -> Unit) {
    Text(stringResource(textRes), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onOpenSettings) { Text(stringResource(R.string.share_open_settings)) }
}

@Composable
private fun SectionTitle(@StringRes titleRes: Int) {
    Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun MembersSection(state: ShareUiState, onRemove: (String) -> Unit, onLeave: () -> Unit) {
    SectionTitle(R.string.share_members)
    state.members.forEach { member ->
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("member_row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(member.name + if (member.isMe) " (" + stringResource(R.string.share_you) + ")" else "")
                Text(
                    text = stringResource(if (member.isOwner) R.string.role_owner else R.string.role_editor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isOwner && !member.isOwner) {
                IconButton(onClick = { onRemove(member.id) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.member_remove, member.name))
                }
            }
        }
    }
    if (!state.isOwner) {
        OutlinedButton(onClick = onLeave, enabled = !state.busy) { Text(stringResource(R.string.leave_list)) }
    }
}

@Composable
private fun InviteSection(state: ShareUiState, actions: ShareActions) {
    var email by rememberSaveable { mutableStateOf("") }
    SectionTitle(R.string.invite_title)
    OutlinedTextField(
        value = email,
        onValueChange = {
            email = it
            if (state.lookup !is LookupUi.Idle) actions.onClearLookup()
        },
        label = { Text(stringResource(R.string.invite_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (email.isNotBlank()) actions.onFindPerson(email) }),
        modifier = Modifier.fillMaxWidth().testTag("invite_email"),
    )
    when (val lookup = state.lookup) {
        LookupUi.Busy -> CircularProgressIndicator(Modifier.padding(4.dp))
        is LookupUi.Found -> {
            Text(stringResource(R.string.invite_found, lookup.lookup.hint.orEmpty()), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.onInvite, enabled = !state.busy) { Text(stringResource(R.string.invite_send)) }
        }
        LookupUi.NotFound -> Text(stringResource(R.string.invite_not_found), color = MaterialTheme.colorScheme.error)
        LookupUi.Idle -> OutlinedButton(
            onClick = { actions.onFindPerson(email) },
            enabled = email.isNotBlank() && !state.busy,
        ) { Text(stringResource(R.string.invite_find)) }
    }
    Text(
        text = stringResource(R.string.invite_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PendingInvitations(state: ShareUiState, actions: ShareActions, nowMillis: Long) {
    SectionTitle(R.string.invitations_waiting)
    state.invitations.forEach { invitation ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("invitation_row"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(invitation.hint ?: stringResource(R.string.invitation_invited_person))
                invitation.expiresAt?.let {
                    Text(
                        stringResource(R.string.invitation_expires, relative(it, nowMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { actions.onRevokeInvitation(invitation.id) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.invitation_cancel))
            }
        }
    }
}

@Composable
private fun CodeSection(state: ShareUiState, actions: ShareActions, listName: String, nowMillis: Long) {
    val context = LocalContext.current
    SectionTitle(R.string.code_title)
    Text(
        text = stringResource(R.string.code_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (val code = state.code) {
        is CodeUi.Issued -> {
            val shown = JoinCode.display(code.code)
            val shareMessage = stringResource(R.string.code_share_message, listName, shown)
            val codeDescription = stringResource(R.string.code_content_description, code.code.toList().joinToString(" "))
            Text(
                text = shown,
                fontSize = 32.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .testTag("issued_code")
                    .semantics { contentDescription = codeDescription },
            )
            Text(stringResource(R.string.code_shown_now), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareMessage)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                ) { Text(stringResource(R.string.code_share)) }
                OutlinedButton(onClick = actions.onRevokeCode, enabled = !state.busy) { Text(stringResource(R.string.code_revoke)) }
            }
            TextButton(onClick = actions.onHideCode) { Text(stringResource(R.string.close)) }
        }
        is CodeUi.Active -> {
            Text(
                text = stringResource(R.string.code_active, code.expiresAt?.let { relative(it, nowMillis) } ?: "—"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onCreateCode, enabled = !state.busy) { Text(stringResource(R.string.code_new)) }
                OutlinedButton(onClick = actions.onRevokeCode, enabled = !state.busy) { Text(stringResource(R.string.code_revoke)) }
            }
        }
        CodeUi.None -> Button(onClick = actions.onCreateCode, enabled = !state.busy) { Text(stringResource(R.string.code_create)) }
    }
}

@Composable
private fun RequestsSection(state: ShareUiState, actions: ShareActions) {
    HorizontalDivider()
    SectionTitle(R.string.requests_title)
    state.requests.forEach { request ->
        Column(Modifier.fillMaxWidth().testTag("request_row"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.request_row, request.requesterName ?: stringResource(R.string.someone)))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.onApprove(request.id) }, enabled = !state.busy) { Text(stringResource(R.string.approve)) }
                OutlinedButton(onClick = { actions.onReject(request.id) }, enabled = !state.busy) { Text(stringResource(R.string.decline)) }
            }
        }
    }
}

private fun relative(timeMillis: Long, nowMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(timeMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()

@Composable
private fun messageText(message: ShareMessage): String = when (message) {
    is ShareMessage.Failed -> stringResource(message.failure.messageRes())
    is ShareMessage.Done -> stringResource(
        when (message.kind) {
            DoneKind.InviteSent -> R.string.share_done_invite_sent
            DoneKind.InvitationRevoked -> R.string.share_done_invitation_revoked
            DoneKind.CodeRevoked -> R.string.share_done_code_revoked
            DoneKind.RequestApproved -> R.string.share_done_approved
            DoneKind.RequestRejected -> R.string.share_done_rejected
            DoneKind.MemberRemoved -> R.string.share_done_removed
        },
    )
}

/** The sentence for a refusal. Shared by the Share and Invitations screens. */
@StringRes
internal fun CollabFailure.messageRes(): Int = when (this) {
    CollabFailure.Forbidden -> R.string.collab_err_forbidden
    CollabFailure.NotAMember -> R.string.collab_err_not_member
    CollabFailure.NotFound -> R.string.collab_err_not_found
    CollabFailure.NotPending -> R.string.collab_err_not_pending
    CollabFailure.Expired -> R.string.collab_err_expired
    CollabFailure.InvalidCode -> R.string.collab_err_invalid_code
    CollabFailure.InvalidInput -> R.string.collab_err_invalid_input
    CollabFailure.AlreadyMember -> R.string.collab_err_already_member
    CollabFailure.AlreadyPending -> R.string.collab_err_already_pending
    CollabFailure.LimitReached -> R.string.collab_err_limit
    CollabFailure.TooManyAttempts -> R.string.collab_err_throttled
    CollabFailure.Conflict -> R.string.collab_err_conflict
    CollabFailure.ServerNotReady -> R.string.collab_err_not_ready
    CollabFailure.NotAvailable -> R.string.collab_err_unavailable
    CollabFailure.Offline -> R.string.collab_err_offline
    CollabFailure.SignInNeeded -> R.string.collab_err_sign_in
    CollabFailure.RateLimited -> R.string.collab_err_rate
    CollabFailure.Unknown -> R.string.collab_err_unknown
}
