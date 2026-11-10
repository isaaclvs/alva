require "application_system_test_case"

class ShoppingModeSystemTest < ApplicationSystemTestCase
  teardown do
    page.current_window.resize_to(390, 844)
  end

  test "starting and leaving shopping changes no data and shows the checklist only" do
    before = ListItem.order(:id).pluck(:id, :name, :status, :lock_version, :shopping_trip_id)
    visit_list
    assert_link "Editar Leite"
    open_menu { assert_link "Baixar PDF" }

    click_button "Iniciar compras"

    assert_selector "html[data-shopping-mode=on]"
    assert_selector "h1", text: "Em compras"
    assert_selector "#list_summary", text: "1 pendente · 1 comprado"
    assert_equal "shopping_title", focused_element_id
    assert_no_button "Iniciar compras"
    assert_no_link "Editar Leite"
    assert_no_button "Remover Leite"
    assert_no_link "Baixar PDF"
    assert_no_selector "summary[aria-label='Mais opções']"
    assert_no_button "Tema claro"
    assert_no_selector "nav[aria-label=Telas]"
    assert_no_button "Adicionar"
    assert_selector "#category_#{categories(:dairy).id}", text: "Laticínios"
    assert_selector "##{dom_id(list_items(:detergent))} .line-through", text: "Detergente"
    assert_button "Compra feita"
    assert_button "Sair das compras"

    click_button "Sair das compras"

    assert_no_selector "html[data-shopping-mode]"
    assert_selector "h1", text: "Lista de compras"
    assert_link "Editar Leite"
    assert_selector "#list_screen summary[aria-label='Mais opções']"
    assert_selector "nav[aria-label=Telas]"
    assert_equal "Iniciar compras", page.evaluate_script("document.activeElement.textContent.trim()")
    assert_selector "#list_screen:not([hidden])"
    assert_equal before, ListItem.order(:id).pluck(:id, :name, :status, :lock_version, :shopping_trip_id)
    assert_equal 0, ShoppingTrip.count
  end

  test "the checklist toggles items and keeps keyboard focus on the row" do
    visit_list
    click_button "Iniciar compras"

    click_button "Leite"
    assert_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"
    assert_selector "#list_summary", text: "0 pendentes · 2 comprados"
    assert list_items(:milk).reload.purchased?
    assert_equal dom_id(list_items(:milk)), page.evaluate_script("document.activeElement.closest('li').id")

    click_button "Detergente"
    assert_selector "##{dom_id(list_items(:detergent))} button[aria-pressed=false]"
    assert_selector "html[data-shopping-mode=on]"
  end

  test "the mode and the list screen survive a reload, even if Adicionar was saved" do
    visit root_path
    assert_selector "#add_screen:not([hidden])"
    show_list
    click_button "Iniciar compras"

    visit root_path
    assert_selector "html[data-shopping-mode=on]"
    assert_selector "#list_screen:not([hidden])"
    assert_selector "h1", text: "Em compras"
    assert_no_link "Editar Leite"
    assert_not_equal "list_item_name", focused_element_id
    assert_no_selector "#nav_add"

    page.driver.browser.manage.add_cookie(name: "screen", value: "add", path: "/")
    visit root_path
    assert_selector "#list_screen:not([hidden])"
    assert_selector "#add_screen[hidden]", visible: :all

    click_button "Sair das compras"
    visit root_path
    assert_no_selector "html[data-shopping-mode]"
  end

  test "rows that arrive by broadcast or refresh keep their controls hidden" do
    visit_list
    connect_turbo_cable_stream_sources
    click_button "Iniciar compras"

    households(:home).add_item("Banana")
    within("#category_#{categories(:produce).id}") { assert_button "Banana" }
    assert_no_link "Editar Banana"
    assert_no_button "Remover Banana"
    assert_no_selector "nav[aria-label=Telas]"

    ListItem.insert!({ name: "Cebola", normalized_name: "cebola", household_id: households(:home).id,
      category_id: categories(:produce).id, public_id: SecureRandom.uuid })
    page.execute_script(<<~JS)
      document.dispatchEvent(new CustomEvent("list-refresh:request", { detail: { reason: "shopping-test", force: true } }))
    JS
    within("#category_#{categories(:produce).id}") { assert_button "Cebola" }
    assert_no_link "Editar Cebola"
    assert_no_button "Remover Cebola"
    assert_no_link "Baixar PDF"
    assert_selector "html[data-shopping-mode=on]"
  end

  test "another device is independent and keeps its normal list" do
    using_session(:shopping) do
      visit_list
      connect_turbo_cable_stream_sources
    end

    visit_list
    click_button "Iniciar compras"
    click_button "Leite"
    assert_selector "##{dom_id(list_items(:milk))} .line-through"

    using_session(:shopping) do
      assert_selector "##{dom_id(list_items(:milk))} .line-through"
      assert_no_selector "html[data-shopping-mode]"
      assert_link "Editar Leite"
      assert_selector "nav[aria-label=Telas]"
      assert_selector "h1", text: "Lista de compras"
    end
  end

  test "Compra feita keeps shopping, offers undo here only, and carries pending items" do
    households(:home).add_item("Banana")
    visit_list
    connect_turbo_cable_stream_sources
    using_session(:shopping) do
      visit_list
      connect_turbo_cable_stream_sources
    end
    click_button "Iniciar compras"

    click_button "Compra feita"

    assert_selector "#toast", text: "Compra registrada"
    assert_selector "html[data-shopping-mode=on]"
    assert_no_selector "##{dom_id(list_items(:detergent))}"
    assert_button "Banana"
    assert_equal [ "Detergente" ], ShoppingTrip.last.list_items.pluck(:name)
    using_session(:shopping) do
      assert_no_selector "##{dom_id(list_items(:detergent))}"
      assert_no_selector "#toast", text: "Compra registrada"
    end

    click_button "Desfazer"
    assert_selector "##{dom_id(list_items(:detergent))} .line-through"
    assert_equal 0, ShoppingTrip.count
    assert_selector "html[data-shopping-mode=on]"
    assert_no_link "Editar Leite"
  end

  test "an empty list in shopping mode shows a message and still lets you leave" do
    ListItem.delete_all
    visit_list
    assert_no_button "Iniciar compras"
    page.driver.browser.manage.add_cookie(name: "shopping_mode", value: "on", path: "/")
    visit root_path

    assert_selector "h1", text: "Em compras"
    assert_text "Nada na lista por enquanto."
    assert_no_button "Adicionar itens"
    assert_no_selector "nav[aria-label=Telas]"
    click_button "Sair das compras"
    assert_selector "h1", text: "Lista de compras"
    assert_button "Adicionar itens"
  end

  test "an open correction blocks starting and keeps the draft" do
    visit_list
    click_on "Editar Leite"
    fill_in "Nome", with: "Leite integral"

    assert_selector "#shopping_editing_hint", text: "Salve ou cancele a edição"
    assert_selector "button[aria-disabled=true]", text: "Iniciar compras"
    click_button "Iniciar compras"

    assert_no_selector "html[data-shopping-mode]"
    assert_field "Nome", with: "Leite integral"
    assert_equal "list_item[name]", page.evaluate_script("document.activeElement.name")

    click_on "Cancelar"
    assert_no_selector "#shopping_editing_hint", visible: :visible
    assert_selector "button[aria-disabled=false]", text: "Iniciar compras"
    click_button "Iniciar compras"
    assert_selector "html[data-shopping-mode=on]"
  end

  test "mobile and desktop have no horizontal overflow in both themes while shopping" do
    visit_list
    click_button "Iniciar compras"

    [ [ 390, 844 ], [ 820, 1000 ], [ 1440, 1000 ] ].each do |width, height|
      page.current_window.resize_to(width, height)
      assert_selector "html[data-shopping-mode=on]"
      assert_no_selector "nav[aria-label=Telas]"
      assert page.evaluate_script("document.documentElement.scrollWidth <= window.innerWidth"), "overflow at #{width}"
      assert_operator row_height("Leite"), :>=, 48
    end

    page.current_window.resize_to(1440, 1000)
    assert_operator page.evaluate_script("document.querySelector('.app-shell').getBoundingClientRect().left"), :>, 100
    assert_equal 2, page.evaluate_script("getComputedStyle(document.querySelector('.shopping-categories')).gridTemplateColumns.split(' ').length")

    click_button "Sair das compras"
    toggle_theme "Tema claro"
    click_button "Iniciar compras"
    assert_selector "html[data-theme=light][data-shopping-mode=on]"
    assert page.evaluate_script("document.documentElement.scrollWidth <= window.innerWidth")
    assert_no_selector "nav[aria-label=Telas]"
  end

  private

  def row_height(name)
    page.evaluate_script("Array.from(document.querySelectorAll('#list_items button')).find((b) => b.textContent.includes(#{name.to_json})).getBoundingClientRect().height")
  end

  test "each aisle says what is left while shopping, and the list only counts once something is bought" do
    households(:home).list_items.create!(name: "Iogurte", category: categories(:dairy))
    visit_list
    within("#category_#{categories(:dairy).id} h2") { assert_no_text "/" }

    click_button "Iniciar compras"
    within("#category_#{categories(:dairy).id} h2") { assert_text "2 restantes" }

    click_button "Leite"
    within("#category_#{categories(:dairy).id} h2") { assert_text "1 restante" }
    click_button "Iogurte"
    within("#category_#{categories(:dairy).id} h2") { assert_text "tudo pego" }

    click_button "Sair das compras"
    within("#category_#{categories(:dairy).id} h2") { assert_text "2/2" }
  end
end
