package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.sync.CommandOutcome
import net.ts.taild80ca0.atlas.twa.sync.CommandReceipt
import net.ts.taild80ca0.atlas.twa.sync.CommandTransport
import net.ts.taild80ca0.atlas.twa.sync.ReceiptStatus
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures
import net.ts.taild80ca0.atlas.twa.sync.SnapshotOutcome
import net.ts.taild80ca0.atlas.twa.sync.SnapshotParse
import net.ts.taild80ca0.atlas.twa.sync.SnapshotParser
import net.ts.taild80ca0.atlas.twa.sync.SnapshotTransport
import net.ts.taild80ca0.atlas.twa.sync.TransientCause
import org.json.JSONArray
import org.json.JSONObject

/**
 * A server in memory for the online client's tests, for the JVM and the instrumented ones alike: it keeps a
 * list, a revision and the **ledger of command ids** the real server keeps (a UUID it already holds is answered
 * `duplicate`, never applied twice), and it fails on demand. Nothing here is the production code under test.
 */
class FakeHouse : SnapshotTransport, CommandTransport {
    class Row(
        val id: String, var name: String, var status: String, var lock: Int, var purchasedAt: String? = null,
        var category: String? = SnapshotFixtures.DAIRY,
    )

    /** What the next request does instead of working. One step per request, in order. */
    sealed interface Step {
        /** Never reaches the server. */
        data object NetworkDown : Step

        /** The server applies the command and the answer is lost on the way back. */
        data object LostAnswer : Step
        data object ServerError : Step
        data object Unauthorized : Step
        data class Refuse(val status: Int, val code: String) : Step
    }

    val rows = ArrayList<Row>()
    var categories: List<JSONObject> = CATEGORIES.mapIndexed { index, (id, name) -> SnapshotFixtures.category(id, name, if (name == "Outros") 99 else index + 1) }
    var revision = 840L

    /** The server's dictionary revision and what it has learned from `correct_item` (normalized name → category). */
    var dictionaryRevision = 12L
    val learned = HashMap<String, String>()
    private val ledger = HashMap<String, CommandReceipt>()
    private val removed = HashMap<String, Row>()

    /** Every document received, with the command id, in order (lost answers and retries included). */
    val sent = ArrayList<Pair<String, String>>()
    var fetches = 0

    /** How long a read of the list takes, to let requests pile up behind it. */
    var fetchDelayMillis = 0L
    val commandSteps = ArrayDeque<Step>()
    val snapshotSteps = ArrayDeque<Step>()

    fun seed(
        name: String, status: String = "pending", lock: Int = 0, id: String = java.util.UUID.randomUUID().toString(),
        category: String? = SnapshotFixtures.DAIRY,
    ): Row =
        Row(id, name, status, lock, if (status == "purchased") "2026-11-10T12:00:00.000000Z" else null, category).also { rows += it }

    /** The dictionary: "Leite" is known (and anything a correction taught); every other name falls to "Outros". */
    private fun categorize(name: String): String =
        learned[name.lowercase()] ?: if (name.equals("Leite", true)) SnapshotFixtures.DAIRY else SnapshotFixtures.OTHERS

    fun named(name: String) = rows.filter { it.name == name }

    // ---- snapshot ------------------------------------------------------------------------------------------

    override suspend fun fetch(credential: AccessCredential): SnapshotOutcome {
        fetches++
        if (fetchDelayMillis > 0) kotlinx.coroutines.delay(fetchDelayMillis)
        when (val step = snapshotSteps.removeFirstOrNull()) {
            Step.NetworkDown, Step.LostAnswer -> return SnapshotOutcome.Transient(TransientCause.NETWORK)
            Step.ServerError -> return SnapshotOutcome.Transient(TransientCause.SERVER_ERROR)
            Step.Unauthorized -> return SnapshotOutcome.Unauthorized
            is Step.Refuse -> return SnapshotOutcome.Refused(step.status, step.code)
            null -> Unit
        }
        val items = rows.map { row ->
            SnapshotFixtures.item(
                row.id, row.name, status = row.status, category = row.category, lockVersion = row.lock, purchasedAt = row.purchasedAt,
                normalizedName = row.name.lowercase(),
            )
        }
        val parsed = SnapshotParser.parse(SnapshotFixtures.bytes(SnapshotFixtures.snapshot(revision = revision, dictionaryRevision = dictionaryRevision, items = items, categories = categories)))
        return SnapshotOutcome.Received((parsed as SnapshotParse.Valid).snapshot)
    }

    // ---- commands ------------------------------------------------------------------------------------------

    override suspend fun send(credential: AccessCredential, commandId: String, document: String): CommandOutcome {
        sent += commandId to document
        val step = commandSteps.removeFirstOrNull()
        when (step) {
            Step.NetworkDown -> return CommandOutcome.Transient(TransientCause.NETWORK)
            Step.ServerError -> return CommandOutcome.Transient(TransientCause.SERVER_ERROR)
            Step.Unauthorized -> return CommandOutcome.Unauthorized
            is Step.Refuse -> return CommandOutcome.Refused(step.status, step.code)
            Step.LostAnswer, null -> Unit
        }
        val receipt = answer(commandId, JSONObject(document))
        return if (step == Step.LostAnswer) CommandOutcome.Transient(TransientCause.NETWORK) else CommandOutcome.Receipt(receipt)
    }

    private fun answer(id: String, command: JSONObject): CommandReceipt {
        ledger[id]?.let { return it.copy(status = ReceiptStatus.DUPLICATE, originalStatus = it.status) }
        val receipt = when (command.getString("type")) {
            "add_items" -> addItems(id, command.getJSONObject("payload").getJSONArray("items"))
            "set_item_state" -> setItemState(id, command)
            "correct_item" -> correctItem(id, command)
            "undo_item_state" -> undoItemState(id, command)
            "remove_item" -> removeItem(id, command)
            "undo_remove" -> undoRemove(id, command)
            else -> receipt(id, ReceiptStatus.REJECTED, error = "unsupported_command")
        }
        if (receipt.status.recordedByServer) ledger[id] = receipt
        return receipt
    }

    private fun addItems(id: String, items: JSONArray): CommandReceipt {
        val reported = JSONArray()
        var changed = false
        val created = ArrayList<Row>()
        for (index in 0 until items.length()) {
            val entry = items.getJSONObject(index)
            val name = entry.getString("name")
            val pending = rows.firstOrNull { it.name.equals(name, true) && it.status == "pending" }
            val purchased = rows.filter { it.name.equals(name, true) && it.status == "purchased" }.lastOrNull()
            val row: Row
            val outcome: String
            when {
                pending != null -> { row = pending; outcome = "duplicate" }
                purchased != null -> {
                    val revives = entry.optJSONObject("revives")
                    if (revives == null || revives.getString("public_id") != purchased.id || revives.getInt("lock_version") != purchased.lock) {
                        return receipt(id, ReceiptStatus.CONFLICT, error = "precondition_failed")
                    }
                    purchased.status = "pending"; purchased.purchasedAt = null; purchased.lock++
                    row = purchased; outcome = "revived"; changed = true
                }
                else -> {
                    row = Row(entry.getString("public_id"), name, "pending", 0, category = categorize(name))
                    created += row; outcome = "created"; changed = true
                }
            }
            reported.put(
                JSONObject().put("public_id", entry.getString("public_id")).put("canonical_public_id", row.id).put("name", row.name)
                    .put("status", row.status).put("lock_version", row.lock).put("outcome", outcome),
            )
        }
        rows += created
        if (changed) revision++
        return receipt(id, if (changed) ReceiptStatus.APPLIED else ReceiptStatus.NOOP, data = JSONObject().put("items", reported))
    }

    private fun setItemState(id: String, command: JSONObject): CommandReceipt {
        val payload = command.getJSONObject("payload")
        val row = rows.firstOrNull { it.id == payload.getString("public_id") } ?: return receipt(id, ReceiptStatus.REJECTED, error = "not_found")
        val desired = payload.getString("desired")
        if (row.status == desired) return receipt(id, ReceiptStatus.NOOP, data = JSONObject().put("item", itemJson(row)))
        if (command.getJSONObject("preconditions").getInt("lock_version") != row.lock) {
            return receipt(id, ReceiptStatus.CONFLICT, error = "precondition_failed")
        }
        row.status = desired; row.lock++
        row.purchasedAt = if (desired == "purchased") "2026-11-10T12:00:00.000000Z" else null
        revision++
        return receipt(id, ReceiptStatus.APPLIED, data = JSONObject().put("item", itemJson(row)))
    }

    /** `correct_item`: the category chosen by hand goes on the item and teaches the dictionary (`learned`). */
    private fun correctItem(id: String, command: JSONObject): CommandReceipt {
        val payload = command.getJSONObject("payload")
        val preconditions = command.getJSONObject("preconditions")
        val row = rows.firstOrNull { it.id == payload.getString("public_id") } ?: return receipt(id, ReceiptStatus.REJECTED, error = "not_found")
        if (preconditions.optInt("lock_version", row.lock) != row.lock) return receipt(id, ReceiptStatus.CONFLICT, error = "precondition_failed")
        val category = payload.optString("category_id").ifEmpty { null }
        var changed = false
        if (category != null && row.category != category) { row.category = category; changed = true }
        if (category != null && learned[row.name.lowercase()] != category && preconditions.getLong("dictionary_revision") == dictionaryRevision) {
            learned[row.name.lowercase()] = category; dictionaryRevision++; changed = true
        }
        if (changed) { row.lock++; revision++ }
        return receipt(id, if (changed) ReceiptStatus.APPLIED else ReceiptStatus.NOOP, data = JSONObject().put("item", itemJson(row)))
    }

    /** `undo_item_state`: restores the given state while the item is still at the version the mark left. */
    private fun undoItemState(id: String, command: JSONObject): CommandReceipt {
        val payload = command.getJSONObject("payload")
        val row = rows.firstOrNull { it.id == payload.getString("public_id") } ?: return receipt(id, ReceiptStatus.REJECTED, error = "not_found")
        val status = payload.getString("status")
        val purchasedAt = if (payload.isNull("purchased_at")) null else payload.getString("purchased_at")
        if (row.status == status && row.purchasedAt == purchasedAt) return receipt(id, ReceiptStatus.NOOP, data = JSONObject().put("item", itemJson(row)))
        if (command.getJSONObject("preconditions").getInt("lock_version") != row.lock) {
            return receipt(id, ReceiptStatus.CONFLICT, error = "precondition_failed")
        }
        row.status = status; row.purchasedAt = purchasedAt; row.lock++
        revision++
        return receipt(id, ReceiptStatus.APPLIED, data = JSONObject().put("item", itemJson(row)))
    }

    private fun removeItem(id: String, command: JSONObject): CommandReceipt {
        val row = rows.firstOrNull { it.id == command.getJSONObject("payload").getString("public_id") }
            ?: return receipt(id, ReceiptStatus.REJECTED, error = "gone")
        if (row.lock != command.getJSONObject("preconditions").getInt("lock_version"))
            return receipt(id, ReceiptStatus.CONFLICT, error = "precondition_failed")
        removed[id] = row
        rows.remove(row)
        revision++
        return receipt(id, ReceiptStatus.APPLIED, data = JSONObject().put("item", itemJson(row)))
    }

    private fun undoRemove(id: String, command: JSONObject): CommandReceipt {
        val original = removed[command.getString("depends_on")]
            ?: return receipt(id, ReceiptStatus.REJECTED, error = "invalid_removal")
        if (rows.any { it.id == original.id || (it.status == "pending" && it.name.equals(original.name, true)) })
            return receipt(id, ReceiptStatus.CONFLICT, error = "precondition_failed")
        rows += Row(original.id, original.name, original.status, original.lock + 1, original.purchasedAt, original.category)
        revision++
        return receipt(id, ReceiptStatus.APPLIED)
    }

    private fun itemJson(row: Row) =
        JSONObject().put("public_id", row.id).put("canonical_public_id", row.id).put("name", row.name).put("status", row.status).put("lock_version", row.lock)

    private fun receipt(id: String, status: ReceiptStatus, error: String? = null, data: JSONObject? = null) = CommandReceipt(
        commandId = id, status = status, originalStatus = null,
        revision = revision.takeIf { status.recordedByServer }, error = error, dataJson = data?.toString(),
        houseRevision = revision, snapshotRequired = status == ReceiptStatus.CONFLICT,
    )

    companion object {
        const val PANTRY = "c0000000-0000-4000-8000-000000000003"
        const val BAKERY = "c0000000-0000-4000-8000-000000000004"
        const val PRODUCE = "c0000000-0000-4000-8000-000000000005"
        const val MEAT = "c0000000-0000-4000-8000-000000000006"
        const val DRINKS = "c0000000-0000-4000-8000-000000000007"
        const val CLEANING = "c0000000-0000-4000-8000-000000000008"
        const val HYGIENE = "c0000000-0000-4000-8000-000000000009"

        private val CATEGORIES = listOf(
            PRODUCE to "Hortifruti", SnapshotFixtures.DAIRY to "Laticínios", PANTRY to "Mercearia", MEAT to "Açougue",
            BAKERY to "Padaria", DRINKS to "Bebidas", CLEANING to "Limpeza", HYGIENE to "Higiene", SnapshotFixtures.OTHERS to "Outros",
        )
    }
}
