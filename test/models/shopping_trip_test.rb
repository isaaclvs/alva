require "test_helper"

class ShoppingTripTest < ActiveSupport::TestCase
  test "valid with household and finished_at" do
    assert ShoppingTrip.new(household: households(:home), finished_at: Time.current).valid?
  end

  test "requires finished_at" do
    trip = ShoppingTrip.new(household: households(:home))
    assert_not trip.valid?
    assert_includes trip.errors[:finished_at], "can't be blank"
  end

  test "requires household" do
    trip = ShoppingTrip.new(finished_at: Time.current)
    assert_not trip.valid?
    assert_includes trip.errors[:household], "must exist"
  end
end
