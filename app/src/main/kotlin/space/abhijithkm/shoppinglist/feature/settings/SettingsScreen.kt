package space.abhijithkm.shoppinglist.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.abhijithkm.shoppinglist.BuildConfig
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.core.model.AppSettings
import space.abhijithkm.shoppinglist.core.model.ThemeMode
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import space.abhijithkm.shoppinglist.feature.account.AccountSection
import space.abhijithkm.shoppinglist.feature.reminders.notificationsEnabled
import space.abhijithkm.shoppinglist.feature.reminders.openNotificationSettings
import space.abhijithkm.shoppinglist.ui.NoInsets

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsOff by remember { mutableStateOf(!notificationsEnabled(context)) }
    // The owner may fix it in the system settings and come back: look again every time the screen is resumed.
    LifecycleResumeEffect(Unit) {
        notificationsOff = !notificationsEnabled(context)
        onPauseOrDispose { }
    }
    SettingsScreenContent(
        settings = settings,
        onThemeModeChange = viewModel::setThemeMode,
        onShowCompletedChange = viewModel::setShowCompleted,
        onRemindersEnabledChange = viewModel::setRemindersEnabled,
        onShoppingModeEnabledChange = viewModel::setShoppingModeEnabled,
        notificationsOff = notificationsOff,
        onFixNotifications = { openNotificationSettings(context) },
        accountSection = { AccountSection() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    settings: AppSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onShowCompletedChange: (Boolean) -> Unit,
    onRemindersEnabledChange: (Boolean) -> Unit = {},
    onShoppingModeEnabledChange: (Boolean) -> Unit = {},
    /** True when this app may not post notifications; the screen then explains and offers the system settings. */
    notificationsOff: Boolean = false,
    onFixNotifications: () -> Unit = {},
    /** The Account block; a slot so this screen's tests need no Hilt. */
    accountSection: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
        contentWindowInsets = NoInsets,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            accountSection()
            HorizontalDivider()
            SectionTitle(R.string.settings_theme)
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = settings.themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.themeMode == mode, onClick = null)
                        Text(
                            text = stringResource(mode.labelRes()),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(
                        value = settings.showCompleted,
                        onValueChange = onShowCompletedChange,
                        role = Role.Switch,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_show_completed))
                    Text(
                        text = stringResource(R.string.settings_show_completed_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.showCompleted, onCheckedChange = null)
            }
            HorizontalDivider()
            SectionTitle(R.string.settings_notifications)
            if (notificationsOff) {
                Text(
                    text = stringResource(R.string.settings_notifications_off),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onFixNotifications)
                        .padding(vertical = 12.dp),
                )
            }
            SwitchRow(
                titleRes = R.string.settings_reminders,
                summaryRes = R.string.settings_reminders_summary,
                checked = settings.remindersEnabled,
                onCheckedChange = onRemindersEnabledChange,
            )
            SwitchRow(
                titleRes = R.string.settings_shopping_mode,
                summaryRes = R.string.settings_shopping_mode_summary,
                checked = settings.shoppingModeEnabled,
                onCheckedChange = onShoppingModeEnabledChange,
            )
            HorizontalDivider()
            SectionTitle(R.string.settings_about)
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(titleRes))
            Text(
                text = stringResource(summaryRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SectionTitle(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
