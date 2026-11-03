require "application_system_test_case"

class JustAddedTest < ApplicationSystemTestCase
  test "what was just added shows its category and can be fixed on the spot" do
    visit root_path
    add_item "Tahine, Banana"

    within("#recent_adds") do
      assert_text "Acabou de adicionar"
      assert_select "Categoria de Banana", selected: "Hortifruti"
      assert_select "Categoria de Tahine", selected: "Outros"
      select "Laticínios", from: "Categoria de Tahine"
      assert_select "Categoria de Tahine", selected: "Laticínios"
    end

    show_list
    within("#category_#{categories(:dairy).id}") { assert_text "Tahine" }
    assert_equal categories(:dairy), ItemCategorizer.call("tahine")
  end

  test "typing a name that is already pending says so before sending" do
    visit root_path

    fill_in "Item", with: "pão, leite"
    assert_selector "#add_duplicate_hint", text: "Leite já está na lista"

    fill_in "Item", with: "pão"
    assert_no_selector "#add_duplicate_hint"
  end

  test "a purchased name is not flagged: adding it brings it back" do
    visit root_path

    fill_in "Item", with: "detergente"
    assert_no_selector "#add_duplicate_hint"
  end
end
