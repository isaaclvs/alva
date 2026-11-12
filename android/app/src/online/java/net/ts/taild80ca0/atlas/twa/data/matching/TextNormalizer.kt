package net.ts.taild80ca0.atlas.twa.data.matching

/**
 * Kotlin twin of Rails' `TextNormalizer`: the one key used on the device for
 * categorization and duplicate detection (screens and widget alike). Its results
 * are checked against the Ruby implementation by `matching_corpus.json`.
 *
 * It follows Ruby on purpose, quirks included (see the corpus notes): non-ASCII
 * characters without an approximation, such as NBSP or a combining accent, become
 * `?`; only ASCII whitespace separates words.
 */
object TextNormalizer {
    private const val MIN_STEM_LENGTH = 4

    /** Key for matching: no accents, lowercase, single spaces, each word reduced to a singular-ish stem. */
    fun key(text: String?): String =
        plain(text).split(' ').filter { it.isNotEmpty() }.joinToString(" ") { stem(it) }

    /** The same text without accents, case or extra spaces, but not stemmed (`TextNormalizer.plain`). */
    fun plain(text: String?): String = squish(Transliteration.transliterate(text.orEmpty()).asciiLowercase())

    /** Ruby's `squish` on ASCII text: runs of whitespace become one space, the edges are stripped (NUL included). */
    private fun squish(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (char in text) {
            if (RubyText.isAsciiSpace(char)) {
                pendingSpace = true
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(char)
            }
        }
        return RubyText.strip(out.toString())
    }

    private fun String.asciiLowercase(): String {
        val out = StringBuilder(length)
        for (char in this) out.append(if (char in 'A'..'Z') char + ('a' - 'A') else char)
        return out.toString()
    }

    /**
     * Applied in order, the first matching rule wins. Words shorter than
     * [MIN_STEM_LENGTH] are left alone ("gas", "sal"). The stem only needs to be
     * the same for the singular and the plural, not a real word.
     */
    private fun stem(word: String): String {
        if (word.length < MIN_STEM_LENGTH) return word

        val reduced = when {
            word.endsWith("oes") || word.endsWith("aes") || word.endsWith("aos") -> word.dropLast(3) + "ao" // limões, pães
            word.endsWith("ais") -> word.dropLast(3) + "al" // cereais → cereal
            word.endsWith("eis") -> word.dropLast(3) + "el" // papéis → papel
            word.endsWith("ns") -> word.dropLast(2) + "m" // bombons → bombom
            word.endsWith("s") -> word.dropLast(1) // bananas → banana
            else -> word
        }
        // "flores" → "flore" must meet "flor", and "vinagre" must meet "vinagres".
        return if (reduced.length >= 2 && reduced.last() == 'e' && reduced[reduced.length - 2].let { it == 'r' || it == 'z' }) {
            reduced.dropLast(1)
        } else {
            reduced
        }
    }
}
