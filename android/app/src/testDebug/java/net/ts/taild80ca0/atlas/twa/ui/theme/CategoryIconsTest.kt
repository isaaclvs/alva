package net.ts.taild80ca0.atlas.twa.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The category icons and tones must be the PWA's: this test reads the real Ruby helper, the CSS and
 * the seeded catalog from the repository, so a category added to `db/seeds/catalog.yml` without an
 * icon — or an icon path copied wrongly into [CategoryIcons] — fails the build.
 */
class CategoryIconsTest {
    private fun repoFile(path: String): File =
        listOf("../../$path", "../$path", path).map(::File).firstOrNull { it.isFile }
            ?: error("$path not found from ${File(".").absoluteFile}; run the tests from the repository checkout")

    /** `"Hortifruti" => [ "M20 4…", "produce" ],` of ListItemsHelper::CATEGORY_ICONS. */
    private val rubyIcons: Map<String, Pair<String, String>> by lazy {
        val helper = repoFile("app/helpers/list_items_helper.rb").readText()
        val map = helper.substringAfter("CATEGORY_ICONS = {").substringBefore("}.freeze")
        Regex("\"([^\"]+)\" => \\[ \"([^\"]+)\", \"(\\w+)\" \\]").findAll(map).associate {
            it.groupValues[1] to (it.groupValues[2] to it.groupValues[3])
        }
    }

    private val cssTones: Set<String> by lazy {
        val css = repoFile("app/assets/tailwind/application.css").readText()
        Regex("""data-tone="(\w+)"""")
            .findAll(css.substringAfter(".category-icon svg"))
            .map { it.groupValues[1] }.toSet()
    }

    private val seededCategories: List<String> by lazy {
        repoFile("db/seeds/catalog.yml").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith(" ") }
            .map { it.removeSuffix(":") }
    }

    @Test
    fun everySeededCategoryHasAnIconOfItsOwn() {
        assertEquals(seededCategories.size, seededCategories.toSet().size)
        assertTrue(seededCategories.size >= 9)
        for (category in seededCategories) {
            val path = CategoryIcons.path(category)
            if (category == "Outros") {
                assertEquals("Outros is the fallback and has no hue", CategoryIcons.FALLBACK, path)
                assertEquals(CategoryTone.Other, CategoryIcons.tone(category))
            } else {
                assertNotEquals("$category falls back to the neutral icon", CategoryIcons.FALLBACK, path)
                assertNotEquals("$category falls back to the neutral tone", CategoryTone.Other, CategoryIcons.tone(category))
            }
        }
    }

    @Test
    fun iconsAndTonesAreTheSameOnBothSides() {
        assertEquals(seededCategories.toSet() - "Outros", rubyIcons.keys)
        for ((category, icon) in rubyIcons) {
            val (path, tone) = icon
            assertEquals("$category icon path", path, CategoryIcons.path(category))
            assertEquals("$category tone", enumValueOf<CategoryTone>(tone.replaceFirstChar { it.uppercase() }), CategoryIcons.tone(category))
            assertTrue("$category tone $tone is missing from the CSS", cssTones.contains(tone))
        }
    }

    @Test
    fun unknownAndMissingCategoriesUseTheNeutralFallback() {
        for (category in listOf(null, "Desconhecida", "")) {
            assertEquals(CategoryIcons.FALLBACK, CategoryIcons.path(category))
            assertEquals(CategoryTone.Other, CategoryIcons.tone(category))
        }
    }
}
