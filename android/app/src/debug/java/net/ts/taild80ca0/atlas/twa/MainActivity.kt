package net.ts.taild80ca0.atlas.twa

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import net.ts.taild80ca0.atlas.twa.access.*
import net.ts.taild80ca0.atlas.twa.online.*
import net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore
import net.ts.taild80ca0.atlas.twa.ui.*
import net.ts.taild80ca0.atlas.twa.ui.theme.*

/** Debug-only native client; the production launcher remains the TWA. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = DevicePreferencesStore(this)
        val access = AccessGraph.houseAccess(this)
        val repository = AccessGraph.onlineRepository(this)
        val invalidation = AccessGraph.foregroundInvalidation(this)
        val pdf = PdfClient(ServerEndpoint.parse(BuildConfig.SERVER_URL), OnlineAccess.of(access))
        // Do not draw a default theme or focus Adicionar before the device preferences arrive.
        var ready = false
        val gate = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (ready) window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return ready
            }
        }
        window.decorView.viewTreeObserver.addOnPreDrawListener(gate)
        lifecycleScope.launch {
            val initial = preferences.values.first()
            applyAlvaSystemBars(AlvaPalettes.of(initial.dark, initial.color))
            setContent {
                val saved by preferences.values.collectAsState(initial)
                AlvaTheme(saved.dark, saved.color) {
                    SideEffect { applyAlvaSystemBars(AlvaPalettes.of(saved.dark, saved.color)) }
                    Surface(color = MaterialTheme.colorScheme.background) {
                        AccessScreen(access = access, connected = { actions -> if (repository != null) ShoppingScreens(
                            repository, accessActions = actions, invalidation = invalidation,
                            preferences = saved, saveDark = preferences::dark, saveColor = preferences::color,
                            saveScreen = preferences::screen,
                            signOut = { if (access.signOut()) null else "Não foi possível sair agora. Tente de novo." },
                            exportPdf = {
                                when (val result = pdf.download()) {
                                    is PdfResult.Failed -> result.message
                                    is PdfResult.Ready -> try {
                                        startActivity(PdfShare.chooser(this@MainActivity, result.bytes)); null
                                    } catch (_: IOException) { "Não foi possível preparar o arquivo PDF. Tente de novo." }
                                    catch (_: ActivityNotFoundException) { "Nenhum aplicativo disponível para abrir ou compartilhar o PDF." }
                                }
                            },
                        ) })
                    }
                }
            }
            ready = true
            window.decorView.invalidate()
        }
    }
}
