package net.ts.taild80ca0.atlas.twa.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncStateEntity)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY position, public_id")
    suspend fun all(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun clear()
}

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_items ORDER BY normalized_name")
    suspend fun entries(): List<CatalogItemEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(entries: List<CatalogItemEntity>)

    @Query("DELETE FROM catalog_items")
    suspend fun clearEntries()

    @Query("SELECT * FROM catalog_state WHERE id = 1")
    suspend fun state(): CatalogStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: CatalogStateEntity)
}

@Dao
interface ConfirmedItemDao {
    @Query("SELECT * FROM confirmed_items ORDER BY created_at, rowid")
    suspend fun all(): List<ConfirmedItemEntity>

    @Upsert
    suspend fun upsert(items: List<ConfirmedItemEntity>)

    @Query("DELETE FROM confirmed_items")
    suspend fun clear()
}

@Dao
interface ListItemDao {
    @Query("SELECT * FROM list_items WHERE trip_public_id IS NULL ORDER BY created_at, rowid")
    suspend fun live(): List<ListItemEntity>

    @Query("SELECT * FROM list_items WHERE public_id = :publicId")
    suspend fun find(publicId: String): ListItemEntity?

    @Query("SELECT * FROM list_items WHERE public_id IN (:publicIds)")
    suspend fun findAll(publicIds: List<String>): List<ListItemEntity>

    /** Insert-or-update in place, so a row keeps its position in the list. */
    @Upsert
    suspend fun upsert(items: List<ListItemEntity>)

    @Query("SELECT * FROM list_items ORDER BY created_at, rowid")
    suspend fun all(): List<ListItemEntity>

    @Query("DELETE FROM list_items")
    suspend fun clear()
}

@Dao
interface CommandDao {
    /** Plain INSERT: a repeated UUID is a constraint failure, never a second action. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(command: CommandEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubjects(subjects: List<CommandSubjectEntity>)

    /** The whole queue in device order. */
    @Query("SELECT * FROM commands ORDER BY sequence")
    suspend fun inOrder(): List<CommandEntity>

    @Query("SELECT * FROM commands WHERE id = :id")
    suspend fun find(id: String): CommandEntity?

    @Query("SELECT * FROM command_subjects WHERE command_id = :id ORDER BY ordinal")
    suspend fun subjectsOf(id: String): List<CommandSubjectEntity>

    /** Most recent queued command that touches any of the records, i.e. the one a new command depends on. */
    @Query(
        """
        SELECT c.* FROM commands c
        JOIN command_subjects s ON s.command_id = c.id
        WHERE s.subject_public_id IN (:subjectIds)
        ORDER BY c.sequence DESC LIMIT 1
        """,
    )
    suspend fun latestTouching(subjectIds: List<String>): CommandEntity?

    /** `public_id`s with a queued command: the items the UI shows as "waiting to sync". */
    @Query("SELECT DISTINCT subject_public_id FROM command_subjects")
    suspend fun subjectIdsInQueue(): List<String>

    @Query("SELECT COUNT(*) FROM commands WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM commands")
    suspend fun count(): Int

    @Query("UPDATE commands SET status = :status, last_error_code = :errorCode WHERE id = :id")
    suspend fun setStatus(id: String, status: String, errorCode: String?)

    /**
     * Stores the wire document once. Guarded in SQL, so the first writer wins even if two
     * callers race: the document can only go from NULL to a value, never be replaced, and
     * never be created for a row that may already have been sent (`attempts > 0`).
     * Returns the number of rows written (0 or 1).
     */
    @Query("UPDATE commands SET wire_document = :document WHERE id = :id AND wire_document IS NULL AND attempts = 0")
    suspend fun freezeWireDocument(id: String, document: String): Int

    /**
     * Counts one attempt and marks the command in flight, only for a frozen command in a
     * status that may be sent. Returns the number of rows written (0 or 1).
     */
    @Query(
        "UPDATE commands SET attempts = attempts + 1, status = :inFlight " +
            "WHERE id = :id AND wire_document IS NOT NULL AND status IN (:sendable)",
    )
    suspend fun recordAttempt(id: String, inFlight: String, sendable: List<String>): Int

    /** Removes a command the server confirmed. Its subjects go with it (cascade). */
    @Query("DELETE FROM commands WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AliasDao {
    @Query("SELECT * FROM sync_aliases WHERE alias_public_id = :aliasPublicId")
    suspend fun find(aliasPublicId: String): SyncAliasEntity?

    @Query("SELECT * FROM sync_aliases ORDER BY alias_public_id")
    suspend fun all(): List<SyncAliasEntity>

    /** Aliases that point at any of [canonicalIds]. */
    @Query("SELECT * FROM sync_aliases WHERE canonical_public_id IN (:canonicalIds)")
    suspend fun pointingAt(canonicalIds: List<String>): List<SyncAliasEntity>

    /** Plain INSERT: a second, different answer for the same alias is a constraint failure, not an overwrite. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(alias: SyncAliasEntity)
}

@Dao
interface ReceiptDao {
    @Query("SELECT * FROM command_receipts WHERE command_id = :commandId")
    suspend fun find(commandId: String): CommandReceiptEntity?

    @Query("SELECT * FROM command_receipts")
    suspend fun all(): List<CommandReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(receipt: CommandReceiptEntity)
}
