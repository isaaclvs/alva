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

  test "clear_purchased! deletes purchased items and keeps pending ones" do
    purchased, pending = list_items(:detergent), list_items(:milk)
    households(:home).clear_purchased!

    assert_not ListItem.exists?(purchased.id)
    assert ListItem.exists?(pending.id)
  end

  test "clear_purchased! broadcasts the list once" do
    ListItem.create!(name: "Arroz", household: households(:home)).toggle_purchased!

    assert_list_broadcast households(:home) do
      households(:home).clear_purchased!
    end
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
end
