require "test_helper"

class HabitualItemTest < ActiveSupport::TestCase
  test "normalizes spaces and stores the shared household association" do
    habit = households(:home).habitual_items.create!(name: "  Café   da manhã ")

    assert_equal "Café da manhã", habit.name
    assert_equal "cafe da manha", habit.normalized_name
    assert_equal households(:home), habit.household
  end

  test "normalized habit names are unique within a household" do
    households(:home).habitual_items.create!(name: "Pão")
    duplicate = households(:home).habitual_items.new(name: " paes ")

    assert_not duplicate.valid?
    assert_includes duplicate.errors[:normalized_name], "has already been taken"
  end

  test "the same habit can be saved in another household" do
    households(:home).habitual_items.create!(name: "Leite")
    other = Household.create!(name: "Outra")

    assert other.habitual_items.create!(name: "leite").persisted?
  end

  test "removing a habit leaves its live and archived list items alone" do
    household = households(:home)
    habit = household.habitual_items.create!(name: "Detergente")
    archived = list_items(:detergent)
    household.finish_shopping!

    assert_difference "ListItem.count", 0 do
      habit.destroy!
    end

    assert_equal [ archived ], household.list_items.archived.to_a
  end
end
