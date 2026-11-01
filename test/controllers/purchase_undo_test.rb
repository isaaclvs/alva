require "test_helper"

class PurchaseUndoTest < ActionDispatch::IntegrationTest
  def toggle(item)
    patch toggle_list_item_path(item), as: :turbo_stream
    assert_response :success
    css_select("form[action='#{undo_purchase_list_item_path(item)}'] input[name=token]").first["value"]
  end

  test "undo restores a purchase once with a category broadcast and a local toast" do
    item = list_items(:milk)
    streams = capture_turbo_stream_broadcasts(households(:home)) { @token = toggle(item) }
    assert_select "#toast", count: 0 # Only the container's contents are sent.
    assert_select "turbo-stream[target=toast]", text: /Leite marcado/
    assert_empty streams.select { |s| s["target"] == "toast" }
    streams = capture_turbo_stream_broadcasts(households(:home)) do
      post undo_purchase_list_item_path(item), params: { token: @token }, as: :turbo_stream
    end
    assert_response :success
    assert item.reload.pending?
    assert_nil item.purchased_at
    assert_equal [ "list_summary", dom_id(item.category), "list_finish", "list_counter" ], streams.map { |s| s["target"] }
    assert_select "turbo-stream[target=toast]", text: ""

    post undo_purchase_list_item_path(item), params: { token: @token }, as: :turbo_stream
    assert_response :conflict
    assert item.reload.pending?
  end

  test "undo of unchecking restores the original purchase time" do
    item = list_items(:detergent)
    original = item.purchased_at
    token = toggle(item)
    assert item.reload.pending?
    post undo_purchase_list_item_path(item), params: { token: token }, as: :turbo_stream
    assert_response :success
    assert item.reload.purchased?
    assert_equal original, item.purchased_at
  end

  test "invalid, expired, cross-item and cross-household tokens are rejected" do
    item = list_items(:milk)
    token = toggle(item)
    post undo_purchase_list_item_path(item), params: { token: token + "tampered" }, as: :turbo_stream
    assert_response :not_found
    post undo_purchase_list_item_path(list_items(:detergent)), params: { token: token }, as: :turbo_stream
    assert_response :not_found
    travel 2.minutes do
      post undo_purchase_list_item_path(item), params: { token: token }, as: :turbo_stream
      assert_response :not_found
    end
    other = Household.create!(name: "Outra casa")
    other_item = other.list_items.create!(name: "Arroz")
    foreign = PurchaseUndo.dump(other_item, status: "pending", purchased_at: nil)
    post undo_purchase_list_item_path(other_item), params: { token: foreign }, as: :turbo_stream
    assert_response :not_found
    assert item.reload.purchased?
  end

  test "later edits, toggles and archiving cannot be overwritten by undo" do
    item = list_items(:milk)
    token = toggle(item)
    item.reload.correct(name: "Leite integral", category_id: nil)
    post undo_purchase_list_item_path(item), params: { token: token }, as: :turbo_stream
    assert_response :conflict
    assert item.reload.purchased?
    assert_equal "Leite integral", item.name

    token = toggle(item)
    item.reload.toggle_purchased!
    post undo_purchase_list_item_path(item), params: { token: token }, as: :turbo_stream
    assert_response :conflict
    assert item.reload.purchased?

    toggle(item)
    token = toggle(item)
    households(:home).finish_shopping!
    post undo_purchase_list_item_path(item), params: { token: token }, as: :turbo_stream
    assert_response :conflict
    assert item.reload.shopping_trip_id
  end

  test "a removed item is not recreated by purchase undo" do
    item = list_items(:milk)
    token = toggle(item)
    item.reload.destroy!
    assert_no_difference "ListItem.count" do
      post undo_purchase_list_item_path(item), params: { token: token }, as: :turbo_stream
    end
    assert_response :conflict
  end

  test "undo does not create a duplicate pending product" do
    item = list_items(:milk)
    token = toggle(item)
    households(:home).list_items.create!(name: "LEITES")
    post undo_purchase_list_item_path(item), params: { token: token }, as: :turbo_stream
    assert_response :conflict
    assert item.reload.purchased?
  end
end
