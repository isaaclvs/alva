require "application_system_test_case"

class ListItemCorrectionsTest < ApplicationSystemTestCase
  test "fixing a typo in place recategorizes the item" do
    households(:home).list_items.create!(name: "Bananna")
    visit_list

    click_on "Editar Bananna"
    fill_in "Nome", with: "Banana"
    click_on "Salvar"

    within("#category_#{categories(:produce).id}") { assert_text "Banana" }
    assert_no_text "Bananna"
    assert_no_field "Nome"
  end

  test "picking a category moves the item and the next one with that name lands there" do
    households(:home).list_items.create!(name: "Ração")
    visit_list

    click_on "Editar Ração"
    select "Hortifruti", from: "Categoria"
    click_on "Salvar"
    within("#category_#{categories(:produce).id}") { assert_text "Ração" }
    assert_no_field "Categoria"

    remove_item "Ração"
    show_add
    add_item "rações"
    assert_text "rações adicionado em Hortifruti"
    show_list

    within("#category_#{categories(:produce).id}") { assert_text "rações" }
  end

  test "cancelling brings the row back unchanged" do
    visit_list

    click_on "Editar Leite"
    fill_in "Nome", with: "Outra coisa"
    click_on "Cancelar"

    assert_button "Leite"
    assert_no_field "Nome"
    assert_equal "Leite", list_items(:milk).reload.name
  end

  test "a duplicate name keeps the form open with a message" do
    households(:home).list_items.create!(name: "Banana")
    visit_list

    click_on "Editar Banana"
    fill_in "Nome", with: "leite"
    click_on "Salvar"

    assert_text "leite já está na lista"
    assert_field "Nome", with: "leite"
  end
end
