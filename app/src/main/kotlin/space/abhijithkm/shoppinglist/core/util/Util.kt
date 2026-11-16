package space.abhijithkm.shoppinglist.core.util

import java.util.UUID
import javax.inject.Inject

/** Time source, injectable so tests control timestamps. */
interface Clock {
    fun nowMillis(): Long
}

class DeviceClock @Inject constructor() : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Source of entity ids. Ids are client-generated UUIDs and double as AppHub primary keys later (idempotent creates). */
interface IdGenerator {
    fun newId(): String
}

class UuidGenerator @Inject constructor() : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
