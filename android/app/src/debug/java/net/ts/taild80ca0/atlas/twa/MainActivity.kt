package net.ts.taild80ca0.atlas.twa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import net.ts.taild80ca0.atlas.twa.access.AccessGraph
import net.ts.taild80ca0.atlas.twa.access.AccessScreen
import net.ts.taild80ca0.atlas.twa.ui.AlvaTheme
import net.ts.taild80ca0.atlas.twa.ui.ShoppingScreens

/** Debug-only offline client; the production launcher remains the TWA. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val access = AccessGraph.houseAccess(this)
        val store = AccessGraph.shoppingListStore(this)
        setContent {
            AlvaTheme {
                AccessScreen(access = access, connected = { ShoppingScreens(store) })
            }
        }
    }
}
