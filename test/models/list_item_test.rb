require "test_helper"

class ListItemTest < ActiveSupport::TestCase
  test "valid with name, household and category" do
    assert list_items(:milk).valid?
  end

  test "valid without category" do
    item = ListItem.new(name: "Coisa nova", household: households(:home))
    assert item.valid?
    assert_nil item.category
  end

  test "requires name" do
    item = ListItem.new(name: "", household: households(:home))
    assert_not item.valid?
    assert_includes item.errors[:name], "can't be blank"
  end

  test "requires household" do
    item = ListItem.new(name: "Arroz")
    assert_not item.valid?
    assert_includes item.errors[:household], "must exist"
  end

  test "defaults to pending" do
    item = ListItem.create!(name: "Arroz", household: households(:home))
    assert item.pending?
  end

  test "can be marked as purchased" do
    item = list_items(:milk)
    item.purchased!
    assert item.reload.purchased?
  end

  test "household has many list items" do
    assert_equal list_items(:milk, :detergent).sort, households(:home).list_items.sort
  end

  test "category has many list items" do
    assert_equal [ list_items(:milk) ], categories(:dairy).list_items.to_a
  end

  test "auto-categorizes on create from dictionary" do
    item = ListItem.create!(name: "Banana", household: households(:home))
    assert_equal categories(:produce), item.category
  end

  test "auto-categorizes ignoring case and accents" do
    item = ListItem.create!(name: "MACA", household: households(:home))
    assert_equal categories(:produce), item.category
  end

  test "falls back to Outros for unknown names" do
    item = ListItem.create!(name: "Coisa desconhecida", household: households(:home))
    assert_equal categories(:other), item.category
  end

  test "keeps explicitly assigned category" do
    item = ListItem.create!(name: "Banana", household: households(:home), category: categories(:other))
    assert_equal categories(:other), item.category
  end

  test "toggle_purchased! flips status and purchased_at" do
    item = list_items(:milk)
    item.toggle_purchased!
    assert item.purchased?
    assert_not_nil item.purchased_at

    item.toggle_purchased!
    assert item.pending?
    assert_nil item.purchased_at
  end

  test "broadcasts the list to the household on create" do
    assert_list_broadcast households(:home) do
      ListItem.create!(name: "Arroz", household: households(:home))
    end
  end

  test "broadcasts the list to the household on purchase toggle" do
    item = list_items(:milk)
    streams = assert_list_broadcast(households(:home)) { item.toggle_purchased! }

    assert_includes streams.last.to_html, "line-through"
  end

  test "broadcasts the list to the household on destroy" do
    assert_list_broadcast households(:home) do
      list_items(:milk).destroy!
    end
  end

  test "rejects a name already pending in the household" do
    item = ListItem.new(name: "Leite", household: households(:home))
    assert_not item.valid?
    assert item.errors.of_kind?(:name, :duplicate_pending)
  end

  test "treats case, accent, spacing and plural variations as duplicates" do
    [ "LEITE", "  leite ", "Léite", "Leites" ].each do |name|
      assert_not ListItem.new(name: name, household: households(:home)).valid?, "#{name} should be a duplicate"
    end
  end

  test "allows re-adding an item that was already purchased" do
    item = ListItem.new(name: "detergente", household: households(:home))
    assert item.valid?
    assert item.save
  end

  test "allows the same name in another household" do
    other = Household.create!(name: "Outra casa")
    assert ListItem.new(name: "Leite", household: other).valid?
  end

  test "on_list and archived split items by shopping trip" do
    household = households(:home)
    trip = household.shopping_trips.create!(finished_at: Time.current)
    list_items(:detergent).update_columns(shopping_trip_id: trip.id)

    assert_equal [ list_items(:milk) ], household.list_items.on_list.to_a
    assert_equal [ list_items(:detergent) ], household.list_items.archived.to_a
  end

  test "archived items don't count as duplicates of a pending item" do
    household = households(:home)
    trip = household.shopping_trips.create!(finished_at: Time.current)
    list_items(:milk).update_columns(shopping_trip_id: trip.id)

    assert_nil household.pending_item_named("Leite")
    assert ListItem.new(name: "Leite", household: household).valid?
  end

  test "archiving keeps the item's name, category and purchased_at" do
    household = households(:home)
    trip = household.shopping_trips.create!(finished_at: Time.current)
    item = list_items(:detergent)
    purchased_at = item.purchased_at
    item.update_columns(shopping_trip_id: trip.id)

    item.reload
    assert_equal "Detergente", item.name
    assert_equal purchased_at, item.purchased_at
    assert item.purchased?
  end
end
