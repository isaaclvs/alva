package net.ts.taild80ca0.atlas.twa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import net.ts.taild80ca0.atlas.twa.access.AccessGraph
import net.ts.taild80ca0.atlas.twa.access.AccessScreen
import net.ts.taild80ca0.atlas.twa.ui.ShoppingScreens
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaPalettes
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme
import net.ts.taild80ca0.atlas.twa.ui.theme.applyAlvaSystemBars

/** Debug-only native client, online like the PWA; the production launcher remains the TWA. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The saved theme and color preferences arrive with #109; until then, the PWA's defaults.
        val dark = true
        val color = AlvaColor.Teal
        applyAlvaSystemBars(AlvaPalettes.of(dark, color))
        val access = AccessGraph.houseAccess(this)
        val repository = AccessGraph.onlineRepository(this)
        val invalidation = AccessGraph.foregroundInvalidation(this)
        setContent {
            AlvaTheme(dark, color) {
                // Status and navigation bars follow the palette (the PWA's theme-color).
                SideEffect { applyAlvaSystemBars(AlvaPalettes.of(dark, color)) }
                Surface(color = MaterialTheme.colorScheme.background) {
                    AccessScreen(access = access, connected = { actions -> if (repository != null) ShoppingScreens(repository, accessActions = actions, invalidation = invalidation) })
                }
            }
        }
    }
}
