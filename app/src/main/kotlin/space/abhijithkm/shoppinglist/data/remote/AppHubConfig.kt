package space.abhijithkm.shoppinglist.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Where AppHub lives. Both values are public (no secret ships in the APK): [baseUrl] is the AppHub
 * root such as `https://host/apphub/`, [app] the AppHub app (database) name. The build supplies them
 * from `BuildConfig` (debug talks to the local fake AppHub, release to the cloud app).
 */
data class AppHubConfig(val baseUrl: String, val app: String) {
    /** Retrofit base URL: root + app, always ending in "/". */
    val appUrl: String = baseUrl.trimEnd('/') + "/" + app.trim('/') + "/"
}

/** One Retrofit instance per OkHttp client; all use [AppHubJson]. */
fun appHubRetrofit(config: AppHubConfig, client: OkHttpClient, json: Json = AppHubJson): Retrofit =
    Retrofit.Builder()
        .baseUrl(config.appUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
