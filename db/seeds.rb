# Idempotent: safe to run multiple times (bin/rails db:seed).

Household.find_or_create_by!(name: "Casa")

catalog = YAML.load_file(Rails.root.join("db/seeds/catalog.yml"))

catalog.each.with_index(1) do |(category_name, item_names), position|
  category = Category.find_or_initialize_by(name: category_name)
  category.update!(position: position)

  item_names.each do |item_name|
    item = CatalogItem.find_or_initialize_by(normalized_name: TextNormalizer.call(item_name))
    item.update!(name: item_name, category: category)
  end
end
