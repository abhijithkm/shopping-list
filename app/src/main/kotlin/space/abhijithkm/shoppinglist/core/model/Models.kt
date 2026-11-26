package space.abhijithkm.shoppinglist.core.model

/** Where a list lives. Guest lists are LOCAL and never leave the device unless migrated explicitly. */
enum class ListOrigin { LOCAL, CLOUD }

/** The signed-in user's role in a list. Local lists are always OWNER. */
enum class MemberRole { OWNER, EDITOR }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How the Lists screen orders lists. Pinned lists always come first. */
enum class ListSort { RECENT, NAME, CREATED }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showCompleted: Boolean = true,
    val listSort: ListSort = ListSort.RECENT,
    val groupByCategory: Boolean = false,
    /** Local notification when someone invites the account, asks to join its list, or removes it from a list. */
    val notifyInvitations: Boolean = true,
    /** Local notification when other members change a shared list. */
    val notifySharedActivity: Boolean = true,
    /** Master switch for timed reminders: off means no reminder notification, whatever each list has set. */
    val remindersEnabled: Boolean = true,
    /** Master switch for the ongoing "Shopping mode" notifications. */
    val shoppingModeEnabled: Boolean = true,
)

/** Input limits, enforced in the repository (and mirrored by the UI). They also match the planned AppHub column sizes. */
object Limits {
    const val LIST_NAME = 80
    const val ITEM_NAME = 120
    const val ITEM_NOTES = 500
    const val UNIT = 20
    const val CATEGORY_NAME = 40
    const val TAG_NAME = 30
    const val MAX_QUANTITY = 1_000_000.0
}

/** What the Lists screen needs about one list, including live item counts. */
data class ListSummary(
    val id: String,
    val name: String,
    val color: Int?,
    val icon: String? = null,
    val isPinned: Boolean,
    val isArchived: Boolean = false,
    val role: MemberRole,
    val totalCount: Int,
    val boughtCount: Int,
    val createdAt: Long = 0L,
    val updatedAt: Long,
    /** Owner and editors this device knows of. More than one, or a list somebody else owns, means the list is shared. */
    val memberCount: Int = 0,
    /** Display name of the owner when the list belongs to someone else. */
    val ownerName: String? = null,
    /** True for a list that belongs to the signed-in account (it is, or will be, on the server); false for guest data. */
    val isInAccount: Boolean = false,
) {
    val remainingCount: Int get() = totalCount - boughtCount

    val isShared: Boolean get() = role == MemberRole.EDITOR || memberCount > 1

    /** Only the owner may rename, archive, pin, recolour or delete the list; editors work on its items. */
    val canManage: Boolean get() = role == MemberRole.OWNER
}

data class Tag(val id: String, val name: String)

/**
 * A category as shown in the UI: one of the 11 built-ins (optionally renamed or recoloured by the
 * user) or a custom one. [name] is null for an un-renamed built-in; the UI then uses the built-in
 * label.
 */
data class Category(
    val key: String,
    val name: String?,
    val color: Int,
    val defaultCategory: DefaultCategory?,
    val isCustomized: Boolean,
) {
    val isDefault: Boolean get() = defaultCategory != null
}

data class Item(
    val id: String,
    val listId: String,
    val name: String,
    val quantity: Double?,
    val unit: String?,
    val categoryKey: String?,
    val notes: String?,
    val isBought: Boolean,
    val boughtAt: Long?,
    val sortOrder: Double,
    val tags: List<Tag>,
    /** AppHub id of the person who added the item (shared lists); null for guest data. */
    val createdBy: String? = null,
)

/** Everything the edit sheet can change on an item. */
data class ItemUpdate(
    val id: String,
    val name: String,
    val quantity: Double?,
    val unit: String?,
    val categoryKey: String?,
    val notes: String?,
    val tagIds: Set<String>,
)
