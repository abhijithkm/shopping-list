package space.abhijithkm.shoppinglist.data.sync

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import space.abhijithkm.shoppinglist.core.model.Limits
import space.abhijithkm.shoppinglist.data.local.CategoryEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingItemEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity
import space.abhijithkm.shoppinglist.data.local.TagEntity
import space.abhijithkm.shoppinglist.data.remote.AppHubJson
import space.abhijithkm.shoppinglist.data.remote.CategoryRow
import space.abhijithkm.shoppinglist.data.remote.ItemRow
import space.abhijithkm.shoppinglist.data.remote.ListRow
import space.abhijithkm.shoppinglist.data.remote.TagRow

/**
 * The synced fields of each entity as canonical JSON primitives keyed by cloud column name, built the same way
 * from a local entity and from a server row so the two compare equal when they mean the same thing. Everything
 * that merges, diffs or is stored as a sync base goes through here (docs/SYNC_STRATEGY.md).
 *
 * Not part of the fields: ids, `list_id`, `builtin_key` (immutable, sent on create only), `created_at_ms`, and
 * `updated_at_ms` (display metadata, sent with every write but never diffed or merged).
 */
object SyncFields {
    // ------------------------------------------------------------------ lists

    fun list(e: ShoppingListEntity): Fields = mapOf(
        "name" to str(e.name.take(Limits.LIST_NAME)),
        "description" to str(e.description?.take(MAX_DESCRIPTION)),
        "icon" to str(e.icon?.take(MAX_ICON)),
        "color" to num(e.color?.toLong()),
        "is_archived" to bool(e.isArchived),
        "is_pinned" to bool(e.isPinned),
        "sort_key" to dbl(e.sortKey),
        DELETED_AT to num(e.deletedAt),
    )

    fun list(r: ListRow): Fields = mapOf(
        "name" to str(r.name),
        "description" to str(r.description),
        "icon" to str(r.icon),
        "color" to num(r.color),
        "is_archived" to bool(r.isArchived),
        "is_pinned" to bool(r.isPinned),
        "sort_key" to dbl(r.sortKey),
        DELETED_AT to num(r.deletedAtMs),
    )

    fun applyTo(e: ShoppingListEntity, f: Fields): ShoppingListEntity = e.copy(
        name = f.string("name") ?: e.name,
        description = f.string("description"),
        icon = f.string("icon"),
        color = f.long("color")?.toInt(),
        isArchived = f.bool("is_archived"),
        isPinned = f.bool("is_pinned"),
        sortKey = f.double("sort_key") ?: e.sortKey,
        deletedAt = f.long(DELETED_AT),
    )

    // ------------------------------------------------------------------ items

    /** [tagIds] are the item's effective tag ids: only tags that exist locally and are not deleted. */
    fun item(e: ShoppingItemEntity, tagIds: Collection<String>): Fields = mapOf(
        "name" to str(e.name.take(Limits.ITEM_NAME)),
        "quantity" to dbl(e.quantity),
        "unit" to str(e.unit?.take(Limits.UNIT)),
        "category_key" to str(e.categoryKey?.take(MAX_KEY)),
        "notes" to str(e.notes?.take(Limits.ITEM_NOTES)),
        "is_bought" to bool(e.isBought),
        "bought_at_ms" to num(e.boughtAt),
        "sort_order" to dbl(e.sortOrder),
        "tag_ids" to tagIdsElement(tagIds),
        DELETED_AT to num(e.deletedAt),
    )

    fun item(r: ItemRow, liveTagIds: Set<String>): Fields = mapOf(
        "name" to str(r.name),
        "quantity" to dbl(r.quantity),
        "unit" to str(r.unit),
        "category_key" to str(r.categoryKey),
        "notes" to str(r.notes),
        "is_bought" to bool(r.isBought),
        "bought_at_ms" to num(r.boughtAtMs),
        "sort_order" to dbl(r.sortOrder),
        "tag_ids" to tagIdsElement(parseTagIds(r.tagIds).filter { it in liveTagIds }),
        DELETED_AT to num(r.deletedAtMs),
    )

    fun applyTo(e: ShoppingItemEntity, f: Fields): ShoppingItemEntity = e.copy(
        name = f.string("name") ?: e.name,
        quantity = f.double("quantity"),
        unit = f.string("unit"),
        categoryKey = f.string("category_key"),
        notes = f.string("notes"),
        isBought = f.bool("is_bought"),
        boughtAt = f.long("bought_at_ms"),
        sortOrder = f.double("sort_order") ?: e.sortOrder,
        deletedAt = f.long(DELETED_AT),
    )

    /** The tag ids held by an item's `tag_ids` field. */
    fun tagIds(f: Fields): List<String> = parseTagIds(f.string("tag_ids"))

    /**
     * Re-canonicalises the `tag_ids` of [f] keeping only tags that exist locally and are not deleted, so a stored
     * base and a local row are compared on the same footing even after a tag was deleted.
     */
    fun withLiveTags(f: Fields, liveTagIds: Set<String>): Fields =
        if ("tag_ids" !in f) f else f + ("tag_ids" to tagIdsElement(tagIds(f).filter { it in liveTagIds }))

    // ------------------------------------------------------------- categories

    fun category(e: CategoryEntity): Fields = mapOf(
        "name" to str(e.name?.take(Limits.CATEGORY_NAME)),
        "color" to num(e.color?.toLong()),
        DELETED_AT to num(e.deletedAt),
    )

    fun category(r: CategoryRow): Fields = mapOf(
        "name" to str(r.name),
        "color" to num(r.color),
        DELETED_AT to num(r.deletedAtMs),
    )

    fun applyTo(e: CategoryEntity, f: Fields): CategoryEntity = e.copy(
        name = f.string("name"),
        color = f.long("color")?.toInt(),
        deletedAt = f.long(DELETED_AT),
    )

    // ------------------------------------------------------------------- tags

    fun tag(e: TagEntity): Fields = mapOf(
        "name" to str(e.name.take(Limits.TAG_NAME)),
        DELETED_AT to num(e.deletedAt),
    )

    fun tag(r: TagRow): Fields = mapOf(
        "name" to str(r.name),
        DELETED_AT to num(r.deletedAtMs),
    )

    fun applyTo(e: TagEntity, f: Fields): TagEntity = e.copy(
        name = f.string("name") ?: e.name,
        deletedAt = f.long(DELETED_AT),
    )

    // ------------------------------------------------------------- (de)serialising

    /** Canonical text stored in `sync_entities.base`: keys sorted so equal field sets give equal text. */
    fun encode(fields: Fields): String = JsonObject(fields.toSortedMap()).toString()

    fun decode(text: String?): Fields? =
        text?.let { runCatching { AppHubJson.parseToJsonElement(it).jsonObject.toMap() }.getOrNull() }

    /**
     * The JSON body of a create or PATCH: the given fields plus [extras] (ids, created_at_ms, updated_at_ms...).
     * Booleans go out as 0 and 1 (MySQL TINYINT(1)).
     */
    fun body(fields: Fields, extras: Map<String, JsonElement> = emptyMap()): JsonObject =
        JsonObject(extras + fields.mapValues { (_, v) -> if (isBoolean(v)) JsonPrimitive(if (v == JsonPrimitive(true)) 1 else 0) else v })

    fun long(value: Long?): JsonElement = num(value)

    /**
     * Deterministic cloud id of a built-in category override. `categories.id` is one primary key shared by every
     * account, so an override of "dairy" must have an id that is unique per account but stable across devices and
     * retries; a name-based UUID of (account, key) is both.
     */
    fun overrideCloudId(accountId: String, builtInKey: String): String =
        UUID.nameUUIDFromBytes("shoppinglist.category.$accountId.$builtInKey".toByteArray(Charsets.UTF_8)).toString()

    fun parseTagIds(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            AppHubJson.parseToJsonElement(text).jsonArray.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        }.getOrDefault(emptyList())
    }

    private fun isBoolean(v: JsonElement) = v is JsonPrimitive && !v.isString && v.booleanOrNull != null

    private fun tagIdsElement(ids: Collection<String>): JsonElement =
        if (ids.isEmpty()) JsonNull else JsonPrimitive(JsonArray(ids.toSortedSet().map { JsonPrimitive(it) }).toString())

    private fun str(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    private fun num(v: Long?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    private fun dbl(v: Double?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    private fun bool(v: Boolean): JsonElement = JsonPrimitive(v)

    private fun Fields.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun Fields.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    private fun Fields.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

    private fun Fields.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private const val MAX_DESCRIPTION = 255
    private const val MAX_ICON = 16
    private const val MAX_KEY = 40
}
