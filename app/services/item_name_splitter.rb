# Splits what was typed in the add field into item names: "leite, pão; ovos"
# → ["leite", "pão", "ovos"]. Separators are comma, semicolon and newline.
# Parts are stripped, blank ones dropped, repeats within the same text dropped
# (compared like the rest of the app: TextNormalizer) and at most MAX_NAMES kept.
class ItemNameSplitter
  SEPARATORS = /[,;\n]/
  MAX_NAMES = 30

  def self.call(text)
    text.to_s.split(SEPARATORS).map(&:strip).reject(&:blank?)
      .uniq { |name| TextNormalizer.call(name) }
      .first(MAX_NAMES)
  end
end
