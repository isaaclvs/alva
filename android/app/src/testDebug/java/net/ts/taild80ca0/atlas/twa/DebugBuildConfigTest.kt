package net.ts.taild80ca0.atlas.twa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the debug install identity: it must be installable next to the
 * production APK and must never reuse the production application id.
 */
class DebugBuildConfigTest {
    @Test
    fun debugBuildUsesDevelopmentIdentity() {
        assertEquals("net.ts.taild80ca0.atlas.twa.debug", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.DEBUG)
        assertTrue(BuildConfig.VERSION_NAME.endsWith("-dev"))
    }
}
