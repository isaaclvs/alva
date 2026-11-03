require "test_helper"

class BuyAgainViewTest < ActionView::TestCase
  helper ListItemsHelper

  def suggestion(name, category = nil)
    BuyAgainSuggestions::Suggestion.new(name, category, 1, Time.current)
  end

  test "renders one add button per suggestion with a quiet add affordance" do
    render partial: "list_items/buy_again", locals: { suggestions: [ suggestion("Leite", categories(:dairy)), suggestion("Arroz") ] }

    assert_select "#buy_again [role=group][aria-labelledby=buy_again_title]"
    assert_select "#buy_again h2#buy_again_title", text: "Comprar de novo"
    assert_select "#buy_again form[action='#{list_items_path}'][method=post]", count: 2
    assert_select "#buy_again input[name='list_item[name]'][value=Leite]"
    assert_select "#buy_again button", text: /Leite/
    assert_select "#buy_again button", text: /Arroz/
    assert_select "#buy_again button span[aria-hidden=true]", text: "+", count: 2
  end

  test "renders only the empty placeholder without suggestions" do
    render partial: "list_items/buy_again", locals: { suggestions: [] }

    assert_select "#buy_again", count: 1
    assert_select "#buy_again *", count: 0
    assert_no_match "Comprar de novo", rendered
  end

  test "escapes names" do
    render partial: "list_items/buy_again", locals: { suggestions: [ suggestion(%(<b>"x"</b>)) ] }

    assert_select "#buy_again b", count: 0
  end
end
