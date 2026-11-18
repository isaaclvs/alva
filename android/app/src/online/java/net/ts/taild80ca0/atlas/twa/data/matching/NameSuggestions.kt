package net.ts.taild80ca0.atlas.twa.data.matching

/**
 * What the add field offers while a name is being typed: the Android twin of the PWA's `<datalist>`, filled by
 * Rails' `NameSuggestions` (`app/services/name_suggestions.rb`).
 *
 * The PWA hands every known name to the browser (`<input list=…>`) and the dropdown filters them as the person
 * types. Here the filtering is explicit and works on the name being typed — the last comma/semicolon/line segment
 * of the field, because one draft may carry several names (`ItemNameSplitter`):
 *
 * - matching compares [TextNormalizer.plain], so "pao" finds "Pão" and "LEITE" finds "leite";
 * - the match is a prefix, like the browser's dropdown, and a name already typed in full is not offered (there is
 *   nothing left to complete);
 * - one suggestion per normalized name, the household's spelling before the dictionary's (see
 *   `ServerSnapshot.suggestionNames`);
 * - sorted like the datalist (accent- and case-blind, then the name) and capped at [LIMIT].
 */
object NameSuggestions {
    /** How many names fit above the field without pushing the add button off the screen. */
    const val LIMIT = 5

    /** The name being typed: what follows the last separator of the draft, stripped (the separators of [ItemNameSplitter]). */
    fun segment(draft: String?): String {
        val text = draft.orEmpty()
        return RubyText.strip(text.substring(lastSeparator(text) + 1))
    }

    /**
     * [draft] with the name being typed replaced by [name]: the names before the last separator stay as typed, and a
     * comma or semicolon left glued to the next name gets a space, as the PWA's hint shows ("Leite, pão").
     */
    fun complete(draft: String?, name: String): String {
        val text = draft.orEmpty()
        val before = text.substring(0, lastSeparator(text) + 1)
        // Only a comma or semicolon needs the space: a line break already starts a new line.
        val needsSpace = before.isNotEmpty() && before.last() in INLINE_SEPARATORS
        return (if (needsSpace) "$before " else before) + name
    }

    /** The names of [known] that complete the name being typed, in the order the PWA's datalist shows them. */
    fun forDraft(draft: String?, known: List<String>, limit: Int = LIMIT): List<String> {
        val typed = segment(draft)
        val key = TextNormalizer.plain(typed)
        if (key.isEmpty() || limit <= 0) return emptyList()
        return known
            // A prefix, like the browser's dropdown. The name typed exactly (with its accents) adds nothing to
            // complete; typing "pao" still offers "Pão", which is one of the reasons the suggestion exists.
            .filter { TextNormalizer.plain(it).startsWith(key) && it != typed }
            .distinctBy { TextNormalizer.key(it) }
            .sortedWith(compareBy({ TextNormalizer.plain(it) }, { it }))
            .take(limit)
    }

    private fun lastSeparator(text: String): Int = text.indexOfLast { it in SEPARATORS }

    private val SEPARATORS = charArrayOf(',', ';', '\n')

    /** The separators that share a line with what follows: only a line break needs no space after it. */
    private val INLINE_SEPARATORS = charArrayOf(',', ';')
}
