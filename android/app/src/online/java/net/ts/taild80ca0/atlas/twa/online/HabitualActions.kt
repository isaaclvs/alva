package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.data.matching.RubyText
import org.json.JSONArray
import org.json.JSONObject

class CreateHabitual(private val name: String) : OnlineAction {
    override fun plan(context: PlanContext): ActionPlan = ActionPlan.Send(context.document(
        "create_habitual", JSONObject().put("name", RubyText.squish(name)).toString(),
    ))
}

class RemoveHabitual(private val publicId: String) : OnlineAction {
    override fun plan(context: PlanContext): ActionPlan = ActionPlan.Send(context.document(
        "remove_habitual", JSONObject().put("public_id", publicId).toString(),
    ))
}

/** Null selects every habitual on the server; selections have no text-field limit of thirty. */
class AddHabituals(private val publicIds: List<String>? = null) : OnlineAction {
    override fun plan(context: PlanContext): ActionPlan {
        if (publicIds != null && publicIds.isEmpty()) return ActionPlan.Invalid(InvalidAction.NO_HABITUALS)
        val payload = if (publicIds == null) JSONObject().put("all", true)
            else JSONObject().put("public_ids", JSONArray(publicIds.distinct()))
        return ActionPlan.Send(context.document("add_habituals", payload.toString()))
    }
}
