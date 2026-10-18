require "test_helper"

class ShoppingTripsControllerTest < ActionDispatch::IntegrationTest
  test "create archives purchased items into a trip and keeps pending ones" do
    purchased, pending = list_items(:detergent), list_items(:milk)

    assert_difference "ShoppingTrip.count", 1 do
      post shopping_trips_path, as: :turbo_stream
    end

    assert_response :success
    assert_equal ShoppingTrip.last, purchased.reload.shopping_trip
    assert_nil pending.reload.shopping_trip
    assert_select "turbo-stream[action=replace][target=list_summary]"
    assert_select "turbo-stream[action=replace][target=list_items]", text: /Leite/
    assert_select "turbo-stream[target=list_items]", text: /Detergente/, count: 0
  end

  test "create broadcasts the list to the household stream" do
    assert_list_broadcast households(:home) do
      post shopping_trips_path, as: :turbo_stream
    end
  end

  test "create with nothing purchased records no trip" do
    list_items(:detergent).destroy!

    assert_no_difference "ShoppingTrip.count" do
      post shopping_trips_path, as: :turbo_stream
    end
    assert_response :success
    assert_select "turbo-stream[target=list_items]", text: /Leite/
  end

  test "create from a plain html request redirects to the list" do
    post shopping_trips_path
    assert_redirected_to root_path
  end
end
