require "application_system_test_case"

class PurchaseUpdatesTest < ApplicationSystemTestCase
  test "purchasing broadcasts the category without replacing other categories" do
    using_session(:other_shopper) do
      visit_list
      connect_turbo_cable_stream_sources
      page.execute_script("document.querySelector('#uncategorized').dataset.clientMarker = 'preserved'")
    end
    visit_list
    click_button "Leite"
    assert_selector "##{dom_id(list_items(:milk))} button[aria-pressed=true]"
    using_session(:other_shopper) do
      assert_selector "##{dom_id(list_items(:milk))} button[aria-pressed=true]"
      assert_selector "#uncategorized[data-client-marker=preserved]"
    end
  end

  test "category updates preserve correction drafts in the same and other categories" do
    household = households(:home)
    cheese = household.list_items.create!(name: "Queijo", category: categories(:dairy))
    visit_list
    click_link "Editar Leite"
    form = "##{dom_id(list_items(:milk), :row)}"
    within(form) { fill_in "Nome", with: "Leite sem lactose" }
    cheese.toggle_purchased!
    within(form) { assert_field "Nome", with: "Leite sem lactose" }
    assert_selector "##{dom_id(cheese)} button[aria-pressed=true]"
    list_items(:detergent).toggle_purchased!
    assert_selector "##{dom_id(list_items(:detergent))} button[aria-pressed=false]"
    within(form) { assert_field "Nome", with: "Leite sem lactose" }
    assert_no_text "Este item saiu da lista"
    within(form) { click_button "Salvar" }
    assert_text "Leite sem lactose"
  end
end
