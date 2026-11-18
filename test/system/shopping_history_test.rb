require "application_system_test_case"

class ShoppingHistoryTest < ApplicationSystemTestCase
  setup do
    @household = households(:home)
    @trip = @household.shopping_trips.create!(finished_at: Time.zone.local(2026, 11, 12, 18, 30))
    @household.list_items.create!(name: "Banana", category: categories(:produce), status: :purchased,
      purchased_at: @trip.finished_at, shopping_trip: @trip)
    @household.list_items.create!(name: "Sabão", category: categories(:other), status: :purchased,
      purchased_at: @trip.finished_at, shopping_trip: @trip)
  end

  test "the menu opens the history and a trip expands into its items by category" do
    visit_list
    open_menu { click_link "Compras" }

    assert_selector "h1", text: "Compras"
    assert_selector "#history_month", text: "Novembro de 2026"
    assert_no_selector "[data-history-trip] li", text: "Banana"

    find("[data-history-trip='#{@trip.public_id}'] summary").click

    within("[data-history-trip='#{@trip.public_id}']") do
      assert_text "Quinta, 12 de novembro · 18:30"
      assert_text "2 itens"
      assert_text "Hortifruti"
      assert_text "Banana"
      assert_text "Outros"
      assert_text "Sabão"
      assert_selector ".category-icon[data-tone=produce]"
      assert_button "Adicionar tudo de novo"
      assert_no_button "Remover"
    end
  end

  test "Adicionar tudo de novo puts the trip back on the list and says what it did" do
    visit shopping_history_path
    find("[data-history-trip='#{@trip.public_id}'] summary").click
    click_button "Adicionar tudo de novo"

    within("#history_feedback") { assert_text "2 itens adicionados" }
    assert_button "Adicionar tudo de novo"

    click_link "Voltar para a lista"
    show_list
    assert_button "Banana"
    assert_button "Sabão"
  end

  test "adding the same trip again reports that the names were already there" do
    visit shopping_history_path
    find("[data-history-trip='#{@trip.public_id}'] summary").click
    click_button "Adicionar tudo de novo"
    within("#history_feedback") { assert_text "2 itens adicionados" }

    click_button "Adicionar tudo de novo"
    within("#history_feedback") { assert_text "Banana e Sabão já estavam na lista" }
  end

  test "months with history are pages, and the screen has no editing" do
    older = @household.shopping_trips.create!(finished_at: Time.zone.local(2026, 10, 3, 9))
    @household.list_items.create!(name: "Arroz", category: categories(:other), status: :purchased,
      purchased_at: older.finished_at, shopping_trip: older)
    visit shopping_history_path

    click_link "Mês anterior"

    assert_selector "#history_month", text: "Outubro de 2026"
    assert_selector "[data-history-trip='#{older.public_id}']"
    assert_no_selector "[data-history-trip='#{@trip.public_id}']"
    click_link "Próximo mês"
    assert_selector "#history_month", text: "Novembro de 2026"
  end

  test "the history touch targets stay at least 44 CSS px" do
    older = @household.shopping_trips.create!(finished_at: Time.zone.local(2026, 10, 3, 9))
    @household.list_items.create!(name: "Arroz", category: categories(:other), status: :purchased,
      purchased_at: older.finished_at, shopping_trip: older)

    visit shopping_history_path

    assert_touch_target "a[aria-label='Voltar para a lista']", "a[href*='month=']",
      "[data-history-trip='#{@trip.public_id}'] summary"
    find("[data-history-trip='#{@trip.public_id}'] summary").click
    assert_touch_target "[data-history-trip='#{@trip.public_id}'] form button"
  end

  test "an empty history says so" do
    @household.list_items.archived.destroy_all
    @household.shopping_trips.destroy_all

    visit shopping_history_path

    assert_selector "#history_empty", text: "Nenhuma compra registrada ainda."
    assert_no_selector "#history_month"
  end
end
