package net.ts.taild80ca0.atlas.twa.data.list

import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Why a receipt was not accepted as the answer to the command it names. Nothing is written for any of them. */
enum class ReceiptRefusal {
    /** No such command in the queue (it may already be consumed and gone). */
    UNKNOWN_COMMAND,

    /** The command has no frozen document, so there is nothing to check the answer against. */
    NOT_FROZEN,

    /** The command's type has no receipt rules yet. */
    UNSUPPORTED_TYPE,

    /** The frozen document and the stored subjects do not line up, so an answer cannot be mapped to items. */
    INCONSISTENT_INTENT,

    /** `data` is not the object the contract describes (missing field, wrong type, bad UUID, bad date). */
    MALFORMED_DATA,

    /** `data` has the shape of another command's answer (`item` for an `add_items`, `items` for a `set_item_state`). */
    WRONG_RESULT_SHAPE,

    /** The entry does not describe the item the document cited: other UUID, name, state or version than the command could produce. */
    ITEM_MISMATCH,

    /** The answer contradicts an alias already recorded, or would chain one alias to another. */
    ALIAS_CONFLICT,

    /** The answer names an item the cited UUID is not known to be an alias of; the earlier receipt that reveals it has not been applied. */
    ALIAS_UNKNOWN,

    /** Revisions or attempt numbers that cannot happen for this command. */
    IMPOSSIBLE_REVISION,

    /** The receipt claims an attempt this command never made. */
    IMPOSSIBLE_ATTEMPT,
}

/** What the server did to one item named by a command. */
enum class ItemOutcome {
    /** `add_items`: a new item with the UUID the device sent. */
    CREATED,

    /** `add_items`: the name was already on the list; the cited UUID is an alias of the item that was. */
    DUPLICATE,

    /** `add_items`: a purchase went back to pending; the claim UUID is an alias of it. */
    REVIVED,

    /** `set_item_state`: the item is in the state asked for (changed, or already there). */
    UPDATED,
}

/**
 * The item as the server reported it **after** the command. This is all a receipt tells: no
 * category, no creation time, no normalized name; those arrive only with a snapshot.
 */
data class ReceiptItem(
    /** `ordinal` of the command subject it belongs to (0 for `set_item_state`). */
    val ordinal: Int,
    /** The UUID the document cited (lower case). */
    val citedPublicId: String,
    /** The server's record (lower case); different from [citedPublicId] when that one is an alias. */
    val canonicalPublicId: String,
    val name: String,
    /** [ItemStatusCodes]. */
    val status: Int,
    /** Epoch millis; the receipt carries microseconds, which are not kept here. */
    val purchasedAt: Long?,
    val lockVersion: Int,
    val outcome: ItemOutcome,
)

data class AliasClaim(val alias: String, val canonical: String)

data class ReceiptEffect(val items: List<ReceiptItem>, val aliases: List<AliasClaim>)

sealed interface ReceiptCheck {
    data class Valid(val effect: ReceiptEffect) : ReceiptCheck

    data class Invalid(val reason: ReceiptRefusal, val detail: String) : ReceiptCheck
}

/**
 * Checks the business `data` of a consumed receipt (`applied`, `noop` or the original result of a
 * `duplicate`) against the command that was actually sent: its frozen document and its stored
 * subjects. The transport already proved the envelope (id, status, revisions); this is the part it
 * leaves alone. Pure: no database, no clock.
 *
 * Rules, all of which must hold or nothing is applied:
 * - **Same command.** `add_items` data has `items`, `set_item_state` data has `item`, and each entry cites
 *   the UUID the document cited, in the document's order, one per entry.
 * - **Coherent identity.** `created` keeps the cited UUID; `revived` is the purchase the document said it revived
 *   (and only for a revive); `duplicate` may point anywhere. A `set_item_state` entry is the item the
 *   cited UUID resolves to through the aliases already known.
 * - **Coherent state.** `created`/`revived` are pending without `purchased_at`; `created` is version 0, `revived` a version
 *   above the one the user saw; a `set_item_state` entry is in the desired state, `applied` with a version
 *   above 0. `applied` needs at least one created/revived entry and `noop` none.
 * - **Names.** A created entry has the name that was typed (compared as the matching key, so the server's
 *   whitespace squish does not matter).
 *
 * Aliases to record are derived here; whether recording them contradicts what the device already
 * has is checked by the caller against the database ([knownAlias]).
 */
object ReceiptValidator {
    private val UUID_FORMAT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * @param applied true when the first run changed something (`applied`), false for `noop`.
     * @param knownAlias canonical id the device already has for an alias id (both lower case), or null.
     */
    fun check(
        type: String,
        document: String,
        subjects: List<WireSubject>,
        applied: Boolean,
        dataJson: String,
        knownAlias: (String) -> String?,
    ): ReceiptCheck {
        val data = try {
            val tokener = JSONTokener(dataJson)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != 0.toChar()) return invalid(ReceiptRefusal.MALFORMED_DATA, "data is not an object")
            value
        } catch (_: JSONException) {
            return invalid(ReceiptRefusal.MALFORMED_DATA, "data is not JSON")
        }
        val wire = try {
            JSONObject(document)
        } catch (_: JSONException) {
            return invalid(ReceiptRefusal.INCONSISTENT_INTENT, "the frozen document is not JSON")
        }
        return when (type) {
            CommandTypes.ADD_ITEMS -> addItems(document, wire, subjects, applied, data, knownAlias)
            CommandTypes.SET_ITEM_STATE -> setItemState(wire, subjects, applied, data, knownAlias)
            else -> invalid(ReceiptRefusal.UNSUPPORTED_TYPE, "no receipt rules for $type")
        }
    }

    private fun addItems(
        document: String,
        wire: JSONObject,
        subjects: List<WireSubject>,
        applied: Boolean,
        data: JSONObject,
        knownAlias: (String) -> String?,
    ): ReceiptCheck {
        if (!data.has("items")) {
            return if (data.has("item")) invalid(ReceiptRefusal.WRONG_RESULT_SHAPE, "an item result for an add_items") else invalid(ReceiptRefusal.MALFORMED_DATA, "no items")
        }
        val links = WireItemLinks.of(document, subjects)
            ?: return invalid(ReceiptRefusal.INCONSISTENT_INTENT, "the document and the subjects do not line up")
        val sent = wire.optJSONObject("payload")?.optJSONArray("items")
            ?: return invalid(ReceiptRefusal.INCONSISTENT_INTENT, "no items in the document")
        val entries = data.opt("items") as? JSONArray ?: return invalid(ReceiptRefusal.MALFORMED_DATA, "items is not an array")
        if (entries.length() != links.size) return invalid(ReceiptRefusal.ITEM_MISMATCH, "${entries.length()} results for ${links.size} items")

        val items = ArrayList<ReceiptItem>(links.size)
        val aliases = ArrayList<AliasClaim>()
        for (link in links) {
            val entry = entries.opt(link.ordinal) as? JSONObject ?: return invalid(ReceiptRefusal.MALFORMED_DATA, "items[${link.ordinal}] is not an object")
            val parsed = when (val read = readEntry(entry, link.ordinal)) {
                is Read.Failed -> return read.refusal
                is Read.Item -> read
            }
            val cited = parsed.cited
            val canonical = parsed.canonical
            if (cited != link.wirePublicId.lowercase()) return invalid(ReceiptRefusal.ITEM_MISMATCH, "items[${link.ordinal}] answers for another UUID")

            val typed = (sent.optJSONObject(link.ordinal)?.opt("name") as? String).orEmpty()
            val revives = sent.optJSONObject(link.ordinal)?.optJSONObject("revives")
            val fresh = parsed.status == ItemStatusCodes.PENDING && parsed.purchasedAt == null
            when (parsed.outcome) {
                ItemOutcome.CREATED -> {
                    if (link.role != SubjectRole.CREATES) return mismatch(link.ordinal, "created for a revive")
                    if (canonical != cited) return mismatch(link.ordinal, "created under another UUID")
                    if (!fresh || parsed.lockVersion != 0) return mismatch(link.ordinal, "a created item is pending at version 0")
                    if (TextNormalizer.plain(parsed.name) != TextNormalizer.plain(typed)) return mismatch(link.ordinal, "created under another name")
                }
                ItemOutcome.REVIVED -> {
                    if (link.role != SubjectRole.TARGETS) return mismatch(link.ordinal, "revived without a revive")
                    if (canonical != link.revivedPublicId?.lowercase()) return mismatch(link.ordinal, "revived another purchase")
                    if (!fresh) return mismatch(link.ordinal, "a revived item is pending")
                    val seen = (revives?.opt("lock_version") as? Number)?.toInt()
                    if (parsed.lockVersion < 1 || (seen != null && parsed.lockVersion <= seen)) return mismatch(link.ordinal, "a revive moves the version")
                }
                ItemOutcome.DUPLICATE -> Unit
                ItemOutcome.UPDATED -> return mismatch(link.ordinal, "updated is not an add_items outcome")
            }
            items += ReceiptItem(link.ordinal, cited, canonical, parsed.name, parsed.status, parsed.purchasedAt, parsed.lockVersion, parsed.outcome)
            if (canonical != cited) aliases += AliasClaim(cited, canonical)
        }

        if (items.map { it.citedPublicId }.toSet().size != items.size) return invalid(ReceiptRefusal.ITEM_MISMATCH, "the same UUID answered twice")
        val changed = items.any { it.outcome == ItemOutcome.CREATED || it.outcome == ItemOutcome.REVIVED }
        if (applied != changed) return invalid(ReceiptRefusal.ITEM_MISMATCH, "the status and the outcomes disagree")
        return aliasProblem(aliases, items, knownAlias) ?: ReceiptCheck.Valid(ReceiptEffect(items, aliases))
    }

    private fun setItemState(
        wire: JSONObject,
        subjects: List<WireSubject>,
        applied: Boolean,
        data: JSONObject,
        knownAlias: (String) -> String?,
    ): ReceiptCheck {
        if (!data.has("item")) {
            return if (data.has("items")) invalid(ReceiptRefusal.WRONG_RESULT_SHAPE, "an items result for a set_item_state") else invalid(ReceiptRefusal.MALFORMED_DATA, "no item")
        }
        val payload = wire.optJSONObject("payload") ?: return invalid(ReceiptRefusal.INCONSISTENT_INTENT, "no payload")
        val cited = (payload.opt("public_id") as? String)?.lowercase()
        val desired = when (payload.opt("desired")) {
            ProjectionReducer.DESIRED_PURCHASED -> ItemStatusCodes.PURCHASED
            ProjectionReducer.DESIRED_PENDING -> ItemStatusCodes.PENDING
            else -> null
        }
        if (cited == null || desired == null) return invalid(ReceiptRefusal.INCONSISTENT_INTENT, "the document has no item or state")
        val subject = subjects.singleOrNull()
        if (subject == null || subject.role != SubjectRole.TARGETS || subject.publicId.lowercase() != cited) {
            return invalid(ReceiptRefusal.INCONSISTENT_INTENT, "the subject is not the cited item")
        }
        val entry = data.opt("item") as? JSONObject ?: return invalid(ReceiptRefusal.MALFORMED_DATA, "item is not an object")
        val parsed = when (val read = readEntry(entry, 0, hasOutcome = false)) {
            is Read.Failed -> return read.refusal
            is Read.Item -> read
        }
        if (parsed.cited != cited) return mismatch(0, "answers for another UUID")
        if (parsed.status != desired) return mismatch(0, "not in the state that was asked for")
        if (applied && parsed.lockVersion < 1) return mismatch(0, "a change moves the version")

        val known = knownAlias(cited)
        if (parsed.canonical != (known ?: cited)) {
            return if (known != null) invalid(ReceiptRefusal.ALIAS_CONFLICT, "the cited UUID is an alias of another item") else invalid(ReceiptRefusal.ALIAS_UNKNOWN, "the cited UUID is an alias this device does not know yet")
        }
        val items = listOf(ReceiptItem(0, cited, parsed.canonical, parsed.name, parsed.status, parsed.purchasedAt, parsed.lockVersion, ItemOutcome.UPDATED))
        return aliasProblem(emptyList(), items, knownAlias) ?: ReceiptCheck.Valid(ReceiptEffect(items, emptyList()))
    }

    /** New aliases against what is known and against each other: one canonical per alias, and no chains. */
    private fun aliasProblem(aliases: List<AliasClaim>, items: List<ReceiptItem>, knownAlias: (String) -> String?): ReceiptCheck.Invalid? {
        for (claim in aliases) {
            knownAlias(claim.alias)?.let { if (it != claim.canonical) return invalid(ReceiptRefusal.ALIAS_CONFLICT, "an alias already points at another item") }
        }
        val sources = aliases.mapTo(HashSet()) { it.alias }
        for (item in items) {
            if (knownAlias(item.canonicalPublicId) != null || item.canonicalPublicId in sources) {
                return invalid(ReceiptRefusal.ALIAS_CONFLICT, "the canonical item is itself an alias")
            }
        }
        return null
    }

    private sealed interface Read {
        data class Failed(val refusal: ReceiptCheck.Invalid) : Read

        data class Item(
            val cited: String,
            val canonical: String,
            val name: String,
            val status: Int,
            val purchasedAt: Long?,
            val lockVersion: Int,
            val outcome: ItemOutcome,
        ) : Read
    }

    private fun readEntry(entry: JSONObject, ordinal: Int, hasOutcome: Boolean = true): Read {
        fun fail(detail: String) = Read.Failed(invalid(ReceiptRefusal.MALFORMED_DATA, "entry $ordinal: $detail"))
        val cited = (entry.opt("public_id") as? String)?.takeIf { UUID_FORMAT.matches(it) } ?: return fail("public_id")
        val canonical = (entry.opt("canonical_public_id") as? String)?.takeIf { UUID_FORMAT.matches(it) } ?: return fail("canonical_public_id")
        val name = (entry.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: return fail("name")
        val status = when (entry.opt("status")) {
            "pending" -> ItemStatusCodes.PENDING
            "purchased" -> ItemStatusCodes.PURCHASED
            else -> return fail("status")
        }
        val purchasedAt = when (val raw = entry.opt("purchased_at")) {
            null, JSONObject.NULL -> null
            is String -> IsoInstant.parseMillis(raw) ?: return fail("purchased_at")
            else -> return fail("purchased_at")
        }
        if ((status == ItemStatusCodes.PURCHASED) != (purchasedAt != null)) return fail("purchased_at does not match status")
        val lockVersion = (entry.opt("lock_version") as? Int)?.takeIf { it >= 0 } ?: return fail("lock_version")
        val outcome = if (hasOutcome) {
            when (entry.opt("outcome")) {
                "created" -> ItemOutcome.CREATED
                "duplicate" -> ItemOutcome.DUPLICATE
                "revived" -> ItemOutcome.REVIVED
                else -> return fail("outcome")
            }
        } else {
            ItemOutcome.UPDATED
        }
        return Read.Item(cited.lowercase(), canonical.lowercase(), name, status, purchasedAt, lockVersion, outcome)
    }

    private fun mismatch(ordinal: Int, detail: String) = invalid(ReceiptRefusal.ITEM_MISMATCH, "entry $ordinal: $detail")

    private fun invalid(reason: ReceiptRefusal, detail: String) = ReceiptCheck.Invalid(reason, detail)
}

/** The UTC instants the server writes (`2026-11-10T12:00:00.123456Z`), without `java.time` (minSdk 24). */
object IsoInstant {
    private val FORMAT = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?Z$")

    /** Epoch millis (sub-millisecond digits are dropped), or null if [text] is not such an instant. */
    fun parseMillis(text: String): Long? {
        val m = FORMAT.matchEntire(text) ?: return null
        val (year, month, day, hour, minute, second) = (1..6).map { m.groupValues[it].toInt() }
        if (month !in 1..12 || day !in 1..daysIn(year, month) || hour > 23 || minute > 59 || second > 59) return null
        val fraction = m.groupValues[7].padEnd(3, '0').take(3).toLong()
        return (daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second) * 1_000L + fraction
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    private fun daysIn(year: Int, month: Int) = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
