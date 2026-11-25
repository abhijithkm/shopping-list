package space.abhijithkm.shoppinglist.feature.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** True when this app may post notifications (permission granted on Android 13+, and not switched off in system settings). */
fun notificationsEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

/**
 * The system screen where a denied permission or a blocked notification can be fixed. ACTION_APP_NOTIFICATION_SETTINGS exists
 * from Android 8; on Android 7 and 7.1 (minSdk is 24) the app's details screen opens instead.
 */
@SuppressLint("InlinedApi")
fun notificationSettingsIntent(context: Context, sdk: Int = Build.VERSION.SDK_INT): Intent {
    val intent = if (sdk >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
    return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Opens the system's notification settings for this app; a device without that screen does nothing instead of crashing. */
fun openNotificationSettings(context: Context) {
    try {
        context.startActivity(notificationSettingsIntent(context))
    } catch (_: ActivityNotFoundException) {
        // No settings screen to open: nothing more to offer.
    }
}

/**
 * Returns a function that asks for the notification permission when that is needed (Android 13+ and not granted yet) and
 * reports the outcome through [onResult]; on older versions, or when already granted, it reports `true` at once.
 * The permission is asked in context, when a reminder or shopping mode is switched on, never at app start.
 */
@Composable
fun rememberNotificationPermissionRequester(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        currentOnResult(granted)
    }
    return remember(launcher, context) {
        {
            val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needed) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else currentOnResult(true)
        }
    }
}
