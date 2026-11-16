package space.abhijithkm.shoppinglist.data.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.abhijithkm.shoppinglist.data.local.CategoryEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingItemEntity
import space.abhijithkm.shoppinglist.data.local.ShoppingListEntity
import space.abhijithkm.shoppinglist.data.local.TagEntity
import space.abhijithkm.shoppinglist.data.remote.AppHubJson
import space.abhijithkm.shoppinglist.data.remote.ItemRow
import space.abhijithkm.shoppinglist.data.remote.ListRow
import space.abhijithkm.shoppinglist.data.remote.RowCodec

class SyncCoreTest {
    // ---- ServerTimestamp --------------------------------------------------------------------------

    @Test
    fun timestampsRoundTripAndDifferencesAreMilliseconds() {
        val a = ServerTimestamp.parse("2026-11-16 14:23:04.969")!!
        val b = ServerTimestamp.parse("2026-11-16 14:23:03.969")!!
        assertEquals(1000L, a - b)
        for (text in listOf("2026-11-16 14:23:04.969", "1970-01-01 00:00:00.000", "2028-02-29 23:59:59.999", "2099-12-31 00:00:00.001")) {
            assertEquals(text, ServerTimestamp.format(ServerTimestamp.parse(text)!!))
        }
    }

    @Test
    fun subtractingCrossesMidnightMonthLeapDayAndYear() {
        assertEquals("2026-02-28 23:59:00.000", ServerTimestamp.minus("2026-03-01 00:01:00.000", 120_000))
        assertEquals("2028-02-29 23:59:30.500", ServerTimestamp.minus("2028-03-01 00:00:30.500", 60_000))
        assertEquals("2025-12-31 23:58:00.000", ServerTimestamp.minus("2026-01-01 00:00:00.000", 120_000))
    }

    @Test
    fun timestampParsingToleratesTheTSeparatorAndMissingFractionAndRejectsGarbage() {
        assertEquals(ServerTimestamp.parse("2026-11-16 10:00:00.000"), ServerTimestamp.parse("2026-11-16T10:00:00"))
        assertEquals(ServerTimestamp.parse("2026-11-16 10:00:00.500"), ServerTimestamp.parse("2026-11-16 10:00:00.5"))
        assertNull(ServerTimestamp.parse("yesterday"))
        assertNull(ServerTimestamp.parse("2026-13-01 00:00:00"))
        assertNull(ServerTimestamp.minus("nope", 1000))
    }

    @Test
    fun orderingAndMax() {
        assertTrue(ServerTimestamp.isBefore("2026-11-16 10:00:00.000", "2026-11-16 10:00:00.001"))
        assertFalse(ServerTimestamp.isBefore("2026-11-16 10:00:00.000", "2026-11-16 10:00:00.000"))
        assertFalse(ServerTimestamp.isBefore("garbage", "2026-11-16 10:00:00.000"))
        assertEquals("2026-11-17 00:00:00.000", ServerTimestamp.max("2026-11-16 23:59:59.999", "2026-11-17 00:00:00.000"))
        assertEquals("x", ServerTimestamp.max(null, "x"))
        assertNull(ServerTimestamp.max(null, null))
    }

    // ---- merge ------------------------------------------------------------------------------------

    private fun f(vararg pairs: Pair<String, Any?>): Fields = pairs.associate { (k, v) ->
        k to when (v) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }

    @Test
    fun noChangeAnywhereMergesToNothing() {
        val x = f("name" to "Milk", DELETED_AT to null)
        val r = mergeFields(x, x, x)
        assertTrue(r.fromServer.isEmpty() && r.toPush.isEmpty() && r.conflicts.isEmpty())
    }

    @Test
    fun aLocalOnlyChangeIsPushedAndNotOverwritten() {
        val base = f("name" to "Milk", "notes" to null)
        val r = mergeFields(base, f("name" to "Milk", "notes" to "2%"), base)
        assertEquals(setOf("notes"), r.toPush)
        assertTrue(r.fromServer.isEmpty())
        assertEquals(JsonPrimitive("2%"), r.merged["notes"])
    }

    @Test
    fun aServerOnlyChangeIsTakenLocally() {
        val base = f("name" to "Milk", "notes" to null)
        val r = mergeFields(base, base, f("name" to "Oat milk", "notes" to null))
        assertEquals(setOf("name"), r.fromServer)
        assertTrue(r.toPush.isEmpty())
    }

    @Test
    fun changesToDifferentFieldsOnBothSidesAreBothKept() {
        val base = f("name" to "Milk", "notes" to null)
        val r = mergeFields(base, f("name" to "Milk", "notes" to "2%"), f("name" to "Oat milk", "notes" to null))
        assertEquals(JsonPrimitive("Oat milk"), r.merged["name"])
        assertEquals(JsonPrimitive("2%"), r.merged["notes"])
        assertEquals(setOf("name"), r.fromServer)
        assertEquals(setOf("notes"), r.toPush)
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun theSameChangeOnBothSidesConvergesWithoutConflict() {
        val base = f("name" to "Milk")
        val r = mergeFields(base, f("name" to "Oat"), f("name" to "Oat"))
        assertTrue(r.conflicts.isEmpty() && r.toPush.isEmpty() && r.fromServer.isEmpty())
    }

    @Test
    fun differentChangesToTheSameFieldAreAConflictAndTheLocalValueWins() {
        val base = f("name" to "Milk")
        val r = mergeFields(base, f("name" to "Mine"), f("name" to "Theirs"))
        assertEquals(setOf("name"), r.conflicts)
        assertEquals(JsonPrimitive("Mine"), r.merged["name"])
        assertEquals(setOf("name"), r.toPush)
        assertTrue(r.fromServer.isEmpty())
    }

    @Test
    fun aDeleteOnEitherSideWinsOverAnEdit() {
        val base = f("name" to "Milk", DELETED_AT to null)
        val deletedLocally = mergeFields(base, f("name" to "Milk", DELETED_AT to 5L), f("name" to "Oat", DELETED_AT to null))
        assertEquals(JsonPrimitive(5L), deletedLocally.merged[DELETED_AT])
        assertEquals(JsonPrimitive("Oat"), deletedLocally.merged["name"])

        val deletedRemotely = mergeFields(base, f("name" to "Mine", DELETED_AT to null), f("name" to "Milk", DELETED_AT to 7L))
        assertEquals(JsonPrimitive(7L), deletedRemotely.merged[DELETED_AT])
        assertEquals(JsonPrimitive("Mine"), deletedRemotely.merged["name"])
        assertTrue(DELETED_AT in deletedRemotely.fromServer)
    }

    @Test
    fun aLocalRestoreOfAnAlreadySyncedDeleteIsPushed() {
        val base = f(DELETED_AT to 5L)
        val r = mergeFields(base, f(DELETED_AT to null), base)
        assertEquals(setOf(DELETED_AT), r.toPush)
        assertEquals(JsonNull, r.merged[DELETED_AT])
    }

    @Test
    fun withoutABaseEveryDifferenceIsAConflictResolvedLocally() {
        val r = mergeFields(null, f("name" to "Mine", "notes" to "x"), f("name" to "Theirs", "notes" to "x"))
        assertEquals(setOf("name"), r.conflicts)
        assertEquals(setOf("name"), r.toPush)
    }

    @Test
    fun withoutABaseANewerServerCopyWinsConflictsButADeleteStillWins() {
        val server = f("name" to "Theirs", "notes" to "n", DELETED_AT to 9L)
        val local = f("name" to "Mine", "notes" to "n", DELETED_AT to null)

        val r = mergeFields(null, local, server, preferServerOnConflict = true)

        assertEquals(JsonPrimitive("Theirs"), r.merged["name"])
        assertEquals(JsonPrimitive(9L), r.merged["deleted_at_ms"])
        assertEquals(setOf("name"), r.conflicts)
        assertTrue(r.toPush.isEmpty())
    }

    @Test
    fun timestampFormattingIgnoresTheDefaultLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val text = ServerTimestamp.format(ServerTimestamp.parse("2026-11-16 14:23:04.969")!!)
            assertEquals("2026-11-16 14:23:04.969", text)
            assertEquals("2026-11-16 14:21:04.969", ServerTimestamp.minus(text, 120_000))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    // ---- fields -----------------------------------------------------------------------------------

    private val list = ShoppingListEntity(
        id = "l1", name = "Weekend", description = null, icon = "🛒", color = -10453621, isPinned = true,
        sortKey = 1700000000000.0, createdAt = 1, updatedAt = 2,
    )

    @Test
    fun aLocalListAndTheMatchingServerRowProduceEqualFields() {
        val row = ListRow(
            id = "l1", name = "Weekend", icon = "🛒", color = -10453621L, isPinned = true, isArchived = false,
            sortKey = 1700000000000.0, createdAtMs = 1, updatedAt = "2026-11-16 10:00:00.000",
        )
        assertEquals(SyncFields.list(list), SyncFields.list(row))
    }

    @Test
    fun aLocalItemAndTheMatchingServerRowProduceEqualFieldsEvenWithTagsInAnyOrder() {
        val item = ShoppingItemEntity(id = "i1", listId = "l1", name = "Milk", quantity = 2.5, unit = "l", categoryKey = "dairy", sortOrder = 3.0, createdAt = 1, updatedAt = 1)
        val row = RowCodec.item(
            AppHubJson.parseToJsonElement(
                """{"id":"i1","list_id":"l1","name":"Milk","quantity":"2.500","unit":"l","category_key":"dairy","notes":null,
                   "is_bought":0,"bought_at_ms":null,"sort_order":3.0,"tag_ids":"[\"b\",\"a\"]","created_at_ms":1,"deleted_at_ms":null}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        val live = setOf("a", "b")
        assertEquals(SyncFields.item(item, listOf("a", "b")), SyncFields.item(row, live))
        assertEquals(SyncFields.item(item, listOf("b", "a")), SyncFields.item(item, listOf("a", "b")))
    }

    @Test
    fun deletedTagsDropOutOfBothSidesOfTheComparison() {
        val row = ItemRow(id = "i1", tagIds = """["a","gone"]""")
        assertEquals(JsonPrimitive("""["a"]"""), SyncFields.item(row, setOf("a"))["tag_ids"])
        assertEquals(JsonNull, SyncFields.item(row, emptySet())["tag_ids"])
        val withBase = SyncFields.withLiveTags(SyncFields.item(row, setOf("a", "gone")), setOf("a"))
        assertEquals(JsonPrimitive("""["a"]"""), withBase["tag_ids"])
    }

    @Test
    fun baseTextRoundTripsAndKeepsNumbersComparable() {
        val fields = SyncFields.list(list)
        val decoded = SyncFields.decode(SyncFields.encode(fields))!!
        assertEquals(fields, decoded)
        assertEquals(SyncFields.encode(fields), SyncFields.encode(fields.toList().reversed().toMap()))
    }

    @Test
    fun requestBodiesSendBooleansAsZeroOne_keepNullsAndAddExtras() {
        val body = SyncFields.body(SyncFields.list(list), mapOf("id" to JsonPrimitive("l1")))
        assertEquals(JsonPrimitive(1), body["is_pinned"])
        assertEquals(JsonPrimitive(0), body["is_archived"])
        assertEquals(JsonNull, body["description"])
        assertEquals(JsonPrimitive("l1"), body["id"])
        assertEquals(JsonPrimitive("Weekend"), body["name"])
    }

    @Test
    fun mergedFieldsWriteBackIntoEntities() {
        val merged = SyncFields.list(list) + ("name" to JsonPrimitive("Renamed")) + ("is_archived" to JsonPrimitive(true)) + (DELETED_AT to JsonPrimitive(99L))
        val updated = SyncFields.applyTo(list, merged)
        assertEquals("Renamed", updated.name)
        assertTrue(updated.isArchived)
        assertEquals(99L, updated.deletedAt)
        assertEquals(list.color, updated.color)
        assertEquals(list.sortKey, updated.sortKey, 0.0)

        val tag = SyncFields.applyTo(TagEntity(id = "t", name = "Old", createdAt = 1, updatedAt = 1), SyncFields.tag(TagEntity(id = "t", name = "New", createdAt = 1, updatedAt = 1)))
        assertEquals("New", tag.name)
        val cat = SyncFields.applyTo(CategoryEntity(id = "dairy", createdAt = 1, updatedAt = 1), mapOf("name" to JsonPrimitive("Milk stuff"), "color" to JsonNull, DELETED_AT to JsonNull))
        assertEquals("Milk stuff", cat.name)
        assertNull(cat.color)
    }

    @Test
    fun builtInCategoryOverrideIdsAreStablePerAccountAndKey() {
        val a = SyncFields.overrideCloudId("acct-1", "dairy")
        assertEquals(a, SyncFields.overrideCloudId("acct-1", "dairy"))
        assertNotEquals(a, SyncFields.overrideCloudId("acct-2", "dairy"))
        assertNotEquals(a, SyncFields.overrideCloudId("acct-1", "produce"))
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(a))
    }

    @Test
    fun tagIdParsingIsForgivingAboutBadInput() {
        assertEquals(emptyList<String>(), SyncFields.parseTagIds(null))
        assertEquals(emptyList<String>(), SyncFields.parseTagIds(""))
        assertEquals(emptyList<String>(), SyncFields.parseTagIds("not json"))
        assertEquals(emptyList<String>(), SyncFields.parseTagIds("""{"a":1}"""))
        assertEquals(listOf("a", "b"), SyncFields.parseTagIds("""["a","b",3]"""))
    }

    // ---- backoff ----------------------------------------------------------------------------------

    @Test
    fun backoffGrowsAndIsCapped() {
        assertEquals(5_000L, Backoff.delayMs(1))
        assertEquals(30_000L, Backoff.delayMs(2))
        assertEquals(120_000L, Backoff.delayMs(3))
        assertEquals(1_800_000L, Backoff.delayMs(5))
        assertEquals(1_800_000L, Backoff.delayMs(50))
        assertEquals(5_000L, Backoff.delayMs(0))
    }
}
