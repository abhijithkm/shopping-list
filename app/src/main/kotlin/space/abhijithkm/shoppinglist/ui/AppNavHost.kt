package space.abhijithkm.shoppinglist.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.feature.categories.CategoriesScreen
import space.abhijithkm.shoppinglist.feature.listdetail.ListDetailScreen
import space.abhijithkm.shoppinglist.feature.lists.ListsScreen
import space.abhijithkm.shoppinglist.feature.settings.SettingsScreen
import space.abhijithkm.shoppinglist.feature.sharing.InvitationsScreen
import space.abhijithkm.shoppinglist.feature.sharing.ShareScreen

const val ROUTE_LISTS = "lists"
const val ROUTE_CATEGORIES = "categories"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_LIST_DETAIL = "list/{listId}"
const val ROUTE_SHARE = "share/{listId}"
const val ROUTE_INVITATIONS = "invitations"

fun listDetailRoute(listId: String) = "list/$listId"

fun shareRoute(listId: String) = "share/$listId"

/** Where a tapped notification wants the app to go. */
sealed interface LaunchTarget {
    data object Inbox : LaunchTarget

    data class OpenList(val listId: String) : LaunchTarget
}

/** The bottom bar is only shown on these; the list detail screen uses the full height. */
private val TOP_LEVEL_ROUTES = setOf(ROUTE_LISTS, ROUTE_CATEGORIES, ROUTE_SETTINGS)

/**
 * Screens own their top bars and the shell owns the bottom bar, so nested Scaffolds must not each
 * apply system-bar insets again.
 */
internal val NoInsets = WindowInsets(0, 0, 0, 0)

private data class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: @Composable () -> Unit,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(ROUTE_LISTS, R.string.nav_lists) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
    },
    TopLevelDestination(ROUTE_CATEGORIES, R.string.nav_categories) {
        Icon(painterResource(R.drawable.ic_category), contentDescription = null)
    },
    TopLevelDestination(ROUTE_SETTINGS, R.string.nav_settings) {
        Icon(Icons.Filled.Settings, contentDescription = null)
    },
)

@Composable
fun AppNavHost(launchTarget: LaunchTarget? = null, onLaunchHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(launchTarget) {
        when (launchTarget) {
            LaunchTarget.Inbox -> navController.navigate(ROUTE_INVITATIONS) { launchSingleTop = true }
            is LaunchTarget.OpenList -> navController.navigate(listDetailRoute(launchTarget.listId)) { launchSingleTop = true }
            null -> return@LaunchedEffect
        }
        onLaunchHandled()
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in TOP_LEVEL_ROUTES) NavigationBar {
                topLevelDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = destination.icon,
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
        contentWindowInsets = NoInsets,
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_LISTS,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(ROUTE_LISTS) {
                ListsScreen(
                    onOpenList = { listId -> navController.navigate(listDetailRoute(listId)) },
                    onOpenInvitations = { navController.navigate(ROUTE_INVITATIONS) },
                )
            }
            composable(
                route = ROUTE_LIST_DETAIL,
                arguments = listOf(navArgument("listId") { type = NavType.StringType }),
            ) {
                ListDetailScreen(
                    onBack = { navController.popBackStack() },
                    onShare = { listId -> navController.navigate(shareRoute(listId)) },
                )
            }
            composable(
                route = ROUTE_SHARE,
                arguments = listOf(navArgument("listId") { type = NavType.StringType }),
            ) {
                ShareScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                )
            }
            composable(ROUTE_INVITATIONS) {
                InvitationsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                )
            }
            composable(ROUTE_CATEGORIES) { CategoriesScreen() }
            composable(ROUTE_SETTINGS) { SettingsScreen() }
        }
    }
}
