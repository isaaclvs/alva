class TextNormalizer
  def self.call(text)
    I18n.transliterate(text.to_s).downcase.squish
  end
end
