require "application_system_test_case"

class ListFilterTest < ApplicationSystemTestCase
  setup do
    @coffee = households(:home).list_items.create!(name: "Café moído", category: categories(:other))
  end

  test "search ignores accents and case and preserves the query when toggling" do
    visit_list
    click_button "Iniciar compras"
    open_search
    fill_in "Buscar na lista", with: "CAFE"
    assert_button "Café moído"
    assert_no_button "Leite"
    assert_no_selector "#category_#{categories(:dairy).id}"

    click_button "Café moído"
    assert_selector "##{dom_id(@coffee)} button[aria-pressed=true]"
    assert_field "Buscar na lista", with: "CAFE"
    click_button "Café moído"
    assert_selector "##{dom_id(@coffee)} button[aria-pressed=false]"

    fill_in "Buscar na lista", with: "inexistente"
    assert_text "Nenhum item encontrado."
    click_button "Limpar busca"
    assert_button "Leite"
    assert_equal "list_search", focused_element_id
  end

  test "only pending combines with search and exiting resets local filters" do
    visit_list
    click_button "Iniciar compras"
    open_search
    check "Só pendentes"
    assert_no_button "Detergente"
    fill_in "Buscar na lista", with: "café"
    click_button "Café moído"
    assert_no_button "Café moído"
    assert_text "Nenhum item encontrado."
    uncheck "Só pendentes"
    assert_button "Café moído"
    click_button "Sair das compras"
    assert_field "Buscar na lista", with: "", visible: :all
    assert_unchecked_field "Só pendentes", visible: :all
    assert_field "Buscar na lista"
    assert_button "Leite"
  end

  test "broadcasts preserve search focus and filter new rows locally" do
    visit_list
    open_search
    fill_in "Buscar na lista", with: "caf"
    household = households(:home)
    household.list_items.create!(name: "Café solúvel", category: categories(:other))
    household.list_items.create!(name: "Arroz", category: categories(:other))
    assert_button "Café solúvel"
    assert_no_button "Arroz"
    assert_field "Buscar na lista", with: "caf"
    assert_equal "list_search", focused_element_id
  end

  test "search stays visible and the header shortcut preserves filters" do
    visit_list
    assert_field "Buscar na lista"

    open_search
    fill_in "Buscar na lista", with: "caf"
    assert_no_button "Leite"

    click_button "Buscar"
    assert_field "Buscar na lista"
    assert_no_button "Leite"
    assert_button "Café moído"
    click_button "Limpar busca"
    assert_button "Leite"
  end
  test "shopping search is near the bottom without covering the pending filter" do
    visit_list
    assert_field "Buscar na lista"
    click_button "Iniciar compras"
    assert_field "Buscar na lista"
    check "Só pendentes"
    assert_checked_field "Só pendentes"
    position = page.evaluate_script("document.getElementById('list_search').getBoundingClientRect().top / window.innerHeight")
    assert_operator position, :>, 0.6
    click_button "Sair das compras"
    assert_field "Buscar na lista"
  end
  test "pending switch can be changed with the keyboard" do
    visit_list
    switch = find('input[role="switch"]')
    assert_unchecked_field "Só pendentes"
    assert_button "Detergente"
    switch.send_keys :space
    assert_checked_field "Só pendentes"
    assert_no_button "Detergente"
    switch.send_keys :space
    assert_unchecked_field "Só pendentes"
    assert_button "Detergente"
  end
end
