# Idempotent: safe to run multiple times (bin/rails db:seed).

Household.find_or_create_by!(name: "Casa")

catalog = YAML.load_file(Rails.root.join("db/seeds/catalog.yml"))
dictionary_changed = false

# Categories are fixed, but the catalog renamed one: "Frios e congelados"
# became "Frios" when "Congelados" took the frozen food. Production never had
# the old name; a dev or test database seeded before the split does, so the
# rename happens in place — same row, id and public_id, so items and devices
# pointing at it keep working — before the loop below would create "Frios" as a
# second category. A second seed run finds nothing to rename.
# A local, not a constant: db:seed can run twice in the same process (tests) and
# a constant would warn about redefinition.
legacy_category_names = { "Frios e congelados" => "Frios" }

legacy_category_names.each do |legacy_name, name|
  legacy = Category.find_by(name: legacy_name)
  next if legacy.nil?

  legacy.update!(name: name)
  dictionary_changed = true
end

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

# Names that had no match before were added to "Outros" and stayed there. Only
# when the catalog actually changed is there anything new to fix, and a
# hand-picked category (Outros included) is in the dictionary as learned, so the
# household's own decisions are never overwritten. The revision comes from
# DictionaryChange.record! below, which already moves every household.
DictionaryRecategorizer.call_all(sync: false) if dictionary_changed

# The dictionary is shared by every household, so a real change moves its own
# revision and each household's. A deploy with no catalog change bumps nothing.
DictionaryChange.record! if dictionary_changed
