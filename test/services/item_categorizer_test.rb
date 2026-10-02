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

  test "falls back to Outros for unknown items" do
    assert_equal categories(:other), ItemCategorizer.call("Coisa desconhecida")
  end

  test "returns nil when fallback category is missing" do
    categories(:other).destroy!
    assert_nil ItemCategorizer.call("Coisa desconhecida")
  end
end
