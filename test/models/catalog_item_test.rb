require "test_helper"

class CatalogItemTest < ActiveSupport::TestCase
  test "valid with name and category" do
    assert catalog_items(:banana).valid?
  end

  test "requires name" do
    item = CatalogItem.new(name: "", category: categories(:produce))
    assert_not item.valid?
    assert_includes item.errors[:name], "can't be blank"
  end

  test "requires category" do
    item = CatalogItem.new(name: "Maçã")
    assert_not item.valid?
    assert_includes item.errors[:category], "must exist"
  end

  test "normalizes name before validation" do
    item = CatalogItem.create!(name: "  Pão de Açúcar ", category: categories(:produce))
    assert_equal "pao de acucar", item.normalized_name
  end

  test "normalized name must be unique" do
    item = CatalogItem.new(name: "BANANA", category: categories(:produce))
    assert_not item.valid?
    assert_includes item.errors[:normalized_name], "has already been taken"
  end

  test "belongs to category listed in its catalog items" do
    assert_includes categories(:produce).catalog_items, catalog_items(:banana)
  end
end
