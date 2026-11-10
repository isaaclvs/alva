package net.ts.taild80ca0.atlas.twa.access

import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The vault on the real Android Keystore: what is stored, what survives and what is lost. */
class KeystoreCredentialVaultTest {
    private val storage = RealAccessStorage()
    private val vault = storage.newVault()
    private val access = StoredAccess(
        active = AccessCredential(12, "token-ativo-123456"),
        pending = AccessCredential(13, "token-pendente-654321"),
        needsReconnect = true,
    )

    @After
    fun cleanup() = storage.cleanup()

    @Test
    fun nothingStoredReadsAsEmpty() {
        assertEquals(VaultRead.Empty, vault.read())
    }

    @Test
    fun aRecordSurvivesANewVaultOverTheSameFile() {
        vault.write(access)

        assertEquals(VaultRead.Found(access), storage.newVault().read())
    }

    @Test
    fun theFileIsCiphertextUnderNoBackupFilesDir() {
        vault.write(access)

        assertEquals(storage.context.noBackupFilesDir, storage.vaultFile.parentFile)
        val bytes = storage.vaultFile.readBytes()
        listOf("token-ativo-123456", "token-pendente-654321", "needs_reconnect", "device_id").forEach {
            assertFalse("'$it' leaked in the file", String(bytes, Charsets.ISO_8859_1).contains(it))
        }
    }

    @Test
    fun theKeyLivesInTheKeystoreAndCannotBeExported() {
        vault.write(access)

        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(storage.alias, null) as SecretKey
        assertNull("the raw key bytes must not be available", key.encoded)
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun everyWriteUsesAFreshIv() {
        vault.write(access)
        val first = storage.vaultFile.readBytes()
        vault.write(access)
        val second = storage.vaultFile.readBytes()

        assertFalse(first.contentEquals(second))
        assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
    }

    @Test
    fun aRestoredFileWithoutItsKeyIsLostNotReadable() {
        vault.write(access)
        val copy = storage.vaultFile.readBytes()
        storage.deleteKey() // what a restore onto another device / a reinstall looks like

        assertEquals(VaultRead.Lost, storage.newVault().read())

        // The same bytes under a different (new) key do not decrypt either.
        storage.vaultFile.writeBytes(copy)
        val other = KeystoreCredentialVault(storage.vaultFile, storage.alias + "_other")
        other.write(StoredAccess(AccessCredential(1, "x")))
        storage.vaultFile.writeBytes(copy)
        assertEquals(VaultRead.Lost, other.read())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(storage.alias + "_other")
    }

    @Test
    fun aLostVaultCanBeWrittenAgain() {
        vault.write(access)
        storage.deleteKey()
        assertEquals(VaultRead.Lost, vault.read())

        val fresh = StoredAccess(AccessCredential(99, "novo"))
        vault.write(fresh)

        assertEquals(VaultRead.Found(fresh), vault.read())
    }

    @Test
    fun aTamperedOrTruncatedFileIsLostAndNeverSilentlyAccepted() {
        vault.write(access)
        val original = storage.vaultFile.readBytes()

        val flipped = original.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 0x01).toByte() }
        storage.vaultFile.writeBytes(flipped)
        assertEquals(VaultRead.Lost, vault.read())

        storage.vaultFile.writeBytes(original.copyOf(8))
        assertEquals(VaultRead.Lost, vault.read())

        storage.vaultFile.writeBytes(ByteArray(0))
        assertEquals(VaultRead.Lost, vault.read())

        storage.vaultFile.writeBytes(byteArrayOf(9, 9, 9))
        assertEquals(VaultRead.Lost, vault.read())
    }

    @Test
    fun aFailedWriteLeavesThePreviousRecordAndNoSecretInTheError() {
        vault.write(access)
        // The parent "directory" is a regular file, so the new write cannot be created.
        val blocker = File(storage.context.noBackupFilesDir, "blocker-${storage.alias}").apply { writeText("x") }
        val broken = KeystoreCredentialVault(File(blocker, "access.bin"), storage.alias)
        try {
            broken.write(access)
            fail("expected the write to fail")
        } catch (error: VaultWriteException) {
            assertFalse(error.message.orEmpty().contains("token-ativo"))
            assertFalse(error.toString().contains("token-ativo"))
        } finally {
            blocker.delete()
        }
        assertEquals(VaultRead.Found(access), vault.read())
    }

    @Test
    fun writingLeavesNoTemporaryFileBehind() {
        vault.write(access)
        vault.write(access.copy(needsReconnect = false))

        val leftovers = storage.context.noBackupFilesDir.list().orEmpty()
            .filter { it.startsWith(storage.vaultFile.name) && it != storage.vaultFile.name }
        assertTrue("unexpected files: $leftovers", leftovers.isEmpty())
        assertNotNull((vault.read() as VaultRead.Found).access.active)
    }

    @Test
    fun theRecordRoundTripsEveryField() {
        val minimal = StoredAccess(active = null, pending = AccessCredential(1, "p"), needsReconnect = false)
        vault.write(minimal)
        assertEquals(VaultRead.Found(minimal), vault.read())
    }
}
