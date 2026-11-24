package space.abhijithkm.shoppinglist.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.abhijithkm.shoppinglist.data.remote.AppHubException
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository
import space.abhijithkm.shoppinglist.data.session.SessionManager
import space.abhijithkm.shoppinglist.data.session.SessionState
import space.abhijithkm.shoppinglist.data.sync.MigrationPlan
import space.abhijithkm.shoppinglist.data.sync.MigrationRepository
import space.abhijithkm.shoppinglist.data.sync.SyncOutcome
import space.abhijithkm.shoppinglist.data.sync.SyncPhase
import space.abhijithkm.shoppinglist.data.sync.SyncRepository
import space.abhijithkm.shoppinglist.data.sync.SyncStatus

enum class AuthMode { SIGN_IN, CREATE, FORGOT }

sealed interface AuthError {
    data object Network : AuthError

    data object EmailNotVerified : AuthError

    data object InvalidEmail : AuthError

    data object MissingPassword : AuthError

    /** The server has no password recovery set up (it needs working mail). */
    data object ResetUnavailable : AuthError

    /** The server's own sentence (wrong password, weak password, address already registered, rate limit...). */
    data class Server(val message: String) : AuthError
}

sealed interface AuthNotice {
    data object RegisteredCheckEmail : AuthNotice

    data object ResetRequested : AuthNotice
}

data class AuthUiState(
    val visible: Boolean = false,
    val mode: AuthMode = AuthMode.SIGN_IN,
    val busy: Boolean = false,
    val error: AuthError? = null,
    val notice: AuthNotice? = null,
)

sealed interface MigrationUiState {
    data object Hidden : MigrationUiState

    data object Checking : MigrationUiState

    data class Choose(val plan: MigrationPlan, val selected: Set<String>, val includeVocabulary: Boolean) : MigrationUiState

    data object Running : MigrationUiState

    data class Done(val listsMoved: Int, val outcome: SyncOutcome) : MigrationUiState
}

sealed interface AccountNotice {
    data class SignedOut(val unsyncedChanges: Int, val lostSharedChanges: Int = 0) : AccountNotice

    data class SyncProblem(val outcome: SyncOutcome) : AccountNotice
}

/**
 * Drives the Account section of Settings. Sign-in is only ever requested here, when the user chooses a
 * cloud feature (sync); the app never asks at launch and every list works without an account.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val session: SessionManager,
    private val sync: SyncRepository,
    private val migration: MigrationRepository,
    repository: LocalShoppingRepository,
) : ViewModel() {
    val sessionState: StateFlow<SessionState> = session.state

    val syncStatus: StateFlow<SyncStatus> = sync.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncStatus(SyncPhase.SIGNED_OUT))

    /** Lists that exist only on this device and could be moved into the account. */
    val guestListCount: StateFlow<Int> = repository.observeGuestListCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val mutableAuth = MutableStateFlow(AuthUiState())
    val auth: StateFlow<AuthUiState> = mutableAuth.asStateFlow()

    private val mutableMigration = MutableStateFlow<MigrationUiState>(MigrationUiState.Hidden)
    val migrationState: StateFlow<MigrationUiState> = mutableMigration.asStateFlow()

    private val mutableNotice = MutableStateFlow<AccountNotice?>(null)
    val notice: StateFlow<AccountNotice?> = mutableNotice.asStateFlow()

    init {
        viewModelScope.launch { session.restore() }
    }

    // ------------------------------------------------------------------------------------- sign in

    fun openAuth(mode: AuthMode = AuthMode.SIGN_IN) {
        mutableAuth.value = AuthUiState(visible = true, mode = mode)
    }

    fun setAuthMode(mode: AuthMode) = mutableAuth.update { it.copy(mode = mode, error = null, notice = null) }

    fun dismissAuth() {
        mutableAuth.value = AuthUiState()
    }

    fun signIn(email: String, password: String) {
        if (mutableAuth.value.busy) return // a double tap must not start a second login
        validate(email, password)?.let { error -> return mutableAuth.update { it.copy(error = error) } }
        launchAuth {
            val account = session.signIn(email, password)
            sync.onSignedIn(account)
            mutableAuth.value = AuthUiState()
            checkMigration()
        }
    }

    fun register(email: String, password: String, displayName: String) {
        if (mutableAuth.value.busy) return
        validate(email, password)?.let { error -> return mutableAuth.update { it.copy(error = error) } }
        launchAuth {
            session.register(email, password, displayName)
            // From here the account exists: whatever goes wrong next, the form goes back to sign-in (a retry of
            // "create" would only answer that the address is taken).
            try {
                val account = session.signIn(email, password)
                sync.onSignedIn(account)
                mutableAuth.value = AuthUiState()
                checkMigration()
            } catch (e: AppHubException.Http) {
                mutableAuth.update { it.copy(mode = AuthMode.SIGN_IN) }
                // The server wants the address confirmed before the first sign-in.
                if (e.reason != EMAIL_NOT_VERIFIED) throw e
                mutableAuth.update { it.copy(notice = AuthNotice.RegisteredCheckEmail) }
            } catch (e: AppHubException) {
                mutableAuth.update { it.copy(mode = AuthMode.SIGN_IN) }
                throw e
            }
        }
    }

    fun requestPasswordReset(email: String) {
        if (mutableAuth.value.busy) return
        if (!looksLikeEmail(email)) return mutableAuth.update { it.copy(error = AuthError.InvalidEmail) }
        launchAuth {
            try {
                session.forgotPassword(email)
                mutableAuth.update { it.copy(mode = AuthMode.SIGN_IN, notice = AuthNotice.ResetRequested) }
            } catch (e: AppHubException.Http) {
                // Recovery needs working mail on the server; until it is configured the endpoint answers 404.
                if (!e.isNotFound) throw e
                mutableAuth.update { it.copy(error = AuthError.ResetUnavailable) }
            }
        }
    }

    private fun launchAuth(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutableAuth.update { it.copy(busy = true, error = null, notice = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppHubException) {
                mutableAuth.update { it.copy(error = e.toAuthError()) }
            } finally {
                mutableAuth.update { it.copy(busy = false) }
            }
        }
    }

    private fun validate(email: String, password: String): AuthError? = when {
        !looksLikeEmail(email) -> AuthError.InvalidEmail
        password.isEmpty() -> AuthError.MissingPassword
        else -> null
    }

    private fun looksLikeEmail(text: String) = EMAIL.matches(text.trim())

    private fun AppHubException.toAuthError(): AuthError = when {
        this is AppHubException.Network -> AuthError.Network
        this is AppHubException.Http && reason == EMAIL_NOT_VERIFIED -> AuthError.EmailNotVerified
        else -> AuthError.Server(message.orEmpty())
    }

    // ----------------------------------------------------------------------------------------- sync

    fun syncNow() {
        viewModelScope.launch {
            val result = sync.syncNow()
            mutableNotice.value = result.outcome.takeIf { it != SyncOutcome.COMPLETED }?.let(AccountNotice::SyncProblem)
        }
    }

    fun retryFailed() {
        viewModelScope.launch {
            val result = sync.retryFailed()
            mutableNotice.value = result.outcome.takeIf { it != SyncOutcome.COMPLETED }?.let(AccountNotice::SyncProblem)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val report = sync.signOut()
            mutableNotice.value = AccountNotice.SignedOut(report.unsyncedChanges, report.lostSharedChanges)
        }
    }

    fun dismissNotice() {
        mutableNotice.value = null
    }

    // ------------------------------------------------------------------------------------ migration

    /** Looks at the guest lists (after a first sync so name clashes with the account are known) and offers to move them. */
    fun checkMigration() {
        viewModelScope.launch {
            mutableMigration.value = MigrationUiState.Checking
            runCatching { sync.syncNow() }
            val plan = migration.plan()
            mutableMigration.value = if (plan.isEmpty) {
                MigrationUiState.Hidden
            } else {
                MigrationUiState.Choose(plan, plan.lists.filterNot { it.nameClash }.map { it.id }.toSet(), includeVocabulary = true)
            }
        }
    }

    fun toggleMigrationList(listId: String) = updateChoice { it.copy(selected = if (listId in it.selected) it.selected - listId else it.selected + listId) }

    fun setIncludeVocabulary(include: Boolean) = updateChoice { it.copy(includeVocabulary = include) }

    private fun updateChoice(transform: (MigrationUiState.Choose) -> MigrationUiState.Choose) {
        mutableMigration.update { (it as? MigrationUiState.Choose)?.let(transform) ?: it }
    }

    fun confirmMigration() {
        val choice = mutableMigration.value as? MigrationUiState.Choose ?: return
        viewModelScope.launch {
            mutableMigration.value = MigrationUiState.Running
            val result = migration.migrate(choice.selected, choice.includeVocabulary)
            mutableMigration.value = MigrationUiState.Done(result.listsMoved, result.outcome)
        }
    }

    fun dismissMigration() {
        mutableMigration.value = MigrationUiState.Hidden
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val EMAIL_NOT_VERIFIED = "email_not_verified"
        val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    }
}
