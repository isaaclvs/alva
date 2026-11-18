package net.ts.taild80ca0.atlas.twa.widgets

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quick add widget and the quick add panel draw no color of their own: every piece is either a
 * white mask (tinted at runtime by `WidgetRuntime.palette()` / `AlvaPalettes`) or left to the code
 * that names a token. This test reads the real resources, like PwaThemeParityTest reads the CSS, so a
 * hard-coded pink stops compiling the suite instead of only showing up on a phone.
 */
class WidgetThemeTest {
    private fun repoFile(path: String): File =
        listOf("../../$path", "../$path", path).map(::File).firstOrNull { it.isFile }
            ?: error("$path not found from ${File(".").absoluteFile}; run the tests from the repository checkout")

    private fun read(path: String) = repoFile(path).readText()

    /** Everything the widget and the panel inflate, plus their drawables and the dialog window. */
    private val themedFiles = listOf(
        "android/app/src/main/res/layout/quick_add_widget.xml",
        "android/app/src/main/res/layout/quick_add_sheet.xml",
        "android/app/src/main/res/values/quick_add_styles.xml",
        "android/app/src/main/res/drawable/quick_add_widget_bg.xml",
        "android/app/src/main/res/drawable/quick_add_widget_border.xml",
        "android/app/src/online/res/drawable/widget_tile.xml",
        "android/app/src/online/res/drawable/widget_circle.xml",
        "android/app/src/online/res/drawable/widget_checkbox.xml",
    )

    /** `#RGB`, `#RRGGBB` and `#AARRGGBB`: any of them is a color the palette cannot change. */
    private val literalColor = Regex("""#[0-9A-Fa-f]{6,8}(?![0-9A-Za-z])""")

    @Test fun theWidgetAndThePanelDeclareNoLiteralColor() {
        for (path in themedFiles) {
            val found = literalColor.findAll(read(path)).map { it.value }.toList()
            assertEquals("$path must take its colors from the palette, found $found", emptyList<String>(), found)
        }
    }

    @Test fun theSharedWidgetShapesAreWhiteMasksTheProviderCanTint() {
        for (path in listOf(
            "android/app/src/main/res/drawable/quick_add_widget_bg.xml",
            "android/app/src/online/res/drawable/widget_tile.xml",
            "android/app/src/online/res/drawable/widget_circle.xml",
            "android/app/src/online/res/drawable/widget_checkbox.xml",
        )) {
            val text = read(path)
            assertTrue("$path must be a white mask", text.contains("@android:color/white"))
            assertFalse("$path must not paint a themed color", text.contains("@color/") || text.contains("#"))
        }
        // The border carries only the hairline: the provider tints that, and the fill stays clear.
        val border = read("android/app/src/main/res/drawable/quick_add_widget_border.xml")
        assertTrue(border.contains("android:color=\"@android:color/transparent\""))
        assertTrue(border.contains("android:color=\"@android:color/white\""))
    }

    /**
     * A raw dp in a radius, margin, padding or control size is a number the shared scale cannot
     * follow. `0dp` is the one exception: it is a weight/constraint, not a measure.
     */
    private val literalDp = Regex("""android:(layout_margin\w*|padding\w*|minWidth|minHeight|layout_width|layout_height|corners?|radius)="(\d+)dp"""")

    @Test fun theWidgetAndThePanelReadTheirMeasuresFromTheDimensionTokens() {
        for (path in themedFiles) {
            val found = literalDp.findAll(read(path))
                .filterNot { it.groupValues[2] == "0" }
                .map { "${it.groupValues[1]}=${it.groupValues[2]}dp" }.toList()
            assertEquals("$path must name an alva_* dimension token, found $found", emptyList<String>(), found)
        }
    }

    @Test fun everyDimensionTheWidgetUsesIsDeclaredInOneScale() {
        val dimen = read("android/app/src/main/res/values/alva_dimens.xml")
        val declared = Regex("""<dimen name="([\w]+)">([\d.]+)dp</dimen>""").findAll(dimen).associate { it.groupValues[1] to it.groupValues[2].toFloat() }
        val used = themedFiles.flatMap { path -> Regex("""@dimen/([\w]+)""").findAll(read(path)).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue("the themed files must use tokens: $used", used.isNotEmpty())
        for (name in used) assertTrue("@dimen/$name is not in alva_dimens.xml", declared.containsKey(name))
        // The scales this change names: the widget keeps the pill corner, the panel the sheet corner.
        assertTrue("android/app/src/main/res/drawable/quick_add_widget_bg.xml must use the pill radius",
            read("android/app/src/main/res/drawable/quick_add_widget_bg.xml").contains("@dimen/alva_radius_pill"))
        assertEquals(28f, declared.getValue("alva_radius_sheet"), 0.01f)
    }
}
