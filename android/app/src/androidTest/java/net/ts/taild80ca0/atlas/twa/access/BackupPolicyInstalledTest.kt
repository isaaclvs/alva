package net.ts.taild80ca0.atlas.twa.access

import android.content.pm.ApplicationInfo
import android.os.Build
import android.security.NetworkSecurityPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

/**
 * Checks the *installed* debug app, not just the source files: the Android
 * package manager must hold the backup rules and the loopback-only network policy.
 */
class BackupPolicyInstalledTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val info: ApplicationInfo = context.applicationInfo
    private val android = "http://schemas.android.com/apk/res/android"

    /**
     * The resource an attribute of the installed `<application>` points to, read from
     * the manifest of the APK on the device (ApplicationInfo does not expose these).
     */
    private fun applicationAttribute(name: String): Int {
        val parser = context.assets.openXmlResourceParser("AndroidManifest.xml")
        parser.use {
            var event = it.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && it.name == "application") {
                    return it.getAttributeResourceValue(android, name, 0)
                }
                event = it.next()
            }
        }
        return 0
    }

    private val fullBackupContent get() = applicationAttribute("fullBackupContent")
    private val dataExtractionRules get() = applicationAttribute("dataExtractionRules")

    private fun excludes(resourceId: Int, section: String?): Set<Pair<String, String>> {
        val parser = context.resources.getXml(resourceId)
        val found = mutableSetOf<Pair<String, String>>()
        var current: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                if (parser.name == "cloud-backup" || parser.name == "device-transfer") current = parser.name
                if (parser.name == "exclude" && (section == null || current == section)) {
                    found += parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path")
                }
            }
            event = parser.next()
        }
        return found
    }

    private val credentialStores = setOf(
        "root" to "no_backup/",
        "sharedpref" to "alva_quick_add.xml",
        "sharedpref" to "__androidx_security_crypto_encrypted_prefs__.xml",
    )

    @Test
    fun backupStaysAllowedButTheRulesAreInstalled() {
        assertTrue(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        assertNotEquals("full backup rules must be installed", 0, fullBackupContent)
    }

    @Test
    fun fullBackupRulesExcludeTheCredentialStores() {
        assertTrue(excludes(fullBackupContent, null).containsAll(credentialStores))
    }

    @Test
    fun dataExtractionRulesExcludeThemFromCloudBackupAndDeviceTransfer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val rules = dataExtractionRules
        assertNotEquals("data extraction rules must be installed", 0, rules)
        assertTrue(excludes(rules, "cloud-backup").containsAll(credentialStores))
        assertTrue(excludes(rules, "device-transfer").containsAll(credentialStores))
    }

    @Test
    fun theVaultFileIsInTheDirectoryThatTheRulesExclude() {
        val vaultFile = KeystoreCredentialVault.defaultFile(context)
        assertEquals(context.noBackupFilesDir, vaultFile.parentFile)
        assertEquals("no_backup", vaultFile.parentFile!!.name)
    }

    @Test
    fun cleartextIsPermittedOnlyOnLoopback() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue(policy.isCleartextTrafficPermitted("localhost"))
        assertFalse(policy.isCleartextTrafficPermitted("atlas.taild80ca0.ts.net"))
        assertFalse(policy.isCleartextTrafficPermitted("192.168.0.10"))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
    }

    @Test
    fun theInstalledAppIsTheDebugVariant() {
        assertEquals(
            "net.ts.taild80ca0.atlas.twa.debug",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
    }
}
