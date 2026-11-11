package net.ts.taild80ca0.atlas.twa.data.list

import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.ListItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import org.json.JSONObject

/** A record a command touches, as stored in `command_subjects`. */
data class ProjectedSubject(
    val publicId: String,
    val role: String,
    /** For `add_items`: the index in `names` this subject belongs to. */
    val ordinal: Int,
    /** The category the device applied when the user acted; null when none was applied. */
    val categoryPublicId: String? = null,
)

/** A command as the projection sees it. */
data class ProjectedCommand(
    val type: String,
    val payload: JSONObject,
    val subjects: List<ProjectedSubject>,
    /** Local time of the intent; used only to display `purchased_at` until the server answers. */
    val createdAt: Long,
)

/**
 * Applies a command to the projection. Pure and deterministic, so the same code
 * runs when the user acts and when the projection is rebuilt from the confirmed
 * state plus the queue: both always agree.
 *
 * Returns only the rows that changed. Unknown command types change nothing (a
 * newer task owns them), and commands on records absent from the projection are
 * ignored instead of recreating them.
 */
object ProjectionReducer {
    /** The rows a command changed and the ids it found to be the same item as one already on the list. */
    class Replay(val changed: List<ListItemEntity>, val sameAs: Map<String, String>)

    fun reduce(current: Map<String, ListItemEntity>, command: ProjectedCommand): List<ListItemEntity> =
        replay(current, command) { it }.changed

    /**
     * [reduce] for a queue that has met the server. [resolve] maps an id the command cites to the
     * id its row has in [current] (the aliases the server revealed): a command queued against a
     * UUID the device made up still finds the item the server kept. A name the device is
     * creating that another device already has pending is not shown twice: the create is
     * left out and reported in [Replay.sameAs], which is what the server will do with it
     * (the UUID becomes an alias), so the commands after it act on the item that exists.
     * It only ever merges with a **pending** item: a purchase is never brought back by a
     * create, because the server needs the `revives` observation for that.
     */
    fun replay(current: Map<String, ListItemEntity>, command: ProjectedCommand, resolve: (String) -> String): Replay =
        when (command.type) {
            CommandTypes.ADD_ITEMS -> addItems(current, command, resolve)
            CommandTypes.SET_ITEM_STATE -> Replay(setItemState(current, command, resolve), emptyMap())
            else -> Replay(emptyList(), emptyMap())
        }

    /**
     * Each name of `add_items` is either a new item (subject `creates`, with the
     * category chosen when the user typed it) or a purchased one typed again
     * (subject `targets`), which goes back to pending exactly like the server's
     * revive: same row, same name, category as the dictionary says. Names the
     * device found already pending are not in the command at all.
     */
    private fun addItems(current: Map<String, ListItemEntity>, command: ProjectedCommand, resolve: (String) -> String): Replay {
        val names = command.payload.getJSONArray("names")
        val sameAs = HashMap<String, String>()
        val changed = command.subjects.sortedBy { it.ordinal }.mapNotNull { subject ->
            when (subject.role) {
                SubjectRole.CREATES -> {
                    val name = names.getString(subject.ordinal)
                    val twin = pendingNamed(current, name, except = subject.publicId)
                    if (twin != null) {
                        sameAs[subject.publicId] = twin.publicId
                        null
                    } else {
                        ListItemEntity(
                            publicId = subject.publicId,
                            name = name,
                            categoryPublicId = subject.categoryPublicId,
                            status = ItemStatusCodes.PENDING,
                            position = null,
                            purchasedAt = null,
                            createdAt = command.createdAt,
                            lockVersion = null,
                            tripPublicId = null,
                        )
                    }
                }
                SubjectRole.TARGETS -> current[resolve(subject.publicId)]
                    ?.takeIf { it.tripPublicId == null && it.status == ItemStatusCodes.PURCHASED }
                    ?.let { item ->
                        item.copy(
                            status = ItemStatusCodes.PENDING,
                            purchasedAt = null,
                            categoryPublicId = subject.categoryPublicId ?: item.categoryPublicId,
                        )
                    }
                else -> null
            }
        }
        return Replay(changed, sameAs)
    }

    private fun pendingNamed(current: Map<String, ListItemEntity>, name: String, except: String): ListItemEntity? {
        val key = TextNormalizer.key(name)
        if (key.isEmpty()) return null
        return current.values.firstOrNull {
            it.publicId != except && it.tripPublicId == null && it.status == ItemStatusCodes.PENDING && TextNormalizer.key(it.name) == key
        }
    }

    private fun setItemState(
        current: Map<String, ListItemEntity>,
        command: ProjectedCommand,
        resolve: (String) -> String,
    ): List<ListItemEntity> {
        val item = current[resolve(command.payload.getString("public_id"))] ?: return emptyList()
        if (item.tripPublicId != null) return emptyList()
        val purchased = command.payload.getString("desired") == DESIRED_PURCHASED
        val status = if (purchased) ItemStatusCodes.PURCHASED else ItemStatusCodes.PENDING
        if (item.status == status) return emptyList()
        return listOf(item.copy(status = status, purchasedAt = if (purchased) command.createdAt else null))
    }

    const val DESIRED_PENDING = "pending"
    const val DESIRED_PURCHASED = "purchased"
}
