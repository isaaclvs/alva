package net.ts.taild80ca0.atlas.twa.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent model of the offline client, in three layers:
 *
 * 1. **Confirmed state** (`sync_state`, `categories`, `confirmed_items`): what the
 *    server last told this device, keyed by the server's `public_id` (UUID).
 *    Written only by reconciliation, never by a user action.
 * 2. **Local projection** (`list_items`): what screens and the widget show,
 *    i.e. the confirmed state with every still-queued command applied.
 * 3. **Queue** (`commands`, `command_subjects`): the ordered, durable intent of
 *    this device, with the UUID that is also the idempotency key.
 *
 * A user action writes layer 2 and layer 3 in one transaction (see
 * `RoomShoppingListStore`), so the projection never shows something the queue
 * does not know about, and the queue never holds an action the screen lost.
 */

/** Single-row table (id is always 1) with what this device knows about the server. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** `public_id` of the household this copy belongs to; null before the first load. */
    @ColumnInfo(name = "household_public_id") val householdPublicId: String?,
    /** Last household `sync_revision` applied to the confirmed state. */
    @ColumnInfo(name = "confirmed_revision") val confirmedRevision: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey @ColumnInfo(name = "public_id") val publicId: String,
    val name: String,
    val position: Int,
)

/**
 * The server's dictionary (`catalog_items` in the snapshot): which category a
 * known name belongs to. Confirmed state like `categories`: replaced as a whole
 * by reconciliation, never edited by a user action. Lookups use the server's own
 * `normalized_name`, so the device matches against exactly what the server stored.
 * No foreign key to `categories`: both are replaced together, in one transaction.
 */
@Entity(tableName = "catalog_items", indices = [Index(value = ["normalized_name"], unique = true)])
data class CatalogItemEntity(
    @PrimaryKey @ColumnInfo(name = "public_id") val publicId: String,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "category_public_id") val categoryPublicId: String,
    /** True when a household corrected a category by hand; the server's seed never overwrites those. */
    val learned: Boolean,
)

/**
 * Single-row table (id is always 1) with the dictionary revision of the last
 * applied catalog. Kept apart from `sync_state` so that writing the household
 * state can never reset it.
 */
@Entity(tableName = "catalog_state")
data class CatalogStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** `dictionary_revision` of the snapshot the stored categories and entries came from. */
    @ColumnInfo(name = "dictionary_revision") val dictionaryRevision: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/** Columns shared by a confirmed item and its projection (they mirror Rails' `list_items`). */
@Entity(tableName = "confirmed_items", indices = [Index("trip_public_id")])
data class ConfirmedItemEntity(
    @PrimaryKey @ColumnInfo(name = "public_id") val publicId: String,
    val name: String,
    @ColumnInfo(name = "category_public_id") val categoryPublicId: String?,
    /** [ItemStatusCodes]: 0 pending, 1 purchased (Rails enum values). */
    val status: Int,
    val position: Int?,
    /** Epoch millis. */
    @ColumnInfo(name = "purchased_at") val purchasedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Rails `lock_version`: the precondition commands carry. */
    @ColumnInfo(name = "lock_version") val lockVersion: Int,
    /** Present = archived (history, not part of the live list). */
    @ColumnInfo(name = "trip_public_id") val tripPublicId: String?,
)

@Entity(
    tableName = "list_items",
    indices = [Index("trip_public_id"), Index("category_public_id")],
)
data class ListItemEntity(
    /** Server `public_id`, or one generated on this device for an item not yet confirmed. */
    @PrimaryKey @ColumnInfo(name = "public_id") val publicId: String,
    val name: String,
    @ColumnInfo(name = "category_public_id") val categoryPublicId: String?,
    val status: Int,
    val position: Int?,
    @ColumnInfo(name = "purchased_at") val purchasedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Confirmed `lock_version`; null while the server has never seen the item. */
    @ColumnInfo(name = "lock_version") val lockVersion: Int?,
    @ColumnInfo(name = "trip_public_id") val tripPublicId: String?,
)

/**
 * One queued command. `sequence` is the per-device order (assigned by SQLite,
 * strictly increasing, never reused); `id` is the UUID sent to the server.
 * Rows leave the queue only when the server confirmed them (or the user
 * resolves a conflict) — there is no expiry.
 */
@Entity(
    tableName = "commands",
    indices = [Index(value = ["id"], unique = true), Index("status")],
)
data class CommandEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val id: String,
    /** Contract v1 command `type`, e.g. `add_items`. Kept open so later commands need no migration. */
    val type: String,
    /** JSON object with the contract `payload`. */
    val payload: String,
    /** JSON object with the contract `preconditions`. */
    val preconditions: String,
    /** UUID of the earlier command of this device this one depends on, if any. */
    @ColumnInfo(name = "depends_on") val dependsOn: String?,
    @ColumnInfo(name = "observed_revision") val observedRevision: Long,
    /** [CommandStatus] name. */
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val attempts: Int = 0,
    /** Contract error `code` of the last failed attempt; the command is kept. */
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
)

/**
 * The records a command touches. Subjects with role `creates` are items whose
 * `public_id` was generated on this device (ordinal matches the `names` index of
 * `add_items`); `targets` are existing ones. Dependencies between commands are
 * derived from shared subjects.
 */
@Entity(
    tableName = "command_subjects",
    primaryKeys = ["command_id", "subject_public_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = CommandEntity::class,
            parentColumns = ["id"],
            childColumns = ["command_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("subject_public_id")],
)
data class CommandSubjectEntity(
    @ColumnInfo(name = "command_id") val commandId: String,
    @ColumnInfo(name = "subject_public_id") val subjectPublicId: String,
    val ordinal: Int,
    val role: String,
    /**
     * What the device decided for this record when the user acted: the category an
     * `add_items` name was given (`creates`) or revived with (`targets`). Local
     * only, never part of the command sent to the server; the projection is rebuilt
     * from it. Null means no category was applied (none known on the device, or an
     * existing one kept).
     */
    @ColumnInfo(name = "category_public_id") val categoryPublicId: String? = null,
)

object ItemStatusCodes {
    const val PENDING = 0
    const val PURCHASED = 1
}

object SubjectRole {
    const val CREATES = "creates"
    const val TARGETS = "targets"
}
