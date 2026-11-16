package space.abhijithkm.shoppinglist.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient
import space.abhijithkm.shoppinglist.BuildConfig
import space.abhijithkm.shoppinglist.data.remote.AppHubAccountApi
import space.abhijithkm.shoppinglist.data.remote.AppHubAuthApi
import space.abhijithkm.shoppinglist.data.remote.AppHubConfig
import space.abhijithkm.shoppinglist.data.remote.AppHubHttp
import space.abhijithkm.shoppinglist.data.remote.AppHubTableApi
import space.abhijithkm.shoppinglist.data.remote.appHubRetrofit
import space.abhijithkm.shoppinglist.data.session.SessionManager

/** Qualifier name of the OkHttp client with no Authorization header and no refresh authenticator. */
const val PLAIN_CLIENT = "plain"

/** Qualifier name of the signed-in client (access token + refresh authenticator). */
const val AUTHED_CLIENT = "authed"

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideAppHubConfig(): AppHubConfig = AppHubConfig(BuildConfig.APPHUB_BASE_URL, BuildConfig.APPHUB_APP)

    /** Sign-in, registration, logout and the token refresh: no retry, a fresh connection per call, no redirects. See [AppHubHttp]. */
    @Provides
    @Singleton
    @Named(PLAIN_CLIENT)
    fun providePlainClient(): OkHttpClient = AppHubHttp.authClient()

    @Provides
    @Singleton
    fun provideAuthApi(config: AppHubConfig, @Named(PLAIN_CLIENT) client: OkHttpClient): AppHubAuthApi =
        appHubRetrofit(config, client).create(AppHubAuthApi::class.java)

    /** The signed-in client: access token, one shared refresh on a 401, its own dispatcher and pool. See [AppHubHttp]. */
    @Provides
    @Singleton
    @Named(AUTHED_CLIENT)
    fun provideAuthedClient(session: SessionManager): OkHttpClient = AppHubHttp.dataClient(session)

    @Provides
    @Singleton
    fun provideTableApi(config: AppHubConfig, @Named(AUTHED_CLIENT) client: OkHttpClient): AppHubTableApi =
        appHubRetrofit(config, client).create(AppHubTableApi::class.java)

    @Provides
    @Singleton
    fun provideAccountApi(config: AppHubConfig, @Named(AUTHED_CLIENT) client: OkHttpClient): AppHubAccountApi =
        appHubRetrofit(config, client).create(AppHubAccountApi::class.java)
}
