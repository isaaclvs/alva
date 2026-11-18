require "test_helper"

class BuyAgainViewTest < ActionView::TestCase
  helper ListItemsHelper

  def suggestion(name, category = nil, cadence_days: nil, due: false)
    BuyAgainSuggestions::Suggestion.new(name, category, 1, Time.current, cadence_days, due)
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

  # --- cadence (issue #163) ---

  test "a due suggestion says how often it runs out and offers Agora não" do
    render partial: "list_items/buy_again", locals: { suggestions: [ suggestion("Arroz", nil, cadence_days: 12, due: true) ] }

    assert_select "[data-buy-again-suggestion=Arroz]", text: /costuma acabar a cada ~12 dias/
    assert_select "[data-buy-again-suggestion=Arroz] button[data-buy-again-dismiss=Arroz]", text: "Agora não"
    assert_select "[data-buy-again-suggestion=Arroz] form[action='#{list_items_path}'] button", text: /Arroz/
  end

  test "a suggestion that is not due carries no label and no Agora não" do
    render partial: "list_items/buy_again", locals: { suggestions: [ suggestion("Arroz", nil, cadence_days: 12) ] }

    assert_select "[data-buy-again-suggestion=Arroz]"
    assert_no_match "costuma acabar", rendered
    assert_select "button[data-buy-again-dismiss]", count: 0
  end

  test "a suggestion without enough history has no cadence to show" do
    render partial: "list_items/buy_again", locals: { suggestions: [ suggestion("Arroz") ] }

    assert_no_match(/costuma acabar/, rendered)
  end

  test "a cadence of one day reads todo dia" do
    render partial: "list_items/buy_again", locals: { suggestions: [ suggestion("Pão", nil, cadence_days: 1, due: true) ] }

    assert_select "[data-buy-again-suggestion=Pão]", text: /costuma acabar todo dia/
  end
end
