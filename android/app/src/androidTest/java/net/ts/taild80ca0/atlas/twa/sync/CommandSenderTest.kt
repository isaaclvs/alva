package net.ts.taild80ca0.atlas.twa.sync

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.AccessState
import net.ts.taild80ca0.atlas.twa.access.ApiResult
import net.ts.taild80ca0.atlas.twa.access.CredentialVault
import net.ts.taild80ca0.atlas.twa.access.HouseAccess
import net.ts.taild80ca0.atlas.twa.access.HouseAccessApi
import net.ts.taild80ca0.atlas.twa.access.HouseholdIdentity
import net.ts.taild80ca0.atlas.twa.access.RoomHouseholdBinding
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import net.ts.taild80ca0.atlas.twa.access.StoredAccess
import net.ts.taild80ca0.atlas.twa.access.VaultRead
import net.ts.taild80ca0.atlas.twa.data.DatabaseTest
import net.ts.taild80ca0.atlas.twa.data.ScriptedIds
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.CommandEntity
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.FreezeResult
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.data.list.PreparationRefusal
import net.ts.taild80ca0.atlas.twa.data.list.WireOutbox
import net.ts.taild80ca0.atlas.twa.data.list.WirePreparer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One safe attempt, on a real Room file and a real HTTP server on loopback (issue #45, third cut):
 * the attempt is counted and committed before the request exists, a document is repeated byte for
 * byte, nothing refused ever reaches the network, and no answer removes or confirms anything.
 * Rails itself is covered by `script/android_rails_transport_check`; here the server is scripted
 * so each failure can be provoked exactly.
 */
class CommandSenderTest : DatabaseTest() {

    private val itemUuid = "9a1d0000-0000-4000-8000-000000000001"

    private class MemoryVault(var access: StoredAccess?) : CredentialVault {
        override fun read(): VaultRead = access?.let { VaultRead.Found(it) } ?: VaultRead.Empty
        override fun write(access: StoredAccess) {
            this.access = access
        }
    }

    private val neverUsedApi = object : HouseAccessApi {
        override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> =
            error("pairing is not part of sending")

        override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> =
            error("identifying is not part of sending")
    }

    private val first = AccessCredential(1, "primeiro-segredo")
    private val second = AccessCredential(2, "segundo-segredo")

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Decides each answer; the default is "no answer scripted". */
    @Volatile
    private var handler: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(500) }

    @Before
    fun startServer() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return handler(request)
                }
            }
            start()
        }
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private class Rig(
        val db: AlvaDatabase,
        val vault: MemoryVault,
        val access: HouseAccess,
        val sender: CommandSender,
    )

    private fun rig(
        db: AlvaDatabase = openDatabase(),
        vault: MemoryVault = MemoryVault(StoredAccess(active = first)),
    ): Rig {
        val access = HouseAccess(vault, neverUsedApi, RoomHouseholdBinding(db))
        val sender = CommandSender(
            WirePreparer(db),
            WireOutbox(db),
            access,
            HttpCommandTransport(ServerEndpoint.parse(server.url("/").toString()), 2_000, 2_000),
        )
        return Rig(db, vault, access, sender)
    }

    /** Queues add (cmd-1) then mark purchased (cmd-2) through the real store; no document is frozen yet. */
    private suspend fun queueAddAndMark(db: AlvaDatabase) {
        val store = storeOn(db, ScriptedIds(itemUuid, "cmd-1", "cmd-2"))
        assertTrue(store.execute(ListIntent.AddItems(listOf("Leite"))) is IntentResult.Saved)
        assertTrue(store.execute(ListIntent.SetItemState(itemUuid, ItemStatus.PURCHASED)) is IntentResult.Saved)
    }

    private fun applied(commandId: String = "cmd-1", revision: Int = 841) = MockResponse().setResponseCode(200).setBody(
        """{"revision":$revision,"results":[{"id":"$commandId","status":"applied","revision":$revision,"data":{"items":[]}}],"snapshot_required":false}""",
    )

    private fun duplicate(commandId: String = "cmd-1", revision: Int = 841) = MockResponse().setResponseCode(200).setBody(
        """{"revision":$revision,"results":[{"id":"$commandId","status":"duplicate","original_status":"applied","revision":$revision,"data":{"items":[]}}],"snapshot_required":false}""",
    )

    private fun bodyOf(request: RecordedRequest): ByteArray = request.body.clone().readByteArray()

    private fun sent(result: SendAttempt): SendAttempt.Sent {
        assertTrue("expected a request to be made, got $result", result is SendAttempt.Sent)
        return result as SendAttempt.Sent
    }

    private fun notSent(result: SendAttempt): SendAttempt.NotSent {
        assertTrue("expected nothing to be sent, got $result", result is SendAttempt.NotSent)
        return result as SendAttempt.NotSent
    }

    // ---- the happy path, and what it must not do -------------------------------------------

    @Test
    fun theAttemptIsCommittedBeforeTheRequestReachesTheServerAndTheAnswerRemovesNothing() = runBlocking {
        val r = rig()
        queueAddAndMark(r.db)
        // A second connection to the same file sees only what has been committed.
        val observer = openDatabase()
        var seenByServer: CommandEntity? = null
        handler = {
            seenByServer = runBlocking { observer.commands().find("cmd-1") }
            applied()
        }

        val result = sent(r.sender.attempt("cmd-1"))

        val duringRequest = seenByServer!!
        assertEquals("the counter was already committed when the request arrived", 1, duringRequest.attempts)
        assertEquals(CommandStatus.IN_FLIGHT.name, duringRequest.status)
        assertNotNull(duringRequest.wireDocument)
        assertEquals(1, result.attempt)
        assertEquals(ReceiptStatus.APPLIED, ((result.outcome as CommandOutcome.Receipt).receipt).status)

        // The exact frozen bytes went out, wrapped and nothing else.
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/commands", request.path)
        assertEquals("Bearer ${first.token}", request.getHeader("Authorization"))
        assertArrayEquals("{\"commands\":[${duringRequest.wireDocument}]}".toByteArray(Charsets.UTF_8), bodyOf(request))

        // A receipt is a value: the queue and the document are as they were.
        val after = r.db.commands().inOrder()
        assertEquals(listOf("cmd-1", "cmd-2"), after.map { it.id })
        assertEquals(duringRequest.wireDocument, after.first().wireDocument)
        assertEquals(CommandStatus.IN_FLIGHT.name, after.first().status)
        assertEquals(CommandStatus.PENDING.name, after.last().status)
        assertNull(after.last().wireDocument)
    }

    @Test
    fun anIntentNeverPreparedIsFrozenBeforeItsFirstAttempt() = runBlocking {
        val r = rig()
        queueAddAndMark(r.db)
        assertNull(r.db.commands().find("cmd-1")!!.wireDocument)
        handler = { applied() }

        r.sender.attempt("cmd-1")

        val frozen = r.db.commands().find("cmd-1")!!.wireDocument!!
        assertTrue(String(bodyOf(requests.single()), Charsets.UTF_8).contains(frozen))
    }

    // ---- failures: nothing lost, the same bytes every time ---------------------------------

    @Test
    fun lostRequestsAndAnswersKeepTheDocumentAndEveryRepeatSendsTheSameBytes() = runBlocking {
        val r = rig()
        queueAddAndMark(r.db)
        val script = ArrayDeque(
            listOf(
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
                MockResponse().setResponseCode(503),
                applied(),
            ),
        )
        handler = { script.removeFirst() }

        val outcomes = (1..4).map { sent(r.sender.attempt("cmd-1")) }

        assertEquals(
            listOf<CommandOutcome>(
                CommandOutcome.Transient(TransientCause.NETWORK),
                CommandOutcome.Transient(TransientCause.TIMEOUT),
                CommandOutcome.Transient(TransientCause.SERVER_ERROR),
            ),
            outcomes.take(3).map { it.outcome },
        )
        assertEquals("one request per call, no hidden retries", 4, requests.size)
        assertEquals(listOf(1, 2, 3, 4), outcomes.map { it.attempt })
        val bodies = requests.map { bodyOf(it) }
        bodies.drop(1).forEach { assertArrayEquals(bodies.first(), it) }
        val row = r.db.commands().find("cmd-1")!!
        assertEquals(4, row.attempts)
        assertEquals(2, r.db.commands().count())
    }

    @Test
    fun rateLimitingIsReportedWithItsWaitAndNotRetried() = runBlocking {
        val r = rig()
        queueAddAndMark(r.db)
        handler = { MockResponse().setResponseCode(429).setHeader("Retry-After", "17").setBody("""{"error":"rate_limited"}""") }

        val result = sent(r.sender.attempt("cmd-1"))

        assertEquals(CommandOutcome.Transient(TransientCause.RATE_LIMITED, 17), result.outcome)
        assertEquals(1, requests.size)
        assertEquals(1, r.db.commands().find("cmd-1")!!.attempts)
    }

    @Test
    fun anAnswerAboutAnotherCommandIsInvalidAndConfirmsNothing() = runBlocking {
        val r = rig()
        queueAddAndMark(r.db)
        handler = { applied(commandId = "cmd-2") }

        val result = sent(r.sender.attempt("cmd-1"))

        assertEquals(CommandOutcome.Invalid(InvalidResponse.WRONG_COMMAND), result.outcome)
        assertEquals(listOf("cmd-1", "cmd-2"), r.db.commands().inOrder().map { it.id })
    }

    @Test
    fun aReceiptNobodyAppliedSurvivesTheProcessAndTheRepeatIsAnsweredDuplicate() = runBlocking {
        val before = openDatabase()
        val firstRig = rig(before)
        queueAddAndMark(before)
        handler = { applied() }
        val firstSent = sent(firstRig.sender.attempt("cmd-1"))
        val frozen = before.commands().find("cmd-1")!!.wireDocument!!
        // The process dies here: the receipt was returned but never applied.
        before.close()

        val reopened = openDatabase()
        assertEquals(listOf("cmd-1", "cmd-2"), reopened.commands().inOrder().map { it.id })
        assertEquals(frozen, reopened.commands().find("cmd-1")!!.wireDocument)
        handler = { duplicate() }
        val repeat = sent(rig(reopened).sender.attempt("cmd-1"))

        assertEquals(1, firstSent.attempt)
        assertEquals(2, repeat.attempt)
        val receipt = (repeat.outcome as CommandOutcome.Receipt).receipt
        assertEquals(ReceiptStatus.DUPLICATE, receipt.status)
        assertEquals(ReceiptStatus.APPLIED, receipt.originalStatus)
        assertArrayEquals(bodyOf(requests[0]), bodyOf(requests[1]))
        assertEquals(2, reopened.commands().count())
    }

    // ---- credentials -----------------------------------------------------------------------

    @Test
    fun aRefusedCredentialFlagsReconnectionKeepsTheQueueAndStopsFurtherSending() = runBlocking {
        val r = rig()
        queueAddAndMark(r.db)
        handler = { MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}""") }

        val result = sent(r.sender.attempt("cmd-1"))

        assertEquals(CommandOutcome.Unauthorized, result.outcome)
        assertEquals(AccessState.NeedsReconnect, r.access.refresh())
        assertEquals(listOf("cmd-1", "cmd-2"), r.db.commands().inOrder().map { it.id })
        assertEquals(1, r.db.commands().find("cmd-1")!!.attempts)
        assertNotNull(r.db.commands().find("cmd-1")!!.wireDocument)

        val again = notSent(r.sender.attempt("cmd-1"))
        assertEquals(NotSentReason.NO_CREDENTIAL, again.reason)
        assertEquals("no request while the device needs to reconnect", 1, requests.size)
        assertEquals("and no attempt was counted for it", 1, r.db.commands().find("cmd-1")!!.attempts)
    }

    @Test
    fun aLateRefusalOfAnOldCredentialCannotKnockOutTheNewOne() = runBlocking {
        val vault = MemoryVault(StoredAccess(active = first))
        val r = rig(vault = vault)
        queueAddAndMark(r.db)
        handler = {
            // The user reconnects while the request is in flight; the old credential is then refused.
            vault.write(StoredAccess(active = second))
            MockResponse().setResponseCode(403).setBody("""{"error":"forbidden_scope"}""")
        }

        sent(r.sender.attempt("cmd-1"))

        assertEquals(AccessState.Connected, r.access.refresh())
        assertEquals(second, r.access.credentialForSync())
    }

    // ---- refusals never reach the network --------------------------------------------------

    private fun assertNeverSent(reason: NotSentReason, result: SendAttempt, attemptsBefore: Int = 0, id: String = "cmd-1", db: AlvaDatabase) = runBlocking {
        assertEquals(reason, notSent(result).reason)
        assertEquals("a refusal must not reach the network", 0, requests.size)
        assertEquals("a refusal must not count an attempt", attemptsBefore, db.commands().find(id)?.attempts ?: attemptsBefore)
    }

    @Test
    fun whatThePreparerOrTheOutboxRefusesIsNeverTransmittedNorCounted() = runBlocking {
        val r = rig()
        queueAddAndMark(r.db)
        handler = { applied() }

        assertNeverSent(NotSentReason.UNKNOWN_COMMAND, r.sender.attempt("nope"), db = r.db, id = "nope")

        for (status in listOf(CommandStatus.BLOCKED, CommandStatus.CONFLICT, CommandStatus.REJECTED)) {
            r.db.commands().setStatus("cmd-2", status.name, "x")
            assertNeverSent(NotSentReason.NOT_SENDABLE, r.sender.attempt("cmd-2"), db = r.db, id = "cmd-2")
        }

        // A row attempted before its document was kept: the bytes it sent are unknown.
        r.db.commands().insert(
            CommandEntity(
                id = "legacy", type = "add_items", payload = """{"names":["Pão"]}""", preconditions = "{}", dependsOn = null,
                observedRevision = 0, status = CommandStatus.IN_FLIGHT.name, createdAt = 1, attempts = 2, wireDocument = null,
            ),
        )
        assertNeverSent(NotSentReason.LEGACY_ALREADY_ATTEMPTED, r.sender.attempt("legacy"), attemptsBefore = 2, db = r.db, id = "legacy")

        // A type with no wire form yet.
        r.db.commands().insert(
            CommandEntity(
                id = "future", type = "remove_item", payload = "{}", preconditions = "{}", dependsOn = null,
                observedRevision = 0, status = CommandStatus.PENDING.name, createdAt = 2,
            ),
        )
        val unsupported = r.sender.attempt("future")
        assertNeverSent(NotSentReason.NOT_PREPARABLE, unsupported, db = r.db, id = "future")
        assertEquals(PreparationRefusal.UNSUPPORTED_TYPE, (unsupported as SendAttempt.NotSent).preparation)
        assertEquals(CommandStatus.PENDING.name, r.db.commands().find("future")!!.status)
    }

    @Test
    fun withoutACredentialNothingIsSentAndNothingIsCounted() = runBlocking {
        val r = rig(vault = MemoryVault(null))
        queueAddAndMark(r.db)
        handler = { applied() }

        assertNeverSent(NotSentReason.NO_CREDENTIAL, r.sender.attempt("cmd-1"), db = r.db)
        // Preparing is local and harmless: the document is already waiting for when the device reconnects.
        assertNotNull(r.db.commands().find("cmd-1")!!.wireDocument)
    }

    @Test
    fun aDocumentOverTheLimitStaysQueuedAndIsNeverTransmitted() = runBlocking {
        val r = rig()
        val store = storeOn(r.db, ScriptedIds(itemUuid, "cmd-1"))
        assertTrue(store.execute(ListIntent.AddItems(listOf("Leite"))) is IntentResult.Saved)
        val huge = "{\"id\":\"cmd-1\",\"type\":\"add_items\",\"depends_on\":null,\"preconditions\":{},\"payload\":{\"items\":[{\"public_id\":\"$itemUuid\",\"name\":\"" +
            "x".repeat(CommandLimits.MAX_DOCUMENT_BYTES) + "\"}]}}"
        assertTrue(WireOutbox(r.db).freeze("cmd-1", huge) is FreezeResult.Frozen)
        handler = { applied() }

        assertNeverSent(NotSentReason.DOCUMENT_TOO_LARGE, r.sender.attempt("cmd-1"), db = r.db)
        assertEquals(huge, r.db.commands().find("cmd-1")!!.wireDocument)
    }
}
