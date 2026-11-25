package space.abhijithkm.shoppinglist.surfaces

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = SurfaceNotifier(context)

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        NotificationChannels.create(context)
        manager.cancelAll()
    }

    @After
    fun tearDown() = manager.cancelAll()

    private fun active(tag: String) = manager.activeNotifications.filter { it.tag == tag }

    /** Posting and cancelling are asynchronous in the system: wait (up to 5 s) until exactly [expected] are showing, then return the count. */
    private fun awaitActive(tag: String, expected: Int): Int {
        val deadline = System.currentTimeMillis() + 5_000
        while (active(tag).size != expected && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return active(tag).size
    }

    private fun content(listId: String = "l1", name: String = "Groceries", open: Int = 2, total: Int = 3) =
        ShoppingModeContent(listId, name, open, total, listOf("Milk", "Eggs"))

    @Test
    fun shoppingModeIsAnOngoingSilentNotificationWithTheCounts() {
        notifier.postShoppingMode(content())
        assertEquals(1, awaitActive("shopping_mode", 1))

        val posted = active("shopping_mode").single()
        assertTrue(posted.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(NotificationChannels.SHOPPING_MODE, posted.notification.channelId)
        val title = posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertTrue(title, title.contains("Groceries") && title.contains("2 of 3"))
        assertEquals(2, posted.notification.actions.size)
    }

    @Test
    fun postingAgainUpdatesTheSameNotification() {
        notifier.postShoppingMode(content(open = 3))
        notifier.postShoppingMode(content(open = 2))

        assertEquals(1, awaitActive("shopping_mode", 1))
    }

    @Test
    fun cancelExceptKeepsOnlyTheWantedLists() {
        notifier.postShoppingMode(content("a"))
        notifier.postShoppingMode(content("b"))
        notifier.postShoppingMode(content("c"))
        assertEquals(3, awaitActive("shopping_mode", 3))

        notifier.cancelShoppingModeExcept(setOf("b"))

        assertEquals(1, awaitActive("shopping_mode", 1))
        assertEquals(notifier.notificationId("b"), active("shopping_mode").single().id)
    }

    @Test
    fun cancelExceptWithNothingWantedClearsThemAll() {
        notifier.postShoppingMode(content("a"))
        assertEquals(1, awaitActive("shopping_mode", 1))

        notifier.cancelShoppingModeExcept(emptySet())

        assertEquals(0, awaitActive("shopping_mode", 0))
    }

    @Test
    fun aReminderUsesTheRemindersChannelAndGoesAwayWhenTapped() {
        notifier.postReminder("l1", "Groceries", 5)
        assertEquals(1, awaitActive("reminder", 1))

        val posted = active("reminder").single()
        assertEquals(NotificationChannels.REMINDERS, posted.notification.channelId)
        assertTrue(posted.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertNotNull(posted.notification.contentIntent)
        assertNull(active("shopping_mode").firstOrNull())
    }

    @Test
    fun aReminderCarriesTheDefaultSoundAndVibrationForDevicesWithoutChannels() {
        // From Android 8 the platform builder clears `defaults` for channel notifications (sound comes from the channel), so only API 24-25 can show it.
        assumeTrue(Build.VERSION.SDK_INT < 26)
        assertEquals(Notification.DEFAULT_ALL, notifier.buildReminder("l1", "Groceries", 5).defaults)
    }

    @Test
    fun theChannelsExistWithTheRightImportance() {
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(NotificationChannels.REMINDERS).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(NotificationChannels.SHOPPING_MODE).importance)
    }
}
