package net.ts.taild80ca0.atlas.twa.online

import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PdfClientTest {
    private val server = MockWebServer().apply { start() }
    private var credential: AccessCredential? = AccessCredential(1, "test-only-token")
    private var refused = 0
    private val access = object : OnlineAccess {
        override suspend fun credential() = credential
        override suspend fun reportUnauthorized(credential: AccessCredential) { refused++ }
    }
    private fun client() = PdfClient(ServerEndpoint.parse(server.url("/").toString()), access)
    @After fun stop() { server.shutdown() }

    @Test fun pdfUsesBearerHeaderAndReturnsTheOriginalBytes() = runBlocking {
        val bytes = "%PDF-1.7\nLista de compras".toByteArray()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        assertArrayEquals(bytes, (client().download() as PdfResult.Ready).bytes)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/list.pdf", request.path)
        assertEquals("Bearer test-only-token", request.getHeader("Authorization"))
        assertEquals("application/pdf", request.getHeader("Accept"))
    }
    @Test fun authorizationFailureRequestsReconnectAndNeverSharesTheErrorBody() = runBlocking {
        for (code in listOf(401, 403)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("private server detail"))
            assertEquals(OnlineProblem.NO_ACCESS.message, (client().download() as PdfResult.Failed).message)
        }
        assertEquals(2, refused)
        credential = null
        assertTrue(client().download() is PdfResult.Failed)
        assertEquals(2, server.requestCount)
    }
    @Test fun redirectAndAnHtmlLoginPageAreNotTreatedAsPdf() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))
        assertTrue(client().download() is PdfResult.Failed)
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("<html>Sign in</html>"))
        assertTrue(client().download() is PdfResult.Failed)
    }
}
