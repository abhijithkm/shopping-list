package space.abhijithkm.shoppinglist.core.model

/** Where a list lives. Guest lists are LOCAL and never leave the device unless migrated explicitly. */
enum class ListOrigin { LOCAL, CLOUD }

/** The signed-in user's role in a list. Local lists are always OWNER. */
enum class MemberRole { OWNER, EDITOR }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showCompleted: Boolean = true,
)

/** What the Lists screen needs about one list, including live item counts. */
data class ListSummary(
    val id: String,
    val name: String,
    val color: Int?,
    val isPinned: Boolean,
    val role: MemberRole,
    val totalCount: Int,
    val boughtCount: Int,
    val updatedAt: Long,
) {
    val remainingCount: Int get() = totalCount - boughtCount
}
