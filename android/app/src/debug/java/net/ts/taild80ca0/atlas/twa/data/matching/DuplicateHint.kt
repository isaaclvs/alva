package net.ts.taild80ca0.atlas.twa.data.matching

import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem

/**
 * The hint shown while typing: which of the typed names are already pending on
 * the list. The local twin of `duplicate_hint_controller.js`.
 *
 * It is only a hint, compared with [TextNormalizer.plain] (no accents, case or
 * extra spaces, but no plural stemming); adding still goes through the full rule
 * in `AddPlanner`, and the server stays the authority.
 */
object DuplicateHint {
    /** Names of the pending items the text repeats, in the order typed, spelled as on the list. */
    fun alreadyPending(typed: String?, items: List<ListItem>): List<String> {
        val pending = HashMap<String, String>()
        items.filter { it.status == ItemStatus.PENDING }.forEach { pending.putIfAbsent(TextNormalizer.plain(it.name), it.name) }

        return typed.orEmpty().split(',', ';', '\n')
            .map { TextNormalizer.plain(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .mapNotNull { pending[it] }
    }
}
