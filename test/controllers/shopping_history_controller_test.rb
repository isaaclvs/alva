require "test_helper"

class ShoppingHistoryControllerTest < ActionDispatch::IntegrationTest
  setup { @household = households(:home) }

  def finished_trip(at, items)
    trip = @household.shopping_trips.create!(finished_at: at)
    items.each do |name, category|
      @household.list_items.create!(name:, category:, status: :purchased, purchased_at: at, shopping_trip: trip)
    end
    trip
  end

  test "index shows the newest month with its trips, newest first" do
    older = finished_trip(Time.zone.local(2026, 10, 3, 10), { "Arroz" => categories(:other) })
    newer = finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other), "Banana" => categories(:produce) })

    get shopping_history_path

    assert_response :success
    assert_select "h1", text: "Compras"
    assert_select "#history_month", text: "Novembro de 2026"
    assert_select "[data-history-trip='#{newer.public_id}']" do
      assert_select "summary", text: /Quinta, 12 de novembro · 18:00/
      assert_select "summary", text: /2 itens/
      assert_select "h3", text: /Hortifruti/
      assert_select "h3", text: /Outros/
      assert_select "li", text: "Banana"
      assert_select "button", text: /Adicionar tudo de novo/
    end
    assert_select "[data-history-trip='#{older.public_id}']", count: 0
    assert_select "a", text: "Mês anterior"
    assert_select "a", text: "Próximo mês", count: 0
  end

  test "index pages by month, with the months beside it" do
    finished_trip(Time.zone.local(2026, 9, 20, 10), { "Café" => categories(:other) })
    october = finished_trip(Time.zone.local(2026, 10, 3, 10), { "Arroz" => categories(:other) })
    finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other) })

    get shopping_history_path(month: "2026-10")

    assert_response :success
    assert_select "#history_month", text: "Outubro de 2026"
    assert_select "[data-history-trip='#{october.public_id}']"
    assert_select "a[href=?]", shopping_history_path(month: "2026-09"), text: "Mês anterior"
    assert_select "a[href=?]", shopping_history_path(month: "2026-11"), text: "Próximo mês"
  end

  test "index at the newest month has no next month and no older page without history" do
    finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other) })

    get shopping_history_path

    assert_select "a", text: "Próximo mês", count: 0
    assert_select "a", text: "Mês anterior", count: 0
  end

  test "index with no history says so and offers the list" do
    get shopping_history_path

    assert_response :success
    assert_select "#history_empty", text: /Nenhuma compra registrada ainda/
    assert_select "a[href=?]", root_path, text: "Voltar para a lista"
  end

  test "index ignores a month without a trip" do
    finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other) })

    get shopping_history_path(month: "2019-01")

    assert_response :success
    assert_select "#history_month", text: "Novembro de 2026"
  end

  test "the menu of both screens opens the history" do
    get root_path

    assert_select "a[href=?]", shopping_history_path, text: "Compras"
  end

  test "add_all puts the trip's names back on the list and summarizes it" do
    trip = finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other), "Banana" => categories(:produce) })

    assert_difference "ListItem.on_list.count", 2 do
      post shopping_history_add_all_path(trip), as: :turbo_stream
    end

    assert_response :success
    assert_select "turbo-stream[action=update][target=history_feedback]", text: /2 itens adicionados/
    assert_select "turbo-stream[target=history_feedback] span", text: /2 itens adicionados/
    assert_equal [ "Banana", "Sabão" ], @household.list_items.on_list.order(:id).last(2).map(&:name)
    assert_equal categories(:produce), @household.list_items.on_list.find_by(name: "Banana").category
  end

  test "add_all counts what was already on the list" do
    trip = finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other), "Banana" => categories(:produce) })
    @household.list_items.create!(name: "Banana", category: categories(:produce))

    assert_difference "ListItem.on_list.count", 1 do
      post shopping_history_add_all_path(trip), as: :turbo_stream
    end

    assert_select "turbo-stream[target=history_feedback] span", text: /1 item adicionado · Banana já estava na lista/
  end

  test "add_all brings back an item bought again" do
    trip = finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other) })

    post shopping_history_add_all_path(trip), as: :turbo_stream

    assert_select "turbo-stream[target=history_feedback] span", text: /1 item adicionado/
    assert_no_difference "ListItem.on_list.count" do
      post shopping_history_add_all_path(trip), as: :turbo_stream
    end
    assert_select "turbo-stream[target=history_feedback] span", text: /Nada adicionado · Sabão já estava na lista/
  end

  test "add_all broadcasts the list once, not the history" do
    trip = finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other), "Banana" => categories(:produce) })

    assert_list_broadcast(@household) do
      post shopping_history_add_all_path(trip), as: :turbo_stream
    end
  end

  test "add_all from another household is not found" do
    other = Household.create!(name: "Outra casa")
    trip = other.shopping_trips.create!(finished_at: Time.current)
    other.list_items.create!(name: "Sabão", status: :purchased, shopping_trip: trip, purchased_at: Time.current)

    assert_no_difference "ListItem.count" do
      post shopping_history_add_all_path(trip), as: :turbo_stream
    end
    assert_redirected_to shopping_history_path
  end

  test "add_all from a plain html request goes back to the history" do
    trip = finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other) })

    post shopping_history_add_all_path(trip)

    assert_redirected_to shopping_history_path
    assert_equal "1 item adicionado", flash[:notice]
  end
end
