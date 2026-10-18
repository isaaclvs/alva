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

  test "groups the list items bought on the trip" do
    trip = households(:home).shopping_trips.create!(finished_at: Time.current)
    item = list_items(:detergent)
    item.update_columns(shopping_trip_id: trip.id)

    assert_equal [ item ], trip.list_items.to_a
    assert_equal trip, item.reload.shopping_trip
    assert_equal [ trip ], households(:home).shopping_trips.to_a
  end
end
