require "application_system_test_case"

class ListItemsTest < ApplicationSystemTestCase
  test "opens with the add field focused" do
    visit root_path
    assert_equal "list_item_name", page.evaluate_script("document.activeElement.id")
  end

  test "adding an item shows it in its category and keeps focus on the field" do
    visit root_path

    fill_in "Item", with: "maçã"
    click_button "Adicionar"

    within "#category_#{categories(:produce).id}" do
      assert_text "maçã"
    end
    assert_field "Item", with: ""
    assert_equal "list_item_name", page.evaluate_script("document.activeElement.id")
  end

  test "unknown item goes to Outros" do
    visit root_path

    fill_in "Item", with: "Coisa nova"
    send_keys :enter

    within("#category_#{categories(:other).id}") { assert_text "Coisa nova" }
  end

  test "hides empty categories" do
    visit root_path

    assert_selector "#category_#{categories(:dairy).id}"
    assert_no_selector "#category_#{categories(:produce).id}"
  end

  test "marking and unmarking as purchased" do
    visit root_path

    click_button "Leite"
    assert_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"

    click_button "Leite"
    assert_no_selector "##{dom_id(list_items(:milk))} .line-through"
    assert_selector "##{dom_id(list_items(:milk))}", text: "Leite"
  end

  test "changes show up live in another open session" do
    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
    end

    visit root_path
    fill_in "Item", with: "Banana"
    click_button "Adicionar"
    assert_selector "#category_#{categories(:produce).id}", text: "Banana"

    using_session(:other) do
      within("#category_#{categories(:produce).id}") { assert_text "Banana" }
    end

    click_button "Leite"
    assert_selector "##{dom_id(list_items(:milk))} .line-through"

    using_session(:other) do
      assert_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"
    end
  end
end
