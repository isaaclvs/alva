require "test_helper"

class NameSuggestionsTest < ActiveSupport::TestCase
  setup { @household = households(:home) }

  test "includes catalog names and the household's own names" do
    @household.list_items.create!(name: "Coisa nova")

    suggestions = NameSuggestions.call(@household)

    assert_includes suggestions, "Banana"
    assert_includes suggestions, "Coisa nova"
    assert_includes suggestions, "Detergente" # purchased still counts
  end

  test "lists each normalized name once" do
    @household.list_items.create!(name: "banana")
    @household.list_items.create!(name: "Maçãs")

    suggestions = NameSuggestions.call(@household)

    assert_equal 1, suggestions.count { |name| TextNormalizer.call(name) == "banana" }
    assert_equal 1, suggestions.count { |name| TextNormalizer.call(name) == TextNormalizer.call("Maçã") }
    assert_equal suggestions.uniq { |name| TextNormalizer.call(name) }, suggestions
  end

  test "household spelling wins over the catalog's" do
    @household.list_items.create!(name: "banana")
    @household.list_items.create!(name: "Maçãs")

    suggestions = NameSuggestions.call(@household)

    assert_includes suggestions, "banana"
    assert_not_includes suggestions, "Banana"
    assert_includes suggestions, "Maçãs"
    assert_not_includes suggestions, "Maçã"
  end

  test "latest household spelling wins when the household used several" do
    @household.list_items.create!(name: "pão").toggle_purchased!
    @household.list_items.create!(name: "Pão")

    suggestions = NameSuggestions.call(@household)

    assert_includes suggestions, "Pão"
    assert_not_includes suggestions, "pão"
  end

  test "sorts ignoring case and accents" do
    @household.list_items.create!(name: "água")
    @household.list_items.create!(name: "abacate")
    @household.list_items.create!(name: "Zebra")

    suggestions = NameSuggestions.call(@household)

    assert_equal [ "abacate", "água" ], suggestions.first(2)
    assert_equal "Zebra", suggestions.last
  end

  test "ignores other households' items" do
    other = Household.create!(name: "Outra")
    other.list_items.create!(name: "Segredo")

    assert_not_includes NameSuggestions.call(@household), "Segredo"
  end
end
