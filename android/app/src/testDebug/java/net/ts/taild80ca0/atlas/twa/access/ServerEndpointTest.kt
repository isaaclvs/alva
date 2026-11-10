package net.ts.taild80ca0.atlas.twa.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerEndpointTest {
    @Test
    fun httpsIsAcceptedForAnyHost() {
        assertEquals("https://atlas.taild80ca0.ts.net", ServerEndpoint.parse("https://atlas.taild80ca0.ts.net").origin)
        assertEquals("https://atlas.taild80ca0.ts.net", ServerEndpoint.parse(" https://Atlas.taild80ca0.ts.net/ ").origin)
        assertEquals("https://alva.example:8443", ServerEndpoint.parse("https://alva.example:8443").origin)
    }

    @Test
    fun plainHttpIsOnlyAcceptedOnTheDevicesOwnLoopback() {
        assertEquals("http://127.0.0.1:3000", ServerEndpoint.parse("http://127.0.0.1:3000").origin)
        assertEquals("http://localhost:8080", ServerEndpoint.parse("http://localhost:8080/").origin)
        assertEquals("http://[::1]:3000", ServerEndpoint.parse("http://[::1]:3000").origin)
    }

    @Test
    fun plainHttpToARealOrLanHostIsNeverAccepted() {
        listOf(
            "http://atlas.taild80ca0.ts.net",
            "http://192.168.0.10:3000",
            "http://10.0.2.2:3000",
            "http://127.0.0.1.evil.example",
            "http://localhost.evil.example",
            "ftp://alva.example",
        ).forEach { assertNull(it, ServerEndpoint.parseOrNull(it)) }
    }

    @Test
    fun onlyABareOriginIsAccepted() {
        listOf(
            "https://user:pass@alva.example",
            "https://alva.example/api",
            "https://alva.example/?x=1",
            "https://alva.example/#frag",
            "alva.example",
            "",
            "https://",
        ).forEach { assertNull(it, ServerEndpoint.parseOrNull(it)) }
    }

    @Test
    fun parseRefusesWhatParseOrNullRefuses() {
        assertThrows(IllegalArgumentException::class.java) { ServerEndpoint.parse("http://atlas.example") }
    }
}
