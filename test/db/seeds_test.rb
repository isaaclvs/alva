require "test_helper"

# db:seed runs on every deploy (alva-update), so it must be safe to repeat and
# must keep what the household taught the dictionary.
class SeedsTest < ActiveSupport::TestCase
  test "running the seeds twice creates everything once" do
    Rails.application.load_seed
    counts = [ Household.count, Category.count, CatalogItem.count ]

    Rails.application.load_seed

    assert_equal counts, [ Household.count, Category.count, CatalogItem.count ]
    assert CatalogItem.exists?(normalized_name: TextNormalizer.call("Pão"))
  end

  test "running the seeds twice keeps every public_id" do
    Rails.application.load_seed
    public_ids = [ Household, Category, CatalogItem ].flat_map { |model| model.order(:id).pluck(:public_id) }

    Rails.application.load_seed

    assert_equal public_ids, [ Household, Category, CatalogItem ].flat_map { |model| model.order(:id).pluck(:public_id) }
  end

  test "keeps a learned category instead of the catalog's" do
    Rails.application.load_seed
    hygiene = Category.find_by!(name: "Higiene")
    leite = CatalogItem.find_by!(normalized_name: TextNormalizer.call("Leite"))
    leite.update!(category: hygiene, learned: true)

    Rails.application.load_seed

    assert_equal hygiene, leite.reload.category
  end

  test "keeps a category learned from a correction, under any spelling, for the next item" do
    Rails.application.load_seed
    household = Household.find_by!(name: "Casa")
    pantry = Category.find_by!(name: "Mercearia")
    produce = Category.find_by!(name: "Hortifruti")

    tomato = household.add_item("Tomates").item
    assert tomato.correct(name: tomato.name, category_id: pantry.id)
    cherry = household.add_item("Tomate cereja").item
    assert cherry.correct(name: cherry.name, category_id: produce.id)
    [ tomato, cherry ].each(&:destroy!)

    assert_no_difference "CatalogItem.count" do
      Rails.application.load_seed
    end

    assert_equal pantry, household.add_item("tomate").item.category
    assert_equal produce, household.add_item("TOMATES CEREJA").item.category
  end

  test "updates entries that were not learned" do
    Rails.application.load_seed
    leite = CatalogItem.find_by!(normalized_name: TextNormalizer.call("Leite"))
    leite.update!(category: Category.find_by!(name: "Higiene"))

    Rails.application.load_seed

    assert_equal "Laticínios", leite.reload.category.name
  end
end
