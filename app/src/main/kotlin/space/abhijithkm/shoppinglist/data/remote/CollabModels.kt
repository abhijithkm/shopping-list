package space.abhijithkm.shoppinglist.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire rows of the collaboration tables (backend/apphub/collab-contract.md). Ids of people are AppHub user ids and
 * come back as numbers or strings, so they use the same tolerant serializer as every other id. Statuses stay plain
 * strings here: the local layer maps them, and an unknown value from a newer server never breaks decoding.
 */

/** One membership of a list. Only the server's triggers write these; the client reads whatever rows it may see. */
@Serializable
data class MemberRow(
    val id: String,
    @SerialName("list_id") val listId: String = "",
    @SerialName("user_id") @Serializable(with = FlexStringSerializer::class) val ownerId: String = "",
    @SerialName("member_id") @Serializable(with = FlexStringSerializer::class) val memberId: String = "",
    /** `owner`, `editor` or `removed`. */
    val role: String = "",
    /** For a removed member: `removed` (by the owner) or `left`. */
    val removal: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("created_at_ms") val createdAtMs: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class InvitationRow(
    val id: String,
    @SerialName("list_id") val listId: String = "",
    /** The inviter, who is the owner of the list. */
    @SerialName("user_id") @Serializable(with = FlexStringSerializer::class) val inviterId: String = "",
    @SerialName("invitee_id") @Serializable(with = FlexStringSerializer::class) val inviteeId: String = "",
    val role: String = "editor",
    /** `pending`, `accepted`, `rejected` or `revoked`; "expired" is derived from [expiresAt]. */
    val status: String = "",
    @SerialName("list_name") val listName: String? = null,
    @SerialName("inviter_name") val inviterName: String? = null,
    /** The masked hint of the invited person (never an address), so the owner can tell open invitations apart. */
    @SerialName("display_hint") val displayHint: String? = null,
    /** `DATETIME(3)`, "YYYY-MM-DD HH:MM:SS.mmm", server (UTC) time. */
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at_ms") val createdAtMs: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class JoinRequestRow(
    val id: String,
    @SerialName("list_id") val listId: String = "",
    /** The person asking to join. */
    @SerialName("user_id") @Serializable(with = FlexStringSerializer::class) val requesterId: String = "",
    @SerialName("owner_id") @Serializable(with = FlexStringSerializer::class) val ownerId: String = "",
    /** `pending`, `approved`, `rejected` or `cancelled`. */
    val status: String = "",
    @SerialName("list_name") val listName: String? = null,
    @SerialName("requester_name") val requesterName: String? = null,
    @SerialName("created_at_ms") val createdAtMs: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** The answer to a `user_lookups` insert: whether the exact address belongs to an account, and a masked hint of its name. */
@Serializable
data class UserLookupRow(
    val id: String,
    /** `found` or `not_found`. */
    val status: String = "",
    @SerialName("display_hint") val displayHint: String? = null,
)

/** The answer to a `code_lookups` insert: the safe preview of a code (list name and inviter), or `invalid`. */
@Serializable
data class CodeLookupRow(
    val id: String,
    /** `found` or `invalid`. */
    val status: String = "",
    @SerialName("list_name") val listName: String? = null,
    @SerialName("inviter_name") val inviterName: String? = null,
)
