require "application_system_test_case"

class PurchaseUndoTest < ApplicationSystemTestCase
  test "undo brings back an item hidden by the pending filter only on the acting device" do
    using_session(:other_shopper) do
      visit_list
      connect_turbo_cable_stream_sources
    end
    visit_list
    click_button "Iniciar compras"
    check "Só pendentes"
    click_button "Leite"
    assert_no_button "Leite"
    within("#toast") { assert_text "Leite marcado" }
    using_session(:other_shopper) do
      assert_selector "##{dom_id(list_items(:milk))} button[aria-pressed=true]"
      assert_no_button "Desfazer"
    end
    within("#toast") { click_button "Desfazer" }
    assert_button "Leite"
    assert_checked_field "Só pendentes"
    assert_selector 'html[data-shopping-mode="on"]'
    assert_no_button "Desfazer"
    using_session(:other_shopper) do
      assert_selector "##{dom_id(list_items(:milk))} button[aria-pressed=false]"
      assert_no_button "Desfazer"
    end
  end

  test "a new marking replaces the previous undo" do
    coffee = households(:home).list_items.create!(name: "Café", category: categories(:other))
    visit_list
    click_button "Iniciar compras"
    click_button "Leite"
    within("#toast") { assert_text "Leite marcado" }
    click_button "Café"
    within("#toast") do
      assert_text "Café marcado"
      assert_no_text "Leite marcado"
      click_button "Desfazer"
    end
    assert_selector "##{dom_id(coffee)} button[aria-pressed=false]"
    assert_selector "##{dom_id(list_items(:milk))} button[aria-pressed=true]"
  end
end
