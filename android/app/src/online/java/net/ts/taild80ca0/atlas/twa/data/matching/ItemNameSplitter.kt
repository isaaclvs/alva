package net.ts.taild80ca0.atlas.twa.data.matching

/**
 * Kotlin twin of Rails' `ItemNameSplitter`: splits what was typed in the add
 * field into item names ("leite, pão; ovos" → leite, pão, ovos).
 *
 * Separators are comma, semicolon and line feed. Parts are stripped, blank ones
 * dropped, each cut at [MAX_NAME_LENGTH] (the same limit `ListItem` validates,
 * so a pasted paragraph never becomes a queued command carrying a huge name),
 * repeats within the text dropped (compared with [TextNormalizer]) and at most
 * [MAX_NAMES] kept. The cap of names belongs to the text field only: names that
 * come from somewhere else (the habituals panel) are added without it.
 */
object ItemNameSplitter {
    const val MAX_NAMES = 30

    /**
     * `ListItem::MAX_NAME_LENGTH`: a name longer than this is refused by the server
     * (`invalid_name`), so the device cuts it before sending. Counted in characters
     * (code points), like Ruby's `String#[0, n]`.
     */
    const val MAX_NAME_LENGTH = 100

    fun split(text: String?): List<String> =
        text.orEmpty().split(',', ';', '\n')
            .mapNotNull { clean(it) }
            .distinctBy { TextNormalizer.key(it) }
            .take(MAX_NAMES)

    /**
     * The text of the field with every name cut at [MAX_NAME_LENGTH], separators left
     * as typed: the twin of the PWA's `name_limit_controller.js`, so the field stops
     * growing instead of letting the person queue a name the server would refuse. [clean]
     * still applies the rule when a name reaches the list, whatever path it came from.
     */
    fun limitDraft(text: String?): String {
        val source = text.orEmpty()
        val out = StringBuilder(source.length)
        var length = 0
        var index = 0
        while (index < source.length) {
            val codePoint = source.codePointAt(index)
            val width = Character.charCount(codePoint)
            when {
                isSeparator(codePoint) -> { length = 0; out.appendRange(source, index, index + width) }
                length < MAX_NAME_LENGTH -> { length += 1; out.appendRange(source, index, index + width) }
            }
            index += width
        }
        return out.toString()
    }

    private fun isSeparator(codePoint: Int) = codePoint == ','.code || codePoint == ';'.code || codePoint == '\n'.code

    /** One name (the correction field) cut at [MAX_NAME_LENGTH], still as typed otherwise. */
    fun limitName(text: String?): String = takeCharacters(text.orEmpty(), MAX_NAME_LENGTH)

    /** A name ready to be added: stripped and cut at [MAX_NAME_LENGTH], or null when nothing usable is left. */
    fun clean(name: String): String? {
        val stripped = RubyText.strip(name)
        if (RubyText.isBlank(stripped)) return null
        return RubyText.strip(takeCharacters(stripped, MAX_NAME_LENGTH)).takeUnless { RubyText.isBlank(it) }
    }

    private fun takeCharacters(text: String, limit: Int): String {
        if (text.codePointCount(0, text.length) <= limit) return text
        return text.substring(0, text.offsetByCodePoints(0, limit))
    }
}
