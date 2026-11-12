package net.ts.taild80ca0.atlas.twa.data.matching

/**
 * The approximations Rails applies in `I18n.transliterate` (i18n gem,
 * `HashTransliterator::DEFAULT_APPROXIMATIONS`), which is what
 * `TextNormalizer` uses to drop accents: Latin letters with diacritics plus
 * the ones that do not decompose, such as ß → ss, æ → ae, ø → o, ł → l, œ → oe.
 *
 * It is a copy, not a Unicode algorithm: NFD plus stripping marks would
 * disagree with the server (it does not know ß, and it would silently fix
 * decomposed input that the server turns into "?"). `matching_corpus.json`
 * holds the same table; the Ruby suite asserts the corpus equals what I18n
 * ships and the Kotlin suite asserts this object equals the corpus, so a gem
 * upgrade that changes the table fails a test until both are updated.
 */
internal object Transliteration {
    /** What every code point I18n has no approximation for becomes. */
    const val UNKNOWN = "?"

    /** Code point → ASCII replacement, for code points above 0x7F. */
    val APPROXIMATIONS: Map<Int, String> = mapOf(
        0x00C0 to "A", // À
        0x00C1 to "A", // Á
        0x00C2 to "A", // Â
        0x00C3 to "A", // Ã
        0x00C4 to "A", // Ä
        0x00C5 to "A", // Å
        0x00C6 to "AE", // Æ
        0x00C7 to "C", // Ç
        0x00C8 to "E", // È
        0x00C9 to "E", // É
        0x00CA to "E", // Ê
        0x00CB to "E", // Ë
        0x00CC to "I", // Ì
        0x00CD to "I", // Í
        0x00CE to "I", // Î
        0x00CF to "I", // Ï
        0x00D0 to "D", // Ð
        0x00D1 to "N", // Ñ
        0x00D2 to "O", // Ò
        0x00D3 to "O", // Ó
        0x00D4 to "O", // Ô
        0x00D5 to "O", // Õ
        0x00D6 to "O", // Ö
        0x00D7 to "x", // ×
        0x00D8 to "O", // Ø
        0x00D9 to "U", // Ù
        0x00DA to "U", // Ú
        0x00DB to "U", // Û
        0x00DC to "U", // Ü
        0x00DD to "Y", // Ý
        0x00DE to "Th", // Þ
        0x00DF to "ss", // ß
        0x00E0 to "a", // à
        0x00E1 to "a", // á
        0x00E2 to "a", // â
        0x00E3 to "a", // ã
        0x00E4 to "a", // ä
        0x00E5 to "a", // å
        0x00E6 to "ae", // æ
        0x00E7 to "c", // ç
        0x00E8 to "e", // è
        0x00E9 to "e", // é
        0x00EA to "e", // ê
        0x00EB to "e", // ë
        0x00EC to "i", // ì
        0x00ED to "i", // í
        0x00EE to "i", // î
        0x00EF to "i", // ï
        0x00F0 to "d", // ð
        0x00F1 to "n", // ñ
        0x00F2 to "o", // ò
        0x00F3 to "o", // ó
        0x00F4 to "o", // ô
        0x00F5 to "o", // õ
        0x00F6 to "o", // ö
        0x00F8 to "o", // ø
        0x00F9 to "u", // ù
        0x00FA to "u", // ú
        0x00FB to "u", // û
        0x00FC to "u", // ü
        0x00FD to "y", // ý
        0x00FE to "th", // þ
        0x00FF to "y", // ÿ
        0x0100 to "A", // Ā
        0x0101 to "a", // ā
        0x0102 to "A", // Ă
        0x0103 to "a", // ă
        0x0104 to "A", // Ą
        0x0105 to "a", // ą
        0x0106 to "C", // Ć
        0x0107 to "c", // ć
        0x0108 to "C", // Ĉ
        0x0109 to "c", // ĉ
        0x010A to "C", // Ċ
        0x010B to "c", // ċ
        0x010C to "C", // Č
        0x010D to "c", // č
        0x010E to "D", // Ď
        0x010F to "d", // ď
        0x0110 to "D", // Đ
        0x0111 to "d", // đ
        0x0112 to "E", // Ē
        0x0113 to "e", // ē
        0x0114 to "E", // Ĕ
        0x0115 to "e", // ĕ
        0x0116 to "E", // Ė
        0x0117 to "e", // ė
        0x0118 to "E", // Ę
        0x0119 to "e", // ę
        0x011A to "E", // Ě
        0x011B to "e", // ě
        0x011C to "G", // Ĝ
        0x011D to "g", // ĝ
        0x011E to "G", // Ğ
        0x011F to "g", // ğ
        0x0120 to "G", // Ġ
        0x0121 to "g", // ġ
        0x0122 to "G", // Ģ
        0x0123 to "g", // ģ
        0x0124 to "H", // Ĥ
        0x0125 to "h", // ĥ
        0x0126 to "H", // Ħ
        0x0127 to "h", // ħ
        0x0128 to "I", // Ĩ
        0x0129 to "i", // ĩ
        0x012A to "I", // Ī
        0x012B to "i", // ī
        0x012C to "I", // Ĭ
        0x012D to "i", // ĭ
        0x012E to "I", // Į
        0x012F to "i", // į
        0x0130 to "I", // İ
        0x0131 to "i", // ı
        0x0132 to "IJ", // Ĳ
        0x0133 to "ij", // ĳ
        0x0134 to "J", // Ĵ
        0x0135 to "j", // ĵ
        0x0136 to "K", // Ķ
        0x0137 to "k", // ķ
        0x0138 to "k", // ĸ
        0x0139 to "L", // Ĺ
        0x013A to "l", // ĺ
        0x013B to "L", // Ļ
        0x013C to "l", // ļ
        0x013D to "L", // Ľ
        0x013E to "l", // ľ
        0x013F to "L", // Ŀ
        0x0140 to "l", // ŀ
        0x0141 to "L", // Ł
        0x0142 to "l", // ł
        0x0143 to "N", // Ń
        0x0144 to "n", // ń
        0x0145 to "N", // Ņ
        0x0146 to "n", // ņ
        0x0147 to "N", // Ň
        0x0148 to "n", // ň
        0x0149 to "'n", // ŉ
        0x014A to "NG", // Ŋ
        0x014B to "ng", // ŋ
        0x014C to "O", // Ō
        0x014D to "o", // ō
        0x014E to "O", // Ŏ
        0x014F to "o", // ŏ
        0x0150 to "O", // Ő
        0x0151 to "o", // ő
        0x0152 to "OE", // Œ
        0x0153 to "oe", // œ
        0x0154 to "R", // Ŕ
        0x0155 to "r", // ŕ
        0x0156 to "R", // Ŗ
        0x0157 to "r", // ŗ
        0x0158 to "R", // Ř
        0x0159 to "r", // ř
        0x015A to "S", // Ś
        0x015B to "s", // ś
        0x015C to "S", // Ŝ
        0x015D to "s", // ŝ
        0x015E to "S", // Ş
        0x015F to "s", // ş
        0x0160 to "S", // Š
        0x0161 to "s", // š
        0x0162 to "T", // Ţ
        0x0163 to "t", // ţ
        0x0164 to "T", // Ť
        0x0165 to "t", // ť
        0x0166 to "T", // Ŧ
        0x0167 to "t", // ŧ
        0x0168 to "U", // Ũ
        0x0169 to "u", // ũ
        0x016A to "U", // Ū
        0x016B to "u", // ū
        0x016C to "U", // Ŭ
        0x016D to "u", // ŭ
        0x016E to "U", // Ů
        0x016F to "u", // ů
        0x0170 to "U", // Ű
        0x0171 to "u", // ű
        0x0172 to "U", // Ų
        0x0173 to "u", // ų
        0x0174 to "W", // Ŵ
        0x0175 to "w", // ŵ
        0x0176 to "Y", // Ŷ
        0x0177 to "y", // ŷ
        0x0178 to "Y", // Ÿ
        0x0179 to "Z", // Ź
        0x017A to "z", // ź
        0x017B to "Z", // Ż
        0x017C to "z", // ż
        0x017D to "Z", // Ž
        0x017E to "z", // ž
        0x01EA to "O", // Ǫ
        0x01EB to "o", // ǫ
        0x01EC to "O", // Ǭ
        0x01ED to "o", // ǭ
        0x1E9E to "SS", // ẞ
    )

    /** Replaces every non-ASCII code point by its approximation, or [UNKNOWN] when there is none. */
    fun transliterate(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint < 0x80) out.append(codePoint.toChar()) else out.append(APPROXIMATIONS[codePoint] ?: UNKNOWN)
        }
        return out.toString()
    }
}
