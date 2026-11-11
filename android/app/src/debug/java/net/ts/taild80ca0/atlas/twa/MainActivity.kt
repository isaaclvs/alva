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

/** Debug-only offline client; the production launcher remains the TWA. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val access = AccessGraph.houseAccess(this)
        val store = AccessGraph.shoppingListStore(this)
        val sync = AccessGraph.syncController(this)
        val invalidation = AccessGraph.foregroundInvalidation(this)
        val conflicts = AccessGraph.conflictResolver(this)
        AccessGraph.recoverBackgroundSync(this)
        setContent {
            AlvaTheme {
                Surface {
                    AccessScreen(access = access, connected = { actions -> ShoppingScreens(store, accessActions = actions, sync = sync, invalidation = invalidation, conflicts = conflicts) })
                }
            }
        }
    }
}
