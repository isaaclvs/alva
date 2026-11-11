package net.ts.taild80ca0.atlas.twa.sync

import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import org.json.JSONArray
import org.json.JSONObject

/**
 * A scripted stand-in for the household's server, small on purpose: one house, `add_items` and `set_item_state`
 * with the ledger rules the sync cycle depends on (a repeated id is a `duplicate` with the original result, another
 * content under the same id is `idempotency_conflict`, an unknown `depends_on` is `blocked`) and a snapshot of what it
 * holds. It exists so the sync cycle's ordering and failure handling can be provoked exactly, on real Room. It is not
 * the contract: real Rails answers are checked by `script/android_rails_sync_check`.
 */
class FakeHouseServer(private val house: String = SnapshotFixtures.HOUSE) : CommandTransport, SnapshotTransport {
    class Item(val publicId: String, var name: String, var purchased: Boolean = false, var lock: Int = 0, val createdAt: Long)

    private class Entry(val digest: String, val status: String, val revision: Long, val data: JSONObject)

    val items = LinkedHashMap<String, Item>()
    private val aliases = HashMap<String, String>()
    private val ledger = HashMap<String, Entry>()
    var revision = 0L

    /** Every request that reached the server: command id and its exact document. */
    val received = mutableListOf<Pair<String, String>>()
    var snapshotRequests = 0

    // ---- faults, consumed one request at a time ---------------------------------------------
    var unreachable = false
    var failBeforeApplying = 0
    var applyThenLoseTheAnswer = 0
    var snapshotOutcome: (() -> SnapshotOutcome?)? = null
    private val scripted = HashMap<String, Pair<ReceiptStatus, String>>()
    private val requestRefusals = HashMap<String, CommandOutcome.Refused>()
    private var clock = 1_700_000_000_000L

    /** The next request for [commandId] is refused with [status] and [error] without any effect. */
    fun refuse(commandId: String, status: ReceiptStatus, error: String) {
        scripted[commandId] = status to error
    }

    /** The next request for [commandId] is refused as a whole ([outcome]), before any command runs. */
    fun refuseRequest(commandId: String, outcome: CommandOutcome.Refused) {
        requestRefusals[commandId] = outcome
    }

    /** Another device adds [name] directly (not through this device's queue). */
    fun addElsewhere(publicId: String, name: String) {
        items[publicId] = Item(publicId, name, createdAt = tick())
        revision++
    }

    fun markElsewhere(publicId: String, purchased: Boolean) {
        items.getValue(publicId).apply { this.purchased = purchased; lock++ }
        revision++
    }

    fun removeElsewhere(publicId: String) {
        items.remove(publicId)
        revision++
    }

    private fun tick() = clock++

    override suspend fun send(credential: AccessCredential, commandId: String, document: String): CommandOutcome {
        received += commandId to document
        if (unreachable) return CommandOutcome.Transient(TransientCause.NETWORK)
        requestRefusals.remove(commandId)?.let { return it }
        if (failBeforeApplying > 0) {
            failBeforeApplying--
            return CommandOutcome.Transient(TransientCause.TIMEOUT)
        }
        val receipt = process(commandId, document)
        if (applyThenLoseTheAnswer > 0) {
            applyThenLoseTheAnswer--
            return CommandOutcome.Transient(TransientCause.NETWORK)
        }
        return CommandOutcome.Receipt(receipt)
    }

    private fun refusal(id: String, status: ReceiptStatus, error: String) =
        CommandReceipt(id, status, null, null, error, "{}", revision, false)

    private fun process(id: String, document: String): CommandReceipt {
        scripted.remove(id)?.let { return refusal(id, it.first, it.second) }
        val digest = JSONObject(document).also { it.remove("id") }.toString()
        ledger[id]?.let { seen ->
            if (seen.digest != digest) return refusal(id, ReceiptStatus.REJECTED, "idempotency_conflict")
            return CommandReceipt(id, ReceiptStatus.DUPLICATE, ReceiptStatus.fromWire(seen.status), seen.revision, null, seen.data.toString(), revision, false)
        }
        val command = JSONObject(document)
        val dependency = command.opt("depends_on") as? String
        if (dependency != null && dependency !in ledger) return refusal(id, ReceiptStatus.BLOCKED, "dependency_not_applied")

        val payload = command.getJSONObject("payload")
        var changed = false
        val data = when (command.getString("type")) {
            "add_items" -> {
                val results = JSONArray()
                val entries = payload.getJSONArray("items")
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val claimed = entry.getString("public_id").lowercase()
                    val name = entry.getString("name")
                    val twin = items.values.firstOrNull { it.name.equals(name, ignoreCase = true) && !it.purchased }
                    val outcome: String
                    val canonical: Item
                    if (twin != null) {
                        outcome = "duplicate"
                        canonical = twin
                        if (claimed != twin.publicId) aliases[claimed] = twin.publicId
                    } else {
                        outcome = "created"
                        canonical = Item(claimed, name, createdAt = tick()).also { items[claimed] = it }
                        changed = true
                    }
                    results.put(wire(canonical, claimed).put("outcome", outcome))
                }
                JSONObject().put("items", results)
            }
            "set_item_state" -> {
                val cited = payload.getString("public_id").lowercase()
                val item = items[aliases[cited] ?: cited] ?: return refusal(id, ReceiptStatus.REJECTED, "gone")
                val purchased = payload.getString("desired") == "purchased"
                if (item.purchased != purchased) {
                    item.purchased = purchased
                    item.lock++
                    changed = true
                }
                JSONObject().put("item", wire(item, cited))
            }
            else -> return refusal(id, ReceiptStatus.REJECTED, "unsupported_command")
        }
        if (changed) revision++
        val status = if (changed) ReceiptStatus.APPLIED else ReceiptStatus.NOOP
        ledger[id] = Entry(digest, status.wire, revision, data)
        return CommandReceipt(id, status, null, revision, null, data.toString(), revision, false)
    }

    private fun wire(item: Item, cited: String): JSONObject = JSONObject()
        .put("public_id", cited)
        .put("canonical_public_id", item.publicId)
        .put("name", item.name)
        .put("status", if (item.purchased) "purchased" else "pending")
        .put("purchased_at", if (item.purchased) "2026-11-10T13:00:00.000000Z" else JSONObject.NULL)
        .put("lock_version", item.lock)

    override suspend fun fetch(credential: AccessCredential): SnapshotOutcome {
        snapshotRequests++
        snapshotOutcome?.invoke()?.let { return it }
        if (unreachable) return SnapshotOutcome.Transient(TransientCause.NETWORK)
        val json = SnapshotFixtures.snapshot(
            revision = revision, dictionaryRevision = 1, house = house,
            items = items.values.map {
                SnapshotFixtures.item(
                    it.publicId, it.name, status = if (it.purchased) "purchased" else "pending",
                    purchasedAt = if (it.purchased) "2026-11-10T13:00:00.000000Z" else null, lockVersion = it.lock,
                    createdAt = net.ts.taild80ca0.atlas.twa.sync.ServerTime.format(it.createdAt * 1000),
                )
            },
        )
        return SnapshotOutcome.Received((SnapshotParser.parse(SnapshotFixtures.bytes(json)) as SnapshotParse.Valid).snapshot)
    }
}
