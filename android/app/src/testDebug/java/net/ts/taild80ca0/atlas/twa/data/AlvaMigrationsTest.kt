package net.ts.taild80ca0.atlas.twa.data

import java.io.File
import net.ts.taild80ca0.atlas.twa.data.db.AlvaMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fails the build when the schema version moves without its schema file and migration. */
class AlvaMigrationsTest {
    private val schemaDir = File("schemas/net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase")

    @Test
    fun migrationsFormAnUnbrokenChainFromVersion1ToTheCurrentVersion() {
        val steps = AlvaMigrations.ALL.sortedBy { it.startVersion }

        var expectedStart = 1
        steps.forEach { step ->
            assertEquals("gap before migration ${step.startVersion}->${step.endVersion}", expectedStart, step.startVersion)
            assertEquals("each step moves exactly one version", step.startVersion + 1, step.endVersion)
            expectedStart = step.endVersion
        }
        assertEquals(AlvaMigrations.CURRENT_VERSION, expectedStart)
    }

    @Test
    fun everyVersionHasItsExportedSchemaInGit() {
        for (version in 1..AlvaMigrations.CURRENT_VERSION) {
            val file = File(schemaDir, "$version.json")
            assertTrue("missing ${file.path}; build once and commit the generated schema", file.isFile)
        }
    }
}
