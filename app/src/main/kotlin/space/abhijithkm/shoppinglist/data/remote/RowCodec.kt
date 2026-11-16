package space.abhijithkm.shoppinglist.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/** Decodes raw AppHub rows into typed rows. A malformed row is a [AppHubException.Protocol], never a crash. */
object RowCodec {
    fun list(row: JsonObject): ListRow = decode(row, ListRow.serializer())
    fun item(row: JsonObject): ItemRow = decode(row, ItemRow.serializer())
    fun category(row: JsonObject): CategoryRow = decode(row, CategoryRow.serializer())
    fun tag(row: JsonObject): TagRow = decode(row, TagRow.serializer())

    private fun <T> decode(row: JsonObject, serializer: KSerializer<T>): T = try {
        AppHubJson.decodeFromJsonElement(serializer, row)
    } catch (e: SerializationException) {
        throw AppHubException.Protocol("A row from the server could not be read.", e)
    } catch (e: IllegalArgumentException) {
        throw AppHubException.Protocol("A row from the server could not be read.", e)
    }
}
