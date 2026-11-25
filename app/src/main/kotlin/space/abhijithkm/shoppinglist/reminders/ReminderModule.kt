package space.abhijithkm.shoppinglist.reminders

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderModule {
    @Binds
    abstract fun bindAlarmGateway(impl: AlarmManagerGateway): AlarmGateway

    companion object {
        @Provides
        fun provideZoneProvider(): ZoneProvider = ZoneProvider { ZoneId.systemDefault() }
    }
}
