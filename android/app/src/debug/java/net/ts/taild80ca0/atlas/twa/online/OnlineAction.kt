package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.sync.CommandReceipt
import net.ts.taild80ca0.atlas.twa.sync.ServerSnapshot
import org.json.JSONObject

/**
 * One thing the person asked for. [OnlineRepository.execute] gives it a new command UUID, asks it for its
 * document, sends that document and reloads the list. Adding an action (#97–#109) means adding one class that
 * implements this interface: the repository, the retries and the results do not change.
 */
interface OnlineAction {
    /**
     * Builds the command from the last snapshot (the only place preconditions such as `lock_version` and
     * `revives` come from), or says why nothing can be sent. Called once per action; the document it returns is
     * what every retry puts on the wire.
     */
    fun plan(context: PlanContext): ActionPlan
}

/** What an action may use to build its document. */
class PlanContext(
    /** The UUID of this action's command, new for every action. */
    val commandId: String,
    val snapshot: ServerSnapshot,
    private val ids: () -> String,
) {
    /** A new UUID, for an item or a claim the command introduces. */
    fun newId(): String = ids()

    /** The envelope of contract v1 around [payload] and [preconditions], both already JSON. */
    fun document(type: String, payload: String, preconditions: String = "{}", dependsOn: String? = null): String =
        buildString {
            append("""{"id":""").append(JSONObject.quote(commandId))
            append(""","type":""").append(JSONObject.quote(type))
            append(""","depends_on":""").append(dependsOn?.let(JSONObject::quote) ?: "null")
            append(""","observed_revision":""").append(snapshot.revision)
            append(""","preconditions":""").append(preconditions)
            append(""","payload":""").append(payload)
            append('}')
        }
}

sealed interface ActionPlan {
    /** Send [document], byte for byte, as the only command of the request. */
    data class Send(val document: String) : ActionPlan

    /** Nothing is sent: the request cannot be applied as asked. */
    data class Invalid(val reason: InvalidAction) : ActionPlan
}

enum class InvalidAction {
    /** Nothing usable was typed. */
    NO_NAMES,

    /** More names than one command takes (30). */
    TOO_MANY_NAMES,

    /** The item is not on the live list of the last snapshot. */
    ITEM_NOT_FOUND,
    INVALID_NAME,
    CATEGORY_NOT_FOUND,
}

/** What happened to one action. The text for each case lives in [OnlineMessages]. */
sealed interface ActionResult {
    /** The server applied the command, or had already (`applied`, `noop` or `duplicate`). */
    data class Success(val receipt: CommandReceipt) : ActionResult

    /** The server refused it with a contract error [code] (`gone`, `precondition_failed`, `invalid_name`…). */
    data class Refused(val code: String) : ActionResult

    /** Nothing was sent: the request makes no sense for the list as it is. */
    data class Invalid(val reason: InvalidAction) : ActionResult

    /** No connectivity, even after the retries. The action may or may not have reached the server. */
    data object Offline : ActionResult

    /** The server answered with an error (5xx, 429), or with something this client cannot read. */
    data object ServerUnavailable : ActionResult

    /** The credential is gone or was refused: the house must be connected again. */
    data object NoAccess : ActionResult
}
