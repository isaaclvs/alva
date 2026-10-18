require "test_helper"

class ItemCategorizerTest < ActiveSupport::TestCase
  test "matches exact dictionary name" do
    assert_equal categories(:dairy), ItemCategorizer.call("Leite")
  end

  test "matches ignoring case, accents and extra spaces" do
    assert_equal categories(:produce), ItemCategorizer.call("maca")
    assert_equal categories(:produce), ItemCategorizer.call("  MAÇÃ ")
    assert_equal categories(:dairy), ItemCategorizer.call("LEITE")
  end

  test "matches plural forms of dictionary names" do
    assert_equal categories(:produce), ItemCategorizer.call("Bananas")
    assert_equal categories(:dairy), ItemCategorizer.call("leites")
  end

  test "falls back to Outros for unknown items" do
    assert_equal categories(:other), ItemCategorizer.call("Coisa desconhecida")
  end

  test "returns nil when fallback category is missing" do
    categories(:other).destroy!
    assert_nil ItemCategorizer.call("Coisa desconhecida")
  end

  test "learn adds a dictionary entry used by the next lookup" do
    entry = ItemCategorizer.learn("Ração do gato", categories(:produce))

    assert entry.learned?
    assert_equal "Ração do gato", entry.name
    assert_equal categories(:produce), ItemCategorizer.call("ração do gato")
    assert_equal categories(:produce), ItemCategorizer.call("Rações do gato")
  end

  test "learn moves an existing entry to the new category" do
    assert_no_difference "CatalogItem.count" do
      ItemCategorizer.learn("leites", categories(:produce))
    end

    entry = CatalogItem.find_by!(normalized_name: TextNormalizer.call("Leite"))
    assert_equal categories(:produce), entry.category
    assert entry.learned?
    assert_equal "Leite", entry.name
  end
end
