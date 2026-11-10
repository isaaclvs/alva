require "application_system_test_case"

class NavigationTest < ApplicationSystemTestCase
  test "opens on Adicionar, ready to type, without the full list" do
    visit root_path

    within("#add_screen") do
      assert_text "Alva"
      assert_text "Nossa casa"
      assert_selector "h1", text: "O que está faltando em casa?"
      assert_text "Lembrou, adicionou."
      assert_text "Separe por vírgula para adicionar vários"
      assert_selector "#habitual_items details:not([open])"
    end
    assert_equal "list_item_name", focused_element_id
    assert_no_selector "#list_items", visible: true
    assert_no_button "Leite"
    assert_selector "#nav_add[aria-current=page]", text: "Adicionar"
    assert_selector "#nav_list:not([aria-current])", text: /Lista\s+1\s+pendente/
  end

  test "switching to Lista closes the keyboard and Adicionar gets the field ready again" do
    visit root_path
    assert_equal "list_item_name", focused_element_id

    show_list
    assert_selector "#add_screen[hidden]", visible: :all
    assert_selector "h1", text: "Lista de compras"
    assert_selector "#list_summary", text: "1 pendente · 1 comprado"
    assert_selector "##{dom_id(list_items(:detergent))} .line-through", text: "Detergente"
    assert_not_equal "list_item_name", focused_element_id

    show_add
    assert_selector "#list_screen[hidden]", visible: :all
    assert_equal "list_item_name", focused_element_id
  end

  test "the open screen is remembered on this device, without autofocus on the list" do
    visit_list
    visit root_path

    assert_selector "#list_screen:not([hidden])"
    assert_selector "#nav_list[aria-current=page]"
    assert_no_selector "#list_item_name", visible: true
    assert_not_equal "list_item_name", focused_element_id

    show_add
    visit root_path
    assert_selector "#add_screen:not([hidden])"
    assert_equal "list_item_name", focused_element_id
  end

  test "coming back from another app keeps the list open and the keyboard closed" do
    visit_list
    ListItem.insert!({ name: "Banana", normalized_name: "banana", household_id: households(:home).id,
      category_id: categories(:produce).id, public_id: SecureRandom.uuid })

    page.execute_script(<<~JS)
      Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" })
      document.dispatchEvent(new Event("visibilitychange"))
      Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" })
      document.dispatchEvent(new Event("visibilitychange"))
    JS

    within("#category_#{categories(:produce).id}") { assert_text "Banana" }
    assert_selector "#list_screen:not([hidden])"
    assert_selector "#list_counter", text: "2"
    assert_not_equal "list_item_name", focused_element_id
  end

  test "navigation is local: another device's add updates the list and counter, not the screen" do
    using_session(:shopping) do
      visit_list
      connect_turbo_cable_stream_sources
      assert_equal "nav_list", focused_element_id
    end

    visit root_path
    connect_turbo_cable_stream_sources
    add_item "Banana"
    assert_text "Banana adicionado em Hortifruti"
    assert_selector "#list_counter", text: "2"
    assert_selector "#add_screen:not([hidden])"

    using_session(:shopping) do
      within("#category_#{categories(:produce).id}") { assert_text "Banana" }
      assert_selector "#list_counter", text: "2"
      assert_selector "#list_screen:not([hidden])"
      assert_selector "#nav_list[aria-current=page]"
      assert_no_text "Banana adicionado"
      assert_equal "nav_list", focused_element_id
    end
  end

  test "list actions keep the list screen" do
    visit_list

    click_button "Leite"
    assert_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"
    assert_selector "#list_counter", text: "0"

    click_on "Editar Leite"
    fill_in "Nome", with: "Leite corrigido"
    select "Hortifruti", from: "Categoria"
    click_button "Salvar"
    assert_selector "#category_#{categories(:produce).id}", text: "Leite corrigido"

    remove_item "Leite corrigido"
    click_button "Desfazer"
    assert_selector "#category_#{categories(:produce).id}", text: "Leite corrigido"

    click_on "Compra feita"
    assert_text "Compra registrada"
    assert_selector "#list_screen:not([hidden])"
    assert_not_equal "list_item_name", focused_element_id
  end

  test "habit drafts and selections survive switching screens and refreshes" do
    habit = Household.first!.habitual_items.create!(name: "Arroz")
    visit root_path
    within("#habitual_items") do
      find("summary").click
      check "Arroz"
      fill_in "Salvar item habitual", with: "Café"
    end

    show_list
    request_list_refresh
    show_add

    assert_selector "#habitual_items details[open]"
    assert_checked_field "habitual_item_#{habit.id}_checkbox"
    assert_field "Salvar item habitual", with: "Café"
  end

  test "a long list stays usable above the bottom navigation" do
    household = households(:home)
    household.batch_list_broadcasts do
      35.times { |index| household.add_item("Produto de teste #{index + 1}") }
    end

    visit_list
    assert_selector "#list_items li", count: 37
    assert_selector "#list_counter", text: "36"

    click_button "Produto de teste 35"
    assert_selector "#list_items li .line-through", text: "Produto de teste 35"
    click_on "Compra feita"
    assert_selector "#list_items li", count: 35
    assert_selector "#nav_add", visible: true
  end

  test "an empty list offers to go add items" do
    ListItem.delete_all
    visit_list

    assert_text "Nada na lista por enquanto."
    assert_selector "#list_counter", text: "0"
    click_button "Adicionar itens"
    assert_selector "#add_screen:not([hidden])"
    assert_selector "#nav_add[aria-current=page]"
    assert_equal "list_item_name", focused_element_id
  end

  private

  def request_list_refresh
    page.driver.browser.execute_async_script(<<~JS)
      const done = arguments[arguments.length - 1]
      const handler = (event) => {
        if (event.detail.reason !== "navigation-test") return
        document.removeEventListener("list-refresh:success", handler)
        done()
      }
      document.addEventListener("list-refresh:success", handler)
      document.dispatchEvent(new CustomEvent("list-refresh:request", {
        detail: { reason: "navigation-test", force: true }
      }))
    JS
  end
end
