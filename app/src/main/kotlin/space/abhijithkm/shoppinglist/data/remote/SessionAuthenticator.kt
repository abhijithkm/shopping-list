package space.abhijithkm.shoppinglist.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import space.abhijithkm.shoppinglist.data.session.RefreshResult
import space.abhijithkm.shoppinglist.data.session.SessionManager

/** Adds the current access token (if any) to every request of the signed-in client. */
class SessionAuthInterceptor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(AUTHORIZATION) != null) return chain.proceed(request)
        val token = session.currentAccessToken() ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(AUTHORIZATION, "Bearer $token").build())
    }
}

/**
 * On a 401 asks the [SessionManager] for a fresh access token (one shared refresh, however many requests
 * failed together) and repeats the request once. Returning null leaves the 401 to the caller, which decides
 * from the session state whether the session ended or the refresh was merely unavailable.
 *
 * Only the signed-in client has this authenticator. Sign-in, registration and the refresh call itself use the
 * plain client, so a wrong password (401) can never trigger a refresh and a failed refresh can never loop.
 */
class SessionAuthenticator(private val session: SessionManager) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        // One retry per call: a 401 on the retried request is final.
        if (response.priorResponse != null) return null
        val rejected = response.request.header(AUTHORIZATION)?.removePrefix("Bearer ")
        val result = runBlocking { session.refreshAfterRejection(rejected) }
        val token = (result as? RefreshResult.Token)?.accessToken ?: return null
        return response.request.newBuilder().header(AUTHORIZATION, "Bearer $token").build()
    }
}

private const val AUTHORIZATION = "Authorization"
