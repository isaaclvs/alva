# Splits what was typed in the add field into item names: "leite, pão; ovos"
# → ["leite", "pão", "ovos"]. Separators are comma, semicolon and newline.
# Parts are stripped, blank ones dropped, each cut at MAX_NAME_LENGTH (the same
# limit ListItem validates, so a pasted paragraph never becomes a row), repeats
# within the same text dropped (compared like the rest of the app:
# TextNormalizer) and at most MAX_NAMES kept.
#
# MAX_NAME_LENGTH counts characters, not bytes, and the cut happens before the
# repeats are dropped: two names that differ only past the limit are the same
# name. The Android twin (ItemNameSplitter.kt) applies the exact same rule; the
# shared corpus pins both.
class ItemNameSplitter
  SEPARATORS = /[,;\n]/
  MAX_NAMES = 30
  MAX_NAME_LENGTH = 100

  def self.call(text)
    text.to_s.split(SEPARATORS).map { |part| clean(part) }.compact
      .uniq { |name| TextNormalizer.call(name) }
      .first(MAX_NAMES)
  end

  # One part of the text, ready to be an item name: stripped and cut at
  # MAX_NAME_LENGTH (the cut can leave a trailing space), or nil when nothing
  # usable is left.
  def self.clean(part)
    name = part.to_s.strip
    return if name.blank?

    name = name[0, MAX_NAME_LENGTH].strip
    name if name.present?
  end
end
