package net.ts.taild80ca0.atlas.twa.access

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The behavior of connecting a device to its household, against a real HTTP
 * server on loopback ([FakeAlvaServer]). It is an abstract class so the same
 * scenarios run twice: on the JVM with in-memory storage (fast feedback) and on
 * an emulator with the real Android Keystore and Room (what actually ships).
 */
abstract class HouseAccessScenarios {
    protected lateinit var server: FakeAlvaServer
    protected lateinit var vault: CredentialVault
    protected lateinit var binding: HouseholdBinding

    protected abstract fun createVault(): CredentialVault
    protected abstract fun createBinding(): HouseholdBinding
    protected open fun cleanup() {}

    @Before
    fun startServer() {
        server = FakeAlvaServer()
        vault = createVault()
        binding = createBinding()
    }

    @After
    fun stopServer() {
        server.close()
        cleanup()
    }

    /** A fresh object over the same storage, like the process starting again. */
    protected fun newAccess(
        api: HouseAccessApi = HttpHouseAccessApi(ServerEndpoint.parse(server.baseUrl), 3_000, 5_000),
        usingVault: CredentialVault = vault,
    ) = HouseAccess(usingVault, api, binding)

    protected fun connected(access: HouseAccess = newAccess()): AccessCredential = runBlocking {
        val result = access.connect(server.password)
        assertTrue("expected Connected, got $result", result is ConnectResult.Connected)
        (vault.read() as VaultRead.Found).access.active!!
    }

    private fun assertNoCredential(message: String = "no credential expected") {
        val read = vault.read()
        val empty = read == VaultRead.Empty ||
            (read is VaultRead.Found && read.access.active == null && read.access.pending == null)
        assertTrue("$message, got $read", empty)
    }

    private fun failure(result: ConnectResult): ConnectError =
        (result as? ConnectResult.Failed)?.error ?: throw AssertionError("expected a failure, got $result")

    private fun serverError(result: ConnectResult): AccessError =
        (failure(result) as? ConnectError.Server)?.error ?: throw AssertionError("expected a server error, got $result")

    @Test
    fun onlySuccessfulConnectionWakesSyncAfterCredentialAndBindingAreStored() = runBlocking {
        var wakeups = 0
        val access = HouseAccess(
            vault, HttpHouseAccessApi(ServerEndpoint.parse(server.baseUrl), 3_000, 5_000), binding,
            onConnected = {
                assertEquals(server.householdPublicId, binding.current())
                assertNotNull((vault.read() as VaultRead.Found).access.active)
                wakeups++
            },
        )
        assertTrue(access.connect("wrong password") is ConnectResult.Failed)
        assertEquals(0, wakeups)
        assertTrue(access.connect(server.password) is ConnectResult.Connected)
        assertEquals(1, wakeups)
    }

    // --- first setup -----------------------------------------------------------------------

    @Test
    fun firstSetupTradesThePasswordForACredentialAndBindsTheHousehold() = runBlocking {
        val access = newAccess()
        assertEquals(AccessState.NotConfigured, access.refresh())

        val result = access.connect(server.password)

        assertEquals(ConnectResult.Connected(server.householdPublicId), result)
        assertEquals(AccessState.Connected, access.state.value)
        assertEquals(server.householdPublicId, binding.current())
        val stored = (vault.read() as VaultRead.Found).access
        val device = server.devices.single()
        assertEquals(AccessCredential(device.id, device.token), stored.active)
        assertNull(stored.pending)
        assertFalse(stored.needsReconnect)
    }

    @Test
    fun thePasswordGoesOnlyToThePairingRequestAndNeverTheSnapshot() = runBlocking {
        newAccess().connect(server.password)

        val pairing = server.pairingRequests().single()
        val sent = JSONObject(pairing.body)
        assertEquals(server.password, sent.getString("password"))
        assertFalse("first setup has no previous token", sent.has("previous_token"))
        assertNull("pairing is not authenticated", pairing.authorization)

        val snapshot = server.snapshotRequests().single()
        assertEquals("Bearer " + server.devices.single().token, snapshot.authorization)
        assertFalse(snapshot.body.contains(server.password))
        assertFalse(snapshot.path.contains(server.password))
    }

    @Test
    fun identifyingTheHouseholdDoesNotClaimTheListWasSynced() = runBlocking {
        server.snapshotRevision = 999
        newAccess().connect(server.password)

        // Only the household id is recorded. The binding exposes no revision at all:
        // the Room-level test asserts confirmed_revision directly.
        assertEquals(server.householdPublicId, binding.current())
    }

    @Test
    fun aBlankPasswordNeverReachesTheServer() = runBlocking {
        val result = newAccess().connect("   ")

        assertEquals(ConnectError.EmptyPassword, failure(result))
        assertTrue(server.requests.isEmpty())
    }

    // --- everyday opening ---------------------------------------------------------------------

    @Test
    fun reopeningTheAppIsLocalAndNeverAsksForThePasswordAgain() = runBlocking {
        connected()
        server.requests.clear()

        val reopened = newAccess()
        assertEquals(AccessState.Connected, reopened.refresh())
        assertNotNull(reopened.credentialForSync())
        assertTrue("opening must not touch the network", server.requests.isEmpty())
    }

    @Test
    fun reopeningWorksWithTheServerDown() = runBlocking {
        connected()
        server.stop()

        assertEquals(AccessState.Connected, newAccess().refresh())
    }

    // --- failed first setup -----------------------------------------------------------------

    @Test
    fun aWrongPasswordIsReportedAndLeavesNothingBehind() = runBlocking {
        val access = newAccess()
        assertEquals(AccessError.WrongPassword, serverError(access.connect("errada")))

        assertNoCredential()
        assertNull(binding.current())
        assertEquals(AccessState.NotConfigured, access.state.value)
    }

    @Test
    fun aServerWithoutAPasswordCannotPair() = runBlocking {
        server.syncAvailable = false
        assertEquals(AccessError.SyncUnavailable, serverError(newAccess().connect(server.password)))
        assertNoCredential()
    }

    @Test
    fun theRateLimitIsReportedAndNothingIsStored() = runBlocking {
        server.pairingBudget = 0
        assertEquals(AccessError.RateLimited(900), serverError(newAccess().connect(server.password)))
        assertNoCredential()
    }

    @Test
    fun aServerErrorIsRetryable() = runBlocking {
        server.failSnapshotsWith(500)
        // Pairing succeeds, identification fails: the credential is kept for the retry.
        val first = newAccess().connect(server.password)
        assertEquals(AccessError.ServerUnavailable, serverError(first))
        assertEquals(AccessState.NotConfigured, newAccess().refresh())
    }

    @Test
    fun noConnectivityIsReportedAsANetworkFailure() = runBlocking {
        val access = newAccess()
        server.stop()

        assertEquals(AccessError.Network, serverError(access.connect(server.password)))
        assertNoCredential()
    }

    @Test
    fun aMalformedPairingAnswerIsRefusedWithoutStoringAnything() = runBlocking {
        server.malformedPairingBody = """{"device_id":7}"""

        assertEquals(AccessError.UnexpectedResponse, serverError(newAccess().connect(server.password)))
        assertNoCredential()
        assertNull(binding.current())
    }

    @Test
    fun aRedirectIsNeverFollowedSoTheSecretStaysWithTheServer() = runBlocking {
        server.redirectPairingTo = "http://localhost:1/steal"

        assertEquals(AccessError.UnexpectedResponse, serverError(newAccess().connect(server.password)))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun anInterruptedFirstSetupResumesWithoutPairingAgain() = runBlocking {
        server.failSnapshotsWith(500)
        newAccess().connect(server.password) // paired, but the household was not identified
        val stored = (vault.read() as VaultRead.Found).access
        assertNotNull("the credential must survive the failure", stored.pending)
        assertNull(stored.active)

        val result = newAccess().connect(server.password)

        assertEquals(ConnectResult.Connected(server.householdPublicId), result)
        assertEquals("no second device was minted", 1, server.pairingRequests().size)
        assertEquals(stored.pending, (vault.read() as VaultRead.Found).access.active)
        assertNull((vault.read() as VaultRead.Found).access.pending)
    }

    // --- revocation and reconnection ------------------------------------------------------------

    @Test
    fun aRevokedCredentialIsDetectedAndKept() = runBlocking {
        val credential = connected()
        server.revoke(credential.deviceId)
        val access = newAccess()

        val check = access.checkConnection()

        assertEquals(AccessError.Unauthorized, serverError(check))
        assertEquals(AccessState.NeedsReconnect, access.state.value)
        assertNull("sync must not keep using a refused credential", access.credentialForSync())
        val stored = (vault.read() as VaultRead.Found).access
        assertEquals(credential, stored.active)
        assertTrue(stored.needsReconnect)
        assertEquals(server.householdPublicId, binding.current())
    }

    @Test
    fun reconnectingSendsTheOldSecretSoTheServerKeepsTheLineage() = runBlocking {
        val old = connected()
        server.revoke(old.deviceId)
        val access = newAccess()
        access.checkConnection()

        val result = access.connect(server.password)

        assertTrue(result is ConnectResult.Connected)
        val sent = JSONObject(server.pairingRequests().last().body)
        assertEquals(old.token, sent.getString("previous_token"))
        val fresh = (vault.read() as VaultRead.Found).access.active!!
        assertNotEquals(old.token, fresh.token)
        assertEquals(server.device(old.deviceId).lineage, server.device(fresh.deviceId).lineage)
        assertEquals(AccessState.Connected, access.state.value)
        assertFalse((vault.read() as VaultRead.Found).access.needsReconnect)
    }

    @Test
    fun aRotatedPasswordNeedsTheNewPasswordButKeepsTheIdentity() = runBlocking {
        val old = connected()
        server.rotatePassword("outra-senha")
        val access = newAccess()
        assertEquals(AccessError.Unauthorized, serverError(access.checkConnection()))

        assertEquals(AccessError.WrongPassword, serverError(access.connect("senha-da-casa")))
        assertEquals("a failed attempt keeps the old secret", old, (vault.read() as VaultRead.Found).access.active)

        assertTrue(access.connect("outra-senha") is ConnectResult.Connected)
        val fresh = (vault.read() as VaultRead.Found).access.active!!
        assertEquals(server.device(old.deviceId).lineage, server.device(fresh.deviceId).lineage)
    }

    @Test
    fun aLostPairingAnswerNeverAbandonsTheIdentity() = runBlocking {
        val old = connected()
        server.revoke(old.deviceId)
        val access = newAccess()
        access.checkConnection()

        // The server commits the new credential, the phone never hears about it.
        server.dropPairingResponses = true
        assertEquals(AccessError.Network, serverError(access.connect(server.password)))
        server.dropPairingResponses = false
        val orphans = server.devices.size - 1
        val afterLoss = (vault.read() as VaultRead.Found).access
        assertEquals("the old secret is still the proof of identity", old, afterLoss.active)
        assertTrue(afterLoss.needsReconnect)
        assertEquals(AccessState.NeedsReconnect, access.state.value)

        // Reauthorizing again presents the same old secret, so the lineage continues.
        assertTrue(newAccess().connect(server.password) is ConnectResult.Connected)

        val previousTokens = server.pairingRequests().drop(1).map { JSONObject(it.body).getString("previous_token") }
        assertTrue(previousTokens.all { it == old.token })
        val fresh = (vault.read() as VaultRead.Found).access.active!!
        val lineage = server.device(old.deviceId).lineage
        assertEquals(lineage, server.device(fresh.deviceId).lineage)
        assertTrue("the answer was lost for at least one committed device", orphans >= 1)
        assertTrue("every orphan is in the same lineage", server.devices.all { it.lineage == lineage })
    }

    @Test
    fun anIdentificationFailureAfterReconnectingKeepsTheNewCredentialForTheRetry() = runBlocking {
        val old = connected()
        server.revoke(old.deviceId)
        newAccess().checkConnection()

        server.failSnapshotsWith(500)
        assertEquals(AccessError.ServerUnavailable, serverError(newAccess().connect(server.password)))
        val interrupted = (vault.read() as VaultRead.Found).access
        assertEquals(old, interrupted.active)
        assertNotNull(interrupted.pending)

        val pairingsBefore = server.pairingRequests().size
        assertTrue(newAccess().connect(server.password) is ConnectResult.Connected)
        assertEquals(pairingsBefore, server.pairingRequests().size)
        assertEquals(interrupted.pending, (vault.read() as VaultRead.Found).access.active)
    }

    @Test
    fun aPendingCredentialTheServerRefusedStillProvesTheLineage() = runBlocking {
        val old = connected()
        server.revoke(old.deviceId)
        newAccess().checkConnection()
        server.failSnapshotsWith(500)
        newAccess().connect(server.password)
        val pending = (vault.read() as VaultRead.Found).access.pending!!
        server.revoke(pending.deviceId)

        assertTrue(newAccess().connect(server.password) is ConnectResult.Connected)

        assertEquals(pending.token, JSONObject(server.pairingRequests().last().body).getString("previous_token"))
        val fresh = (vault.read() as VaultRead.Found).access.active!!
        assertEquals(server.device(old.deviceId).lineage, server.device(fresh.deviceId).lineage)
    }

    @Test
    fun anUnknownPreviousSecretIsRefusedWithoutChangingAnything() = runBlocking {
        val credential = connected()
        server.devices.clear() // a server that never issued it
        val access = newAccess()
        access.checkConnection()

        assertEquals(AccessError.InvalidPreviousToken, serverError(access.connect(server.password)))
        assertEquals(credential, (vault.read() as VaultRead.Found).access.active)
        assertEquals(server.householdPublicId, binding.current())
    }

    @Test
    fun aLateRefusalOfAnOlderCredentialDoesNotKnockOutTheCurrentOne() = runBlocking {
        val old = connected()
        server.revoke(old.deviceId)
        val access = newAccess()
        access.checkConnection()
        access.connect(server.password)

        access.reportUnauthorized(old)

        assertEquals(AccessState.Connected, access.refresh())
        assertNotNull(access.credentialForSync())
    }

    @Test
    fun theSenderCanReportARefusedCredential() = runBlocking {
        val credential = connected()
        val access = newAccess()

        access.reportUnauthorized(credential)

        assertEquals(AccessState.NeedsReconnect, access.state.value)
        assertEquals(credential, (vault.read() as VaultRead.Found).access.active)
    }

    // --- households never mix -------------------------------------------------------------------

    @Test
    fun aDeviceBoundToAnotherHouseholdRefusesTheSwitch() = runBlocking {
        assertEquals(BindResult.Bound, binding.bind("outra-casa"))
        val access = newAccess()

        val result = access.connect(server.password)

        assertEquals(ConnectError.OtherHousehold, failure(result))
        assertEquals("outra-casa", binding.current())
        assertNoCredential("the other household's credential is not kept")
        assertEquals(AccessState.NeedsReconnect, access.state.value)
    }

    @Test
    fun refusingAnotherHouseholdKeepsTheCredentialTheDeviceHad() = runBlocking {
        val credential = connected()
        newAccess().reportUnauthorized(credential) // the phone is asked to reconnect
        val otherServer = FakeAlvaServer(householdPublicId = "casa-b", password = "senha-b")
        // The phone still holds the first household's secret; `casa-b` has never seen it.
        try {
            val access = HouseAccess(
                vault,
                HttpHouseAccessApi(ServerEndpoint.parse(otherServer.baseUrl), 3_000, 5_000),
                binding,
            )
            assertEquals(AccessError.InvalidPreviousToken, serverError(access.connect("senha-b")))
            assertEquals(credential, (vault.read() as VaultRead.Found).access.active)
            assertEquals(server.householdPublicId, binding.current())
        } finally {
            otherServer.close()
        }
    }

    @Test
    fun aPhoneWithoutItsSecretStillCannotAttachToAnotherHousehold() = runBlocking {
        binding.bind("casa-a")
        // The secret was lost (nothing in the vault): a brand-new lineage is the only option,
        // but the household check still stops a different server.
        val access = newAccess()

        assertEquals(ConnectError.OtherHousehold, failure(access.connect(server.password)))
        assertEquals("casa-a", binding.current())
        assertEquals(1, server.pairingRequests().size)
    }

    @Test
    fun sameHouseholdAgainIsAccepted() = runBlocking {
        binding.bind(server.householdPublicId)

        assertTrue(newAccess().connect(server.password) is ConnectResult.Connected)
    }

    // --- concurrency and storage ----------------------------------------------------------------

    @Test
    fun twoSimultaneousConnectsPairOnlyOnce() = runBlocking {
        val access = newAccess()

        val results = (1..2).map { async { access.connect(server.password) } }.awaitAll()

        assertTrue(results.all { it is ConnectResult.Connected })
        assertEquals(1, server.pairingRequests().size)
    }

    @Test
    fun aCredentialThatCannotBeStoredIsNotAccepted() = runBlocking {
        val failing = FlakyVault(vault).apply { failWrites = true }
        val access = newAccess(usingVault = failing)

        assertEquals(ConnectError.StorageFailed, failure(access.connect(server.password)))
        assertNoCredential()
        assertNull(binding.current())
    }

    @Test
    fun anUnreadableStorageDoesNotLetTheDeviceMintANewIdentity() = runBlocking {
        connected()
        val unavailable = FlakyVault(vault).apply { unavailable = true }
        val access = newAccess(usingVault = unavailable)
        val pairingsBefore = server.pairingRequests().size

        assertEquals(AccessState.StorageUnavailable, access.refresh())
        assertEquals(ConnectError.StorageFailed, failure(access.connect(server.password)))
        assertEquals(pairingsBefore, server.pairingRequests().size)
    }

    @Test
    fun aLostCredentialOnADatabaseWithAHouseholdAsksToReconnect() = runBlocking {
        connected()
        val lost = FlakyVault(vault).apply { lost = true }
        val access = newAccess(usingVault = lost)

        assertEquals(AccessState.NeedsReconnect, access.refresh())
    }

    // --- secrets stay secret --------------------------------------------------------------------

    @Test
    fun credentialsNeverPrintTheirSecret() {
        val credential = AccessCredential(7, "super-secret-token")
        assertFalse(credential.toString().contains("super-secret-token"))
        assertFalse(StoredAccess(credential, credential, true).toString().contains("super-secret-token"))
    }

    @Test
    fun noUserFacingMessageExposesInternals() {
        val errors = listOf(
            ConnectError.EmptyPassword,
            ConnectError.OtherHousehold,
            ConnectError.StorageFailed,
        ) + listOf(
            AccessError.WrongPassword, AccessError.SyncUnavailable, AccessError.RateLimited(900),
            AccessError.InvalidPreviousToken, AccessError.Unauthorized, AccessError.ServerUnavailable,
            AccessError.Network, AccessError.Tls, AccessError.UnexpectedResponse,
        ).map { ConnectError.Server(it) }
        val forbidden = listOf("http", "token", "401", "403", "422", "429", "500", "exception", "keystore", "json", "lineage", "previous")
        errors.forEach { error ->
            val message = AccessMessages.forError(error)
            assertTrue(message.isNotBlank())
            forbidden.forEach { assertFalse("'$message' mentions '$it'", message.lowercase().contains(it)) }
        }
    }
}

/** Wraps a vault to inject the failures the real one only has in rare conditions. */
class FlakyVault(private val delegate: CredentialVault) : CredentialVault {
    @Volatile var failWrites = false
    @Volatile var unavailable = false
    @Volatile var lost = false

    override fun read(): VaultRead = when {
        unavailable -> VaultRead.Unavailable
        lost -> VaultRead.Lost
        else -> delegate.read()
    }

    override fun write(access: StoredAccess) {
        if (failWrites) throw VaultWriteException(java.io.IOException("disk full"))
        delegate.write(access)
    }
}
