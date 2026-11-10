package net.ts.taild80ca0.atlas.twa.data.list

/** Lifecycle of a queued command. Confirmed commands are deleted, so they have no status. */
enum class CommandStatus {
    /** Waiting to be sent, in device order. */
    PENDING,

    /** Handed to the sender; kept until the server answers. */
    IN_FLIGHT,

    /** A command it depends on is in conflict or was rejected; it keeps its intent. */
    BLOCKED,

    /** A precondition failed on the server; the user must review. */
    CONFLICT,

    /** Definitive server error; the user must review. */
    REJECTED,
    ;

    /** Statuses in which a later command that depends on this one cannot proceed. */
    val stopsDependents: Boolean get() = this == BLOCKED || this == CONFLICT || this == REJECTED
}

/** Contract v1 command types implemented locally so far. */
object CommandTypes {
    const val ADD_ITEMS = "add_items"
    const val SET_ITEM_STATE = "set_item_state"
}
