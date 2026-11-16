package space.abhijithkm.shoppinglist.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import space.abhijithkm.shoppinglist.BuildConfig
import space.abhijithkm.shoppinglist.data.remote.AppHubAccountApi
import space.abhijithkm.shoppinglist.data.remote.AppHubAuthApi
import space.abhijithkm.shoppinglist.data.remote.AppHubConfig
import space.abhijithkm.shoppinglist.data.remote.AppHubTableApi
import space.abhijithkm.shoppinglist.data.remote.SessionAuthInterceptor
import space.abhijithkm.shoppinglist.data.remote.SessionAuthenticator
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

    /**
     * Used for sign-in, registration, logout and the token refresh. `retryOnConnectionFailure(false)`
     * matters: AppHub refresh tokens work once, so a transparent resend of a refresh whose answer was
     * lost would present an already-used token and end the whole session. There is deliberately no
     * logging interceptor: bodies and headers hold passwords and tokens.
     */
    @Provides
    @Singleton
    @Named(PLAIN_CLIENT)
    fun providePlainClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        // A redirect would resend the request body (a password or a one-time refresh token) to wherever it points.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Provides
    @Singleton
    fun provideAuthApi(config: AppHubConfig, @Named(PLAIN_CLIENT) client: OkHttpClient): AppHubAuthApi =
        appHubRetrofit(config, client).create(AppHubAuthApi::class.java)

    /**
     * The signed-in client: adds the access token and, on a 401, shares one refresh and repeats the request
     * once. Built from the plain client so both share a connection pool and timeouts.
     */
    @Provides
    @Singleton
    @Named(AUTHED_CLIENT)
    fun provideAuthedClient(@Named(PLAIN_CLIENT) plain: OkHttpClient, session: SessionManager): OkHttpClient =
        plain.newBuilder()
            // Its own dispatcher: the authenticator blocks a thread of this client while the refresh call (on the
            // plain client) needs a slot, so the two must not compete for the same per-host limit.
            .dispatcher(Dispatcher())
            .addInterceptor(SessionAuthInterceptor(session))
            .authenticator(SessionAuthenticator(session))
            // Data calls are idempotent by design (client ids, PATCH of fields), so the transparent
            // resend after a dropped pooled connection is safe here; the refresh call never goes through this client.
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    fun provideTableApi(config: AppHubConfig, @Named(AUTHED_CLIENT) client: OkHttpClient): AppHubTableApi =
        appHubRetrofit(config, client).create(AppHubTableApi::class.java)

    @Provides
    @Singleton
    fun provideAccountApi(config: AppHubConfig, @Named(AUTHED_CLIENT) client: OkHttpClient): AppHubAccountApi =
        appHubRetrofit(config, client).create(AppHubAccountApi::class.java)
}
