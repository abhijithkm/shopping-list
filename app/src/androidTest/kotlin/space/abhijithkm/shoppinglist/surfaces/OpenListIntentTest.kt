package space.abhijithkm.shoppinglist.surfaces

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.abhijithkm.shoppinglist.MainActivity

@RunWith(AndroidJUnit4::class)
class OpenListIntentTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun theIntentTargetsMainActivityAndCarriesTheListId() {
        val intent = openListIntent(context, "list-42")

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("list-42", intent.getStringExtra(EXTRA_OPEN_LIST))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun theRequestIsReadBackWithItsToken() {
        val request = openListIntent(context, "list-42").toOpenListRequest(token = 7L)

        assertEquals(OpenListRequest("list-42", 7L), request)
    }

    @Test
    fun aMissingOrBlankExtraGivesNoRequest() {
        assertNull(Intent().toOpenListRequest(1L))
        assertNull(Intent().putExtra(EXTRA_OPEN_LIST, "  ").toOpenListRequest(1L))
    }
}
