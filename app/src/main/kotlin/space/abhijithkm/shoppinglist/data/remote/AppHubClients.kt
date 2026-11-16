package space.abhijithkm.shoppinglist.data.remote

import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import space.abhijithkm.shoppinglist.data.session.SessionManager

/**
 * The two HTTP client configurations used against AppHub, in one place so the app and its tests use exactly the same.
 * There is deliberately no logging interceptor: bodies and headers hold passwords and tokens.
 */
object AppHubHttp {
    /**
     * For sign-in, registration, logout, password reset and the token refresh (no Authorization header, no
     * authenticator).
     *  - No automatic retry: AppHub refresh tokens work once, so a transparent resend of a refresh whose answer was
     *    lost would present a used token and end the whole session.
     *  - A **fresh connection for every call** (`Connection: close`): the real server closes idle keep-alive
     *    connections after a few seconds, and without a retry the first call after a pause on such a connection would
     *    fail as a false "can't reach the server" (found against the real cloud app). These calls are rare, so the
     *    extra TLS handshake costs nothing that matters.
     *  - No redirects: a redirect would resend a password or one-time token to wherever it points.
     */
    fun authClient(): OkHttpClient = builder()
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Connection", "close").build()) }
        .build()

    /**
     * For the data calls of a signed-in user: adds the access token and, on a 401, shares one refresh and repeats the
     * request once. It has its own dispatcher (the authenticator blocks a thread of this client while the refresh call,
     * on [authClient], needs a connection) and its own pool. Data calls are idempotent by design (client ids,
     * PATCH of fields), so the automatic retry after a dropped pooled connection is safe here.
     */
    fun dataClient(session: SessionManager): OkHttpClient = builder()
        .dispatcher(Dispatcher())
        .retryOnConnectionFailure(true)
        .addInterceptor(SessionAuthInterceptor(session))
        .authenticator(SessionAuthenticator(session))
        .build()

    private fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
}
