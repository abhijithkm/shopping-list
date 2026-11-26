package space.abhijithkm.shoppinglist.feature.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.ui.NoInsets

/** Everything the Invitations screen can ask for. Defaults are no-ops so tests only wire what they check. */
data class InvitationsActions(
    val onAccept: (invitationId: String) -> Unit = {},
    val onDecline: (invitationId: String) -> Unit = {},
    val onCancelRequest: (requestId: String) -> Unit = {},
    val onCheckCode: (rawCode: String) -> Unit = {},
    val onClearPreview: () -> Unit = {},
    val onRequestAccess: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

@Composable
fun InvitationsScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: InvitationsViewModel = hiltViewModel(), // wired to Hilt; the content below is stateless
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenSettings) {
        InvitationsActions(
            onAccept = viewModel::accept,
            onDecline = viewModel::decline,
            onCancelRequest = viewModel::cancelRequest,
            onCheckCode = viewModel::previewCode,
            onClearPreview = viewModel::clearPreview,
            onRequestAccess = viewModel::requestAccess,
            onDismissMessage = viewModel::dismissMessage,
            onOpenSettings = onOpenSettings,
        )
    }
    InvitationsContent(state = state, actions = actions, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitationsContent(
    state: InvitationsUiState,
    actions: InvitationsActions,
    onBack: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { messageText(it) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            actions.onDismissMessage()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.invitations_title)) },
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
            if (!state.signedIn) {
                Text(stringResource(R.string.invitations_sign_in), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = actions.onOpenSettings) { Text(stringResource(R.string.share_open_settings)) }
                return@Column
            }
            if (state.invitations.isEmpty()) {
                Text(
                    text = stringResource(R.string.invitations_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.invitations.forEach { invitation ->
                ElevatedCard(Modifier.fillMaxWidth().testTag("invitation_card")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(invitation.listName ?: stringResource(R.string.a_list), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.invitation_from, invitation.inviterName ?: stringResource(R.string.someone)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { actions.onAccept(invitation.id) }, enabled = !state.busy) { Text(stringResource(R.string.accept)) }
                            OutlinedButton(onClick = { actions.onDecline(invitation.id) }, enabled = !state.busy) {
                                Text(stringResource(R.string.decline))
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            JoinWithCode(state, actions)
            if (state.requests.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.requests_mine), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                state.requests.forEach { request ->
                    Row(Modifier.fillMaxWidth().testTag("my_request_row")) {
                        Text(
                            text = stringResource(R.string.request_mine_row, request.listName ?: stringResource(R.string.a_list)),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { actions.onCancelRequest(request.id) }, enabled = !state.busy) {
                            Text(stringResource(R.string.request_cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinWithCode(state: InvitationsUiState, actions: InvitationsActions) {
    // The code is typed into remembered (not saved) state: it must not be written into the saved-state bundle.
    var code by remember { mutableStateOf("") }
    Text(stringResource(R.string.join_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    OutlinedTextField(
        value = code,
        onValueChange = {
            code = it.take(MAX_TYPED)
            if (state.preview !is PreviewUi.Idle) actions.onClearPreview()
        },
        label = { Text(stringResource(R.string.join_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (code.isNotBlank()) actions.onCheckCode(code) }),
        modifier = Modifier.fillMaxWidth().testTag("join_code_field"),
    )
    when (val preview = state.preview) {
        PreviewUi.Busy -> CircularProgressIndicator(Modifier.padding(4.dp))
        is PreviewUi.Found -> {
            Text(
                stringResource(
                    R.string.join_preview,
                    preview.preview.listName ?: stringResource(R.string.a_list),
                    preview.preview.inviterName ?: stringResource(R.string.someone),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(stringResource(R.string.join_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    actions.onRequestAccess()
                    code = ""
                },
                enabled = !state.busy,
            ) { Text(stringResource(R.string.join_ask)) }
        }
        PreviewUi.Invalid -> Text(stringResource(R.string.join_invalid), color = MaterialTheme.colorScheme.error)
        PreviewUi.Idle -> OutlinedButton(
            onClick = { actions.onCheckCode(code) },
            enabled = code.isNotBlank() && !state.busy,
        ) { Text(stringResource(R.string.join_check)) }
    }
}

@Composable
private fun messageText(message: InvitationsMessage): String = when (message) {
    is InvitationsMessage.Failed -> stringResource(message.failure.messageRes())
    InvitationsMessage.Joined -> stringResource(R.string.inv_done_joined)
    InvitationsMessage.Declined -> stringResource(R.string.inv_done_declined)
    InvitationsMessage.RequestSent -> stringResource(R.string.inv_done_request_sent)
    InvitationsMessage.RequestCancelled -> stringResource(R.string.inv_done_request_cancelled)
}

private const val MAX_TYPED = 16
