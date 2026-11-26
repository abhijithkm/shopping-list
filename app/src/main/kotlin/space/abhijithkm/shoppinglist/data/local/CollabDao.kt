package space.abhijithkm.shoppinglist.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Local caches of the server's collaboration state. Every pull rewrites the account's rows completely (the server
 * is the only writer), so nothing here is ever edited by the UI.
 */
@Dao
interface CollabDao {
    // ------------------------------------------------------------------ rewrite (pull)

    @Query("SELECT * FROM list_members WHERE accountId = :accountId")
    suspend fun members(accountId: String): List<MemberEntity>

    @Query("DELETE FROM list_members WHERE accountId = :accountId")
    suspend fun deleteMembers(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(rows: List<MemberEntity>)

    @Query("SELECT * FROM invitations WHERE accountId = :accountId")
    suspend fun invitations(accountId: String): List<InvitationEntity>

    @Query("DELETE FROM invitations WHERE accountId = :accountId")
    suspend fun deleteInvitations(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvitations(rows: List<InvitationEntity>)

    @Query("SELECT * FROM join_requests WHERE accountId = :accountId")
    suspend fun joinRequests(accountId: String): List<JoinRequestEntity>

    @Query("DELETE FROM join_requests WHERE accountId = :accountId")
    suspend fun deleteJoinRequests(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJoinRequests(rows: List<JoinRequestEntity>)

    /** Names the owner of every list that belongs to someone else, from the membership rows the account can see. */
    @Query(
        """UPDATE lists SET ownerName = (SELECT m.displayName FROM list_members m WHERE m.listId = lists.id AND m.role = 'owner' LIMIT 1)
           WHERE accountId = :accountId AND role = 'EDITOR'""",
    )
    suspend fun refreshOwnerNames(accountId: String)

    // ------------------------------------------------------------------ what the screens show

    @Query(
        """SELECT * FROM list_members WHERE accountId = :accountId AND listId = :listId AND role IN ('owner', 'editor')
           ORDER BY CASE role WHEN 'owner' THEN 0 ELSE 1 END, displayName COLLATE NOCASE""",
    )
    fun observeActiveMembers(accountId: String, listId: String): Flow<List<MemberEntity>>

    /** Invitations addressed to the account that can still be answered. */
    @Query(
        """SELECT * FROM invitations WHERE accountId = :accountId AND inviteeId = :accountId AND status = 'pending'
           AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY createdAt DESC""",
    )
    fun observeInvitationsToMe(accountId: String, now: Long): Flow<List<InvitationEntity>>

    /** Invitations the account sent for one list that are still open. */
    @Query(
        """SELECT * FROM invitations WHERE accountId = :accountId AND listId = :listId AND inviterId = :accountId
           AND status = 'pending' AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY createdAt DESC""",
    )
    fun observeInvitationsForList(accountId: String, listId: String, now: Long): Flow<List<InvitationEntity>>

    /** Requests addressed to the owner for one list. */
    @Query(
        """SELECT * FROM join_requests WHERE accountId = :accountId AND listId = :listId AND ownerId = :accountId
           AND status = 'pending' ORDER BY createdAt DESC""",
    )
    fun observeRequestsForList(accountId: String, listId: String): Flow<List<JoinRequestEntity>>

    /** Requests the account made itself that are still waiting for the owner. */
    @Query("SELECT * FROM join_requests WHERE accountId = :accountId AND requesterId = :accountId AND status = 'pending' ORDER BY createdAt DESC")
    fun observeMyRequests(accountId: String): Flow<List<JoinRequestEntity>>

    /** Things waiting for the account's answer: invitations to it plus join requests to its lists. */
    @Query(
        """SELECT
           (SELECT COUNT(*) FROM invitations WHERE accountId = :accountId AND inviteeId = :accountId AND status = 'pending'
              AND (expiresAt IS NULL OR expiresAt > :now))
         + (SELECT COUNT(*) FROM join_requests WHERE accountId = :accountId AND ownerId = :accountId AND status = 'pending')""",
    )
    fun observeInboxCount(accountId: String, now: Long): Flow<Int>

    // ------------------------------------------------------------------ notifications

    @Query(
        """SELECT * FROM invitations WHERE accountId = :accountId AND inviteeId = :accountId AND status = 'pending'
           AND notified = 0 AND (expiresAt IS NULL OR expiresAt > :now)""",
    )
    suspend fun invitationsToAnnounce(accountId: String, now: Long): List<InvitationEntity>

    @Query("UPDATE invitations SET notified = 1 WHERE id IN (:ids)")
    suspend fun markInvitationsNotified(ids: List<String>)

    @Query("SELECT * FROM join_requests WHERE accountId = :accountId AND ownerId = :accountId AND status = 'pending' AND notified = 0")
    suspend fun requestsToAnnounce(accountId: String): List<JoinRequestEntity>

    @Query("UPDATE join_requests SET notified = 1 WHERE id IN (:ids)")
    suspend fun markRequestsNotified(ids: List<String>)

    @Query("SELECT * FROM list_members WHERE accountId = :accountId AND memberId = :accountId AND role = 'removed' AND notified = 0")
    suspend fun removalsToAnnounce(accountId: String): List<MemberEntity>

    @Query("UPDATE list_members SET notified = 1 WHERE id IN (:ids)")
    suspend fun markMembersNotified(ids: List<String>)
}
