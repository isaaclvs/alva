package net.ts.taild80ca0.atlas.twa

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The front door of the static launcher shortcuts (`res/xml/shortcuts.xml`): no UI of its own, it only hands
 * [Shortcuts]'s intent for its URI to the screen that already implements it, and finishes. It exists because a
 * static shortcut's XML cannot carry extras, while the quick panel should not be exported to other apps.
 *
 * A device that is not paired yet needs nothing special: [MainActivity] shows its entry screen for the two list
 * shortcuts, and the quick panel opens it instead of asking for a name that could not be sent.
 */
class ShortcutsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shortcuts.intent(this, intent?.dataString)?.let(::startActivity)
        finish()
    }
}
