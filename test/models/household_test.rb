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

  test "clear_purchased! deletes purchased items and keeps pending ones" do
    purchased, pending = list_items(:detergent), list_items(:milk)
    households(:home).clear_purchased!

    assert_not ListItem.exists?(purchased.id)
    assert ListItem.exists?(pending.id)
  end

  test "clear_purchased! broadcasts the list once" do
    ListItem.create!(name: "Arroz", household: households(:home)).toggle_purchased!

    assert_turbo_stream_broadcasts households(:home), count: 1 do
      households(:home).clear_purchased!
    end
  end
end
