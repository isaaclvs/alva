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
 * The 1 -> 2 step (issue #42) is tested with rows in every old table; the machinery tests
 * prove the exported schemas, [MigrationTestHelper] and the factory's migration list work
 * and that a missing migration fails instead of erasing data. Each future version needs
 * its own test with data in the tables it changes.
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
        assertEquals(AlvaMigrations.CURRENT_VERSION - 1, AlvaMigrations.ALL.size)
    }

    @Test
    fun version1To2KeepsEveryRowAndAddsTheCatalogAndTheSubjectCategory() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO sync_state (id, household_public_id, confirmed_revision) VALUES (1, 'house-1', 840)")
            execSQL("INSERT INTO categories (public_id, name, position) VALUES ('cat-1', 'Laticínios', 2)")
            execSQL(
                "INSERT INTO confirmed_items (public_id, name, category_public_id, status, position, purchased_at, created_at, lock_version, trip_public_id) " +
                    "VALUES ('i-confirmed', 'Café', 'cat-1', 1, NULL, 55, 9, 3, NULL)",
            )
            execSQL(
                "INSERT INTO list_items (public_id, name, category_public_id, status, position, purchased_at, created_at, lock_version, trip_public_id) " +
                    "VALUES ('i-local', 'Leite', NULL, 0, NULL, NULL, 10, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, depends_on, observed_revision, status, created_at, attempts, last_error_code) " +
                    "VALUES ('c-1', 'add_items', '{\"names\":[\"Leite\"]}', '{}', NULL, 840, 'PENDING', 10, 2, 'timeout')",
            )
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, depends_on, observed_revision, status, created_at, attempts, last_error_code) " +
                    "VALUES ('c-2', 'set_item_state', '{\"public_id\":\"i-local\",\"desired\":\"purchased\"}', '{}', 'c-1', 840, 'BLOCKED', 11, 0, NULL)",
            )
            execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role) VALUES ('c-1', 'i-local', 0, 'creates')")
            execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role) VALUES ('c-2', 'i-local', 0, 'targets')")
            close()
        }

        helper.runMigrationsAndValidate(name, 2, true, AlvaMigrations.MIGRATION_1_2).apply {
            fun scalar(sql: String): String = query(sql).use { c -> c.moveToFirst(); c.getString(0) }
            // Nothing that existed was touched.
            assertEquals("house-1|840", scalar("SELECT household_public_id || '|' || confirmed_revision FROM sync_state"))
            assertEquals("Laticínios", scalar("SELECT name FROM categories WHERE public_id = 'cat-1'"))
            assertEquals("Café|1|55|3", scalar("SELECT name || '|' || status || '|' || purchased_at || '|' || lock_version FROM confirmed_items"))
            assertEquals("Leite|10", scalar("SELECT name || '|' || created_at FROM list_items WHERE public_id = 'i-local'"))
            assertEquals("c-1,c-2", scalar("SELECT group_concat(id) FROM (SELECT id FROM commands ORDER BY sequence)"))
            assertEquals("PENDING|2|timeout", scalar("SELECT status || '|' || attempts || '|' || last_error_code FROM commands WHERE id = 'c-1'"))
            assertEquals("c-1", scalar("SELECT depends_on FROM commands WHERE id = 'c-2'"))
            assertEquals("2", scalar("SELECT COUNT(*) FROM command_subjects"))
            // The new pieces start empty and old subjects have no decided category.
            assertEquals("0", scalar("SELECT COUNT(*) FROM catalog_items"))
            assertEquals("0", scalar("SELECT COUNT(*) FROM catalog_state"))
            assertEquals("2", scalar("SELECT COUNT(*) FROM command_subjects WHERE category_public_id IS NULL"))
            // ...and they work: the unique normalized name and the cascade from commands still hold.
            execSQL("INSERT INTO catalog_items (public_id, name, normalized_name, category_public_id, learned) VALUES ('e-1', 'Leite', 'leite', 'cat-1', 0)")
            val duplicate = try {
                execSQL("INSERT INTO catalog_items (public_id, name, normalized_name, category_public_id, learned) VALUES ('e-2', 'Leites', 'leite', 'cat-1', 0)")
                false
            } catch (e: SQLiteConstraintException) {
                true
            }
            assertTrue("normalized_name must stay unique", duplicate)
            close()
        }

        // The migrated file opens through the factory and the old queue rebuilds the same projection.
        val db = AlvaDatabaseFactory.create(InstrumentationRegistry.getInstrumentation().targetContext, name)
        try {
            kotlinx.coroutines.runBlocking {
                val store = net.ts.taild80ca0.atlas.twa.data.list.RoomShoppingListStore(db)
                store.rebuildProjection()
                assertEquals(listOf("Café", "Leite"), db.listItems().all().map { it.name })
                assertEquals(listOf(1, 1), db.listItems().all().map { it.status })
                // A new add on top of the migrated queue works, with local matching.
                val saved = store.execute(net.ts.taild80ca0.atlas.twa.data.list.ListIntent.AddText("Pão"))
                assertTrue(saved is net.ts.taild80ca0.atlas.twa.data.list.IntentResult.Saved)
                assertEquals(listOf("c-1", "c-2"), db.commands().inOrder().map { it.id }.take(2))
            }
        } finally {
            db.close()
        }
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
