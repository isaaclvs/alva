package net.ts.taild80ca0.atlas.twa.sync

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import net.ts.taild80ca0.atlas.twa.data.list.WireBuild
import net.ts.taild80ca0.atlas.twa.data.list.WireEnvelopeBuilder
import net.ts.taild80ca0.atlas.twa.data.list.WireSource
import net.ts.taild80ca0.atlas.twa.data.list.WireSubject
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The Kotlin transport against a REAL Rails server (`POST /api/v1/commands`, the real ledger and
 * executors, a throwaway SQLite database). It only runs under `script/android_rails_transport_check`,
 * which starts Rails with `RAILS_ENV=test`, fixtures and no personal data, and names it in the file
 * `ALVA_RAILS_HARNESS`; anywhere else it is skipped (and the script refuses a skipped run).
 *
 * The documents are built by [WireEnvelopeBuilder] from stored-intent shapes, the same code the
 * Android queue uses. The lost-response case runs through [ResponseDroppingProxy]: Rails commits and
 * answers, the client never hears it, and the repeat must come back `duplicate` with no second effect.
 *
 * What this is not: it runs on the JVM, not on Android. It proves the wire contract and the
 * client's HTTP/receipt code against Rails; Room, the emulator's network stack and two devices
 * converging are other tests.
 */
class RailsCommandTransportTest {
    private val harness: JSONObject? = System.getenv("ALVA_RAILS_HARNESS")?.let { JSONObject(File(it).readText()) }
    private val closeables = mutableListOf<AutoCloseable>()

    private lateinit var baseUrl: String
    private lateinit var sync: AccessCredential
    private lateinit var transport: HttpCommandTransport

    @Before
    fun connect() {
        assumeTrue("needs script/android_rails_transport_check (ALVA_RAILS_HARNESS)", harness != null)
        baseUrl = harness!!.getString("url")
        // Never anything but the harness's own loopback server.
        assertTrue(baseUrl, baseUrl.startsWith("http://127.0.0.1:"))
        sync = AccessCredential(1, harness.getString("sync_token"))
        transport = HttpCommandTransport(ServerEndpoint.parse(baseUrl), 5_000, 20_000)
    }

    @After
    fun stop() {
        closeables.forEach { it.close() }
    }

    // ---- documents, built the way the queue builds them ----------------------------------

    private fun newId() = UUID.randomUUID().toString()

    /** A name no other test (and no fixture) can collide with: letters only, random. */
    private fun newName() = "Teste " + UUID.randomUUID().toString().filter { it.isLetter() }.take(10)

    private fun build(source: WireSource): String =
        (WireEnvelopeBuilder.build(source, IdGenerator { newId() }) as WireBuild.Built).document

    private fun addDocument(commandId: String, itemId: String, name: String): String = build(
        WireSource(
            commandId, "add_items", null, 0,
            JSONObject().put("names", org.json.JSONArray().put(name)).toString(), "{}",
            listOf(WireSubject(itemId, SubjectRole.CREATES, 0)), null,
        ),
    )

    private fun setStateDocument(
        commandId: String,
        itemId: String,
        desired: String,
        dependsOn: String?,
        lockVersion: Int? = null,
    ): String = build(
        WireSource(
            commandId, "set_item_state", dependsOn, 0,
            """{"public_id":"$itemId","desired":"$desired"}""",
            lockVersion?.let { """{"lock_version":$it}""" } ?: "{}",
            listOf(WireSubject(itemId, SubjectRole.TARGETS, 0)),
            dependsOn?.let { setOf(itemId) },
        ),
    )

    private fun send(
        commandId: String,
        document: String,
        through: HttpCommandTransport = transport,
        credential: AccessCredential = sync,
    ): CommandOutcome = runBlocking { through.send(credential, commandId, document) }

    private fun receipt(outcome: CommandOutcome): CommandReceipt {
        assertTrue("expected a receipt, got $outcome", outcome is CommandOutcome.Receipt)
        return (outcome as CommandOutcome.Receipt).receipt
    }

    /** What Rails holds now, read the way a snapshot call reads it (test-side, raw HTTP). */
    private fun snapshot(): JSONObject {
        val connection = URL("$baseUrl/api/v1/snapshot").openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer ${sync.token}")
        connection.setRequestProperty("Connection", "close")
        return connection.inputStream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }.also { connection.disconnect() }
    }

    private fun itemsWith(snapshot: JSONObject, publicId: String): List<JSONObject> {
        val items = snapshot.getJSONArray("list_items")
        return (0 until items.length()).map { items.getJSONObject(it) }.filter { it.getString("public_id") == publicId }
    }

    private fun lossy(): Pair<ResponseDroppingProxy, HttpCommandTransport> {
        val port = baseUrl.substringAfterLast(':').toInt()
        val proxy = ResponseDroppingProxy(port).also { closeables += it }
        return proxy to HttpCommandTransport(ServerEndpoint.parse("http://127.0.0.1:${proxy.port}"), 5_000, 20_000)
    }

    // ---- the tests ------------------------------------------------------------------------

    @Test
    fun anAppliedCommandSentAgainWithTheSameBytesIsADuplicateWithTheOriginalResult() {
        val itemId = newId()
        val commandId = newId()
        val document = addDocument(commandId, itemId, newName())

        val first = receipt(send(commandId, document))
        val again = receipt(send(commandId, document))

        assertEquals(ReceiptStatus.APPLIED, first.status)
        assertEquals(ReceiptStatus.DUPLICATE, again.status)
        assertEquals(ReceiptStatus.APPLIED, again.originalStatus)
        assertEquals(first.revision, again.revision)
        assertEquals(JSONObject(first.dataJson!!).toString(), JSONObject(again.dataJson!!).toString())
        val entry = JSONObject(first.dataJson!!).getJSONArray("items").getJSONObject(0)
        assertEquals("created", entry.getString("outcome"))
        assertEquals(itemId, entry.getString("public_id"))
        assertEquals(itemId, entry.getString("canonical_public_id"))
        assertEquals(1, itemsWith(snapshot(), itemId).size)
    }

    @Test
    fun aResponseLostAfterCommitIsRepeatedAsADuplicateWithoutASecondEffect() {
        val (proxy, lossyTransport) = lossy()
        val itemId = newId()
        val commandId = newId()
        val name = newName()
        val document = addDocument(commandId, itemId, name)
        val revisionBefore = snapshot().getLong("revision")

        proxy.dropNextResponses(1)
        val lost = send(commandId, document, through = lossyTransport)

        // The client heard nothing: a transient loss, not an answer.
        assertEquals(CommandOutcome.Transient(TransientCause.NETWORK), lost)
        assertEquals(1, proxy.answeredByServer.get())
        assertEquals(1, proxy.dropped.get())
        // ...but the server did commit: the item exists, once, and the revision moved by one.
        val afterLoss = snapshot()
        assertEquals(revisionBefore + 1, afterLoss.getLong("revision"))
        val stored = itemsWith(afterLoss, itemId).single()
        assertEquals(name, stored.getString("name"))

        // The repeat sends the very same bytes and learns what already happened.
        val repeated = receipt(send(commandId, document, through = lossyTransport))

        assertEquals(ReceiptStatus.DUPLICATE, repeated.status)
        assertEquals(ReceiptStatus.APPLIED, repeated.originalStatus)
        assertEquals(afterLoss.getLong("revision"), repeated.revision)
        assertEquals(itemId, JSONObject(repeated.dataJson!!).getJSONArray("items").getJSONObject(0).getString("canonical_public_id"))
        assertEquals(2, proxy.requestBodies.size)
        assertArrayEquals(proxy.requestBodies[0], proxy.requestBodies[1])
        val afterRepeat = snapshot()
        assertEquals("no second effect", afterLoss.getLong("revision"), afterRepeat.getLong("revision"))
        assertEquals(1, itemsWith(afterRepeat, itemId).size)
    }

    @Test
    fun aDependentCommandSurvivesALostAnswerOfItsDependency() {
        val (proxy, lossyTransport) = lossy()
        val itemId = newId()
        val addId = newId()
        val markId = newId()
        val addDoc = addDocument(addId, itemId, newName())
        val markDoc = setStateDocument(markId, itemId, "purchased", dependsOn = addId)

        proxy.dropNextResponses(1)
        assertEquals(CommandOutcome.Transient(TransientCause.NETWORK), send(addId, addDoc, through = lossyTransport))
        // The mark goes before the add is retried: the server already has the add, so the dependency holds
        // and the mark inherits the version the add left, with no version on the wire.
        val marked = receipt(send(markId, markDoc))
        assertEquals(ReceiptStatus.APPLIED, marked.status)

        assertEquals(ReceiptStatus.DUPLICATE, receipt(send(addId, addDoc)).status)
        assertEquals(ReceiptStatus.DUPLICATE, receipt(send(markId, markDoc)).status)
        val item = itemsWith(snapshot(), itemId).single()
        assertEquals("purchased", item.getString("status"))
        assertEquals(1, item.getInt("lock_version"))
    }

    @Test
    fun aDependentSentBeforeItsDependencyIsBlockedAndAppliesOnceTheDependencyIsRecorded() {
        val itemId = newId()
        val addId = newId()
        val markId = newId()
        val addDoc = addDocument(addId, itemId, newName())
        val markDoc = setStateDocument(markId, itemId, "purchased", dependsOn = addId)

        val early = receipt(send(markId, markDoc))
        assertEquals(ReceiptStatus.BLOCKED, early.status)
        assertEquals("dependency_not_applied", early.error)
        assertEquals(0, itemsWith(snapshot(), itemId).size)

        assertEquals(ReceiptStatus.APPLIED, receipt(send(addId, addDoc)).status)
        assertEquals(ReceiptStatus.APPLIED, receipt(send(markId, markDoc)).status)
        assertEquals("purchased", itemsWith(snapshot(), itemId).single().getString("status"))
    }

    @Test
    fun businessRefusalsComeBackAsReceiptsAndChangeNothing() {
        val itemId = newId()
        val addId = newId()
        val addDoc = addDocument(addId, itemId, newName())
        assertEquals(ReceiptStatus.APPLIED, receipt(send(addId, addDoc)).status)

        // Already in the wanted state: noop (consumed by the server).
        val noopId = newId()
        val noop = receipt(send(noopId, setStateDocument(noopId, itemId, "pending", dependsOn = addId)))
        assertEquals(ReceiptStatus.NOOP, noop.status)

        // The version the user saw is stale: conflict, with the signal to download a snapshot.
        val staleId = newId()
        val conflict = receipt(send(staleId, setStateDocument(staleId, itemId, "purchased", dependsOn = null, lockVersion = 9)))
        assertEquals(ReceiptStatus.CONFLICT, conflict.status)
        assertEquals("precondition_failed", conflict.error)
        assertTrue(conflict.snapshotRequired)

        // An item the household never had: rejected not_found.
        val strayId = newId()
        val rejected = receipt(send(strayId, setStateDocument(strayId, newId(), "purchased", dependsOn = null, lockVersion = 0)))
        assertEquals(ReceiptStatus.REJECTED, rejected.status)
        assertEquals("not_found", rejected.error)

        // The same command id with other content is an idempotency conflict, never applied.
        val otherContent = addDocument(addId, newId(), newName())
        val reuse = receipt(send(addId, otherContent))
        assertEquals(ReceiptStatus.REJECTED, reuse.status)
        assertEquals("idempotency_conflict", reuse.error)

        assertEquals("pending", itemsWith(snapshot(), itemId).single().getString("status"))
    }

    @Test
    fun aCommandTypeRailsDoesNotRunYetIsRejectedNotConfirmed() {
        val commandId = newId()
        val document = """{"id":"$commandId","type":"remove_item","depends_on":null,"preconditions":{},"payload":{"public_id":"${newId()}"}}"""

        val result = receipt(send(commandId, document))

        assertEquals(ReceiptStatus.REJECTED, result.status)
        assertEquals("unsupported_command", result.error)
    }

    @Test
    fun aRefusedCredentialIsUnauthorizedAndHasNoEffect() {
        val before = snapshot().getLong("revision")
        val commandId = newId()
        val document = addDocument(commandId, newId(), newName())

        val revoked = AccessCredential(2, harness!!.getString("revoked_token"))
        val widget = AccessCredential(3, harness.getString("add_only_token"))
        val unknown = AccessCredential(4, "token-que-o-servidor-nunca-emitiu")

        for (credential in listOf(revoked, widget, unknown)) {
            assertEquals(CommandOutcome.Unauthorized, send(commandId, document, credential = credential))
        }
        assertEquals(before, snapshot().getLong("revision"))
    }

    @Test
    fun theAnswerIsAboutTheCommandThatWasSentEvenIfTheCallerNamesAnotherOne() {
        val commandId = newId()
        val document = addDocument(commandId, newId(), newName())

        val outcome = send(newId(), document) // asks for a different command's receipt

        assertEquals(CommandOutcome.Invalid(InvalidResponse.WRONG_COMMAND), outcome)
    }
}
