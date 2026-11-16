package space.abhijithkm.shoppinglist.data.sync

import androidx.room.withTransaction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.abhijithkm.shoppinglist.core.model.DefaultCategory
import space.abhijithkm.shoppinglist.core.model.ListOrigin
import space.abhijithkm.shoppinglist.core.util.Clock
import space.abhijithkm.shoppinglist.data.local.AppDatabase
import space.abhijithkm.shoppinglist.data.local.CategoryEntity
import space.abhijithkm.shoppinglist.data.local.ItemTagCrossRef
import space.abhijithkm.shoppinglist.data.local.ShoppingItemEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity
import space.abhijithkm.shoppinglist.data.local.SyncEntityEntity
import space.abhijithkm.shoppinglist.data.local.SyncType
import space.abhijithkm.shoppinglist.data.local.TagEntity
import space.abhijithkm.shoppinglist.data.remote.RowCodec
import space.abhijithkm.shoppinglist.data.remote.Tables

/** One dirty local row, ready to push. */
internal class DirtyRow(
    val localId: String,
    val revision: Int,
    val deletedAt: Long?,
    /** The current local synced fields. */
    val fields: Fields,
    /** Extra columns sent with a create only: id, list_id, builtin_key, created_at_ms. */
    val createExtras: Map<String, JsonElement>,
    /** Client clock of the last edit, sent as `updated_at_ms` with every write (lists and items). */
    val updatedAtMs: Long?,
    /** For items, the id of the list that must exist on the server first. */
    val parentLocalId: String? = null,
    /** Brings a stored base into the same shape as [fields] before they are compared (items: only live tags). */
    val normalizeBase: (Fields) -> Fields = { it },
)

internal enum class ApplyOutcome { INSERTED, MERGED, IGNORED, SKIPPED_PARENT_MISSING }

/** [changed] is true when the local row was inserted or one of its fields was taken from the server. */
internal class ApplyResult(
    val outcome: ApplyOutcome,
    val conflicts: Int = 0,
    val changed: Boolean = false,
    /** Set for a list tombstone this device has never seen: its items must not hold the pull cursor back. */
    val ignoredId: String? = null,
)

/** Everything that differs between the four synced tables: how to read dirty rows and how to apply a server row. */
internal abstract class EntityAdapter(protected val db: AppDatabase, protected val clock: Clock) {
    abstract val type: String
    abstract val table: String

    protected val lists get() = db.listDao()
    protected val items get() = db.itemDao()
    protected val categories get() = db.categoryDao()
    protected val tags get() = db.tagDao()
    protected val sync get() = db.syncDao()

    abstract suspend fun dirty(account: String): List<DirtyRow>

    /** The id the row has on the server (equal to the local id except for built-in category overrides). */
    open fun cloudId(account: String, localId: String): String = localId

    abstract suspend fun markClean(localId: String, revision: Int)

    /** Removes a row that never reached the server and has been deleted again: nothing to tell the server. */
    abstract suspend fun purgeLocal(localId: String)

    /**
     * Merges one server row into the local database (one transaction). [deadParents] are ids of lists the server
     * has deleted that this device never saw; an item of such a list is ignored instead of waiting for it.
     */
    abstract suspend fun apply(account: String, raw: JsonObject, deadParents: Set<String> = emptySet()): ApplyResult

    abstract fun updatedAtOf(raw: JsonObject): String?

    /** The synced fields of a raw server row, in the same shape as the local fields. */
    abstract suspend fun serverFields(raw: JsonObject): Fields

    protected suspend fun saveBase(localId: String, serverFields: Fields) {
        val existing = sync.entity(type, localId)
        sync.upsertEntity((existing ?: SyncEntityEntity(type, localId)).copy(base = SyncFields.encode(serverFields)))
    }

    protected fun newRevisionIfNeeded(revision: Int, serverRevision: Int, mustPush: Boolean): Int =
        if (mustPush && revision <= serverRevision) serverRevision + 1 else revision

    protected fun ms(value: Long): JsonElement = JsonPrimitive(value)
}

internal class ListAdapter(db: AppDatabase, clock: Clock) : EntityAdapter(db, clock) {
    override val type = SyncType.LIST
    override val table = Tables.LISTS

    override suspend fun dirty(account: String) = sync.dirtyLists(account).map {
        DirtyRow(
            localId = it.id,
            revision = it.revision,
            deletedAt = it.deletedAt,
            fields = SyncFields.list(it),
            createExtras = mapOf("id" to JsonPrimitive(it.id), "created_at_ms" to ms(it.createdAt)),
            updatedAtMs = it.updatedAt,
        )
    }

    override suspend fun markClean(localId: String, revision: Int) = sync.markListClean(localId, revision)

    override suspend fun purgeLocal(localId: String) = lists.purge(localId)

    override fun updatedAtOf(raw: JsonObject) = RowCodec.list(raw).updatedAt

    override suspend fun serverFields(raw: JsonObject) = SyncFields.list(RowCodec.list(raw))

    override suspend fun apply(account: String, raw: JsonObject, deadParents: Set<String>): ApplyResult = db.withTransaction {
        val row = RowCodec.list(raw)
        val serverFields = SyncFields.list(row)
        val local = lists.getById(row.id)
        if (local == null) {
            if (row.deletedAtMs != null) return@withTransaction ApplyResult(ApplyOutcome.IGNORED, ignoredId = row.id)
            val now = clock.nowMillis()
            lists.upsert(
                ShoppingListEntity(
                    id = row.id,
                    accountId = account,
                    origin = ListOrigin.CLOUD,
                    name = row.name,
                    description = row.description,
                    icon = row.icon,
                    color = row.color?.toInt(),
                    isArchived = row.isArchived,
                    isPinned = row.isPinned,
                    sortKey = row.sortKey,
                    createdAt = row.createdAtMs.takeIf { it > 0 } ?: now,
                    updatedAt = row.updatedAtMs.takeIf { it > 0 } ?: now,
                ),
            )
            saveBase(row.id, serverFields)
            return@withTransaction ApplyResult(ApplyOutcome.INSERTED, changed = true)
        }
        if (local.accountId != account) return@withTransaction ApplyResult(ApplyOutcome.IGNORED)
        val base = SyncFields.decode(sync.entity(type, row.id)?.base)
        // No base: the row was (re)adopted from guest data. If the server copy was edited after the local one, it is the newer.
        val merge = mergeFields(base, SyncFields.list(local), serverFields, preferServerOnConflict = base == null && row.updatedAtMs > local.updatedAt)
        var updated = if (merge.fromServer.isEmpty()) local else SyncFields.applyTo(local, merge.merged)
        if (merge.fromServer.isNotEmpty() && row.updatedAtMs > updated.updatedAt) updated = updated.copy(updatedAt = row.updatedAtMs)
        updated = updated.copy(revision = newRevisionIfNeeded(updated.revision, updated.serverRevision, merge.toPush.isNotEmpty()))
        if (updated != local) lists.upsert(updated)
        saveBase(row.id, serverFields)
        if (merge.toPush.isEmpty()) sync.markListClean(row.id, updated.revision)
        ApplyResult(ApplyOutcome.MERGED, merge.conflicts.size, changed = merge.fromServer.isNotEmpty())
    }
}

internal class ItemAdapter(db: AppDatabase, clock: Clock) : EntityAdapter(db, clock) {
    override val type = SyncType.ITEM
    override val table = Tables.ITEMS

    override suspend fun dirty(account: String): List<DirtyRow> {
        val live = tags.liveIds().toSet()
        return sync.dirtyItems(account).map {
            DirtyRow(
                localId = it.id,
                revision = it.revision,
                deletedAt = it.deletedAt,
                fields = SyncFields.item(it, items.tagIdsOf(it.id).filter { id -> id in live }),
                createExtras = mapOf(
                    "id" to JsonPrimitive(it.id),
                    "list_id" to JsonPrimitive(it.listId),
                    "created_at_ms" to ms(it.createdAt),
                ),
                updatedAtMs = it.updatedAt,
                parentLocalId = it.listId,
                normalizeBase = { base -> SyncFields.withLiveTags(base, live) },
            )
        }
    }

    override suspend fun markClean(localId: String, revision: Int) = sync.markItemClean(localId, revision)

    override suspend fun purgeLocal(localId: String) = items.purge(localId)

    override fun updatedAtOf(raw: JsonObject) = RowCodec.item(raw).updatedAt

    override suspend fun serverFields(raw: JsonObject) = SyncFields.item(RowCodec.item(raw), tags.liveIds().toSet())

    override suspend fun apply(account: String, raw: JsonObject, deadParents: Set<String>): ApplyResult = db.withTransaction {
        val row = RowCodec.item(raw)
        val live = tags.liveIds().toSet()
        val serverFields = SyncFields.item(row, live)
        val local = items.getById(row.id)
        if (local == null) {
            if (row.deletedAtMs != null) return@withTransaction ApplyResult(ApplyOutcome.IGNORED)
            val list = lists.getById(row.listId)
            if (list == null) {
                // Deleted lists are one tombstone and their items stay live on the server: do not wait for a list that will never come.
                return@withTransaction ApplyResult(if (row.listId in deadParents) ApplyOutcome.IGNORED else ApplyOutcome.SKIPPED_PARENT_MISSING)
            }
            if (list.accountId != account) return@withTransaction ApplyResult(ApplyOutcome.IGNORED)
            val now = clock.nowMillis()
            items.upsert(
                ShoppingItemEntity(
                    id = row.id,
                    listId = row.listId,
                    name = row.name,
                    quantity = row.quantity,
                    unit = row.unit,
                    categoryKey = row.categoryKey,
                    notes = row.notes,
                    isBought = row.isBought,
                    boughtAt = row.boughtAtMs,
                    sortOrder = row.sortOrder,
                    createdAt = row.createdAtMs.takeIf { it > 0 } ?: now,
                    updatedAt = row.updatedAtMs.takeIf { it > 0 } ?: now,
                ),
            )
            items.insertTagLinks(SyncFields.tagIds(serverFields).map { ItemTagCrossRef(row.id, it, row.listId) })
            lists.touchAtLeast(row.listId, row.updatedAtMs)
            saveBase(row.id, serverFields)
            return@withTransaction ApplyResult(ApplyOutcome.INSERTED, changed = true)
        }
        val list = lists.getById(local.listId)
        if (list == null || list.accountId != account) return@withTransaction ApplyResult(ApplyOutcome.IGNORED)
        val localTags = items.tagIdsOf(local.id).filter { it in live }
        val base = SyncFields.decode(sync.entity(type, row.id)?.base)?.let { SyncFields.withLiveTags(it, live) }
        val merge = mergeFields(base, SyncFields.item(local, localTags), serverFields, preferServerOnConflict = base == null && row.updatedAtMs > local.updatedAt)
        var updated = if (merge.fromServer.isEmpty()) local else SyncFields.applyTo(local, merge.merged)
        if (merge.fromServer.isNotEmpty() && row.updatedAtMs > updated.updatedAt) updated = updated.copy(updatedAt = row.updatedAtMs)
        updated = updated.copy(revision = newRevisionIfNeeded(updated.revision, updated.serverRevision, merge.toPush.isNotEmpty()))
        if (updated != local) items.upsert(updated)
        if ("tag_ids" in merge.fromServer) {
            items.deleteTagLinks(local.id)
            items.insertTagLinks(SyncFields.tagIds(merge.merged).map { ItemTagCrossRef(local.id, it, local.listId) })
        }
        if (merge.fromServer.isNotEmpty()) lists.touchAtLeast(local.listId, row.updatedAtMs)
        saveBase(row.id, serverFields)
        if (merge.toPush.isEmpty()) sync.markItemClean(row.id, updated.revision)
        ApplyResult(ApplyOutcome.MERGED, merge.conflicts.size, changed = merge.fromServer.isNotEmpty())
    }
}

internal class CategoryAdapter(db: AppDatabase, clock: Clock) : EntityAdapter(db, clock) {
    override val type = SyncType.CATEGORY
    override val table = Tables.CATEGORIES

    override fun cloudId(account: String, localId: String): String =
        if (DefaultCategory.fromKey(localId) != null) SyncFields.overrideCloudId(account, localId) else localId

    override suspend fun dirty(account: String): List<DirtyRow> = sync.dirtyCategories(account).mapNotNull {
        val builtIn = DefaultCategory.fromKey(it.id) != null
        // The server requires a name or a colour on every category row. An override that carries neither has
        // nothing to say; remember it as synced instead of sending a row the server would refuse.
        if (it.name == null && it.color == null && it.deletedAt == null) {
            saveBase(it.id, SyncFields.category(it))
            sync.markCategoryClean(it.id, it.revision)
            return@mapNotNull null
        }
        DirtyRow(
            localId = it.id,
            revision = it.revision,
            deletedAt = it.deletedAt,
            fields = SyncFields.category(it),
            createExtras = buildMap {
                put("id", JsonPrimitive(cloudId(account, it.id)))
                put("created_at_ms", ms(it.createdAt))
                if (builtIn) put("builtin_key", JsonPrimitive(it.id))
            },
            updatedAtMs = null,
        )
    }

    override suspend fun markClean(localId: String, revision: Int) = sync.markCategoryClean(localId, revision)

    override suspend fun purgeLocal(localId: String) = categories.purge(localId)

    override fun updatedAtOf(raw: JsonObject) = RowCodec.category(raw).updatedAt

    override suspend fun serverFields(raw: JsonObject) = SyncFields.category(RowCodec.category(raw))

    override suspend fun apply(account: String, raw: JsonObject, deadParents: Set<String>): ApplyResult = db.withTransaction {
        val row = RowCodec.category(raw)
        val localId = when {
            row.builtinKey == null -> row.id
            // Only the account's own deterministic row is an override. Another row naming the same built-in (made by
            // other software, or by hand) would otherwise overwrite it in whatever order the rows arrive.
            DefaultCategory.fromKey(row.builtinKey) != null ->
                if (row.id == SyncFields.overrideCloudId(account, row.builtinKey)) row.builtinKey
                else return@withTransaction ApplyResult(ApplyOutcome.IGNORED)
            // An override of a built-in this app version does not know (a newer app made it): ignore it.
            else -> return@withTransaction ApplyResult(ApplyOutcome.IGNORED)
        }
        val serverFields = SyncFields.category(row)
        val local = categories.getById(localId)
        if (local == null) {
            if (row.deletedAtMs != null) return@withTransaction ApplyResult(ApplyOutcome.IGNORED)
            val now = clock.nowMillis()
            categories.upsert(
                CategoryEntity(
                    id = localId,
                    accountId = account,
                    name = row.name,
                    color = row.color?.toInt(),
                    createdAt = row.createdAtMs.takeIf { it > 0 } ?: now,
                    updatedAt = now,
                ),
            )
            saveBase(localId, serverFields)
            return@withTransaction ApplyResult(ApplyOutcome.INSERTED, changed = true)
        }
        if (local.accountId != account) return@withTransaction ApplyResult(ApplyOutcome.IGNORED)
        val base = SyncFields.decode(sync.entity(type, localId)?.base)
        val merge = mergeFields(base, SyncFields.category(local), serverFields)
        var updated = if (merge.fromServer.isEmpty()) local else SyncFields.applyTo(local, merge.merged).copy(updatedAt = clock.nowMillis())
        updated = updated.copy(revision = newRevisionIfNeeded(updated.revision, updated.serverRevision, merge.toPush.isNotEmpty()))
        if (updated != local) categories.upsert(updated)
        saveBase(localId, serverFields)
        if (merge.toPush.isEmpty()) sync.markCategoryClean(localId, updated.revision)
        ApplyResult(ApplyOutcome.MERGED, merge.conflicts.size, changed = merge.fromServer.isNotEmpty())
    }
}

internal class TagAdapter(db: AppDatabase, clock: Clock) : EntityAdapter(db, clock) {
    override val type = SyncType.TAG
    override val table = Tables.TAGS

    override suspend fun dirty(account: String) = sync.dirtyTags(account).map {
        DirtyRow(
            localId = it.id,
            revision = it.revision,
            deletedAt = it.deletedAt,
            fields = SyncFields.tag(it),
            createExtras = mapOf("id" to JsonPrimitive(it.id), "created_at_ms" to ms(it.createdAt)),
            updatedAtMs = null,
        )
    }

    override suspend fun markClean(localId: String, revision: Int) = sync.markTagClean(localId, revision)

    override suspend fun purgeLocal(localId: String) = tags.purge(localId)

    override fun updatedAtOf(raw: JsonObject) = RowCodec.tag(raw).updatedAt

    override suspend fun serverFields(raw: JsonObject) = SyncFields.tag(RowCodec.tag(raw))

    override suspend fun apply(account: String, raw: JsonObject, deadParents: Set<String>): ApplyResult = db.withTransaction {
        val row = RowCodec.tag(raw)
        val serverFields = SyncFields.tag(row)
        val local = tags.getById(row.id)
        if (local == null) {
            if (row.deletedAtMs != null) return@withTransaction ApplyResult(ApplyOutcome.IGNORED)
            val now = clock.nowMillis()
            tags.upsert(
                TagEntity(
                    id = row.id,
                    accountId = account,
                    name = row.name,
                    createdAt = row.createdAtMs.takeIf { it > 0 } ?: now,
                    updatedAt = now,
                ),
            )
            saveBase(row.id, serverFields)
            return@withTransaction ApplyResult(ApplyOutcome.INSERTED, changed = true)
        }
        if (local.accountId != account) return@withTransaction ApplyResult(ApplyOutcome.IGNORED)
        val base = SyncFields.decode(sync.entity(type, row.id)?.base)
        val merge = mergeFields(base, SyncFields.tag(local), serverFields)
        var updated = if (merge.fromServer.isEmpty()) local else SyncFields.applyTo(local, merge.merged).copy(updatedAt = clock.nowMillis())
        updated = updated.copy(revision = newRevisionIfNeeded(updated.revision, updated.serverRevision, merge.toPush.isNotEmpty()))
        if (updated != local) tags.upsert(updated)
        if (merge.fromServer.contains(DELETED_AT) && updated.deletedAt != null) items.deleteLinksForTag(row.id)
        saveBase(row.id, serverFields)
        if (merge.toPush.isEmpty()) sync.markTagClean(row.id, updated.revision)
        ApplyResult(ApplyOutcome.MERGED, merge.conflicts.size, changed = merge.fromServer.isNotEmpty())
    }
}
