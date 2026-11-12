package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.sync.CommandReceipt
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

class FinishShopping(private val observed: List<net.ts.taild80ca0.atlas.twa.sync.SnapshotItem>? = null) : OnlineAction {
    override fun plan(context: PlanContext): ActionPlan {
        val bought = (observed ?: context.snapshot.listItems).filter { it.status == ItemStatusCodes.PURCHASED }
        if (bought.isEmpty()) return ActionPlan.Invalid(InvalidAction.NO_PURCHASED_ITEMS)
        val entries = bought.joinToString(",") { """{"public_id":${JSONObject.quote(it.publicId)},"lock_version":${it.lockVersion}}""" }
        return ActionPlan.Send(context.document("finish_shopping", """{"items":[$entries]}"""))
    }
}

class UndoShopping(private val tripPublicId: String) : OnlineAction {
    override fun plan(context: PlanContext): ActionPlan = ActionPlan.Send(
        context.document("undo_shopping", """{"trip_public_id":${JSONObject.quote(tripPublicId)}}"""),
    )
}

fun CommandReceipt.shoppingTripPublicId(): String? {
    return try {
        val data = dataJson?.let { JSONTokener(it).nextValue() as? JSONObject } ?: return null
        data.optJSONObject("trip")?.optString("public_id")?.takeIf { value ->
            value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
        }
    } catch (_: JSONException) { null }
}
