package space.abhijithkm.shoppinglist.surfaces

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SurfaceModule {
    @Binds
    abstract fun bindChangeSource(impl: RoomChangeSource): ChangeSource

    @Binds
    abstract fun bindSurfaceRefresher(impl: DefaultSurfaceRefresher): SurfaceRefresher
}
