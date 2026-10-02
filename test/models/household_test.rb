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
end
