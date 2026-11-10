package net.ts.taild80ca0.atlas.twa.access

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONException
import org.json.JSONObject

/** What reading the vault found. */
sealed interface VaultRead {
    /** Nothing was ever stored (or it was cleared). */
    data object Empty : VaultRead

    data class Found(val access: StoredAccess) : VaultRead

    /**
     * A record exists but can never be read again here: the Keystore key is gone
     * (reinstall, restore onto another device, cleared keystore) or the file is
     * corrupt. The secret is unrecoverable; the device has to be authorized again.
     */
    data object Lost : VaultRead

    /** A transient failure. Nothing is known: never treat it as [Lost] or [Empty]. */
    data object Unavailable : VaultRead
}

class VaultWriteException(cause: Throwable) : Exception("Could not store the access credential", cause)

interface CredentialVault {
    fun read(): VaultRead

    /** Atomic: after a failure the previous record is still the one stored. */
    @Throws(VaultWriteException::class)
    fun write(access: StoredAccess)
}

/**
 * Stores the credential encrypted with an AES-256-GCM key that lives in the Android
 * Keystore and can never be exported, in a file under `noBackupFilesDir`.
 *
 * - The key never leaves the Keystore, so the file is useless on any other device
 *   or after a reinstall: a restored copy fails to decrypt and reads as [VaultRead.Lost].
 * - `noBackupFilesDir` is excluded from Auto Backup and device transfer; the debug
 *   backup rules also exclude it explicitly (see docs/plans/android-house-access.md).
 * - The household password is never an input here: only the issued credential.
 * - Writes go through [AtomicFile] (temp file + fsync + rename), so a crash leaves
 *   either the old record or the new one.
 */
class KeystoreCredentialVault(
    private val file: File,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : CredentialVault {
    private val lock = Any()

    override fun read(): VaultRead = synchronized(lock) {
        val bytes = try {
            AtomicFile(file).readFully()
        } catch (_: FileNotFoundException) {
            return@synchronized VaultRead.Empty
        } catch (_: IOException) {
            return@synchronized VaultRead.Unavailable
        }
        val key = try {
            existingKey()
        } catch (_: GeneralSecurityException) {
            return@synchronized VaultRead.Unavailable
        } catch (_: IOException) {
            return@synchronized VaultRead.Unavailable
        } ?: return@synchronized VaultRead.Lost
        decrypt(bytes, key)
    }

    override fun write(access: StoredAccess) = synchronized(lock) {
        val atomic = AtomicFile(file)
        try {
            file.parentFile?.mkdirs()
            val record = encrypt(serialize(access), existingKey() ?: createKey())
            val out = atomic.startWrite()
            try {
                out.write(record)
                atomic.finishWrite(out)
            } catch (error: Throwable) {
                atomic.failWrite(out)
                throw error
            }
        } catch (error: Exception) {
            // Wrapped without the message: nothing in it can describe the secret.
            throw VaultWriteException(error)
        }
    }

    private fun decrypt(record: ByteArray, key: Key): VaultRead {
        return try {
            val input = DataInputStream(record.inputStream())
            if (input.readByte() != FORMAT_VERSION) return VaultRead.Lost
            val iv = ByteArray(IV_BYTES).also { input.readFully(it) }
            val cipherText = input.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(cipherText)
            VaultRead.Found(deserialize(plain))
        } catch (_: AEADBadTagException) {
            VaultRead.Lost
        } catch (_: BadPaddingException) {
            VaultRead.Lost
        } catch (_: JSONException) {
            VaultRead.Lost
        } catch (_: IOException) {
            // A truncated record (EOF) is corrupt, not transient.
            VaultRead.Lost
        } catch (_: GeneralSecurityException) {
            VaultRead.Unavailable
        }
    }

    private fun encrypt(plain: ByteArray, key: Key): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key) // the Keystore picks a fresh IV
        cipher.updateAAD(AAD)
        val cipherText = cipher.doFinal(plain)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeByte(FORMAT_VERSION.toInt())
            write(cipher.iv)
            write(cipherText)
        }
        return out.toByteArray()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): Key? = keyStore().getKey(keyAlias, null)

    private fun createKey(): Key {
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun serialize(access: StoredAccess): ByteArray {
        fun JSONObject.credential(name: String, value: AccessCredential?) {
            if (value != null) {
                put(name, JSONObject().put("device_id", value.deviceId).put("token", value.token))
            }
        }
        return JSONObject()
            .put("needs_reconnect", access.needsReconnect)
            .apply {
                credential("active", access.active)
                credential("pending", access.pending)
            }
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun deserialize(plain: ByteArray): StoredAccess {
        val json = JSONObject(String(plain, Charsets.UTF_8))
        fun credential(name: String): AccessCredential? {
            val node = json.optJSONObject(name) ?: return null
            return AccessCredential(node.getLong("device_id"), node.getString("token"))
        }
        return StoredAccess(
            active = credential("active"),
            pending = credential("pending"),
            needsReconnect = json.optBoolean("needs_reconnect", false),
        )
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "alva_sync_credential_v1"
        const val DEFAULT_FILE_NAME = "alva-access.bin"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val AAD = "alva-access-v1".toByteArray(Charsets.UTF_8)

        /** The record lives where no backup or device transfer reaches it. */
        fun defaultFile(context: android.content.Context): File =
            File(context.noBackupFilesDir, DEFAULT_FILE_NAME)
    }
}
