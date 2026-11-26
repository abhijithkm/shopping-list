package space.abhijithkm.shoppinglist.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.abhijithkm.shoppinglist.data.local.ItemDao
import space.abhijithkm.shoppinglist.data.local.ShoppingListDao
import space.abhijithkm.shoppinglist.data.repository.LocalShoppingRepository

/** Gives the widget and its action callbacks (which Hilt does not create) access to the app's data. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun lists(): ShoppingListDao

    fun items(): ItemDao

    fun repository(): LocalShoppingRepository
}
