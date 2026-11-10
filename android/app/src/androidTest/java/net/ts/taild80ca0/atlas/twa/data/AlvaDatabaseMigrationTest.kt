package net.ts.taild80ca0.atlas.twa.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabaseFactory
import net.ts.taild80ca0.atlas.twa.data.db.AlvaMigrations
import net.ts.taild80ca0.atlas.twa.data.db.CommandSubjectEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Migration policy: data (above all the queue) is never discarded.
 *
 * With the schema still at version 1 there is no version-to-version step to run, so these
 * tests prove the machinery a first real migration relies on (exported schemas,
 * [MigrationTestHelper], the factory's migration list) and that a missing migration fails
 * instead of erasing data. The first change to the schema must add its own
 * 1 -> 2 test with rows in the old tables.
 */
class AlvaDatabaseMigrationTest {
    private val name = "migration-${UUID.randomUUID()}.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AlvaDatabase::class.java,
    )

    @After
    fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
    }

    @Test
    fun everyOlderVersionMigratesToTheCurrentOneKeepingItsRows() {
        for (from in 1 until AlvaMigrations.CURRENT_VERSION) {
            helper.createDatabase(name, from).close()
            helper.runMigrationsAndValidate(name, AlvaMigrations.CURRENT_VERSION, true, *AlvaMigrations.ALL).close()
        }
        // Today CURRENT_VERSION == 1, so the loop is empty; it starts to cover 1 -> 2 as soon as that exists.
        assertEquals(AlvaMigrations.CURRENT_VERSION - 1, AlvaMigrations.ALL.size)
    }

    @Test
    fun aVersion1DatabaseWithQueuedWorkOpensThroughTheFactoryUnchanged() {
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO list_items (public_id, name, status, created_at) VALUES ('i1', 'Leite', 0, 10)",
            )
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, observed_revision, status, created_at, attempts) " +
                    "VALUES ('c1', 'add_items', '{\"names\":[\"Leite\"]}', '{}', 3, 'PENDING', 10, 0)",
            )
            execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role) VALUES ('c1', 'i1', 0, 'creates')")
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AlvaDatabaseFactory.create(context, name)
        try {
            kotlinx.coroutines.runBlocking {
                assertEquals(listOf("Leite"), db.listItems().all().map { it.name })
                assertEquals(listOf("c1"), db.commands().inOrder().map { it.id })
                assertEquals(listOf("i1"), db.commands().subjectsOf("c1").map { it.subjectPublicId })
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun aDatabaseWithoutAMigrationPathFailsToOpenAndKeepsItsData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, observed_revision, status, created_at, attempts) " +
                    "VALUES ('c1', 'add_items', '{}', '{}', 0, 'PENDING', 1, 0)",
            )
            close()
        }
        // The file now claims a version this build has no migration for (e.g. a newer app wrote it).
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = AlvaMigrations.CURRENT_VERSION + 1
        }

        try {
            AlvaDatabaseFactory.create(context, name).openHelper.writableDatabase
            fail("opening must fail without a migration instead of recreating the database")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("migration", ignoreCase = true))
        }

        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            val ids = it.rawQuery("SELECT id FROM commands", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            assertEquals("the queued command must still be there", listOf("c1"), ids)
        }
    }

    @Test
    fun aSubjectWithoutItsCommandIsRefusedByTheDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AlvaDatabaseFactory.create(context, name)
        try {
            val refused = try {
                kotlinx.coroutines.runBlocking {
                    db.commands().insertSubjects(listOf(CommandSubjectEntity("ghost", "i1", 0, "creates")))
                }
                false
            } catch (e: SQLiteConstraintException) {
                true
            }
            assertTrue("foreign keys must stay enforced for the queue", refused)
        } finally {
            db.close()
        }
    }
}
