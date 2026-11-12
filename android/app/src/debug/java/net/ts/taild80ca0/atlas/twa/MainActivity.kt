package net.ts.taild80ca0.atlas.twa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.material3.Surface
import net.ts.taild80ca0.atlas.twa.access.AccessGraph
import net.ts.taild80ca0.atlas.twa.access.AccessScreen
import net.ts.taild80ca0.atlas.twa.ui.AlvaTheme
import net.ts.taild80ca0.atlas.twa.ui.ShoppingScreens

/** Debug-only native client, online like the PWA; the production launcher remains the TWA. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val access = AccessGraph.houseAccess(this)
        val repository = AccessGraph.onlineRepository(this)
        val invalidation = AccessGraph.foregroundInvalidation(this)
        setContent {
            AlvaTheme {
                Surface {
                    AccessScreen(access = access, connected = { actions -> if (repository != null) ShoppingScreens(repository, accessActions = actions, invalidation = invalidation) })
                }
            }
        }
    }
}
