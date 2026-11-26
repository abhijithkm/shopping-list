package space.abhijithkm.shoppinglist.data.sync

import androidx.room.withTransaction
import space.abhijithkm.shoppinglist.core.model.MemberRole
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.local.InvitationEntity
import space.abhijithkm.shoppinglist.data.local.JoinRequestEntity
import space.abhijithkm.shoppinglist.data.local.MemberEntity
import space.abhijithkm.shoppinglist.data.remote.InvitationRow
import space.abhijithkm.shoppinglist.data.remote.JoinRequestRow
import space.abhijithkm.shoppinglist.data.remote.MemberRow

/** What one membership snapshot changed on this device. */
internal class MembersApplied(
    /** A list this account may now see is not on the device yet: its rows are older than the pull cursors, read everything. */
    val needsFullListPull: Boolean,
    /** Lists whose local copy was removed because the account lost access to them. */
    val removedListIds: List<String>,
)

/**
 * Applies complete snapshots of the collaboration tables to the local caches. A snapshot is everything the account may
 * see, so rows missing from it are gone from the server's point of view. The server decides; this class only mirrors
 * it, and also drops the local copy of a list the account is no longer a member of.
 */
internal class CollabStore(private val db: AppDatabase) {
    private val collab = db.collabDao()
    private val lists = db.listDao()
    private val sync = db.syncDao()

    suspend fun applyMembers(account: String, rows: List<MemberRow>): MembersApplied = db.withTransaction {
        val previous = collab.members(account).associateBy { it.id }
        val removed = ArrayList<String>()
        var needsFull = false
        val entities = rows.map { row ->
            val prev = previous[row.id]
            // A row seen for the first time is history unless it just cost the account a list (then it is announced once).
            var notified = prev?.notified ?: true
            val mine = row.memberId == account
            if (mine && row.role == ROLE_REMOVED) {
                val list = lists.getById(row.listId)
                if (list != null && list.accountId == account && list.role == MemberRole.EDITOR) {
                    lists.purge(row.listId)
                    removed += row.listId
                    if (prev?.role != ROLE_REMOVED) notified = false
                }
            } else if (mine && (row.role == ROLE_OWNER || row.role == ROLE_EDITOR) && (prev == null || prev.role == ROLE_REMOVED)) {
                if (lists.getById(row.listId) == null) needsFull = true
            }
            MemberEntity(
                id = row.id,
                accountId = account,
                listId = row.listId,
                memberId = row.memberId,
                role = row.role,
                removal = row.removal,
                displayName = row.displayName,
                createdAt = row.createdAtMs,
                notified = notified,
            )
        }
        collab.deleteMembers(account)
        collab.insertMembers(entities)
        collab.refreshOwnerNames(account)
        if (removed.isNotEmpty()) sync.deleteOrphanEntities()
        MembersApplied(needsFull, removed)
    }

    suspend fun applyInvitations(account: String, rows: List<InvitationRow>) = db.withTransaction {
        val previous = collab.invitations(account).associateBy { it.id }
        val entities = rows.map { row ->
            InvitationEntity(
                id = row.id,
                accountId = account,
                listId = row.listId,
                listName = row.listName,
                inviterId = row.inviterId,
                inviterName = row.inviterName,
                inviteeId = row.inviteeId,
                inviteeHint = row.displayHint,
                status = row.status,
                expiresAt = row.expiresAt?.let { ServerTimestamp.parse(it) },
                createdAt = row.createdAtMs,
                // Only a new invitation waiting for this account's answer is announced.
                notified = previous[row.id]?.notified ?: !(row.status == STATUS_PENDING && row.inviteeId == account),
            )
        }
        collab.deleteInvitations(account)
        collab.insertInvitations(entities)
    }

    suspend fun applyJoinRequests(account: String, rows: List<JoinRequestRow>) = db.withTransaction {
        val previous = collab.joinRequests(account).associateBy { it.id }
        val entities = rows.map { row ->
            JoinRequestEntity(
                id = row.id,
                accountId = account,
                listId = row.listId,
                listName = row.listName,
                requesterId = row.requesterId,
                requesterName = row.requesterName,
                ownerId = row.ownerId,
                status = row.status,
                createdAt = row.createdAtMs,
                notified = previous[row.id]?.notified ?: !(row.status == STATUS_PENDING && row.ownerId == account),
            )
        }
        collab.deleteJoinRequests(account)
        collab.insertJoinRequests(entities)
    }

    /** Forgets every collaboration cache of an account (sign-out, account switch). */
    suspend fun clear(account: String) {
        collab.deleteMembers(account)
        collab.deleteInvitations(account)
        collab.deleteJoinRequests(account)
    }

    companion object {
        const val ROLE_OWNER = "owner"
        const val ROLE_EDITOR = "editor"
        const val ROLE_REMOVED = "removed"
        const val STATUS_PENDING = "pending"
    }
}
