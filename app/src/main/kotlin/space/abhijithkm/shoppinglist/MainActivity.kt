package space.abhijithkm.shoppinglist

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import space.abhijithkm.shoppinglist.data.collab.CollabNotifier
import space.abhijithkm.shoppinglist.feature.settings.SettingsViewModel
import space.abhijithkm.shoppinglist.surfaces.OpenListRequest
import space.abhijithkm.shoppinglist.surfaces.toOpenListRequest
import space.abhijithkm.shoppinglist.ui.AppNavHost
import space.abhijithkm.shoppinglist.ui.LaunchTarget
import space.abhijithkm.shoppinglist.ui.theme.ShoppingListTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Where a tapped notification wants to go; cleared once the navigation host has acted on it. */
    private var launchTarget by mutableStateOf<LaunchTarget?>(null)
    /** Set when a widget, reminder or notification asked to open a list; read once by the navigation host. */
    private var openListRequest by mutableStateOf<OpenListRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not after a rotation or process restart: the saved navigation state already holds where the user was.
        if (savedInstanceState == null) {
            launchTarget = targetOf(intent)
            openListRequest = intent.toOpenListRequest(SystemClock.elapsedRealtime())
        }
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            ShoppingListTheme(themeMode = settings.themeMode) {
                AppNavHost(
                    launchTarget = launchTarget,
                    onLaunchHandled = { launchTarget = null },
                    openListRequest = openListRequest,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchTarget = targetOf(intent)
        openListRequest = intent.toOpenListRequest(SystemClock.elapsedRealtime())
    }

    private fun targetOf(intent: Intent?): LaunchTarget? = when {
        intent == null -> null
        intent.hasExtra(CollabNotifier.EXTRA_OPEN_LIST) -> intent.getStringExtra(CollabNotifier.EXTRA_OPEN_LIST)?.let(LaunchTarget::OpenList)
        intent.getBooleanExtra(CollabNotifier.EXTRA_OPEN_INBOX, false) -> LaunchTarget.Inbox
        else -> null
    }
}
