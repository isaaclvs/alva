package net.ts.taild80ca0.atlas.twa.data.list

/**
 * Which ids the device takes to be the same item, and why. Two sources, kept apart because only one of
 * them is something the server said:
 *
 * - **durable aliases** (`sync_aliases`): the server answered a command and told us that a UUID it
 *   received is another item. Written by `ReceiptApplier`, never removed.
 * - **provisional merges**: the projection found, while replaying the queue over the confirmed items, that a
 *   name the device is creating is already pending on the list (another phone added it), so the create is
 *   not shown as a second row and the commands after it act on the item that exists. This is only what the
 *   server is *expected* to do with the create; it is recomputed on every projection, is never stored as an
 *   alias, never reaches a wire document, and disappears when the answer replaces it with a durable alias
 *   (or with a different outcome).
 *
 * Everything that has to follow "the row the user sees" back to the commands that touch it (what a new command
 * depends on, which rows still wait for the server) asks here, so the projection and the queue cannot disagree
 * about what is the same item.
 */
class ItemIdentities(durable: Map<String, String>) {
    private val durable: Map<String, String> = durable
    private val provisional = HashMap<String, String>()

    /** The id of the row an id cited by a command ends up on (an id with no known twin is its own row). */
    fun resolve(id: String): String {
        val key = id.lowercase()
        return provisional[id] ?: provisional[key] ?: durable[key] ?: id
    }

    /** The row an id was merged into, or null when it is its own row. */
    fun rowOf(id: String): String? = resolve(id).takeIf { it != id }

    fun merge(sameAs: Map<String, String>) {
        provisional += sameAs
    }

    /** [ids] and every id the device holds to be the same item: what to look for in `command_subjects`. */
    fun citedAs(ids: Collection<String>): List<String> {
        val rows = ids.toSet()
        return (ids + durable.filterValues { it in rows }.keys + provisional.filterValues { it in rows }.keys).distinct()
    }

    /** True when [id] is only the same item as its row by a projection guess, not because the server said so. */
    fun isProvisional(id: String): Boolean = provisional.containsKey(id) || provisional.containsKey(id.lowercase())
}
