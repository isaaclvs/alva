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

  test "create offers the archived items to buy again" do
    post shopping_trips_path, as: :turbo_stream

    assert_select "turbo-stream[action=replace][target=buy_again] button", text: /Detergente/
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

  test "create answers with an undo toast, not in the broadcast" do
    streams = assert_list_broadcast households(:home) do
      post shopping_trips_path, as: :turbo_stream
    end

    trip = ShoppingTrip.last
    assert_select "turbo-stream[action=update][target=toast]" do
      assert_select "[role=status]", text: /Compra registrada/
      assert_select "form[action='#{shopping_trip_path(trip)}'] input[name=_method][value=delete]"
      assert_select "form[action='#{shopping_trip_path(trip)}'] button", text: "Desfazer"
    end
    assert_empty streams.select { |stream| stream["target"] == "toast" }
  end

  test "create with nothing purchased shows no toast" do
    list_items(:detergent).destroy!

    post shopping_trips_path, as: :turbo_stream
    assert_select "turbo-stream[target=toast]", count: 0
  end

  test "destroy puts the purchased items back and deletes the trip" do
    purchased = list_items(:detergent)
    post shopping_trips_path, as: :turbo_stream
    trip = ShoppingTrip.last

    assert_list_broadcast households(:home) do
      assert_difference "ShoppingTrip.count", -1 do
        delete shopping_trip_path(trip), as: :turbo_stream
      end
    end

    assert_response :success
    assert_nil purchased.reload.shopping_trip_id
    assert_select "turbo-stream[target=list_items]", text: /Detergente/
    assert_select "turbo-stream[action=update][target=toast]"
    assert_select "[role=status]", count: 0
  end

  test "destroy from a plain html request redirects to the list" do
    post shopping_trips_path, as: :turbo_stream

    delete shopping_trip_path(ShoppingTrip.last)
    assert_redirected_to root_path
  end

  test "destroy resyncs when a trip is no longer the latest" do
    older = households(:home).finish_shopping!
    households(:home).list_items.create!(name: "Arroz", status: :purchased, purchased_at: Time.current)
    households(:home).finish_shopping!

    assert_no_difference [ "ShoppingTrip.count", "ListItem.on_list.count" ] do
      delete shopping_trip_path(older), as: :turbo_stream
    end
    assert_response :conflict
    assert_includes response.body, "A lista mudou em outro aparelho"
  end

  test "destroy is a 404 for another household and a conflict after undo" do
    other = Household.create!(name: "Outra").shopping_trips.create!(finished_at: Time.current)
    delete shopping_trip_path(other), as: :turbo_stream
    assert_response :not_found
    assert ShoppingTrip.exists?(other.id)

    trip = households(:home).finish_shopping!
    delete shopping_trip_path(trip), as: :turbo_stream
    assert_response :success
    delete shopping_trip_path(trip), as: :turbo_stream
    assert_response :conflict
    assert_includes response.body, "A lista mudou em outro aparelho"
  end
end
