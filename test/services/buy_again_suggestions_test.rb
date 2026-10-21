require "test_helper"

class BuyAgainSuggestionsTest < ActiveSupport::TestCase
  setup do
    @household = households(:home)
    @clock = 10.days.ago
  end

  # Archives a purchased item named `name` into a new trip, `ago` in the past.
  def buy(name, ago: nil, category: nil, trip: nil)
    purchased_at = ago || (@clock += 1.hour)
    trip ||= @household.shopping_trips.create!(finished_at: purchased_at)
    # Built directly: archived history can legitimately repeat a name that is pending now.
    @household.list_items.new(name: name, normalized_name: TextNormalizer.call(name), category: category,
      status: :purchased, purchased_at: purchased_at, shopping_trip: trip)
      .tap { |item| item.save!(validate: false) }
  end

  def names(**options)
    BuyAgainSuggestions.call(@household, **options).map(&:name)
  end

  test "is empty without purchase history" do
    assert_empty BuyAgainSuggestions.call(@household)
  end

  test "ignores items that were never archived" do
    assert_empty BuyAgainSuggestions.call(@household) # fixtures: one pending, one purchased, none archived
  end

  test "ranks by the number of trips a name was bought in" do
    buy "Arroz"
    buy "Feijão"
    buy "Feijão"
    buy "Feijão"
    buy "Café"
    buy "Café"

    assert_equal %w[Feijão Café Arroz], names
  end

  test "counts trips, not rows, when a name shows up twice in the same trip" do
    trip = @household.shopping_trips.create!(finished_at: 3.days.ago)
    buy "Arroz", ago: 3.days.ago, trip: trip
    buy "Arroz", ago: 3.days.ago, trip: trip
    buy "Café", ago: 5.days.ago
    buy "Café", ago: 4.days.ago

    suggestions = BuyAgainSuggestions.call(@household)

    assert_equal %w[Café Arroz], suggestions.map(&:name)
    assert_equal [ 2, 1 ], suggestions.map(&:trips)
  end

  test "breaks ties by the most recent purchase" do
    buy "Arroz", ago: 5.days.ago
    buy "Café", ago: 1.day.ago
    buy "Feijão", ago: 3.days.ago

    assert_equal %w[Café Feijão Arroz], names
  end

  test "groups names after normalization and shows the latest spelling and category" do
    produce = categories(:produce)
    dairy = categories(:dairy)
    buy "maçã", ago: 5.days.ago, category: produce
    buy "Maçãs", ago: 2.days.ago, category: dairy
    buy "  MAÇA ", ago: 4.days.ago, category: produce

    suggestions = BuyAgainSuggestions.call(@household)

    assert_equal 1, suggestions.size
    assert_equal "Maçãs", suggestions.first.name
    assert_equal dairy, suggestions.first.category
    assert_equal 3, suggestions.first.trips
  end

  test "leaves out names currently pending on the list" do
    buy "Leite" # milk is pending in the fixtures
    buy "Arroz"

    assert_equal %w[Arroz], names
  end

  test "leaves out names currently purchased on the list" do
    buy "Detergente" # detergent is purchased, not archived, in the fixtures
    buy "Arroz"

    assert_equal %w[Arroz], names
  end

  test "comes back once the name leaves the list" do
    buy "Arroz"
    item = @household.list_items.create!(name: "arroz")
    assert_empty names

    item.destroy!
    assert_equal %w[Arroz], names
  end

  test "keeps only the top limit" do
    10.times { |i| buy "Item #{i}" }

    assert_equal 8, names.size
    assert_equal 3, names(limit: 3).size
    assert_equal [ "Item 9", "Item 8" ], names(limit: 2)
  end

  test "ignores other households' history" do
    other = Household.create!(name: "Outra")
    trip = other.shopping_trips.create!(finished_at: 1.day.ago)
    other.list_items.create!(name: "Segredo").update_columns(shopping_trip_id: trip.id, purchased_at: 1.day.ago)

    assert_not_includes names, "Segredo"
  end

  test "uses few queries" do
    buy "Arroz", category: categories(:produce)
    buy "Café"

    queries = []
    callback = ->(*, payload) { queries << payload[:sql] unless payload[:name] == "SCHEMA" }
    ActiveSupport::Notifications.subscribed(callback, "sql.active_record") { BuyAgainSuggestions.call(@household) }

    assert_operator queries.size, :<=, 3
  end
end
