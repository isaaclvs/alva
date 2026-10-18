require "application_system_test_case"

class UndoTest < ApplicationSystemTestCase
  test "removing an item and undoing puts it back in the same place" do
    milk = list_items(:milk)
    households(:home).list_items.create!(name: "Iogurte", category: categories(:dairy))
    visit root_path
    dairy = "#category_#{categories(:dairy).id} li"
    assert_equal %w[Leite Iogurte], all(dairy).map { |li| li.text.strip }

    click_button "Remover Leite"

    within("#toast") do
      assert_selector "[role=status]", text: "Leite removido"
      assert_button "Desfazer"
    end
    assert_no_selector "##{dom_id(milk)}"

    within("#toast") { click_button "Desfazer" }

    assert_selector "##{dom_id(milk)}", text: "Leite"
    assert_equal %w[Leite Iogurte], all(dairy).map { |li| li.text.strip }
    assert_no_selector "#toast [role=status]"
    assert_equal 1, ListItem.where(name: "Leite").count
  end

  test "undoing the removal of a purchased item keeps it purchased" do
    detergent = list_items(:detergent)
    visit root_path

    click_button "Remover Detergente"
    assert_selector "#toast", text: "Detergente removido"
    click_button "Desfazer"

    assert_selector "##{dom_id(detergent)} .line-through", text: "Detergente"
  end

  test "finishing the shopping and undoing brings the purchased items back" do
    visit root_path

    click_on "Compra feita"

    assert_no_selector "#list_items", text: "Detergente"
    within("#toast") { assert_selector "[role=status]", text: "Compra registrada" }
    assert_equal 1, ShoppingTrip.count

    within("#toast") { click_button "Desfazer" }

    assert_selector "##{dom_id(list_items(:detergent))} .line-through", text: "Detergente"
    assert_button "Compra feita"
    assert_no_selector "#toast [role=status]"
    assert_equal 0, ShoppingTrip.count
    assert_nil list_items(:detergent).reload.shopping_trip_id
  end

  test "the toast goes away by itself" do
    visit root_path
    click_button "Remover Leite"
    assert_selector "#toast [role=status]"

    # The controller's default delay is 5 seconds.
    using_wait_time(8) { assert_no_selector "#toast [role=status]" }
    assert_no_selector "#list_items", text: "Leite"
  end

  test "a new toast replaces the previous one" do
    visit root_path

    click_button "Remover Leite"
    assert_selector "#toast", text: "Leite removido"
    click_button "Remover Detergente"

    assert_selector "#toast", text: "Detergente removido"
    assert_selector "#toast [role=status]", count: 1
    assert_no_selector "#toast", text: "Leite removido"
  end

  test "the other device sees the change but not the toast, and the undo reaches it too" do
    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
      assert_text "Leite"
    end

    visit root_path
    click_button "Remover Leite"
    assert_selector "#toast", text: "Leite removido"

    using_session(:other) do
      assert_no_selector "#list_items", text: "Leite"
      assert_no_selector "#toast [role=status]"
    end

    click_button "Desfazer"

    using_session(:other) do
      assert_text "Leite"
      assert_no_selector "#toast [role=status]"
    end
  end
end
