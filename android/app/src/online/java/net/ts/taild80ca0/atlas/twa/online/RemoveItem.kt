package net.ts.taild80ca0.atlas.twa.online

import org.json.JSONObject

/** Remove only the live version observed by this device. Rails keeps the original attributes for undo. */
class RemoveItem(private val publicId: String, private val observedLockVersion: Int? = null) : OnlineAction {
    override fun plan(context: PlanContext): ActionPlan {
        val item = context.snapshot.listItems.firstOrNull { it.publicId.equals(publicId, ignoreCase = true) }
            ?: return ActionPlan.Invalid(InvalidAction.ITEM_NOT_FOUND)
        return ActionPlan.Send(context.document(
            "remove_item", """{"public_id":${JSONObject.quote(item.publicId)}}""",
            """{"lock_version":${observedLockVersion ?: item.lockVersion}}""",
        ))
    }
}

/** Compensates the successful removal receipt, without sending client-authored copies of the item. */
class UndoRemove(private val removalCommandId: String) : OnlineAction {
    override fun plan(context: PlanContext) = ActionPlan.Send(
        context.document("undo_remove", "{}", dependsOn = removalCommandId),
    )
}
