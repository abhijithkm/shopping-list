package space.abhijithkm.shoppinglist.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text

/** The home-screen widget. Task 1 is only the skeleton; the real content arrives with the widget task. */
class ShoppingListWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Text("Shopping list") }
    }
}
