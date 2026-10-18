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

  test "add_item creates a pending item" do
    household = households(:home)

    result = assert_difference("ListItem.count", 1) { household.add_item("Banana") }

    assert_equal :created, result.outcome
    assert result.item.persisted?
    assert_equal "Banana", result.item.name
    assert result.item.pending?
  end

  test "add_item reports a pending duplicate and returns the existing item" do
    result = assert_no_difference("ListItem.count") { households(:home).add_item("  LEITE ") }

    assert_equal :duplicate, result.outcome
    assert_equal list_items(:milk), result.item
  end

  test "add_item reports a blank name as invalid" do
    result = assert_no_difference("ListItem.count") { households(:home).add_item(" ") }

    assert_equal :invalid, result.outcome
    assert_not result.item.persisted?
    assert result.item.errors[:name].any?
  end

  test "add_item revives a purchased item instead of creating a row" do
    purchased = list_items(:detergent)

    result = assert_no_difference("ListItem.count") { households(:home).add_item("detergentes") }

    assert_equal :revived, result.outcome
    assert_equal purchased, result.item
    assert purchased.reload.pending?
    assert_nil purchased.purchased_at
  end

  test "add_item keeps the original name when reviving" do
    households(:home).add_item("DETERGENTE")

    assert_equal "Detergente", list_items(:detergent).reload.name
  end

  test "add_item revives the most recently purchased match" do
    household = households(:home)
    older = household.list_items.create!(name: "Arroz", status: :purchased, purchased_at: 3.days.ago)
    newer = household.list_items.create!(name: "arroz", status: :purchased, purchased_at: 1.hour.ago)

    result = household.add_item("Arroz")

    assert_equal newer, result.item
    assert newer.reload.pending?
    assert older.reload.purchased?
  end

  test "add_item broadcasts the list once when reviving" do
    assert_list_broadcast households(:home) do
      households(:home).add_item("Detergente")
    end
  end

  test "add_item prefers the pending duplicate over a purchased match" do
    household = households(:home)
    # insert! skips the pending-duplicate validation, which doesn't look at the new item's status.
    ListItem.insert!({ name: "Leite", household_id: household.id, status: 1, purchased_at: 1.hour.ago })

    result = household.add_item("Leite")

    assert_equal :duplicate, result.outcome
    assert_equal list_items(:milk), result.item
  end

  def removed_snapshot(item)
    ListItemSnapshot.load(ListItemSnapshot.dump(item)).tap { item.destroy! }
  end

  test "restore_item brings a removed item back exactly as it was" do
    household = households(:home)
    original = household.list_items.create!(name: "Pão", category: categories(:dairy), status: :purchased,
      purchased_at: 2.hours.ago, created_at: 3.days.ago)
    attributes = original.reload.attributes.except("updated_at")

    restored = household.restore_item(removed_snapshot(original))

    assert_equal attributes, restored.reload.attributes.except("updated_at")
  end

  test "restore_item puts the item back in its original position" do
    household = households(:home)
    first = household.list_items.create!(name: "Queijo", category: categories(:dairy), created_at: 2.days.ago)
    household.list_items.create!(name: "Iogurte", category: categories(:dairy))

    household.restore_item(removed_snapshot(first))

    names = household.list_items_by_category.to_h.fetch(categories(:dairy)).map(&:name)
    assert_equal %w[Queijo Leite Iogurte], names
  end

  test "restore_item broadcasts the list once" do
    snapshot = removed_snapshot(list_items(:milk))

    assert_list_broadcast(households(:home)) { households(:home).restore_item(snapshot) }
  end

  test "restore_item does nothing when a pending item with the same name is on the list" do
    household = households(:home)
    snapshot = removed_snapshot(list_items(:milk))
    readded = household.list_items.create!(name: "leite")

    assert_no_difference "ListItem.count" do
      assert_nil household.restore_item(snapshot)
    end
    assert_equal [ readded ], household.list_items.on_list.pending.to_a
  end

  test "restore_item does nothing when the item is already back" do
    household = households(:home)
    snapshot = ListItemSnapshot.load(ListItemSnapshot.dump(list_items(:milk)))

    assert_no_difference "ListItem.count" do
      assert_nil household.restore_item(snapshot)
    end
  end

  test "restore_item ignores a snapshot from another household" do
    other = Household.create!(name: "Outra")
    snapshot = removed_snapshot(list_items(:milk))

    assert_no_difference "ListItem.count" do
      assert_nil other.restore_item(snapshot)
    end
  end

  test "undo_shopping! puts the trip's items back as they were and deletes the trip" do
    household = households(:home)
    purchased = list_items(:detergent)
    purchased_at = purchased.purchased_at
    trip = household.finish_shopping!

    assert_difference "ShoppingTrip.count", -1 do
      assert household.undo_shopping!(trip)
    end

    purchased.reload
    assert_nil purchased.shopping_trip_id
    assert purchased.purchased?
    assert_equal purchased_at, purchased.purchased_at
    assert_not ShoppingTrip.exists?(trip.id)
    assert_includes household.list_items.on_list, purchased
  end

  test "undo_shopping! leaves pending items and older trips alone" do
    household = households(:home)
    older = household.shopping_trips.create!(finished_at: 1.week.ago)
    old_item = household.list_items.create!(name: "Sabão", status: :purchased, purchased_at: 1.week.ago, shopping_trip: older)
    trip = household.finish_shopping!

    assert household.undo_shopping!(trip)

    assert_equal older, old_item.reload.shopping_trip
    assert_nil list_items(:milk).reload.shopping_trip_id
    assert list_items(:milk).pending?
  end

  test "undo_shopping! only undoes the most recent trip" do
    household = households(:home)
    older = household.finish_shopping!
    household.list_items.create!(name: "Arroz", status: :purchased, purchased_at: Time.current)
    latest = household.finish_shopping!

    assert_no_difference "ShoppingTrip.count" do
      assert_not household.undo_shopping!(older)
    end
    assert_equal older, list_items(:detergent).reload.shopping_trip
    assert household.undo_shopping!(latest)
    assert household.undo_shopping!(older)
  end

  test "undo_shopping! ignores a trip of another household" do
    other = Household.create!(name: "Outra")
    trip = other.shopping_trips.create!(finished_at: Time.current)

    assert_not households(:home).undo_shopping!(trip)
    assert ShoppingTrip.exists?(trip.id)
  end

  test "undo_shopping! broadcasts the list once" do
    trip = households(:home).finish_shopping!

    assert_list_broadcast(households(:home)) { households(:home).undo_shopping!(trip) }
  end
end
