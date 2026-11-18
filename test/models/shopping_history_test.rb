require "test_helper"

class ShoppingHistoryTest < ActiveSupport::TestCase
  setup { @household = households(:home) }

  def finished_trip(at, items:)
    trip = @household.shopping_trips.create!(finished_at: at)
    items.each do |item|
      @household.list_items.create!(name: item, status: :purchased, purchased_at: at, shopping_trip: trip)
    end
    trip
  end

  test "no history is an empty page without a month" do
    page = ShoppingHistory.call(@household)

    assert_nil page.month
    assert_empty page.months
    assert_empty page.trips
    assert page.empty?
    assert_nil page.older_month
    assert_nil page.newer_month
  end

  test "the newest month opens first, trips newest first" do
    older = finished_trip(Time.zone.local(2026, 10, 3, 10), items: [ "Arroz" ])
    newer = finished_trip(Time.zone.local(2026, 11, 12, 18), items: [ "Sabão" ])
    latest = finished_trip(Time.zone.local(2026, 11, 15, 9), items: [ "Pão" ])

    page = ShoppingHistory.call(@household)

    assert_equal "2026-11", page.month.key
    assert_equal [ latest, newer ], page.trips.map(&:trip)
    assert_equal [ "2026-11", "2026-10" ], page.months.map(&:key)
    assert_equal [ 2, 1 ], page.months.map(&:trip_count)
    assert_equal "2026-10", page.older_month.key
    assert_nil page.newer_month
    assert_includes [ older, newer, latest ], page.trips.first.trip
  end

  test "a page holds one month and points to the ones beside it" do
    finished_trip(Time.zone.local(2026, 9, 20, 10), items: [ "Café" ])
    finished_trip(Time.zone.local(2026, 10, 3, 10), items: [ "Arroz" ])
    finished_trip(Time.zone.local(2026, 11, 12, 18), items: [ "Sabão" ])

    page = ShoppingHistory.call(@household, month: "2026-10")

    assert_equal "2026-10", page.month.key
    assert_equal [ "Arroz" ], page.trips.flat_map(&:names)
    assert_equal "2026-09", page.older_month.key
    assert_equal "2026-11", page.newer_month.key
  end

  test "a month without trips falls back to the newest one" do
    finished_trip(Time.zone.local(2026, 11, 12, 18), items: [ "Sabão" ])

    assert_equal "2026-11", ShoppingHistory.call(@household, month: "2026-02").month.key
    assert_equal "2026-11", ShoppingHistory.call(@household, month: "not-a-month").month.key
    assert_equal "2026-11", ShoppingHistory.call(@household, month: nil).month.key
  end

  test "a trip finished late at night belongs to that evening, not to UTC's next day" do
    # 22:30 in São Paulo (UTC-3) is already 01:30 of the next day in UTC.
    trip = finished_trip(Time.zone.local(2026, 10, 31, 22, 30), items: [ "Sabão" ])

    page = ShoppingHistory.call(@household)

    assert_equal "2026-10", page.month.key
    assert_equal [ trip ], page.trips.map(&:trip)
  end

  test "the trips are grouped by category like the list, uncategorized last" do
    trip = @household.shopping_trips.create!(finished_at: Time.zone.local(2026, 11, 12, 18))
    produce = @household.list_items.create!(name: "Banana", category: categories(:produce), status: :purchased, shopping_trip: trip)
    dairy = @household.list_items.create!(name: "Sabão", category: categories(:dairy), status: :purchased, shopping_trip: trip)
    # Uncategorized ("Sem categoria"), unlike "Outros", only survives as nil.
    no_category = @household.list_items.create!(name: "Coisa", status: :purchased, shopping_trip: trip)
    no_category.update_columns(category_id: nil)
    @household.list_items.create!(name: "Água", category: categories(:dairy), status: :purchased, shopping_trip: trip)

    entry = ShoppingHistory.call(@household).trips.first

    assert_equal [ categories(:produce), categories(:dairy), nil ], entry.groups.map(&:first)
    assert_equal [ produce ], entry.groups.first.last
    assert_equal [ "Água", "Sabão" ], entry.groups.second.last.map(&:name)
    assert_equal [ no_category ], entry.groups.last.last
    assert_equal 4, entry.items_count
    assert_equal [ "Banana", "Água", "Sabão", "Coisa" ], entry.names
  end

  test "another household's trips and items stay out" do
    other = Household.create!(name: "Outra casa")
    other_trip = other.shopping_trips.create!(finished_at: Time.zone.local(2026, 11, 12, 18))
    other.list_items.create!(name: "Sabão", status: :purchased, shopping_trip: other_trip)
    finished_trip(Time.zone.local(2026, 10, 3, 10), items: [ "Arroz" ])

    page = ShoppingHistory.call(@household)

    assert_equal [ "2026-10" ], page.months.map(&:key)
    assert_equal [ "Arroz" ], page.trips.flat_map(&:names)
  end

  test "a live item is not history" do
    finished_trip(Time.zone.local(2026, 11, 12, 18), items: [ "Sabão" ])

    assert_equal [ "Sabão" ], ShoppingHistory.call(@household).trips.flat_map(&:names)
    assert_not_includes @household.list_items.on_list.map(&:name), "Sabão"
  end
end
