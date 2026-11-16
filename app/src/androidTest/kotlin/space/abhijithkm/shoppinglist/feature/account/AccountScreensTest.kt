package space.abhijithkm.shoppinglist.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import space.abhijithkm.shoppinglist.data.session.AccountInfo
import space.abhijithkm.shoppinglist.data.session.SessionState
import space.abhijithkm.shoppinglist.data.sync.GuestList
import space.abhijithkm.shoppinglist.data.sync.MigrationPlan
import space.abhijithkm.shoppinglist.data.sync.SyncOutcome
import space.abhijithkm.shoppinglist.data.sync.SyncPhase
import space.abhijithkm.shoppinglist.data.sync.SyncStatus
import space.abhijithkm.shoppinglist.ui.theme.ShoppingListTheme

/** Stateless content tests for the account UI (no Hilt, no network). Controls are found by their visible text. */
class AccountScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val ann = AccountInfo("42", "ann@example.com", "Ann")
    private val now = 1_700_000_000_000L

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { ShoppingListTheme { content() } }
        composeRule.waitForIdle() // v2 rule queues composition on a StandardTestDispatcher
    }

    private fun section(
        session: SessionState,
        sync: SyncStatus = SyncStatus(SyncPhase.SIGNED_OUT),
        guest: Int = 0,
        notice: AccountNotice? = null,
        actions: AccountActions = AccountActions(),
    ) = show { AccountSectionContent(session, sync, guest, notice, now, actions) }

    // ---- account section --------------------------------------------------------------------------

    @Test
    fun signedOutExplainsAndOffersSignIn() {
        var clicked = false
        section(SessionState.SignedOut, guest = 2, actions = AccountActions(onSignIn = { clicked = true }))

        composeRule.onNodeWithText("Your lists are stored only on this device", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("2 lists are only on this device").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in or create account").performClick()
        assertEquals(true, clicked)
    }

    @Test
    fun signedInShowsWhoStatusAndLastSync() {
        section(SessionState.SignedIn(ann), SyncStatus(SyncPhase.UP_TO_DATE, ann, lastSuccessAt = now - 5 * 60_000L))

        composeRule.onNodeWithText("Signed in as Ann · ann@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Up to date").assertIsDisplayed()
        composeRule.onNodeWithText("Last synced 5 minutes ago").assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").assertIsEnabled()
    }

    @Test
    fun neverSyncedSaysSo() {
        section(SessionState.SignedIn(ann), SyncStatus(SyncPhase.UP_TO_DATE, ann))
        composeRule.onNodeWithText("Not synced yet").assertIsDisplayed()
    }

    @Test
    fun pendingAndFailedChangesAreCountedAndRetryable() {
        var retried = false
        section(
            SessionState.SignedIn(ann),
            SyncStatus(SyncPhase.HAS_FAILURES, ann, pending = 3, failed = 2, lastConflicts = 1),
            actions = AccountActions(onRetryFailed = { retried = true }),
        )

        composeRule.onNodeWithText("3 changes are waiting to sync").assertIsDisplayed()
        composeRule.onNodeWithText("2 changes could not be synced").assertIsDisplayed()
        composeRule.onNodeWithText("The last sync merged 1 conflicting edit. Your version was kept.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(true, retried)
    }

    @Test
    fun whileSyncingTheButtonIsDisabledAndTheStatusSaysSo() {
        section(SessionState.SignedIn(ann), SyncStatus(SyncPhase.SYNCING, ann))

        composeRule.onNodeWithText("Syncing…").assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").assertIsNotEnabled()
    }

    @Test
    fun anEndedSessionTellsTheUserTheirListsAreSafe() {
        var clicked = false
        section(
            SessionState.NeedsReauth(ann),
            SyncStatus(SyncPhase.NEEDS_SIGN_IN, ann, pending = 4),
            actions = AccountActions(onSignIn = { clicked = true }),
        )

        composeRule.onNodeWithText("Please sign in again as ann@example.com", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Your lists are safe on this device", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Sign in again").performClick()
        assertEquals(true, clicked)
    }

    @Test
    fun signedInWithGuestListsOffersToMoveThem() {
        var moved = false
        section(
            SessionState.SignedIn(ann),
            SyncStatus(SyncPhase.UP_TO_DATE, ann),
            guest = 1,
            actions = AccountActions(onMoveGuestLists = { moved = true }),
        )

        composeRule.onNodeWithText("1 list is only on this device").assertIsDisplayed()
        composeRule.onNodeWithText("Move to account").performClick()
        assertEquals(true, moved)
    }

    @Test
    fun noticesAreShownAndDismissible() {
        var dismissed = false
        section(
            SessionState.SignedOut,
            notice = AccountNotice.SignedOut(unsyncedChanges = 3),
            actions = AccountActions(onDismissNotice = { dismissed = true }),
        )

        composeRule.onNodeWithText("Signed out. 3 changes had not synced and stay only on this device.").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun syncProblemNoticesExplainWhatHappensNext() {
        section(SessionState.SignedIn(ann), SyncStatus(SyncPhase.PENDING, ann, pending = 1), notice = AccountNotice.SyncProblem(SyncOutcome.OFFLINE))
        composeRule.onNodeWithText("Couldn't sync: no connection. It will be tried again automatically.").assertIsDisplayed()
    }

    // ---- sign-in form -----------------------------------------------------------------------------

    private fun signInButton() = composeRule.onNode(hasText("Sign in") and hasClickAction())

    @Test
    fun signInSendsTheTypedEmailAndPassword() {
        var sent: Pair<String, String>? = null
        show { AuthFormContent(AuthUiState(visible = true), AuthActions(onSignIn = { e, p -> sent = e to p })) }

        composeRule.onNodeWithText("Email").performTextInput("ann@example.com")
        composeRule.onNodeWithText("Password").performTextInput("hunter22")
        signInButton().performClick()

        assertEquals("ann@example.com" to "hunter22", sent)
    }

    @Test
    fun theFormShowsServerNetworkAndValidationErrors() {
        var state by mutableStateOf(AuthUiState(visible = true, error = AuthError.Server("Invalid email or password.")))
        show { AuthFormContent(state, AuthActions()) }
        composeRule.onNodeWithText("Invalid email or password.").assertIsDisplayed()

        state = state.copy(error = AuthError.Network)
        composeRule.onNodeWithText("Can't reach the server. Check your connection and try again.").assertIsDisplayed()

        state = state.copy(error = AuthError.InvalidEmail)
        composeRule.onNodeWithText("Enter a valid email address.").assertIsDisplayed()

        state = state.copy(error = AuthError.EmailNotVerified)
        composeRule.onNodeWithText("Please confirm your email address first, then sign in.").assertIsDisplayed()
    }

    @Test
    fun whileBusyTheFormCannotBeSubmittedTwice() {
        show { AuthFormContent(AuthUiState(visible = true, busy = true), AuthActions()) }
        signInButton().assertIsNotEnabled()
    }

    @Test
    fun theFormSwitchesBetweenSignInCreateAndReset() {
        var mode: AuthMode? = null
        show { AuthFormContent(AuthUiState(visible = true), AuthActions(onModeChange = { mode = it })) }

        composeRule.onNodeWithText("Create an account").performClick()
        assertEquals(AuthMode.CREATE, mode)
        composeRule.onNodeWithText("Forgot password?").performClick()
        assertEquals(AuthMode.FORGOT, mode)
    }

    @Test
    fun createAccountAlsoAsksForAnOptionalName() {
        var sent: Triple<String, String, String>? = null
        show {
            AuthFormContent(
                AuthUiState(visible = true, mode = AuthMode.CREATE),
                AuthActions(onCreate = { e, p, n -> sent = Triple(e, p, n) }),
            )
        }

        composeRule.onNodeWithText("Email").performTextInput("new@example.com")
        composeRule.onNodeWithText("Password").performTextInput("a-long-password")
        composeRule.onNodeWithText("Your name (optional)").performTextInput("Ann")
        composeRule.onNode(hasText("Create account") and hasClickAction()).performClick()

        assertEquals(Triple("new@example.com", "a-long-password", "Ann"), sent)
    }

    @Test
    fun resetAsksOnlyForTheEmail() {
        var sent: String? = null
        show { AuthFormContent(AuthUiState(visible = true, mode = AuthMode.FORGOT), AuthActions(onForgot = { sent = it })) }

        composeRule.onNodeWithText("Email").performTextInput("ann@example.com")
        composeRule.onNode(hasText("Send reset link") and hasClickAction()).performClick()

        assertEquals("ann@example.com", sent)
        composeRule.onNodeWithText("Password").assertDoesNotExist()
    }

    @Test
    fun theNoticeAfterRegisteringTellsTheUserToConfirmTheirEmail() {
        show { AuthFormContent(AuthUiState(visible = true, notice = AuthNotice.RegisteredCheckEmail), AuthActions()) }
        composeRule.onNodeWithText("Account created. Check your email to confirm the address, then sign in.").assertIsDisplayed()
    }

    // ---- migration --------------------------------------------------------------------------------

    private val plan = MigrationPlan(
        lists = listOf(
            GuestList("l1", "Groceries", itemCount = 3, nameClash = true),
            GuestList("l2", "Hardware", itemCount = 1, nameClash = false),
        ),
        vocabularyCount = 4,
    )

    @Test
    fun migrationListsGuestListsFlagsClashesAndReportsToggles() {
        val toggled = mutableListOf<String>()
        show {
            MigrationContent(
                MigrationUiState.Choose(plan, selected = setOf("l2"), includeVocabulary = true),
                MigrationActions(onToggleList = { toggled += it }),
            )
        }

        composeRule.onNodeWithText("Groceries").assertIsDisplayed()
        composeRule.onNodeWithText("3 items").assertIsDisplayed()
        composeRule.onNodeWithText("Your account already has a list with this name.").assertIsDisplayed()
        composeRule.onNodeWithText("1 item").assertIsDisplayed()
        composeRule.onNodeWithText("Also move my custom categories and tags (4)").assertIsDisplayed()
        composeRule.onNodeWithText("Hardware").performClick()
        assertEquals(listOf("l2"), toggled)
    }

    @Test
    fun theMoveButtonCountsTheSelectionAndNeedsOne() {
        var confirmed = false
        show {
            MigrationContent(
                MigrationUiState.Choose(plan, selected = setOf("l1", "l2"), includeVocabulary = false),
                MigrationActions(onConfirm = { confirmed = true }),
            )
        }
        composeRule.onNodeWithText("Move 2 lists").performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun withNothingSelectedTheMoveButtonIsDisabled() {
        show { MigrationContent(MigrationUiState.Choose(plan, emptySet(), includeVocabulary = true), MigrationActions()) }
        composeRule.onNodeWithText("Move 0 lists").assertIsNotEnabled()
    }

    @Test
    fun notNowKeepsEverythingOnTheDevice() {
        var dismissed = false
        show { MigrationContent(MigrationUiState.Choose(plan, emptySet(), true), MigrationActions(onDismiss = { dismissed = true })) }
        composeRule.onNodeWithText("Not now").performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun migrationShowsProgressAndTheResult() {
        var state by mutableStateOf<MigrationUiState>(MigrationUiState.Checking)
        show { MigrationContent(state, MigrationActions()) }
        composeRule.onNodeWithText("Checking your account…").assertIsDisplayed()

        state = MigrationUiState.Running
        composeRule.onNodeWithText("Moving your lists…").assertIsDisplayed()

        state = MigrationUiState.Done(listsMoved = 2, outcome = SyncOutcome.COMPLETED)
        composeRule.onNodeWithText("2 lists were moved to your account.").assertIsDisplayed()

        state = MigrationUiState.Done(listsMoved = 1, outcome = SyncOutcome.OFFLINE)
        composeRule.onNodeWithText("1 list was moved to your account.").assertIsDisplayed()
        composeRule.onNodeWithText("upload as soon as the connection allows", substring = true).assertIsDisplayed()
    }
}
