require "application_system_test_case"

class HabitualItemsTest < ApplicationSystemTestCase
  test "saves a habit, adds a selected habit, and keeps it after finishing shopping" do
    visit root_path
    within("#habitual_items") do
      find("summary").click
      fill_in "Salvar item habitual", with: "Arroz"
      click_button "Salvar"
    end

    assert_selector "#habitual_items details[open]"
    assert_selector "#toast [role=status]", text: /Arroz salvo nos habituais/
    assert_equal 0, ListItem.where(name: "Arroz").count

    within("#habitual_items") do
      check "Arroz"
      click_button "Adicionar selecionados"
    end

    assert_selector "#list_items li", text: "Arroz", count: 1
    assert_selector "#habitual_item_#{Household.first!.habitual_items.sole.id}"
    assert_equal 1, Household.first!.habitual_items.count

    click_button "Leite"
    click_on "Compra feita"
    assert_selector "#habitual_item_#{Household.first!.habitual_items.sole.id}"
    assert_equal "Arroz", Household.first!.habitual_items.sole.name
  end

  test "habits update in another open household session" do
    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
      assert_selector "#habitual_items", text: "Ainda não há itens habituais salvos", visible: :all
    end

    visit root_path
    within("#habitual_items") do
      find("summary").click
      fill_in "Salvar item habitual", with: "Café"
      click_button "Salvar"
    end

    using_session(:other) do
      assert_text "Habituais"
      find("#habitual_items summary").click
      assert_text "Café"
    end
  end

  test "add all reports duplicates and removing a habit leaves list history intact" do
    household = Household.first!
    household.habitual_items.create!(name: "Leite")
    household.habitual_items.create!(name: "Detergente")
    households(:home).finish_shopping!

    visit root_path
    within("#habitual_items") do
      find("summary").click
      click_button "Adicionar todos"
    end

    assert_selector "#toast [role=status]", text: /1 adicionados, 1 já estavam na lista/
    assert_equal 1, household.list_items.on_list.pending.where(name: "Leite").count
    assert_equal 1, household.list_items.archived.where(name: "Detergente").count

    detergent_habit = household.habitual_items.find_by!(name: "Detergente")
    within("#habitual_items") do
      within("form[action='#{habitual_item_path(detergent_habit)}']") { click_button "Remover" }
    end

    assert_no_selector "#habitual_item_#{detergent_habit.id}"
    assert_equal 1, household.list_items.archived.where(name: "Detergente").count
  end

  test "a list broadcast preserves the habits disclosure and selected checkboxes" do
    household = Household.first!
    habit = household.habitual_items.create!(name: "Arroz")

    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
    end
    visit root_path
    connect_turbo_cable_stream_sources
    find("#habitual_items summary").click
    check "Arroz"
    fill_in "Salvar item habitual", with: "Café em edição"

    using_session(:other) do
      fill_in "Item", with: "Banana fora"
      click_button "Adicionar"
    end

    assert_selector "#habitual_items details[open]"
    assert_checked_field "habitual_item_#{habit.id}_checkbox"
    assert_field "Salvar item habitual", with: "Café em edição"
  end

  test "a reconnect catches up with habitual changes made while disconnected" do
    existing_habit = Household.first!.habitual_items.create!(name: "Arroz")
    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
    end
    visit root_path
    connect_turbo_cable_stream_sources

    page.evaluate_async_script(<<~JS)
      const done = arguments[arguments.length - 1]
      import("@hotwired/turbo-rails").then(async ({ cable }) => {
        window.testCableConsumer = await cable.getConsumer()
        window.testCableConsumer.disconnect()
        done()
      })
    JS
    assert_no_selector "turbo-cable-stream-source[connected]", visible: :all
    find("#habitual_items summary").click
    check "Arroz"
    fill_in "Salvar item habitual", with: "Café em rascunho"

    using_session(:other) do
      find("#habitual_items summary").click
      fill_in "Salvar item habitual", with: "Café desconectado"
      click_button "Salvar"
      assert_selector "#toast [role=status]", text: /Café desconectado salvo nos habituais/
    end
    habit_from_other_session = Household.first!.habitual_items.find_by!(name: "Café desconectado")
    assert_no_selector "#habitual_item_#{habit_from_other_session.id}"

    page.execute_script("window.testCableConsumer.connect()")
    assert_selector "turbo-cable-stream-source[connected]", visible: :all
    assert_selector "#habitual_items details[open]"
    assert_selector "#habitual_item_#{habit_from_other_session.id}"
    assert_checked_field "habitual_item_#{existing_habit.id}_checkbox"
    assert_field "Salvar item habitual", with: "Café em rascunho"
  end
end
