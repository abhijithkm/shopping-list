package space.abhijithkm.shoppinglist.feature.reminders

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** ACTION_APP_NOTIFICATION_SETTINGS exists from Android 8 only: on Android 7 the "fix" row must open the app's details instead of crashing. */
@RunWith(AndroidJUnit4::class)
class NotificationSettingsIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun fromAndroid8TheAppNotificationSettingsOpen() {
        val intent = notificationSettingsIntent(context, sdk = 26)

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun onAndroid7TheAppDetailsOpenInstead() {
        val intent = notificationSettingsIntent(context, sdk = 25)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }
}
