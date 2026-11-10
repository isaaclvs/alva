package net.ts.taild80ca0.atlas.twa.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabaseFactory
import net.ts.taild80ca0.atlas.twa.data.list.Clock
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import net.ts.taild80ca0.atlas.twa.data.list.RoomShoppingListStore
import org.junit.After
import org.junit.Before

/** Hands out the scripted ids first, then random UUIDs. */
class ScriptedIds(private vararg val scripted: String) : IdGenerator {
    private var next = 0
    override fun newId(): String = scripted.getOrNull(next++) ?: UUID.randomUUID().toString()
}

/** A strictly increasing fake clock: each call is 1 ms after the previous one. */
class TickingClock(private var now: Long = 1_000_000L) : Clock {
    override fun nowMillis(): Long = now++
}

/**
 * Base for tests on a real database file (never in memory), so that closing and
 * reopening exercises what survives on disk. Opened databases are closed and the
 * file removed after each test.
 */
abstract class DatabaseTest {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val fileName = "test-${UUID.randomUUID()}.db"
    private val opened = mutableListOf<AlvaDatabase>()

    @Before
    fun removeLeftovers() {
        context.deleteDatabase(fileName)
    }

    @After
    fun closeAndDelete() {
        opened.forEach { if (it.isOpen) it.close() }
        context.deleteDatabase(fileName)
    }

    protected fun openDatabase(): AlvaDatabase =
        AlvaDatabaseFactory.create(context, fileName).also { opened += it }

    protected fun storeOn(
        db: AlvaDatabase,
        ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() },
        clock: Clock = TickingClock(),
    ) = RoomShoppingListStore(db, ids, clock)
}
