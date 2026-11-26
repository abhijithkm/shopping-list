package space.abhijithkm.shoppinglist.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Wire types for AppHub (docs/APPHUB_INTEGRATION.md). Every answer is an envelope; the data rows are
 * handled as raw JSON objects by [AppHubTableApi] and decoded into the row types below by [RowCodec].
 * Unknown fields are ignored on purpose so a server that adds a column never breaks the app.
 */
@Serializable
data class Envelope<T>(
    val ok: Boolean = false,
    val data: T? = null,
    val meta: Meta? = null,
    val error: ApiError? = null,
)

@Serializable
data class Meta(
    val page: Int = 1,
    val limit: Int = 0,
    val total: Int = 0,
    val pages: Int = 0,
)

@Serializable
data class ApiError(
    val status: Int = 0,
    val message: String = "",
    val details: JsonElement? = null,
    @SerialName("request_id") val requestId: String? = null,
)

// ---------------------------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------------------------

// Requests that carry a secret print without it, so a stray log line or crash report cannot leak one.

@Serializable
data class LoginRequest(val email: String, val password: String) {
    override fun toString(): String = "LoginRequest(<hidden>)"
}

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String? = null,
) {
    override fun toString(): String = "RegisterRequest(<hidden>)"
}

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String) {
    override fun toString(): String = "RefreshRequest(<hidden>)"
}

@Serializable
data class ForgotRequest(val email: String)

@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
) {
    override fun toString(): String = "ChangePasswordRequest(<hidden>)"
}

@Serializable
data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = DEFAULT_ACCESS_TTL_SECONDS,
    @SerialName("token_type") val tokenType: String? = null,
    val user: AppHubUser? = null,
) {
    // Never print tokens (logs, crash reports): the default data-class toString would.
    override fun toString(): String = "TokenPair(expiresIn=$expiresIn, user=$user)"

    companion object {
        const val DEFAULT_ACCESS_TTL_SECONDS = 900L
    }
}

@Serializable
data class AppHubUser(
    @Serializable(with = FlexStringSerializer::class) val id: String = "",
    val email: String = "",
    @SerialName("display_name") val displayName: String? = null,
    val role: String? = null,
)

@Serializable
data class RegisterResult(
    val user: AppHubUser? = null,
    @SerialName("verification_email_sent") val verificationEmailSent: Boolean = false,
)

// ---------------------------------------------------------------------------------------------
// Rows. AppHub returns DECIMAL as a string, TINYINT(1) as 0/1 and ids as numbers or strings, so the
// "Flex" serializers accept either form instead of failing on a harmless representation change.
// ---------------------------------------------------------------------------------------------

object FlexStringSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("FlexString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeString()
        return (element as? JsonPrimitive)?.content ?: throw SerializationException("Expected a scalar, got $element")
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

object FlexBooleanSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("FlexBoolean", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Boolean {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeBoolean()
        val primitive = element as? JsonPrimitive ?: throw SerializationException("Expected a scalar, got $element")
        return primitive.booleanOrNull ?: when (primitive.content) {
            "1" -> true
            "0" -> false
            else -> throw SerializationException("Not a boolean: ${primitive.content}")
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

object FlexIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("FlexInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeInt()
        val primitive = element as? JsonPrimitive ?: throw SerializationException("Expected a scalar, got $element")
        return primitive.content.toIntOrNull() ?: primitive.doubleOrNull?.toInt() ?: throw SerializationException("Not a number: ${primitive.content}")
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

object FlexDoubleSerializer : KSerializer<Double> {
    override val descriptor = PrimitiveSerialDescriptor("FlexDouble", PrimitiveKind.DOUBLE)
    override fun deserialize(decoder: Decoder): Double {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeDouble()
        val primitive = element as? JsonPrimitive ?: throw SerializationException("Expected a scalar, got $element")
        return primitive.doubleOrNull ?: throw SerializationException("Not a number: ${primitive.content}")
    }

    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

/** Columns every synced table carries (docs/APPHUB_INTEGRATION.md). */
interface SyncedRow {
    val id: String
    val createdAtMs: Long
    val deletedAtMs: Long?

    /** Server-managed `DATETIME(3)`, "YYYY-MM-DD HH:MM:SS.mmm"; the pull cursor. Compared only with other server values. */
    val updatedAt: String?
}

@Serializable
data class ListRow(
    override val id: String,
    val name: String = "",
    val description: String? = null,
    val icon: String? = null,
    val color: Long? = null,
    @SerialName("is_archived") @Serializable(with = FlexBooleanSerializer::class) val isArchived: Boolean = false,
    @SerialName("is_pinned") @Serializable(with = FlexBooleanSerializer::class) val isPinned: Boolean = false,
    @SerialName("sort_key") @Serializable(with = FlexDoubleSerializer::class) val sortKey: Double = 0.0,
    /** Client clock of the last user edit (shown as "updated"); not the server-managed [updatedAt]. */
    @SerialName("updated_at_ms") val updatedAtMs: Long = 0,
    @SerialName("created_at_ms") override val createdAtMs: Long = 0,
    @SerialName("deleted_at_ms") override val deletedAtMs: Long? = null,
    @SerialName("updated_at") override val updatedAt: String? = null,
    /** The owner's AppHub id (the owner column). Differs from the signed-in user for a list shared with them. */
    @SerialName("user_id") @Serializable(with = FlexStringSerializer::class) val userId: String? = null,
) : SyncedRow

@Serializable
data class ItemRow(
    override val id: String,
    @SerialName("list_id") val listId: String = "",
    val name: String = "",
    @Serializable(with = FlexDoubleSerializer::class) val quantity: Double? = null,
    val unit: String? = null,
    @SerialName("category_key") val categoryKey: String? = null,
    val notes: String? = null,
    @SerialName("is_bought") @Serializable(with = FlexBooleanSerializer::class) val isBought: Boolean = false,
    @SerialName("bought_at_ms") val boughtAtMs: Long? = null,
    @SerialName("sort_order") @Serializable(with = FlexDoubleSerializer::class) val sortOrder: Double = 0.0,
    /** JSON text holding an array of tag ids, e.g. `["uuid1","uuid2"]` (no item_tags table in the cloud). */
    @SerialName("tag_ids") val tagIds: String? = null,
    @SerialName("updated_at_ms") val updatedAtMs: Long = 0,
    @SerialName("created_at_ms") override val createdAtMs: Long = 0,
    @SerialName("deleted_at_ms") override val deletedAtMs: Long? = null,
    @SerialName("updated_at") override val updatedAt: String? = null,
    /** The creator's AppHub id (the owner column of the item). */
    @SerialName("user_id") @Serializable(with = FlexStringSerializer::class) val userId: String? = null,
    /** Who changed the row last (set by the server's trigger); null on a server without collaboration. */
    @SerialName("updated_by") @Serializable(with = FlexStringSerializer::class) val updatedBy: String? = null,
    /** The server's per-row edit counter, sent back as `base_revision` so a concurrent edit is detected; 0 = server has none. */
    @Serializable(with = FlexIntSerializer::class) val revision: Int = 0,
) : SyncedRow

@Serializable
data class CategoryRow(
    override val id: String,
    /** Set when this row renames or recolours a built-in category (e.g. "dairy"); null for custom categories. */
    @SerialName("builtin_key") val builtinKey: String? = null,
    val name: String? = null,
    val color: Long? = null,
    @SerialName("created_at_ms") override val createdAtMs: Long = 0,
    @SerialName("deleted_at_ms") override val deletedAtMs: Long? = null,
    @SerialName("updated_at") override val updatedAt: String? = null,
) : SyncedRow

@Serializable
data class TagRow(
    override val id: String,
    val name: String = "",
    @SerialName("created_at_ms") override val createdAtMs: Long = 0,
    @SerialName("deleted_at_ms") override val deletedAtMs: Long? = null,
    @SerialName("updated_at") override val updatedAt: String? = null,
) : SyncedRow

/** Cloud table names. */
object Tables {
    const val LISTS = "shopping_lists"
    const val ITEMS = "shopping_items"
    const val CATEGORIES = "categories"
    const val TAGS = "tags"

    // Collaboration (Phase 4, backend/apphub/collab-contract.md). Absent on a server that has not been provisioned for it.
    const val MEMBERS = "list_members"
    const val INVITATIONS = "list_invitations"
    const val JOIN_REQUESTS = "join_requests"
    const val USER_LOOKUPS = "user_lookups"
    const val JOIN_CODES = "join_codes"
    const val CODE_LOOKUPS = "code_lookups"
    const val COMMANDS = "list_commands"
}

/** The one JSON configuration used for every request and every answer. */
val AppHubJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}
