package net.ts.taild80ca0.atlas.twa.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import net.ts.taild80ca0.atlas.twa.R
import org.junit.Assert.*
import org.junit.Test

class PdfShareTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Suppress("DEPRECATION")
    @Test fun chooserSharesAndOpensOnlyTheExportedContentUri() = runBlocking {
        val bytes = "%PDF-1.7\nLista".toByteArray()
        val chooser = PdfShare.chooser(context, bytes)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        val view = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.single() as Intent
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(uri, view.data)
        val unrelated = File.createTempFile("private-", ".txt", context.cacheDir)
        try {
            FileProvider.getUriForFile(context, context.getString(R.string.providerAuthority), unrelated)
            fail("Other cache files must stay private")
        } catch (_: IllegalArgumentException) { /* Only list_exports and the old splash directory are exposed. */ }
        finally { unrelated.delete() }
    }
}
