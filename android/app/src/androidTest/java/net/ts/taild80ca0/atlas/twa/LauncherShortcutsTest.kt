package net.ts.taild80ca0.atlas.twa

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

/**
 * The launcher shortcuts as the installed app publishes them (#158): the launcher activity declares three,
 * each names its own URI, and that URI becomes the intent of a screen that already exists.
 */
class LauncherShortcutsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** What each `<shortcut>` of `res/xml/shortcuts.xml` says, read from the installed resources. */
    private data class Declared(val id: String, val shortLabel: Int, val longLabel: Int, val icon: Int, val data: String, val action: String)

    private fun declared(): List<Declared> {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java), PackageManager.GET_META_DATA)
        val resId = info.metaData.getInt("android.app.shortcuts")
        assertTrue("the launcher activity must declare android.app.shortcuts", resId != 0)
        val parser = context.resources.getXml(resId)
        val shortcuts = mutableListOf<Declared>()
        var current: String? = null
        var label = 0
        var longLabel = 0
        var icon = 0
        var data: String? = null
        var action: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "shortcut") {
                current = parser.getAttributeValue(ANDROID, "shortcutId")
                label = parser.getAttributeResourceValue(ANDROID, "shortcutShortLabel", 0)
                longLabel = parser.getAttributeResourceValue(ANDROID, "shortcutLongLabel", 0)
                icon = parser.getAttributeResourceValue(ANDROID, "icon", 0)
            } else if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent") {
                data = parser.getAttributeValue(ANDROID, "data")
                action = parser.getAttributeValue(ANDROID, "action")
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "shortcut") {
                shortcuts += Declared(current!!, label, longLabel, icon, data!!, action!!)
            }
        }
        parser.close()
        return shortcuts
    }

    @Test fun theLauncherActivityDeclaresTheThreeShortcutsWithPortugueseLabelsAndIcons() {
        val shortcuts = declared()
        assertEquals(listOf("add_item", "shopping_list", "shopping_mode"), shortcuts.map { it.id })
        assertEquals(listOf("Adicionar item", "Lista", "Modo compras"),
            shortcuts.map { context.getString(it.shortLabel) })
        assertEquals(listOf("Adicionar um item à lista", "Abrir a lista de compras", "Iniciar as compras agora"),
            shortcuts.map { context.getString(it.longLabel) })
        shortcuts.forEach { shortcut ->
            assertTrue("${shortcut.id} needs a launcher icon", shortcut.icon != 0)
            context.getDrawable(shortcut.icon)
            assertEquals(Intent.ACTION_VIEW, shortcut.action)
        }
        assertEquals(listOf("alva-shortcut://add", "alva-shortcut://list", "alva-shortcut://shopping"),
            shortcuts.map { it.data })
    }

    @Test fun theSystemPublishesTheSameThreeShortcuts() {
        val shortcuts = context.getSystemService(ShortcutManager::class.java).manifestShortcuts
        assertEquals(setOf("add_item", "shopping_list", "shopping_mode"), shortcuts.map { it.id }.toSet())
        assertEquals(setOf("Adicionar item", "Lista", "Modo compras"), shortcuts.map { it.shortLabel.toString() }.toSet())
    }

    @Test fun everyShortcutUriBecomesTheIntentOfAScreenThatAlreadyExists() {
        val add = Shortcuts.intent(context, "alva-shortcut://add")!!
        assertEquals(QuickAddActivity::class.java.name, add.component?.className)
        assertTrue("an unpaired device must reach the entry screen",
            add.getBooleanExtra(QuickAddActivity.EXTRA_OPEN_APP_WHEN_UNPAIRED, false))

        val list = Shortcuts.intent(context, "alva-shortcut://list")!!
        assertEquals(MainActivity::class.java.name, list.component?.className)
        assertEquals(MainActivity.SCREEN_LIST, list.getStringExtra(MainActivity.EXTRA_SCREEN))
        assertEquals(false, list.getBooleanExtra(MainActivity.EXTRA_START_SHOPPING, false))

        val shopping = Shortcuts.intent(context, "alva-shortcut://shopping")!!
        assertEquals(MainActivity::class.java.name, shopping.component?.className)
        assertEquals(MainActivity.SCREEN_LIST, shopping.getStringExtra(MainActivity.EXTRA_SCREEN))
        assertTrue(shopping.getBooleanExtra(MainActivity.EXTRA_START_SHOPPING, false))
    }

    @Test fun theShortcutUriIsHandledByTheAppsOwnFrontDoorOnly() {
        val resolved = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_DEFAULT).setData(android.net.Uri.parse("alva-shortcut://list")), 0)
        assertEquals(1, resolved.size)
        assertEquals(ShortcutsActivity::class.java.name, resolved.single().activityInfo.name)
        assertTrue(resolved.single().activityInfo.exported)
        assertNull("a host the app does not own opens nothing", Shortcuts.intent(context, "alva-shortcut://unknown"))
        assertNull("a URI of another scheme opens nothing", Shortcuts.intent(context, "alva-dev://widget-design"))
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
