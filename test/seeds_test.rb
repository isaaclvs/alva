require "test_helper"

class SeedsTest < ActiveSupport::TestCase
  CATALOG = YAML.load_file(Rails.root.join("db/seeds/catalog.yml"))

  setup do
    Rails.application.load_seed
  end

  test "creates the default household" do
    assert Household.exists?(name: "Casa")
  end

  test "is idempotent" do
    counts = -> { [ Household.count, Category.count, CatalogItem.count ] }
    before = counts.call
    Rails.application.load_seed
    assert_equal before, counts.call
  end

  test "creates every category in catalog order" do
    CATALOG.keys.each.with_index(1) do |name, position|
      assert_equal position, Category.find_by!(name: name).position
    end
  end

  test "Congelados sits between Açougue and Bebidas" do
    names = Category.order(:position).pluck(:name)
    index = names.index("Congelados")

    assert_equal "Açougue", names[index - 1]
    # Padaria and Mercearia come between; the next shopping aisle is Drinks.
    assert_operator names.index("Bebidas"), :>, index
    assert_equal %w[Sorvete Açaí Nuggets], CATALOG["Congelados"] & %w[Sorvete Açaí Nuggets]
  end

  test "polpas and frozen food land in Congelados, frios stay put" do
    {
      "Polpa de acerola" => "Congelados", "Polpa de cajá" => "Congelados",
      "Polpa de macurujá" => "Congelados", "Sorvete" => "Congelados",
      "Pizza congelada" => "Congelados", "Pão de queijo congelado" => "Congelados",
      "Nuggets" => "Congelados", "Presunto" => "Frios e congelados",
      "Mussarela" => "Frios e congelados"
    }.each do |name, category|
      assert_equal category, ItemCategorizer.call(name)&.name, name
    end
  end

  test "maps every catalog item to its category" do
    CATALOG.each do |category_name, item_names|
      item_names.each do |item_name|
        item = CatalogItem.find_by!(normalized_name: TextNormalizer.call(item_name))
        assert_equal category_name, item.category.name, "#{item_name} in wrong category"
      end
    end
  end

  test "has at least 5 items per category" do
    CATALOG.each do |category_name, item_names|
      assert_operator item_names.size, :>=, 5, "#{category_name} has fewer than 5 items"
    end
  end

  test "has no duplicate items after normalization" do
    normalized = CATALOG.values.flatten.map { |name| TextNormalizer.call(name) }
    assert_equal normalized.uniq.size, normalized.size
  end

  test "keeps the everyday Brazilian basket out of Outros" do
    # The first three waves of the roadmap issue #148: names that used to fall
    # through to the fallback and needed a manual fix.
    expected = {
      "Presunto" => "Frios e congelados", "Mussarela" => "Frios e congelados",
      "Sorvete" => "Congelados", "Salsicha" => "Frios e congelados",
      "Margarina" => "Laticínios", "Leite em pó" => "Laticínios",
      "Maionese" => "Mercearia", "Ketchup" => "Mercearia", "Cuscuz" => "Mercearia",
      "Tapioca" => "Mercearia", "Pipoca" => "Mercearia", "Fubá" => "Mercearia",
      "Mel" => "Mercearia", "Farofa branca" => "Mercearia", "Rosquinha de coco" => "Mercearia",
      "Polpa de acerola" => "Congelados", "Polpa de macurujá" => "Congelados",
      "Pano de chão" => "Limpeza",
      "Pimentão" => "Hortifruti", "Melancia" => "Hortifruti", "Abacaxi" => "Hortifruti",
      "Coentro" => "Hortifruti",
      "Papel toalha" => "Limpeza", "Lava-louças" => "Limpeza", "Álcool" => "Limpeza",
      "Cloro" => "Limpeza",
      "Absorvente" => "Higiene", "Fralda" => "Farmácia", "Dipirona" => "Farmácia",
      "Ração" => "Pet"
    }

    expected.each do |name, category|
      assert_equal category, ItemCategorizer.call(name)&.name, name
    end
  end

  test "has enough names in each category for the basket to land right" do
    assert_operator CATALOG.values.flatten.size, :>=, 250
  end

  test "every seeded category has an icon and a PDF color" do
    missing_icon = Category.pluck(:name) - ListItemsHelper::CATEGORY_ICONS.keys - [ ItemCategorizer::FALLBACK_CATEGORY ]
    missing_color = Category.pluck(:name) - ListPdf::CATEGORY_COLORS.keys - [ ItemCategorizer::FALLBACK_CATEGORY ]

    assert_empty missing_icon
    assert_empty missing_color
  end
end
