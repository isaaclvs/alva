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
import net.ts.taild80ca0.atlas.twa.data.list.AttemptResult
import net.ts.taild80ca0.atlas.twa.data.list.FreezeResult
import net.ts.taild80ca0.atlas.twa.data.list.WireOutbox
import net.ts.taild80ca0.atlas.twa.data.list.WireRefusal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Migration policy: data (above all the queue) is never discarded.
 *
 * The 1 -> 2 (issue #42), 2 -> 3, 3 -> 4 and 4 -> 5 (issue #45) steps are tested with rows in every old table; the machinery tests
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

    private val v2Tables = listOf(
        "sync_state", "categories", "catalog_items", "catalog_state", "confirmed_items",
        "list_items", "commands", "command_subjects",
    )

    /** Every column of every row of [tables] (except [skip]), in a stable order, as comparable text. */
    private fun dump(db: androidx.sqlite.db.SupportSQLiteDatabase, tables: List<String>, skip: Set<String> = emptySet()) =
        tables.associateWith { table ->
            db.query("SELECT * FROM $table ORDER BY rowid").use { c ->
                val columns = (0 until c.columnCount).filter { c.getColumnName(it) !in skip }
                buildList {
                    while (c.moveToNext()) {
                        add(columns.joinToString("|") { i -> c.getColumnName(i) + "=" + (if (c.isNull(i)) "NULL" else c.getString(i)) })
                    }
                }
            }
        }

    @Test
    fun version2To3KeepsEveryRowOfEveryTableAndLeavesTheQueueUnfrozen() {
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO sync_state (id, household_public_id, confirmed_revision) VALUES (1, 'house-1', 840)")
            execSQL("INSERT INTO categories (public_id, name, position) VALUES ('cat-1', 'Laticínios', 2)")
            execSQL("INSERT INTO categories (public_id, name, position) VALUES ('cat-2', 'Outros', 9)")
            execSQL("INSERT INTO catalog_items (public_id, name, normalized_name, category_public_id, learned) VALUES ('e-1', 'Leite', 'leite', 'cat-1', 0)")
            execSQL("INSERT INTO catalog_items (public_id, name, normalized_name, category_public_id, learned) VALUES ('e-2', 'Pão', 'pao', 'cat-2', 1)")
            execSQL("INSERT INTO catalog_state (id, dictionary_revision) VALUES (1, 17)")
            execSQL(
                "INSERT INTO confirmed_items (public_id, name, category_public_id, status, position, purchased_at, created_at, lock_version, trip_public_id) " +
                    "VALUES ('i-confirmed', 'Café', 'cat-1', 1, 4, 55, 9, 3, NULL)",
            )
            execSQL(
                "INSERT INTO confirmed_items (public_id, name, category_public_id, status, position, purchased_at, created_at, lock_version, trip_public_id) " +
                    "VALUES ('i-archived', 'Arroz', NULL, 1, NULL, 40, 8, 1, 'trip-1')",
            )
            execSQL(
                "INSERT INTO list_items (public_id, name, category_public_id, status, position, purchased_at, created_at, lock_version, trip_public_id) " +
                    "VALUES ('i-local', 'Leite', 'cat-1', 0, NULL, NULL, 10, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO list_items (public_id, name, category_public_id, status, position, purchased_at, created_at, lock_version, trip_public_id) " +
                    "VALUES ('i-confirmed', 'Café', 'cat-1', 1, 4, 55, 9, 3, NULL)",
            )
            // Legacy queue: tried twice (bytes unknown), never tried, dependent and blocked.
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, depends_on, observed_revision, status, created_at, attempts, last_error_code) " +
                    "VALUES ('c-1', 'add_items', '{\"names\":[\"Leite\"]}', '{}', NULL, 840, 'IN_FLIGHT', 10, 2, 'timeout')",
            )
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, depends_on, observed_revision, status, created_at, attempts, last_error_code) " +
                    "VALUES ('c-2', 'set_item_state', '{\"public_id\":\"i-local\",\"desired\":\"purchased\"}', '{}', 'c-1', 840, 'BLOCKED', 11, 0, NULL)",
            )
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, depends_on, observed_revision, status, created_at, attempts, last_error_code) " +
                    "VALUES ('c-3', 'set_item_state', '{\"public_id\":\"i-confirmed\",\"desired\":\"pending\"}', '{\"lock_version\":3}', NULL, 840, 'PENDING', 12, 0, NULL)",
            )
            execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role, category_public_id) VALUES ('c-1', 'i-local', 0, 'creates', 'cat-1')")
            execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role, category_public_id) VALUES ('c-2', 'i-local', 0, 'targets', NULL)")
            execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role, category_public_id) VALUES ('c-3', 'i-confirmed', 0, 'targets', NULL)")
            close()
        }
        // Snapshot the file as version 2 left it (a second read-only look after closing the helper's writer).
        val before = helper.runMigrationsAndValidate(name, 2, false).use { dump(it, v2Tables) }
        assertEquals(
            "every v2 table is populated before migrating",
            mapOf(
                "sync_state" to 1, "categories" to 2, "catalog_items" to 2, "catalog_state" to 1,
                "confirmed_items" to 2, "list_items" to 2, "commands" to 3, "command_subjects" to 3,
            ),
            before.mapValues { it.value.size },
        )

        helper.runMigrationsAndValidate(name, 3, true, AlvaMigrations.MIGRATION_2_3).apply {
            val after = dump(this, v2Tables, skip = setOf("wire_document"))
            assertEquals("no row of any table may change, move or disappear", before, after)
            assertEquals(
                listOf("c-1", "c-2", "c-3"),
                query("SELECT id FROM commands ORDER BY sequence").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } },
            )
            // Nothing is frozen, nothing is invented for rows that already existed.
            assertEquals(3, query("SELECT COUNT(*) FROM commands WHERE wire_document IS NULL").use { it.moveToFirst(); it.getInt(0) })
            close()
        }

        // Through the real factory: the old queue is readable, the unsafe legacy row is refused
        // intact, the never-tried rows stay available, and a new action can be frozen and tried.
        val db = AlvaDatabaseFactory.create(InstrumentationRegistry.getInstrumentation().targetContext, name)
        try {
            kotlinx.coroutines.runBlocking {
                val outbox = WireOutbox(db)
                val queue = db.commands().inOrder()
                assertEquals(listOf("c-1", "c-2", "c-3"), queue.map { it.id })
                val refused = outbox.beginAttempt("c-1") as AttemptResult.Refused
                assertEquals(WireRefusal.LEGACY_ALREADY_ATTEMPTED, refused.reason)
                assertEquals(WireRefusal.NOT_FROZEN, (outbox.beginAttempt("c-3") as AttemptResult.Refused).reason)
                assertEquals(queue, db.commands().inOrder())

                val store = net.ts.taild80ca0.atlas.twa.data.list.RoomShoppingListStore(db)
                val saved = store.execute(net.ts.taild80ca0.atlas.twa.data.list.ListIntent.AddText("Pão")) as net.ts.taild80ca0.atlas.twa.data.list.IntentResult.Saved
                val wire = """{"id":"${saved.commandId}","type":"add_items","depends_on":null,"preconditions":{},"payload":{"items":[{"public_id":"${saved.itemIds.single()}","name":"Pão"}]}}"""
                assertTrue((outbox.freeze(saved.commandId, wire) as FreezeResult.Frozen).created)
                assertEquals(wire, (outbox.beginAttempt(saved.commandId) as AttemptResult.Started).document)
                assertEquals(listOf("c-1", "c-2", "c-3", saved.commandId), db.commands().inOrder().map { it.id })
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

    @Test
    fun version3To4KeepsEveryRowIncludingFrozenDocumentsAndAddsEmptyAliasAndReceiptTables() {
        val frozen = """{"id":"c-1","type":"add_items","depends_on":null,"preconditions":{},"payload":{"items":[{"public_id":"i-local","name":"Leite"}]}}"""
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO sync_state (id, household_public_id, confirmed_revision) VALUES (1, 'house-1', 840)")
            execSQL("INSERT INTO categories (public_id, name, position) VALUES ('cat-1', 'Laticínios', 2)")
            execSQL("INSERT INTO catalog_items (public_id, name, normalized_name, category_public_id, learned) VALUES ('e-1', 'Leite', 'leite', 'cat-1', 0)")
            execSQL("INSERT INTO catalog_state (id, dictionary_revision) VALUES (1, 17)")
            execSQL(
                "INSERT INTO confirmed_items (public_id, name, category_public_id, status, position, purchased_at, created_at, lock_version, trip_public_id) " +
                    "VALUES ('i-confirmed', 'Café', 'cat-1', 1, 4, 55, 9, 3, NULL)",
            )
            execSQL(
                "INSERT INTO list_items (public_id, name, category_public_id, status, position, purchased_at, created_at, lock_version, trip_public_id) " +
                    "VALUES ('i-local', 'Leite', 'cat-1', 0, NULL, NULL, 10, NULL, NULL)",
            )
            // A frozen, already tried command; a dependent still unfrozen; and a conflict.
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, depends_on, observed_revision, status, created_at, attempts, last_error_code, wire_document) " +
                    "VALUES ('c-1', 'add_items', '{\"names\":[\"Leite\"]}', '{}', NULL, 840, 'IN_FLIGHT', 10, 2, NULL, ?)",
                arrayOf<Any>(frozen),
            )
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, depends_on, observed_revision, status, created_at, attempts, last_error_code, wire_document) " +
                    "VALUES ('c-2', 'set_item_state', '{\"public_id\":\"i-local\",\"desired\":\"purchased\"}', '{}', 'c-1', 840, 'PENDING', 11, 0, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, depends_on, observed_revision, status, created_at, attempts, last_error_code, wire_document) " +
                    "VALUES ('c-3', 'set_item_state', '{\"public_id\":\"i-confirmed\",\"desired\":\"pending\"}', '{\"lock_version\":3}', NULL, 840, 'CONFLICT', 12, 1, 'precondition_failed', NULL)",
            )
            execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role, category_public_id) VALUES ('c-1', 'i-local', 0, 'creates', 'cat-1')")
            execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role, category_public_id) VALUES ('c-2', 'i-local', 0, 'targets', NULL)")
            close()
        }
        val tables = v2Tables
        val before = helper.runMigrationsAndValidate(name, 3, false).use { dump(it, tables) }
        assertTrue("every v3 table is populated", before.values.all { it.isNotEmpty() })

        helper.runMigrationsAndValidate(name, 4, true, AlvaMigrations.MIGRATION_3_4).apply {
            assertEquals(
                "no row of any table may change, move or disappear (only the new column is added)",
                before,
                dump(this, tables, skip = setOf("answered_attempt")),
            )
            fun scalar(sql: String): String = query(sql).use { c -> c.moveToFirst(); c.getString(0) }
            assertEquals(frozen, scalar("SELECT wire_document FROM commands WHERE id = 'c-1'"))
            // Nothing is known about the answers to attempts made before the column existed.
            assertEquals("0,0,0", scalar("SELECT group_concat(answered_attempt) FROM (SELECT answered_attempt FROM commands ORDER BY sequence)"))
            // The new tables start empty.
            assertEquals("0", scalar("SELECT COUNT(*) FROM sync_aliases"))
            assertEquals("0", scalar("SELECT COUNT(*) FROM command_receipts"))
            // ...and work: an alias is unique, a receipt belongs to a command and goes with it
            // (the raw helper connection does not enforce foreign keys unless asked).
            execSQL("PRAGMA foreign_keys = ON")
            execSQL("INSERT INTO sync_aliases (alias_public_id, canonical_public_id, source_command_id, revision) VALUES ('a-1', 'i-confirmed', 'c-1', 841)")
            val twice = try {
                execSQL("INSERT INTO sync_aliases (alias_public_id, canonical_public_id, source_command_id, revision) VALUES ('a-1', 'other', 'c-9', 842)")
                false
            } catch (e: SQLiteConstraintException) {
                true
            }
            assertTrue("an alias can only be recorded once", twice)
            val orphan = try {
                execSQL("INSERT INTO command_receipts (command_id, outcome, via_duplicate, revision, house_revision, attempt, data_json, received_at) VALUES ('ghost', 'applied', 0, 1, 1, 1, '{}', 1)")
                false
            } catch (e: SQLiteConstraintException) {
                true
            }
            assertTrue("a receipt needs its command", orphan)
            execSQL("INSERT INTO command_receipts (command_id, outcome, via_duplicate, revision, house_revision, attempt, data_json, received_at) VALUES ('c-1', 'applied', 0, 841, 841, 2, '{}', 1)")
            execSQL("DELETE FROM commands WHERE id = 'c-1'")
            assertEquals("0", scalar("SELECT COUNT(*) FROM command_receipts"))
            assertEquals("the alias outlives the command", "1", scalar("SELECT COUNT(*) FROM sync_aliases"))
            close()
        }

        val db = AlvaDatabaseFactory.create(InstrumentationRegistry.getInstrumentation().targetContext, name)
        try {
            kotlinx.coroutines.runBlocking {
                assertEquals(listOf("c-2", "c-3"), db.commands().inOrder().map { it.id })
                assertEquals(listOf("a-1"), db.aliases().all().map { it.aliasPublicId })
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun version4To5KeepsEveryRowOfEveryTableAndAddsTheSnapshotStorageEmpty() {
        val frozen = """{"id":"c-1","type":"add_items","depends_on":null,"preconditions":{},"payload":{"items":[{"public_id":"i-local","name":"Leite"}]}}"""
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO sync_state (id, household_public_id, confirmed_revision) VALUES (1, 'house-1', 840)")
            execSQL("INSERT INTO categories (public_id, name, position) VALUES ('cat-1', 'Laticínios', 2)")
            execSQL("INSERT INTO catalog_items (public_id, name, normalized_name, category_public_id, learned) VALUES ('e-1', 'Leite', 'leite', 'cat-1', 0)")
            execSQL("INSERT INTO catalog_state (id, dictionary_revision) VALUES (1, 17)")
            execSQL(
                "INSERT INTO confirmed_items (public_id, name, category_public_id, status, position, purchased_at, created_at, lock_version, trip_public_id) " +
                    "VALUES ('i-confirmed', 'Café', 'cat-1', 1, 4, 55, 9, 3, NULL)",
            )
            execSQL(
                "INSERT INTO list_items (public_id, name, category_public_id, status, position, purchased_at, created_at, lock_version, trip_public_id) " +
                    "VALUES ('i-local', 'Leite', 'cat-1', 0, NULL, NULL, 10, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, depends_on, observed_revision, status, created_at, attempts, last_error_code, wire_document, answered_attempt) " +
                    "VALUES ('c-1', 'add_items', '{\"names\":[\"Leite\"]}', '{}', NULL, 840, 'ACKNOWLEDGED', 10, 2, NULL, ?, 0)",
                arrayOf<Any>(frozen),
            )
            execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, depends_on, observed_revision, status, created_at, attempts, last_error_code, wire_document, answered_attempt) " +
                    "VALUES ('c-2', 'set_item_state', '{\"public_id\":\"i-local\",\"desired\":\"purchased\"}', '{}', 'c-1', 840, 'CONFLICT', 11, 1, 'precondition_failed', NULL, 1)",
            )
            execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role, category_public_id) VALUES ('c-1', 'i-local', 0, 'creates', 'cat-1')")
            execSQL("INSERT INTO sync_aliases (alias_public_id, canonical_public_id, source_command_id, revision) VALUES ('a-1', 'i-confirmed', 'c-1', 841)")
            execSQL("INSERT INTO command_receipts (command_id, outcome, via_duplicate, revision, house_revision, attempt, data_json, received_at) VALUES ('c-1', 'applied', 0, 841, 841, 2, '{}', 1)")
            close()
        }
        val v4Tables = v2Tables + listOf("sync_aliases", "command_receipts")
        val before = helper.runMigrationsAndValidate(name, 4, false).use { dump(it, v4Tables) }
        assertTrue("every v4 table is populated", before.values.all { it.isNotEmpty() })
        val newColumns = setOf("household_name", "normalized_name", "created_at_micros", "updated_at_micros", "purchased_at_micros", "server_order")

        helper.runMigrationsAndValidate(name, 5, true, AlvaMigrations.MIGRATION_4_5).apply {
            assertEquals(
                "no row of any table may change, move or disappear (only new columns are added)",
                before,
                dump(this, v4Tables, skip = newColumns),
            )
            fun scalar(sql: String): String = query(sql).use { c -> c.moveToFirst(); c.getString(0) }
            // Nothing is invented for rows that already existed.
            assertEquals("1", scalar("SELECT COUNT(*) FROM confirmed_items WHERE normalized_name IS NULL AND created_at_micros IS NULL AND updated_at_micros IS NULL AND purchased_at_micros IS NULL AND server_order IS NULL"))
            assertEquals("1", scalar("SELECT COUNT(*) FROM sync_state WHERE household_name IS NULL AND household_public_id = 'house-1' AND confirmed_revision = 840"))
            assertEquals(frozen, scalar("SELECT wire_document FROM commands WHERE id = 'c-1'"))
            // The new tables start empty and work.
            listOf("archived_items", "shopping_trips", "habitual_items", "tombstones").forEach {
                assertEquals("$it starts empty", "0", scalar("SELECT COUNT(*) FROM $it"))
            }
            execSQL("INSERT INTO shopping_trips (public_id, finished_at_micros, created_at_micros, server_order) VALUES ('t-1', 1, 1, 0)")
            execSQL(
                "INSERT INTO archived_items (public_id, name, normalized_name, category_public_id, status, position, purchased_at_micros, created_at_micros, updated_at_micros, lock_version, trip_public_id, server_order) " +
                    "VALUES ('h-1', 'Arroz', 'arroz', NULL, 1, NULL, 5, 1, 1, 2, 't-1', 0)",
            )
            close()
        }

        val db = AlvaDatabaseFactory.create(InstrumentationRegistry.getInstrumentation().targetContext, name)
        try {
            kotlinx.coroutines.runBlocking {
                assertEquals(listOf("c-1", "c-2"), db.commands().inOrder().map { it.id })
                assertEquals(listOf("a-1"), db.aliases().all().map { it.aliasPublicId })
                assertEquals(840L, db.syncState().get()?.confirmedRevision)
                assertEquals(listOf("h-1"), db.history().archived().map { it.publicId })
            }
        } finally {
            db.close()
        }
    }
}
