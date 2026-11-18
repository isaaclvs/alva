package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer

/** What "Adicionar tudo de novo" did: the per-name outcomes of every batch, in order. */
sealed interface HistoryReadd {
    /** Every name reached the server. Some may already have been on the list (duplicates). */
    data class Done(val items: List<AddedItem>) : HistoryReadd

    /**
     * A batch was refused, offline or unanswered. [items] are the outcomes of the batches that did
     * land before it; the list is reloaded by [OnlineRepository.execute] all the same.
     */
    data class Failed(val result: ActionResult, val items: List<AddedItem>) : HistoryReadd
}

/**
 * "Adicionar tudo de novo": puts a finished trip's names back on the list with the existing
 * `add_items` command, so duplicates, purchases brought back and the single broadcast are the same
 * as typing the names. No new command exists for history: a trip is just a list of names.
 *
 * A trip can hold more names than one command takes ([AddItems.MAX_NAMES]), so it goes in batches.
 * The first batch that does not succeed stops the rest: the person sees what happened, and the
 * names already on the list are there for real (each batch is its own command).
 */
suspend fun OnlineRepository.readdHistory(trip: HistoryTrip): HistoryReadd {
    val names = trip.names.distinctBy { TextNormalizer.key(it) }.filter { it.isNotBlank() }
    if (names.isEmpty()) return HistoryReadd.Failed(ActionResult.Invalid(InvalidAction.NO_NAMES), emptyList())

    val added = ArrayList<AddedItem>()
    for (batch in names.chunked(AddItems.MAX_NAMES)) {
        when (val result = execute(AddItems(batch))) {
            is ActionResult.Success -> added += result.receipt.addedItems()
            else -> return HistoryReadd.Failed(result, added)
        }
    }
    return HistoryReadd.Done(added)
}
