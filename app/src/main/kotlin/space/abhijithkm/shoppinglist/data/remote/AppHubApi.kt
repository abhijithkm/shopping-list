package space.abhijithkm.shoppinglist.data.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Authentication endpoints, relative to `{base}/{app}/`. Used by the plain HTTP client (no
 * Authorization header, no refresh authenticator) for everything except [me], [access] and
 * [changePassword], which need a signed-in client; see `NetworkModule`.
 */
interface AppHubAuthApi {
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): Envelope<RegisterResult>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Envelope<TokenPair>

    /** One-time refresh token: the caller must persist the answer before using it and must never retry. */
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Envelope<TokenPair>

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Envelope<JsonElement>

    @POST("auth/forgot")
    suspend fun forgot(@Body body: ForgotRequest): Envelope<JsonElement>
}

/** Endpoints that need the signed-in client. */
interface AppHubAccountApi {
    @GET("auth/me")
    suspend fun me(): Envelope<AppHubUser>

    @GET("auth/access")
    suspend fun access(): Envelope<JsonObject>

    @POST("auth/password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): Envelope<JsonElement>
}

/**
 * Generic table access, one interface for the four cloud tables. Rows are raw JSON objects so a PATCH
 * can send exactly the changed fields (including explicit nulls) and [RowCodec] decodes them into typed rows.
 */
interface AppHubTableApi {
    @GET("{table}")
    suspend fun list(
        @Path("table") table: String,
        @Query("sort") sort: String = "updated_at",
        @Query("order") order: String = "desc",
        @Query("limit") limit: Int = PAGE_LIMIT,
        @Query("page") page: Int = 1,
    ): Envelope<List<JsonObject>>

    @GET("{table}/{id}")
    suspend fun get(@Path("table") table: String, @Path("id") id: String): Envelope<JsonObject>

    @POST("{table}")
    suspend fun create(@Path("table") table: String, @Body row: JsonObject): Envelope<JsonObject>

    @PATCH("{table}/{id}")
    suspend fun patch(
        @Path("table") table: String,
        @Path("id") id: String,
        @Body fields: JsonObject,
    ): Envelope<JsonObject>

    companion object {
        /** AppHub's maximum page size. */
        const val PAGE_LIMIT = 100
    }
}
