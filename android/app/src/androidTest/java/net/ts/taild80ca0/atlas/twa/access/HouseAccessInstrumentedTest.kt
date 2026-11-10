package net.ts.taild80ca0.atlas.twa.access

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same scenarios as the JVM test, on an emulator with the real Android
 * Keystore, a real file under `noBackupFilesDir`, a real Room database and real
 * HTTP to loopback (allowed by the debug-only network security config).
 */
class HouseAccessInstrumentedTest : HouseAccessScenarios() {
    private val storage = RealAccessStorage()
    private val database by lazy { storage.openDatabase() }

    override fun createVault(): CredentialVault = storage.newVault()

    override fun createBinding(): HouseholdBinding = RoomHouseholdBinding(database)

    override fun cleanup() = storage.cleanup()

    @Test
    fun thePasswordAndTheSecretNeverReachAnyFileOfTheApp() = runBlocking {
        val credential = connected()
        // Force the database and prefs onto disk.
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()

        val needles = listOf(server.password, credential.token).map { it.toByteArray() }
        val roots = listOfNotNull(
            storage.context.filesDir.parentFile, // the whole private data dir
            storage.context.externalCacheDir,
            storage.context.getExternalFilesDir(null),
        )
        val leaks = roots.flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }
            .filter { file ->
                val bytes = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
                needles.any { needle -> indexOf(bytes, needle) >= 0 }
            }
        assertTrue("secret found in: ${leaks.map { it.path }}", leaks.isEmpty())
        assertTrue(storage.vaultFile.exists())
        assertEquals(storage.context.noBackupFilesDir, storage.vaultFile.parentFile)
    }

    @Test
    fun confirmedRevisionIsNeverMovedBySettingUp() = runBlocking {
        // A copy that was already reconciled keeps its revision; a new one starts at 0.
        connected()
        assertEquals(0L, database.syncState().get()!!.confirmedRevision)
        assertEquals(server.householdPublicId, database.syncState().get()!!.householdPublicId)
        assertNotEquals("the server's revision was not copied", 0, server.snapshotRevision)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
