package space.abhijithkm.shoppinglist.feature.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import space.abhijithkm.shoppinglist.R
import space.abhijithkm.shoppinglist.data.local.ReminderEntity
import space.abhijithkm.shoppinglist.data.local.ReminderRepeat

/**
 * The reminder editor of one list, as a bottom sheet. Saving always stores the reminder; if notifications are not allowed
 * yet the permission is asked, and a denial leaves the sheet open with the "notifications are off" explanation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSheet(onDismiss: () -> Unit, viewModel: ReminderViewModel = hiltViewModel()) {
    val reminder by viewModel.reminder.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsOff by remember { mutableStateOf(!notificationsEnabled(context)) }
    val requestPermission = rememberNotificationPermissionRequester {
        if (notificationsEnabled(context)) onDismiss() else notificationsOff = true
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // The stored reminder arrives a moment after the sheet opens: rebuild the editor once when it does.
        key(reminder != null) {
            ReminderSheetContent(
                listId = viewModel.listId,
                initial = reminder,
                notificationsOff = notificationsOff,
                onSave = { saved ->
                    viewModel.save(saved)
                    if (notificationsEnabled(context)) onDismiss() else requestPermission()
                },
                onDisable = {
                    viewModel.disable()
                    onDismiss()
                },
                onFixNotifications = { openNotificationSettings(context) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSheetContent(
    listId: String,
    initial: ReminderEntity?,
    notificationsOff: Boolean,
    onSave: (ReminderEntity) -> Unit,
    onDisable: () -> Unit,
    onFixNotifications: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val locale: Locale = LocalConfiguration.current.locales[0] // observable, unlike Locale.getDefault()
    var repeat by rememberSaveable { mutableStateOf(initial?.repeat ?: ReminderRepeat.DAILY) }
    var hour by rememberSaveable { mutableIntStateOf(initial?.hour ?: 9) }
    var minute by rememberSaveable { mutableIntStateOf(initial?.minute ?: 0) }
    var mask by rememberSaveable { mutableIntStateOf(initial?.daysOfWeekMask ?: 0) }
    var onlyIfItemsRemain by rememberSaveable { mutableStateOf(initial?.onlyIfItemsRemain ?: true) }
    var epochDay by rememberSaveable {
        mutableLongStateOf(
            initial?.oneTimeAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
                ?: LocalDate.now().plusDays(1).toEpochDay(),
        )
    }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    fun oneTimeMillis(): Long =
        LocalDate.ofEpochDay(epochDay).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    val valid = when (repeat) {
        ReminderRepeat.WEEKLY -> mask != 0
        ReminderRepeat.NONE -> oneTimeMillis() > System.currentTimeMillis()
        ReminderRepeat.DAILY -> true
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.reminder_title), style = MaterialTheme.typography.titleLarge)

        if (notificationsOff) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.reminder_notifications_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFixNotifications) { Text(stringResource(R.string.reminder_open_settings)) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RepeatChip(R.string.reminder_repeat_once, repeat == ReminderRepeat.NONE) { repeat = ReminderRepeat.NONE }
            RepeatChip(R.string.reminder_repeat_daily, repeat == ReminderRepeat.DAILY) { repeat = ReminderRepeat.DAILY }
            RepeatChip(R.string.reminder_repeat_weekly, repeat == ReminderRepeat.WEEKLY) { repeat = ReminderRepeat.WEEKLY }
        }

        if (repeat == ReminderRepeat.WEEKLY) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (index in 0..6) {
                    val label = DayOfWeek.of(index + 1).getDisplayName(TextStyle.SHORT, locale)
                    FilterChip(
                        selected = mask and (1 shl index) != 0,
                        onClick = { mask = mask xor (1 shl index) },
                        label = { Text(label) },
                    )
                }
            }
        }

        if (repeat == ReminderRepeat.NONE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.reminder_date), modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { pickingDate = true }) {
                    Text(LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.reminder_time), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { pickingTime = true }) { Text(String.format(locale, "%02d:%02d", hour, minute)) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = onlyIfItemsRemain, onValueChange = { onlyIfItemsRemain = it }, role = Role.Switch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.reminder_only_if_items), modifier = Modifier.weight(1f))
            Switch(checked = onlyIfItemsRemain, onCheckedChange = null)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (initial != null && initial.enabled) {
                TextButton(onClick = onDisable) { Text(stringResource(R.string.reminder_turn_off)) }
            }
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Button(
                    enabled = valid,
                    onClick = {
                        onSave(
                            ReminderEntity(
                                listId = listId,
                                enabled = true,
                                repeat = repeat,
                                hour = hour,
                                minute = minute,
                                daysOfWeekMask = if (repeat == ReminderRepeat.WEEKLY) mask else 0,
                                oneTimeAtMillis = if (repeat == ReminderRepeat.NONE) oneTimeMillis() else null,
                                onlyIfItemsRemain = onlyIfItemsRemain,
                                lastFiredAt = initial?.lastFiredAt,
                            ),
                        )
                    },
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }

    if (pickingTime) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    hour = state.hour
                    minute = state.minute
                    pickingTime = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state = state) },
        )
    }

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { epochDay = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate().toEpochDay() }
                    pickingDate = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun RepeatChip(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(labelRes)) })
}
