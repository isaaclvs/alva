require "test_helper"

class HouseholdTest < ActiveSupport::TestCase
  test "valid with a name" do
    assert households(:home).valid?
  end

  test "requires name" do
    household = Household.new(name: "")
    assert_not household.valid?
    assert_includes household.errors[:name], "can't be blank"
  end

  test "groups list items by category position, uncategorized last" do
    groups = households(:home).list_items_by_category
    assert_equal [ categories(:dairy), nil ], groups.map(&:first)
    assert_equal [ list_items(:milk) ], groups.first.last
  end

  test "lists pending items before purchased ones within a category" do
    household = households(:home)
    bread = household.list_items.create!(name: "Pão", category: categories(:dairy))
    list_items(:milk).toggle_purchased!

    dairy_items = household.list_items_by_category.to_h.fetch(categories(:dairy))
    assert_equal [ bread, list_items(:milk) ], dairy_items
  end

  test "finish_shopping! archives purchased items into a trip and keeps pending ones" do
    household = households(:home)
    purchased, pending = list_items(:detergent), list_items(:milk)

    freeze_time do
      trip = household.finish_shopping!

      assert_equal household, trip.household
      assert_equal Time.current, trip.finished_at
      assert_equal [ purchased ], trip.list_items.to_a
    end
    assert_equal [ pending ], household.list_items.on_list.to_a
    assert_nil pending.reload.shopping_trip
  end

  test "finish_shopping! keeps the archived item's history intact" do
    item = list_items(:detergent)
    purchased_at = item.purchased_at
    households(:home).finish_shopping!

    item.reload
    assert_equal "Detergente", item.name
    assert_equal purchased_at, item.purchased_at
    assert item.purchased?
    assert_includes households(:home).list_items.archived, item
  end

  test "finish_shopping! ignores items already archived by an earlier trip" do
    household = households(:home)
    first = household.finish_shopping!
    list_items(:milk).toggle_purchased!
    second = household.finish_shopping!

    assert_equal [ list_items(:detergent) ], first.list_items.to_a
    assert_equal [ list_items(:milk) ], second.list_items.to_a
  end

  test "finish_shopping! does nothing when nothing was purchased" do
    list_items(:detergent).toggle_purchased!

    assert_no_difference "ShoppingTrip.count" do
      assert_nil households(:home).finish_shopping!
    end
    assert_empty households(:home).list_items.archived
  end

  test "finish_shopping! only touches its own household" do
    other = Household.create!(name: "Outra")
    other_item = other.list_items.create!(name: "Arroz")
    other_item.toggle_purchased!
    households(:home).finish_shopping!

    assert_nil other_item.reload.shopping_trip
  end

  test "finish_shopping! lets an archived name be added again as pending" do
    household = households(:home)
    list_items(:milk).toggle_purchased!
    household.finish_shopping!

    new_milk = household.list_items.create!(name: "Leite")
    assert new_milk.pending?
    assert_nil new_milk.shopping_trip
    assert_equal 2, ListItem.where(name: "Leite").count
  end

  test "finish_shopping! broadcasts the list once" do
    streams = assert_list_broadcast households(:home) do
      households(:home).finish_shopping!
    end

    assert_not_includes streams.last.to_html, "Detergente"
    assert_includes streams.last.to_html, "Leite"
  end

  test "finish_shopping! doesn't broadcast when nothing was purchased" do
    list_items(:detergent).toggle_purchased!

    streams = capture_turbo_stream_broadcasts(households(:home)) do
      households(:home).finish_shopping!
    end
    assert_empty streams
  end

  test "list_items_by_category leaves out archived items" do
    household = households(:home)
    trip = household.shopping_trips.create!(finished_at: Time.current)
    list_items(:detergent).update_columns(shopping_trip_id: trip.id)

    assert_equal [ list_items(:milk) ], household.list_items_by_category.flat_map(&:last)
  end
end
