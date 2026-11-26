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
import space.abhijithkm.shoppinglist.feature.settings.SettingsViewModel
import space.abhijithkm.shoppinglist.surfaces.OpenListRequest
import space.abhijithkm.shoppinglist.surfaces.toOpenListRequest
import space.abhijithkm.shoppinglist.ui.AppNavHost
import space.abhijithkm.shoppinglist.ui.theme.ShoppingListTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Set when a widget, reminder or notification asked to open a list; read once by the navigation host. */
    private var openListRequest by mutableStateOf<OpenListRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not after a rotation or process restart: the saved navigation state already holds where the user was.
        if (savedInstanceState == null) openListRequest = intent.toOpenListRequest(SystemClock.elapsedRealtime())
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            ShoppingListTheme(themeMode = settings.themeMode) {
                AppNavHost(openListRequest = openListRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openListRequest = intent.toOpenListRequest(SystemClock.elapsedRealtime())
    }
}
