package net.ts.taild80ca0.atlas.twa.data.list

import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.ListItemEntity
import org.json.JSONObject

/** A command as the projection sees it. */
data class ProjectedCommand(
    val type: String,
    val payload: JSONObject,
    /** Record ids generated on this device for `add_items`, aligned with `names`. */
    val createdIds: List<String>,
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
    fun reduce(current: Map<String, ListItemEntity>, command: ProjectedCommand): List<ListItemEntity> =
        when (command.type) {
            CommandTypes.ADD_ITEMS -> addItems(command)
            CommandTypes.SET_ITEM_STATE -> setItemState(current, command)
            else -> emptyList()
        }

    private fun addItems(command: ProjectedCommand): List<ListItemEntity> {
        val names = command.payload.getJSONArray("names")
        return command.createdIds.mapIndexed { index, publicId ->
            ListItemEntity(
                publicId = publicId,
                name = names.getString(index),
                categoryPublicId = null,
                status = ItemStatusCodes.PENDING,
                position = null,
                purchasedAt = null,
                createdAt = command.createdAt,
                lockVersion = null,
                tripPublicId = null,
            )
        }
    }

    private fun setItemState(
        current: Map<String, ListItemEntity>,
        command: ProjectedCommand,
    ): List<ListItemEntity> {
        val item = current[command.payload.getString("public_id")] ?: return emptyList()
        if (item.tripPublicId != null) return emptyList()
        val purchased = command.payload.getString("desired") == DESIRED_PURCHASED
        val status = if (purchased) ItemStatusCodes.PURCHASED else ItemStatusCodes.PENDING
        if (item.status == status) return emptyList()
        return listOf(item.copy(status = status, purchasedAt = if (purchased) command.createdAt else null))
    }

    const val DESIRED_PENDING = "pending"
    const val DESIRED_PURCHASED = "purchased"
}
