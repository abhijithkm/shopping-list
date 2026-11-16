package space.abhijithkm.shoppinglist.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.core.util.DeviceClock
import space.abhijithkm.shoppinglist.core.util.IdGenerator
import space.abhijithkm.shoppinglist.core.util.UuidGenerator
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.local.ItemDao
import space.abhijithkm.shoppinglist.data.local.ShoppingListDao

@Module
@InstallIn(SingletonComponent::class)
abstract class UtilModule {
    @Binds
    abstract fun bindClock(impl: DeviceClock): Clock

    @Binds
    abstract fun bindIdGenerator(impl: UuidGenerator): IdGenerator
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    // No destructive migration fallback: losing a user's lists silently is never acceptable.
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "shoppinglist.db").build()

    @Provides
    fun provideListDao(db: AppDatabase): ShoppingListDao = db.listDao()

    @Provides
    fun provideItemDao(db: AppDatabase): ItemDao = db.itemDao()
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") })
}
