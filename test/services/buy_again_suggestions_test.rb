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
      status: :purchased, purchased_at: purchased_at, shopping_trip: trip, public_id: SecureRandom.uuid)
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

    assert_equal BuyAgainSuggestions::LIMIT, names.size
    assert_equal 5, names.size
    assert_equal 3, names(limit: 3).size
    assert_equal [ "Item 9", "Item 8" ], names(limit: 2)
  end

  test "ignores other households' history" do
    other = Household.create!(name: "Outra")
    trip = other.shopping_trips.create!(finished_at: 1.day.ago)
    other.list_items.create!(name: "Segredo").update_columns(shopping_trip_id: trip.id, purchased_at: 1.day.ago)

    assert_not_includes names, "Segredo"
  end

  test "same-time ties use the latest row and preserve the purchase timestamp" do
    time = Time.utc(2026, 10, 1, 12, 30)
    buy "Pão", ago: time, category: categories(:produce)
    buy "Pães", ago: time, category: categories(:dairy)
    result = BuyAgainSuggestions.call(@household).first
    assert_equal "Pães", result.name
    assert_equal categories(:dairy), result.category
    assert_equal time, result.last_purchased_at
    assert_equal 2, result.trips
  end

  test "falls back to updated_at when historical purchase time is missing" do
    buy "Arroz", ago: 3.days.ago
    row = buy "Café", ago: 2.days.ago
    row.update_columns(purchased_at: nil, updated_at: 1.day.ago)
    assert_equal %w[Café Arroz], names
  end

  test "uses few queries" do
    buy "Arroz", category: categories(:produce)
    buy "Café"

    queries = []
    callback = ->(*, payload) { queries << payload[:sql] unless payload[:name] == "SCHEMA" }
    ActiveSupport::Notifications.subscribed(callback, "sql.active_record") { BuyAgainSuggestions.call(@household) }

    assert_operator queries.size, :<=, 3
  end

  # --- cadence (issue #163): median days between purchases, "probably running out" ---

  # Buys `name` once on each of the given dates, one trip each, on the house clock.
  def buy_on(name, *dates)
    dates.map { |date| buy name, ago: Time.zone.local(date.year, date.month, date.day, 10) }
  end

  test "computes the median interval between purchase days, rounded" do
    travel_to Time.zone.local(2026, 11, 18, 9) do
      buy_on "Arroz", Date.new(2026, 10, 5), Date.new(2026, 10, 15), Date.new(2026, 10, 29)

      suggestion = BuyAgainSuggestions.call(@household).sole

      assert_equal 3, suggestion.trips
      assert_equal 12, suggestion.cadence_days # intervals 10 and 14 → median 12
      assert_predicate suggestion, :due? # 20 days since the last purchase > 12
    end
  end

  test "the median is the middle gap with an odd number of intervals" do
    travel_to Time.zone.local(2026, 11, 18, 9) do
      buy_on "Café", Date.new(2026, 10, 1), Date.new(2026, 10, 3), Date.new(2026, 10, 9), Date.new(2026, 10, 17)

      assert_equal 6, BuyAgainSuggestions.call(@household).sole.cadence_days # gaps 2, 6, 8
    end
  end

  test "a name needs at least three purchase days before it has a cadence" do
    travel_to Time.zone.local(2026, 11, 18, 9) do
      buy_on "Arroz", Date.new(2026, 10, 1), Date.new(2026, 10, 5)

      suggestion = BuyAgainSuggestions.call(@household).sole

      assert_nil suggestion.cadence_days
      assert_not_predicate suggestion, :due?
    end
  end

  test "a name bought twice on the same day counts that day once" do
    travel_to Time.zone.local(2026, 11, 18, 9) do
      buy_on "Arroz", Date.new(2026, 10, 1), Date.new(2026, 10, 1), Date.new(2026, 10, 5), Date.new(2026, 10, 9)

      suggestion = BuyAgainSuggestions.call(@household).sole

      assert_equal 4, suggestion.trips
      assert_equal 4, suggestion.cadence_days # purchase days 1, 5, 9: one interval of 8 → 4 + 4
      assert_predicate suggestion, :due?
    end
  end

  test "two trips finished on the same day are one purchase day" do
    travel_to Time.zone.local(2026, 11, 18, 9) do
      day = Date.new(2026, 10, 1)
      buy "Arroz", ago: Time.zone.local(day.year, day.month, day.day, 9)
      buy "Arroz", ago: Time.zone.local(day.year, day.month, day.day, 18)
      buy_on "Arroz", Date.new(2026, 10, 5), Date.new(2026, 10, 9)

      assert_equal 4, BuyAgainSuggestions.call(@household).sole.cadence_days
    end
  end

  test "is not due on the very day the median interval is reached" do
    travel_to Time.zone.local(2026, 11, 18, 9) do
      buy_on "Arroz", Date.new(2026, 10, 25), Date.new(2026, 11, 2), Date.new(2026, 11, 10)

      suggestion = BuyAgainSuggestions.call(@household).sole

      assert_equal 8, suggestion.cadence_days
      assert_not_predicate suggestion, :due? # exactly 8 days later is not late yet
    end
  end

  test "is due the day after the median interval is reached" do
    travel_to Time.zone.local(2026, 11, 19, 9) do
      buy_on "Arroz", Date.new(2026, 10, 25), Date.new(2026, 11, 2), Date.new(2026, 11, 10)

      assert_predicate BuyAgainSuggestions.call(@household).sole, :due?
    end
  end

  test "counts purchase days on the house clock, not in UTC" do
    travel_to Time.zone.local(2026, 11, 18, 9) do
      # 01:00 UTC on the 2nd is still the evening of the 1st in São Paulo: the
      # same purchase day as 21:00 local.
      buy "Arroz", ago: Time.zone.local(2026, 10, 1, 21)
      buy "Arroz", ago: Time.utc(2026, 10, 2, 1)
      buy_on "Arroz", Date.new(2026, 10, 5), Date.new(2026, 10, 9)

      assert_equal 4, BuyAgainSuggestions.call(@household).sole.cadence_days # days 1, 5, 9
    end
  end

  test "a due name comes before the merely frequent ones, without growing past the limit" do
    travel_to Time.zone.local(2026, 11, 18, 9) do
      buy_on "Arroz", Date.new(2026, 10, 1), Date.new(2026, 10, 3), Date.new(2026, 10, 5)
      3.times { buy "Feijão" }

      suggestions = BuyAgainSuggestions.call(@household)

      assert_equal %w[Arroz Feijão], suggestions.map(&:name)
      assert_predicate suggestions.first, :due?
      assert_equal 1, BuyAgainSuggestions.call(@household, limit: 1).size
    end
  end

  test "a limit of zero asks for nothing and a negative limit is a bug" do
    buy "Arroz"

    assert_empty BuyAgainSuggestions.call(@household, limit: 0)
    error = assert_raises(ArgumentError) { BuyAgainSuggestions.call(@household, limit: -1) }
    assert_equal "limit must be nonnegative", error.message
  end

  test "the due ones are cut before the rest when the limit is small" do
    travel_to Time.zone.local(2026, 11, 18, 9) do
      buy_on "Arroz", Date.new(2026, 10, 1), Date.new(2026, 10, 3), Date.new(2026, 10, 5)
      8.times { |index| buy "Item #{index}" }

      names = BuyAgainSuggestions.call(@household).map(&:name)

      assert_equal "Arroz", names.first
      assert_equal BuyAgainSuggestions::LIMIT, names.size
    end
  end
end
