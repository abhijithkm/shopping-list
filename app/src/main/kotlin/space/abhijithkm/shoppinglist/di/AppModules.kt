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
import javax.inject.Named
import javax.inject.Singleton
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.core.util.DeviceClock
import space.abhijithkm.shoppinglist.core.util.IdGenerator
import space.abhijithkm.shoppinglist.core.util.UuidGenerator
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.local.ItemDao
import space.abhijithkm.shoppinglist.data.local.ShoppingListDao
import space.abhijithkm.shoppinglist.data.local.CollabDao
import space.abhijithkm.shoppinglist.data.local.SyncDao
import space.abhijithkm.shoppinglist.data.repository.AccountScope
import space.abhijithkm.shoppinglist.data.repository.SessionAccountScope
import space.abhijithkm.shoppinglist.data.session.AesGcmSecretCipher
import space.abhijithkm.shoppinglist.data.session.DataStoreSessionStorage
import space.abhijithkm.shoppinglist.data.session.KeystoreKeyProvider
import space.abhijithkm.shoppinglist.data.session.SecretCipher
import space.abhijithkm.shoppinglist.data.session.SessionStorage
import space.abhijithkm.shoppinglist.data.collab.CollabNotifier
import space.abhijithkm.shoppinglist.data.sync.SyncObserver
import space.abhijithkm.shoppinglist.data.sync.SyncScheduler
import space.abhijithkm.shoppinglist.data.sync.WorkManagerSyncScheduler

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
abstract class AccountModule {
    @Binds
    abstract fun bindAccountScope(impl: SessionAccountScope): AccountScope

    @Binds
    abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    /** Local notifications follow what each sync run found. */
    @Binds
    abstract fun bindSyncObserver(impl: CollabNotifier): SyncObserver
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

    @Provides
    fun provideSyncDao(db: AppDatabase): SyncDao = db.syncDao()

    @Provides
    fun provideCollabDao(db: AppDatabase): CollabDao = db.collabDao()
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") })

    /** Sign-in session: identity in clear, refresh token encrypted by [SecretCipher]. Never backed up. */
    @Provides
    @Singleton
    @Named(SESSION_STORE)
    fun provideSessionDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("session") })

    @Provides
    @Singleton
    fun provideSecretCipher(): SecretCipher = AesGcmSecretCipher(KeystoreKeyProvider())

    @Provides
    @Singleton
    fun provideSessionStorage(@Named(SESSION_STORE) store: DataStore<Preferences>, cipher: SecretCipher): SessionStorage =
        DataStoreSessionStorage(store, cipher)
}

private const val SESSION_STORE = "session"
