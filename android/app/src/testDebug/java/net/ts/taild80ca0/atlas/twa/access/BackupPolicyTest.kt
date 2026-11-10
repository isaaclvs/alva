package net.ts.taild80ca0.atlas.twa.access

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The backup policy lives in XML resources. These tests read the files that ship
 * in the debug build, so deleting an exclusion fails here (the instrumented test
 * additionally checks that the installed app really applies them).
 */
class BackupPolicyTest {
    private fun xml(name: String): Element {
        val file = File("src/debug/res/xml/$name")
        assertTrue("${file.path} must exist", file.exists())
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun excludes(parent: Element): Set<Pair<String, String>> {
        val result = mutableSetOf<Pair<String, String>>()
        var node: Node? = parent.firstChild
        while (node != null) {
            if (node is Element && node.tagName == "exclude") result += node.getAttribute("domain") to node.getAttribute("path")
            node = node.nextSibling
        }
        return result
    }

    private val credentialFiles = setOf(
        "root" to "no_backup/",
        "sharedpref" to "alva_quick_add.xml",
        "sharedpref" to "__androidx_security_crypto_encrypted_prefs__.xml",
    )

    @Test
    fun theVaultLivesInNoBackupAndTheRulesExcludeThatDirectory() {
        val vaultFile = File("no_backup", KeystoreCredentialVault.DEFAULT_FILE_NAME)
        assertEquals("no_backup", vaultFile.parent)
        assertTrue(("root" to "no_backup/") in excludes(xml("alva_backup_rules.xml")))
    }

    @Test
    fun fullBackupRulesExcludeEveryCredentialStore() {
        val root = xml("alva_backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        assertTrue(excludes(root).containsAll(credentialFiles))
    }

    @Test
    fun dataExtractionRulesExcludeEveryCredentialStoreFromCloudAndDeviceTransfer() {
        val root = xml("alva_data_extraction_rules.xml")
        assertEquals("data-extraction-rules", root.tagName)
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val node = root.getElementsByTagName(section).item(0) as Element
            assertTrue("$section must exclude the credentials", excludes(node).containsAll(credentialFiles))
        }
    }

    @Test
    fun theRoomDatabaseIsNotExcludedSoTheUnsentQueueCanSurviveARestore() {
        val all = excludes(xml("alva_backup_rules.xml")) + excludes(xml("alva_data_extraction_rules.xml"))
        assertFalse(all.any { it.first == "database" })
    }

    @Test
    fun cleartextIsOnlyAllowedForTheDevicesOwnLoopback() {
        val root = xml("alva_network_security_config.xml")
        val base = root.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val configs = root.getElementsByTagName("domain-config")
        assertEquals(1, configs.length)
        val config = configs.item(0) as Element
        assertEquals("true", config.getAttribute("cleartextTrafficPermitted"))
        val domains = (0 until config.getElementsByTagName("domain").length)
            .map { config.getElementsByTagName("domain").item(it).textContent.trim() }
        assertEquals(setOf("127.0.0.1", "localhost"), domains.toSet())
        assertFalse(root.getElementsByTagName("certificates").length > 0)
        assertFalse(root.getElementsByTagName("trust-anchors").length > 0)
    }
}
