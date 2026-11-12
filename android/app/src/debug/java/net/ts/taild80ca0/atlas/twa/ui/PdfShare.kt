package net.ts.taild80ca0.atlas.twa.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ts.taild80ca0.atlas.twa.R

object PdfShare {
    suspend fun chooser(context: Context, bytes: ByteArray): Intent = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "list_exports").apply { if (!isDirectory && !mkdirs()) throw java.io.IOException("Export directory unavailable") }
        // Keep recent exports usable by receivers while limiting old cache files to a day.
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
        val file = File.createTempFile("alva-lista-", ".pdf", directory)
        try { file.writeBytes(bytes) } catch (error: Exception) { file.delete(); throw error }
        val uri = FileProvider.getUriForFile(context, context.getString(R.string.providerAuthority), file)
        val clip = ClipData.newRawUri("Lista de compras", uri)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf"); clipData = clip; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Compartilhar PDF").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(view)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
