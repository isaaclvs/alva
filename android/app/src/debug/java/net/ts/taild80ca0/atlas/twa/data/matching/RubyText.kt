package net.ts.taild80ca0.atlas.twa.data.matching

/**
 * The few Ruby `String` behaviors the matching rules depend on. Kotlin's `trim()`
 * and `isBlank()` would also treat NBSP and other Unicode spaces as whitespace,
 * which Ruby's `strip` does not; the corpus has examples for the difference.
 */
internal object RubyText {
    /** `[[:space:]]` limited to ASCII: tab, line feed, vertical tab, form feed, carriage return, space. */
    fun isAsciiSpace(char: Char): Boolean = char == ' ' || char in '\t'..'\r'

    /** What `String#strip` removes: ASCII whitespace and NUL. */
    private fun isStripped(char: Char): Boolean = char == '\u0000' || isAsciiSpace(char)

    fun strip(text: String): String = text.trim { isStripped(it) }

    /** `String#blank?` in ActiveSupport: nothing but Unicode white space (`[[:space:]]`). */
    fun isBlank(text: String): Boolean = text.all { isUnicodeSpace(it) }

    private fun isUnicodeSpace(char: Char): Boolean =
        isAsciiSpace(char) || char == '\u0085' || char == ' ' || char == ' ' ||
            char in ' '..' ' || char == ' ' || char == ' ' ||
            char == ' ' || char == ' ' || char == '　'
}
