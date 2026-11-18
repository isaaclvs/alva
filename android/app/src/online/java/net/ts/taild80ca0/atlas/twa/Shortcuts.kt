package net.ts.taild80ca0.atlas.twa

import android.content.Context
import android.content.Intent

/** Where a launcher shortcut of `res/xml/shortcuts.xml` lands. */
enum class ShortcutDestination {
    /** The quick panel of the "Adicionar" widget. */
    ADD_ITEM,

    /** The Lista screen, like the shopping list widget. */
    LIST,

    /** The Lista screen already in shopping mode, like tapping "Iniciar compras". */
    SHOPPING_MODE,
}

/**
 * The three static launcher shortcuts (long press on the Alva icon) and how their URI maps to a screen.
 *
 * A static shortcut's `<intent>` cannot carry extras, so `shortcuts.xml` gives each one a URI of [SCHEME] and
 * [ShortcutsActivity] turns it into the typed intent the screens already understand. Every destination reuses an
 * existing path — the quick panel of the widget, Lista, and Lista's own "Iniciar compras" — so a shortcut never
 * invents a fourth way into the app. [destination] is plain string parsing, tested without Android.
 */
object Shortcuts {
    const val SCHEME = "alva-shortcut"

    /** Host of the "Adicionar item" shortcut, paired with [ShortcutDestination.ADD_ITEM]. */
    const val ADD_ITEM = "add"

    /** Host of the "Lista" shortcut, paired with [ShortcutDestination.LIST]. */
    const val LIST = "list"

    /** Host of the "Modo compras" shortcut, paired with [ShortcutDestination.SHOPPING_MODE]. */
    const val SHOPPING_MODE = "shopping"

    /** The destination a shortcut URI names, or `null` for anything this app does not own. */
    fun destination(data: String?): ShortcutDestination? {
        val prefix = "$SCHEME://"
        if (data == null || !data.startsWith(prefix)) return null
        return when (data.substring(prefix.length).substringBefore('/').substringBefore('?')) {
            ADD_ITEM -> ShortcutDestination.ADD_ITEM
            LIST -> ShortcutDestination.LIST
            SHOPPING_MODE -> ShortcutDestination.SHOPPING_MODE
            else -> null
        }
    }

    /** The intent a shortcut URI asks for, or `null` for anything this app does not own. */
    fun intent(context: Context, data: String?): Intent? = when (destination(data)) {
        ShortcutDestination.ADD_ITEM -> Intent(context, QuickAddActivity::class.java)
            .putExtra(QuickAddActivity.EXTRA_OPEN_APP_WHEN_UNPAIRED, true)
        ShortcutDestination.LIST -> Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_LIST)
        ShortcutDestination.SHOPPING_MODE -> Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_LIST)
            .putExtra(MainActivity.EXTRA_START_SHOPPING, true)
        null -> null
    }
}
