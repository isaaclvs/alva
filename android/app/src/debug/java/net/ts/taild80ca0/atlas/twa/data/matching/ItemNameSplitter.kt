package net.ts.taild80ca0.atlas.twa.data.matching

/**
 * Kotlin twin of Rails' `ItemNameSplitter`: splits what was typed in the add
 * field into item names ("leite, pão; ovos" → leite, pão, ovos).
 *
 * Separators are comma, semicolon and line feed. Parts are stripped, blank ones
 * dropped, repeats within the text dropped (compared with [TextNormalizer]) and
 * at most [MAX_NAMES] kept. The cap belongs to the text field only: names that
 * come from somewhere else (the habituals panel) are added without it.
 */
object ItemNameSplitter {
    const val MAX_NAMES = 30

    fun split(text: String?): List<String> =
        text.orEmpty().split(',', ';', '\n')
            .mapNotNull { clean(it) }
            .distinctBy { TextNormalizer.key(it) }
            .take(MAX_NAMES)

    /** A name ready to be added: stripped, or null when nothing usable is left. */
    fun clean(name: String): String? = RubyText.strip(name).takeUnless { RubyText.isBlank(it) }
}
