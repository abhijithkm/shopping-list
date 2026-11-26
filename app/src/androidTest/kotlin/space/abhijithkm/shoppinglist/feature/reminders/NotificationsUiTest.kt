package space.abhijithkm.shoppinglist.feature.reminders

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.abhijithkm.shoppinglist.core.model.AppSettings
import space.abhijithkm.shoppinglist.core.model.ThemeMode
import space.abhijithkm.shoppinglist.data.local.ReminderEntity
import space.abhijithkm.shoppinglist.data.local.ReminderRepeat
import space.abhijithkm.shoppinglist.feature.settings.SettingsScreenContent
import space.abhijithkm.shoppinglist.ui.theme.ShoppingListTheme

@RunWith(AndroidJUnit4::class)
class NotificationsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var saved: ReminderEntity? = null
    private var disabled = false
    private var fixClicked = false

    private fun showSheet(initial: ReminderEntity? = null, notificationsOff: Boolean = false) {
        composeRule.setContent {
            ShoppingListTheme(themeMode = ThemeMode.LIGHT) {
                ReminderSheetContent(
                    listId = "l1",
                    initial = initial,
                    notificationsOff = notificationsOff,
                    onSave = { saved = it },
                    onDisable = { disabled = true },
                    onFixNotifications = { fixClicked = true },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun theDeniedStateShowsTheBannerAndTheFixButton() {
        showSheet(notificationsOff = true)

        composeRule.onNodeWithText("Notifications are off", substring = true).assertExists()
        composeRule.onNodeWithText("Open settings").performClick()

        assertTrue(fixClicked)
    }

    @Test
    fun noBannerWhenNotificationsAreOn() {
        showSheet(notificationsOff = false)

        composeRule.onNodeWithText("Notifications are off", substring = true).assertDoesNotExist()
    }

    @Test
    fun aNewReminderDefaultsToDailyAtNineAndOnlyWhenItemsRemain() {
        showSheet()

        composeRule.onNodeWithText("Save").performClick()

        val r = saved!!
        assertEquals("l1", r.listId)
        assertEquals(ReminderRepeat.DAILY, r.repeat)
        assertEquals(9, r.hour)
        assertEquals(0, r.minute)
        assertTrue(r.enabled)
        assertTrue(r.onlyIfItemsRemain)
    }

    @Test
    fun weeklyNeedsADayBeforeItCanBeSaved() {
        showSheet()

        composeRule.onNodeWithText("Weekly").performClick()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()

        composeRule.onNodeWithText("Mon").performClick()
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()

        val r = saved!!
        assertEquals(ReminderRepeat.WEEKLY, r.repeat)
        assertEquals(0b0000001, r.daysOfWeekMask)
    }

    @Test
    fun theOnlyIfItemsAreLeftSwitchIsSaved() {
        showSheet()

        composeRule.onNodeWithText("Only if items are left").performClick()
        composeRule.onNodeWithText("Save").performClick()

        assertFalse(saved!!.onlyIfItemsRemain)
    }

    @Test
    fun anExistingReminderCanBeTurnedOff() {
        showSheet(initial = ReminderEntity(listId = "l1", repeat = ReminderRepeat.DAILY, hour = 18, minute = 30))

        composeRule.onNodeWithText("Turn off reminder").performClick()

        assertTrue(disabled)
    }

    @Test
    fun aNewReminderHasNoTurnOffButton() {
        showSheet()

        composeRule.onNodeWithText("Turn off reminder").assertDoesNotExist()
    }

    @Test
    fun theSettingsSwitchesReportTheirNewValue() {
        var reminders: Boolean? = null
        var shoppingMode: Boolean? = null
        composeRule.setContent {
            ShoppingListTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreenContent(
                    settings = AppSettings(),
                    onThemeModeChange = {},
                    onShowCompletedChange = {},
                    onRemindersEnabledChange = { reminders = it },
                    onShoppingModeEnabledChange = { shoppingMode = it },
                )
            }
        }

        composeRule.onNodeWithText("Reminders").performClick()
        composeRule.onNodeWithText("Shopping mode").performClick()

        assertEquals(false, reminders)
        assertEquals(false, shoppingMode)
    }

    @Test
    fun theSettingsShowAFixRowOnlyWhenNotificationsAreOff() {
        var opened = false
        composeRule.setContent {
            ShoppingListTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreenContent(
                    settings = AppSettings(),
                    onThemeModeChange = {},
                    onShowCompletedChange = {},
                    notificationsOff = true,
                    onFixNotifications = { opened = true },
                )
            }
        }

        composeRule.onNodeWithText("Notifications are off for this app", substring = true).performClick()

        assertTrue(opened)
    }

    @Test
    fun theSettingsHideTheFixRowWhenNotificationsAreOn() {
        composeRule.setContent {
            ShoppingListTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreenContent(
                    settings = AppSettings(),
                    onThemeModeChange = {},
                    onShowCompletedChange = {},
                    notificationsOff = false,
                )
            }
        }

        composeRule.onNodeWithText("Notifications are off for this app", substring = true).assertDoesNotExist()
    }
}
