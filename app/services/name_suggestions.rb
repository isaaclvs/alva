# Names offered while typing in the add field: what the household has already
# written (any status, so history counts) plus the catalog. One suggestion per
# normalized name, so "Leite" and "leite" show up once, and the household's own
# spelling wins over the catalog's. Sorted ignoring case and accents.
class NameSuggestions
  def self.call(household)
    new(household).call
  end

  def initialize(household)
    @household = household
  end

  def call
    # Most recent first, so the spelling used last is the one that is kept.
    latest_ids = @household.list_items.group(:normalized_name).select("MAX(id)")
    household_names = @household.list_items.where(id: latest_ids).order(id: :desc).pluck(:normalized_name, :name)
    catalog_names = CatalogItem.pluck(:normalized_name, :name)

    (household_names + catalog_names)
      .uniq(&:first).map(&:last)
      .sort_by { |name| [ TextNormalizer.plain(name), name ] }
  end
end
