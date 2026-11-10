# Picks a category for a typed item name via exact lookup in the CatalogItem
# dictionary, falling back to "Outros". No AI, no fuzzy matching. The
# dictionary grows when the household corrects a category by hand (learn).
class ItemCategorizer
  FALLBACK_CATEGORY = "Outros"

  def self.call(name)
    catalog_item = CatalogItem.find_by(normalized_name: TextNormalizer.call(name))
    catalog_item&.category || Category.find_by(name: FALLBACK_CATEGORY)
  end

  # Remembers that items called `name` (or its plural/singular) belong to
  # `category`, so the next one lands there. Marked learned so db:seed keeps it.
  # Nothing is written when the entry already says exactly that, so
  # `entry.saved_changes` stays empty and the caller can tell the dictionary
  # did not change.
  def self.learn(name, category)
    entry = CatalogItem.find_or_initialize_by(normalized_name: TextNormalizer.call(name))
    entry.name = name if entry.new_record?
    entry.category = category
    entry.learned = true
    entry.save! if entry.changed?
    entry
  end
end
