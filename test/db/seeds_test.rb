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

  test "keeps a learned category instead of the catalog's" do
    Rails.application.load_seed
    hygiene = Category.find_by!(name: "Higiene")
    leite = CatalogItem.find_by!(normalized_name: TextNormalizer.call("Leite"))
    leite.update!(category: hygiene, learned: true)

    Rails.application.load_seed

    assert_equal hygiene, leite.reload.category
  end

  test "updates entries that were not learned" do
    Rails.application.load_seed
    leite = CatalogItem.find_by!(normalized_name: TextNormalizer.call("Leite"))
    leite.update!(category: Category.find_by!(name: "Higiene"))

    Rails.application.load_seed

    assert_equal "Laticínios", leite.reload.category.name
  end
end
