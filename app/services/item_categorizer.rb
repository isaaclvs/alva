# Picks a category for a typed item name via exact lookup in the CatalogItem
# dictionary, falling back to "Outros". No AI, no fuzzy matching.
class ItemCategorizer
  FALLBACK_CATEGORY = "Outros"

  def self.call(name)
    # Exact match only. Extension point: fuzzy matching or learning from
    # manual recategorizations would plug in here, before the fallback.
    catalog_item = CatalogItem.find_by(normalized_name: TextNormalizer.call(name))
    catalog_item&.category || Category.find_by(name: FALLBACK_CATEGORY)
  end
end
