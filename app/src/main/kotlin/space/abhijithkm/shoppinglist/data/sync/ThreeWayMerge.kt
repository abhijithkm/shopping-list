package space.abhijithkm.shoppinglist.data.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** A row's synced fields by cloud column name; values are canonical JSON primitives (see [SyncFields]). */
typealias Fields = Map<String, JsonElement>

/**
 * Result of merging one row.
 *
 * @property merged the value of every field after the merge
 * @property fromServer fields whose [merged] value differs from the local value: write these to the local row
 * @property toPush fields whose [merged] value differs from the server value: send these in a PATCH
 * @property conflicts fields changed on both sides to different values; the local value was kept (last pusher wins),
 *   except `deleted_at_ms` where a delete from either side wins
 */
data class MergeResult(
    val merged: Fields,
    val fromServer: Set<String>,
    val toPush: Set<String>,
    val conflicts: Set<String>,
)

/**
 * Field-level three-way merge of local, server and the common ancestor [base] (what the server last agreed
 * on). A field changed on one side only is taken from that side; the same change on both sides converges;
 * different changes to the same field are a conflict.
 *
 * Conflict rule, chosen for a personal list edited from a few devices: the local value wins, because it is
 * about to be pushed and the user just made it (the other device's edit to that one field is overwritten, and the
 * conflict is counted and shown). Deleting always wins over editing: a row deleted on either side stays
 * deleted. A [base] of null (never synced, server row found anyway) treats every differing field as a conflict;
 * with [preferServerOnConflict] the server value wins those instead (used when the server copy is the newer one, for
 * example a list that was signed out, edited on another phone and then moved back into the account).
 */
fun mergeFields(base: Fields?, local: Fields, server: Fields, preferServerOnConflict: Boolean = false): MergeResult {
    val keys = local.keys + server.keys
    val merged = HashMap<String, JsonElement>(keys.size)
    val conflicts = HashSet<String>()
    for (field in keys) {
        val l = local[field] ?: JsonNull
        val s = server[field] ?: JsonNull
        val b = base?.get(field) ?: JsonNull
        merged[field] = when {
            l == s -> l
            base != null && l == b -> s
            base != null && s == b -> l
            field == DELETED_AT -> if (l != JsonNull) l else s
            else -> {
                conflicts += field
                if (preferServerOnConflict) s else l
            }
        }
    }
    val fromServer = keys.filter { (merged[it] ?: JsonNull) != (local[it] ?: JsonNull) }.toSet()
    val toPush = keys.filter { (merged[it] ?: JsonNull) != (server[it] ?: JsonNull) }.toSet()
    return MergeResult(merged, fromServer, toPush, conflicts)
}

internal const val DELETED_AT = "deleted_at_ms"
