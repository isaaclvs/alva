# Turns an item name into the key used for matching (categorization and
# duplicate detection): no accents, lowercase, single spaces, and each word
# reduced to a singular-ish stem so "Bananas" matches "Banana" and "Pães"
# matches "Pão".
#
# The stem only needs to be the same for the singular and plural forms of a
# word, not a real Portuguese word: "vinagre" and "vinagres" both become
# "vinagr".
class TextNormalizer
  # Applied in order; the first matching rule wins. Words shorter than
  # MIN_STEM_LENGTH are left alone ("gas", "sal").
  PLURAL_RULES = [
    [ /(oes|aes|aos)\z/, "ao" ], # limões, pães, irmãos → limão, pão, irmão
    [ /ais\z/, "al" ],            # cereais → cereal
    [ /eis\z/, "el" ],            # papéis, pastéis → papel, pastel
    [ /ns\z/, "m" ],              # bombons → bombom
    [ /s\z/, "" ]                 # bananas, ovos → banana, ovo
  ].freeze

  MIN_STEM_LENGTH = 4

  def self.call(text)
    I18n.transliterate(text.to_s).downcase.squish
      .split(" ").map { |word| stem(word) }.join(" ")
  end

  def self.stem(word)
    return word if word.length < MIN_STEM_LENGTH

    pattern, replacement = PLURAL_RULES.find { |rule_pattern, _| word.match?(rule_pattern) }
    word = word.sub(pattern, replacement) if pattern
    # "flores" → "flore" must meet "flor", and "vinagre" must meet "vinagres".
    word.sub(/(?<=[rz])e\z/, "")
  end
  private_class_method :stem
end
