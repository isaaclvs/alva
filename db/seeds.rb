# Idempotent: safe to run multiple times (bin/rails db:seed).

Household.find_or_create_by!(name: "Casa")

catalog = YAML.load_file(Rails.root.join("db/seeds/catalog.yml"))
dictionary_changed = false

catalog.each.with_index(1) do |(category_name, item_names), position|
  category = Category.find_or_initialize_by(name: category_name)
  category.position = position
  if category.changed?
    category.save!
    dictionary_changed = true
  end

  item_names.each do |item_name|
    item = CatalogItem.find_or_initialize_by(normalized_name: TextNormalizer.call(item_name))
    # A category picked by hand in the app wins over the catalog.
    next if item.learned?

    item.name = item_name
    item.category = category
    if item.changed?
      item.save!
      dictionary_changed = true
    end
  end
end

# The dictionary is shared by every household, so a real change moves its own
# revision and each household's. A deploy with no catalog change bumps nothing.
DictionaryChange.record! if dictionary_changed
