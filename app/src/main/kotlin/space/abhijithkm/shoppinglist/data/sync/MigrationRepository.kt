package space.abhijithkm.shoppinglist.data.sync

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.session.SessionManager

/** A list that exists only on this device. [nameClash] is true when the account already has a list with that name. */
data class GuestList(val id: String, val name: String, val itemCount: Int, val nameClash: Boolean)

data class MigrationPlan(
    val lists: List<GuestList>,
    /** Guest categories (custom ones and renamed or recoloured built-ins) and tags that would join the account. */
    val vocabularyCount: Int,
) {
    val isEmpty: Boolean get() = lists.isEmpty()
}

data class MigrationResult(val listsMoved: Int, val outcome: SyncOutcome)

/**
 * Guest-to-account migration. Moving a list into the account only changes who owns it (`accountId`), inside one
 * transaction; the upload is then the ordinary sync, which is resumable and idempotent (client UUIDs, 409 means
 * "already there"). So:
 *  - nothing is ever deleted: a list the user does not select stays a guest list, and a failed upload leaves the
 *    list on the device with its pending state, uploaded by a later run;
 *  - order, colours, icons, categories, tags, notes, quantities and bought state travel as they are, because the
 *    rows themselves are what is uploaded (ids and sort keys unchanged);
 *  - running it twice cannot duplicate anything: adopted lists are no longer guest lists, and a list that already
 *    reached the server is found by its id and only differences are patched.
 * Lists whose name matches an account list are flagged so the user can decide; they are never merged silently.
 */
@Singleton
class MigrationRepository @Inject constructor(
    private val db: AppDatabase,
    private val session: SessionManager,
    private val engine: SyncEngine,
) {
    /** What could be moved. Call after a first sync so that [GuestList.nameClash] sees the account's lists. */
    suspend fun plan(): MigrationPlan {
        session.restore()
        val account = session.account?.userId
        val taken = account?.let { db.listDao().listsOf(it) }.orEmpty()
            .filter { it.deletedAt == null }
            .map { it.name.normalizedName() }
            .toSet()
        val lists = db.listDao().guestLists().map {
            GuestList(
                id = it.id,
                name = it.name,
                itemCount = db.itemDao().countLive(it.id),
                nameClash = it.name.normalizedName() in taken,
            )
        }
        val vocabulary = db.categoryDao().guestCount() + db.tagDao().guestCount()
        return MigrationPlan(lists, vocabulary)
    }

    /**
     * Moves [listIds] (and, when [includeVocabulary], the guest categories and tags) into the signed-in account and
     * starts the upload.
     */
    suspend fun migrate(listIds: Set<String>, includeVocabulary: Boolean = true): MigrationResult {
        session.restore()
        val account = session.account?.userId ?: return MigrationResult(0, SyncOutcome.NOT_SIGNED_IN)
        db.withTransaction {
            listIds.forEach { db.listDao().adopt(it, account) }
            if (includeVocabulary) {
                db.categoryDao().adoptGuest(account)
                db.tagDao().adoptGuest(account)
            }
        }
        return MigrationResult(listIds.size, engine.run(SyncTrigger.SIGN_IN).outcome)
    }

    private fun String.normalizedName() = trim().lowercase()
}
