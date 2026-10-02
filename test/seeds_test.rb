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
end
