package net.ts.taild80ca0.atlas.twa.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.KeyStore
import java.util.UUID
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabaseFactory

/**
 * A real Keystore vault and a real Room database, both isolated per test (own key
 * alias, own files) so a test can never touch the credential of the installed app.
 */
class RealAccessStorage {
    val context: Context = ApplicationProvider.getApplicationContext()
    private val id = UUID.randomUUID().toString()
    val alias = "alva_test_$id"
    val vaultFile = File(context.noBackupFilesDir, "test-access-$id.bin")
    val databaseName = "test-access-$id.db"
    private val databases = mutableListOf<AlvaDatabase>()

    fun newVault(): KeystoreCredentialVault = KeystoreCredentialVault(vaultFile, alias)

    fun openDatabase(): AlvaDatabase = AlvaDatabaseFactory.create(context, databaseName).also { databases += it }

    fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.apply {
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    fun cleanup() {
        databases.forEach { if (it.isOpen) it.close() }
        context.deleteDatabase(databaseName)
        vaultFile.delete()
        File(vaultFile.path + ".new").delete()
        File(vaultFile.path + ".bak").delete()
        deleteKey()
    }
}
