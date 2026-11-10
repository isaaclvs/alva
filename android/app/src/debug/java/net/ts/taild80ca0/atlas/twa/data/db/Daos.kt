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

    /** Removes a command the server confirmed. Its subjects go with it (cascade). */
    @Query("DELETE FROM commands WHERE id = :id")
    suspend fun delete(id: String)
}
