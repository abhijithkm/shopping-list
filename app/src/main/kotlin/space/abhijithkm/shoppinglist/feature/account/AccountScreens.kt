package space.abhijithkm.shoppinglist.feature.account

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.data.session.SessionState
import space.abhijithkm.shoppinglist.data.sync.SyncOutcome
import space.abhijithkm.shoppinglist.data.sync.SyncPhase
import space.abhijithkm.shoppinglist.data.sync.SyncStatus

// ------------------------------------------------------------------------------------ account section

/** The Account block of Settings. Hosts the sign-in and migration sheets and the sign-out confirmation. */
@Composable
fun AccountSection(viewModel: AccountViewModel = hiltViewModel()) {
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    val sync by viewModel.syncStatus.collectAsStateWithLifecycle()
    val guestLists by viewModel.guestListCount.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val auth by viewModel.auth.collectAsStateWithLifecycle()
    val migration by viewModel.migrationState.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    AccountSectionContent(
        session = session,
        sync = sync,
        guestListCount = guestLists,
        notice = notice,
        nowMillis = System.currentTimeMillis(),
        actions = AccountActions(
            onSignIn = { viewModel.openAuth() },
            onSyncNow = viewModel::syncNow,
            onSignOut = { confirmSignOut = true },
            onRetryFailed = viewModel::retryFailed,
            onMoveGuestLists = viewModel::checkMigration,
            onDismissNotice = viewModel::dismissNotice,
        ),
    )

    if (auth.visible) {
        AuthSheet(
            state = auth,
            actions = AuthActions(
                onSignIn = viewModel::signIn,
                onCreate = viewModel::register,
                onForgot = viewModel::requestPasswordReset,
                onModeChange = viewModel::setAuthMode,
                onDismiss = viewModel::dismissAuth,
            ),
        )
    }
    if (migration != MigrationUiState.Hidden) {
        MigrationSheet(
            state = migration,
            actions = MigrationActions(
                onToggleList = viewModel::toggleMigrationList,
                onIncludeVocabulary = viewModel::setIncludeVocabulary,
                onConfirm = viewModel::confirmMigration,
                onDismiss = viewModel::dismissMigration,
            ),
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.account_sign_out_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.account_sign_out_body))
                    val pending = sync.pending + sync.failed
                    if (pending > 0) Text(pluralStringResource(R.plurals.sync_pending, pending, pending))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.account_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.auth_cancel)) } },
        )
    }
}

data class AccountActions(
    val onSignIn: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onRetryFailed: () -> Unit = {},
    val onMoveGuestLists: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

@Composable
fun AccountSectionContent(
    session: SessionState,
    sync: SyncStatus,
    guestListCount: Int,
    notice: AccountNotice?,
    nowMillis: Long,
    actions: AccountActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.account_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        when (session) {
            SessionState.Loading -> Unit
            SessionState.SignedOut -> {
                Text(stringResource(R.string.account_signed_out_summary), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = actions.onSignIn) { Text(stringResource(R.string.account_sign_in_button)) }
                if (guestListCount > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.account_guest_lists, guestListCount, guestListCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is SessionState.NeedsReauth -> {
                Text(stringResource(R.string.account_needs_sign_in, session.account.email), style = MaterialTheme.typography.bodyMedium)
                SyncStatusLines(sync, nowMillis)
                Button(onClick = actions.onSignIn) { Text(stringResource(R.string.account_sign_in_again)) }
            }
            is SessionState.SignedIn -> SignedInBlock(session, sync, guestListCount, nowMillis, actions)
        }
        notice?.let { NoticeRow(it, actions.onDismissNotice) }
    }
}

@Composable
private fun SignedInBlock(
    session: SessionState.SignedIn,
    sync: SyncStatus,
    guestListCount: Int,
    nowMillis: Long,
    actions: AccountActions,
) {
    val who = session.account.displayName?.takeIf { it.isNotBlank() }?.let { "$it · ${session.account.email}" } ?: session.account.email
    Text(stringResource(R.string.account_signed_in_as, who), style = MaterialTheme.typography.bodyMedium)
    SyncStatusLines(sync, nowMillis)
    if (sync.failed > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(R.plurals.sync_failed, sync.failed, sync.failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onRetryFailed) { Text(stringResource(R.string.account_retry)) }
        }
    }
    if (sync.lastConflicts > 0) {
        Text(
            text = pluralStringResource(R.plurals.sync_conflicts, sync.lastConflicts, sync.lastConflicts),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = actions.onSyncNow, enabled = sync.phase != SyncPhase.SYNCING) {
            Text(stringResource(R.string.account_sync_now))
        }
        TextButton(onClick = actions.onSignOut) { Text(stringResource(R.string.account_sign_out)) }
    }
    if (guestListCount > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(R.plurals.account_guest_lists, guestListCount, guestListCount),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onMoveGuestLists) { Text(stringResource(R.string.account_move_action)) }
        }
    }
}

@Composable
private fun SyncStatusLines(sync: SyncStatus, nowMillis: Long) {
    val headline = when (sync.phase) {
        SyncPhase.SYNCING -> stringResource(R.string.sync_syncing)
        SyncPhase.NEEDS_SIGN_IN -> stringResource(R.string.sync_needs_sign_in)
        SyncPhase.PENDING, SyncPhase.HAS_FAILURES ->
            if (sync.pending > 0) pluralStringResource(R.plurals.sync_pending, sync.pending, sync.pending) else null
        SyncPhase.UP_TO_DATE -> stringResource(R.string.sync_up_to_date)
        SyncPhase.SIGNED_OUT -> stringResource(R.string.sync_signed_out)
    }
    if (headline != null) Text(headline, style = MaterialTheme.typography.bodyMedium)
    val last = sync.lastSuccessAt
    Text(
        text = if (last == null) {
            stringResource(R.string.sync_never)
        } else {
            stringResource(R.string.sync_last, DateUtils.getRelativeTimeSpanString(last, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString())
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NoticeRow(notice: AccountNotice, onDismiss: () -> Unit) {
    val text = when (notice) {
        is AccountNotice.SignedOut ->
            if (notice.unsyncedChanges > 0) {
                pluralStringResource(R.plurals.account_signed_out_unsynced, notice.unsyncedChanges, notice.unsyncedChanges)
            } else {
                stringResource(R.string.account_signed_out_done)
            }
        is AccountNotice.SyncProblem -> stringResource(
            when (notice.outcome) {
                SyncOutcome.OFFLINE -> R.string.sync_problem_offline
                SyncOutcome.SERVER_UNAVAILABLE, SyncOutcome.INTERRUPTED -> R.string.sync_problem_server
                SyncOutcome.RATE_LIMITED -> R.string.sync_problem_rate
                SyncOutcome.NEEDS_SIGN_IN, SyncOutcome.NOT_SIGNED_IN -> R.string.sync_problem_sign_in
                SyncOutcome.COMPLETED -> R.string.sync_problem_unknown
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_dismiss)) }
    }
}

// ------------------------------------------------------------------------------------------ sign in

data class AuthActions(
    val onSignIn: (email: String, password: String) -> Unit = { _, _ -> },
    val onCreate: (email: String, password: String, name: String) -> Unit = { _, _, _ -> },
    val onForgot: (email: String) -> Unit = {},
    val onModeChange: (AuthMode) -> Unit = {},
    val onDismiss: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthSheet(state: AuthUiState, actions: AuthActions) {
    ModalBottomSheet(
        onDismissRequest = { if (!state.busy) actions.onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        AuthFormContent(state, actions)
    }
}

@Composable
fun AuthFormContent(state: AuthUiState, actions: AuthActions) {
    var email by rememberSaveable { mutableStateOf("") }
    // Not rememberSaveable: saved state can be written to disk, and a password must never be.
    var password by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    fun submit() {
        if (state.busy) return
        when (state.mode) {
            AuthMode.SIGN_IN -> actions.onSignIn(email, password)
            AuthMode.CREATE -> actions.onCreate(email, password, name)
            AuthMode.FORGOT -> actions.onForgot(email)
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                when (state.mode) {
                    AuthMode.SIGN_IN -> R.string.auth_title_sign_in
                    AuthMode.CREATE -> R.string.auth_title_create
                    AuthMode.FORGOT -> R.string.auth_title_forgot
                },
            ),
            style = MaterialTheme.typography.titleLarge,
        )
        state.notice?.let {
            Text(
                text = stringResource(if (it == AuthNotice.RegisteredCheckEmail) R.string.auth_notice_registered else R.string.auth_notice_reset),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.auth_email)) },
            singleLine = true,
            enabled = !state.busy,
            isError = state.error == AuthError.InvalidEmail,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = if (state.mode == AuthMode.FORGOT) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.mode != AuthMode.FORGOT) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.auth_password)) },
                singleLine = true,
                enabled = !state.busy,
                isError = state.error == AuthError.MissingPassword,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (state.mode == AuthMode.CREATE) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(stringResource(if (showPassword) R.string.auth_hide_password else R.string.auth_show_password))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.mode == AuthMode.CREATE) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.auth_display_name)) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.error?.let { error ->
            Text(
                text = when (error) {
                    AuthError.Network -> stringResource(R.string.auth_error_network)
                    AuthError.EmailNotVerified -> stringResource(R.string.auth_error_unverified)
                    AuthError.InvalidEmail -> stringResource(R.string.auth_error_email)
                    AuthError.MissingPassword -> stringResource(R.string.auth_error_password)
                    AuthError.ResetUnavailable -> stringResource(R.string.auth_error_reset_unavailable)
                    is AuthError.Server -> error.message
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = { submit() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    when (state.mode) {
                        AuthMode.SIGN_IN -> R.string.auth_submit_sign_in
                        AuthMode.CREATE -> R.string.auth_submit_create
                        AuthMode.FORGOT -> R.string.auth_submit_forgot
                    },
                ),
            )
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            when (state.mode) {
                AuthMode.SIGN_IN -> {
                    TextButton(onClick = { actions.onModeChange(AuthMode.CREATE) }, enabled = !state.busy) {
                        Text(stringResource(R.string.auth_to_create))
                    }
                    TextButton(onClick = { actions.onModeChange(AuthMode.FORGOT) }, enabled = !state.busy) {
                        Text(stringResource(R.string.auth_forgot))
                    }
                }
                AuthMode.CREATE, AuthMode.FORGOT ->
                    TextButton(onClick = { actions.onModeChange(AuthMode.SIGN_IN) }, enabled = !state.busy) {
                        Text(stringResource(R.string.auth_to_sign_in))
                    }
            }
        }
        Text(
            text = stringResource(R.string.auth_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = actions.onDismiss, enabled = !state.busy, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.auth_cancel))
        }
    }
}

// ----------------------------------------------------------------------------------------- migration

data class MigrationActions(
    val onToggleList: (String) -> Unit = {},
    val onIncludeVocabulary: (Boolean) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationSheet(state: MigrationUiState, actions: MigrationActions) {
    ModalBottomSheet(
        onDismissRequest = { if (state != MigrationUiState.Running) actions.onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        MigrationContent(state, actions)
    }
}

@Composable
fun MigrationContent(state: MigrationUiState, actions: MigrationActions) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            MigrationUiState.Hidden -> Unit
            MigrationUiState.Checking -> Busy(R.string.migrate_checking)
            MigrationUiState.Running -> Busy(R.string.migrate_running)
            is MigrationUiState.Choose -> Choose(state, actions)
            is MigrationUiState.Done -> {
                Text(stringResource(R.string.migrate_done_title), style = MaterialTheme.typography.titleLarge)
                Text(pluralStringResource(R.plurals.migrate_done_body, state.listsMoved, state.listsMoved))
                if (state.outcome != SyncOutcome.COMPLETED) Text(stringResource(R.string.migrate_done_queued))
                Button(onClick = actions.onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.migrate_close)) }
            }
        }
    }
}

@Composable
private fun Busy(messageRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(stringResource(messageRes), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ColumnScope.Choose(state: MigrationUiState.Choose, actions: MigrationActions) {
    Text(stringResource(R.string.migrate_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.migrate_summary), style = MaterialTheme.typography.bodyMedium)
    state.plan.lists.forEach { guest ->
        val checked = guest.id in state.selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = checked, onValueChange = { actions.onToggleList(guest.id) }, role = Role.Checkbox),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column(Modifier.padding(start = 16.dp)) {
                Text(guest.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = pluralStringResource(R.plurals.migrate_items, guest.itemCount, guest.itemCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (guest.nameClash) {
                    Text(
                        text = stringResource(R.string.migrate_clash),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    if (state.plan.vocabularyCount > 0) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = state.includeVocabulary, onValueChange = actions.onIncludeVocabulary, role = Role.Checkbox),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.includeVocabulary, onCheckedChange = null)
            Text(
                text = pluralStringResource(R.plurals.migrate_vocabulary, state.plan.vocabularyCount, state.plan.vocabularyCount),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
        TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.migrate_not_now)) }
        Button(onClick = actions.onConfirm, enabled = state.selected.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.migrate_confirm, state.selected.size, state.selected.size))
        }
    }
}
