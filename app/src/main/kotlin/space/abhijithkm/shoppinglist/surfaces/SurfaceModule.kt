package space.abhijithkm.shoppinglist.surfaces

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.abhijithkm.shoppinglist.data.repository.NotificationPrefs
import space.abhijithkm.shoppinglist.data.repository.SettingsNotificationPrefs

@Module
@InstallIn(SingletonComponent::class)
abstract class SurfaceModule {
    @Binds
    abstract fun bindChangeSource(impl: RoomChangeSource): ChangeSource

    @Binds
    abstract fun bindSurfaceRefresher(impl: DefaultSurfaceRefresher): SurfaceRefresher

    @Binds
    abstract fun bindNotificationPrefs(impl: SettingsNotificationPrefs): NotificationPrefs
}
