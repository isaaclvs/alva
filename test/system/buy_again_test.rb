require "application_system_test_case"

class BuyAgainTest < ApplicationSystemTestCase
  test "no chips without purchase history" do
    visit root_path

    assert_selector "#buy_again", visible: :all
    assert_no_text "Comprar de novo"
  end

  test "an item bought on a finished trip shows up as a chip that adds it back" do
    visit root_path
    click_button "Leite"
    assert_selector "##{dom_id(list_items(:milk))} .line-through"

    accept_confirm { click_on "Compra feita" }

    within("#buy_again") do
      assert_text "Comprar de novo"
      assert_button "Leite"
      click_button "Leite"
    end

    assert_selector "#list_items li", text: "Leite", count: 1
    assert_no_selector "#list_items li .line-through", text: "Leite"
    assert_no_selector "#buy_again button", text: "Leite"
    assert_selector "#buy_again button", text: "Detergente"
    assert_equal "pending", ListItem.on_list.find_by!(name: "Leite").status
  end

  test "chips update live on another open session" do
    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
      assert_no_text "Comprar de novo"
    end

    visit root_path
    accept_confirm { click_on "Compra feita" }
    within("#buy_again") { assert_button "Detergente" }

    using_session(:other) do
      within("#buy_again") { assert_button "Detergente" }
    end

    click_button "Detergente"
    assert_selector "#list_items li", text: "Detergente"

    using_session(:other) do
      assert_selector "#list_items li", text: "Detergente"
      assert_no_text "Comprar de novo"
    end
  end
end
