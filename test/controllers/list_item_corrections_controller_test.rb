require "test_helper"

class ListItemCorrectionsControllerTest < ActionDispatch::IntegrationTest
  FRAME = { "Turbo-Frame" => "list_item_row" }.freeze
  STREAM = { "Accept" => "text/vnd.turbo-stream.html" }.freeze

  test "edit renders the form inside the row frame" do
    item = list_items(:milk)

    get edit_list_item_path(item), headers: { "Turbo-Frame" => ActionView::RecordIdentifier.dom_id(item, :row) }

    assert_response :success
    assert_select "turbo-frame##{ActionView::RecordIdentifier.dom_id(item, :row)} form" do
      assert_select "input[name='list_item[name]'][value=Leite]"
      assert_select "select[name='list_item[category_id]'] option[selected][value=?]", item.category_id.to_s
    end
  end

  test "edit offers Sem categoria only for an uncategorized item" do
    get edit_list_item_path(list_items(:milk)), headers: FRAME
    assert_select "option", text: Category::NONE_LABEL, count: 0

    get edit_list_item_path(list_items(:detergent)), headers: FRAME
    assert_select "option[value='']", text: Category::NONE_LABEL
  end

  test "show renders the row back for Cancelar" do
    get list_item_path(list_items(:milk)), headers: FRAME

    assert_response :success
    assert_select "turbo-frame form[action=?]", "/list_items/#{list_items(:milk).id}/toggle"
  end

  test "edit and show opened outside the frame go back to the list" do
    get edit_list_item_path(list_items(:milk))
    assert_redirected_to root_path

    get list_item_path(list_items(:milk))
    assert_redirected_to root_path
  end

  test "update renames and recategorizes, refreshing the list" do
    item = households(:home).list_items.create!(name: "Bananna")

    patch list_item_path(item), params: { list_item: { name: "Banana", category_id: item.category_id } }, headers: STREAM

    assert_response :success
    assert_equal "text/vnd.turbo-stream.html", response.media_type
    assert_equal [ "Banana", categories(:produce) ], [ item.reload.name, item.category ]
    assert_select "turbo-stream[action=replace][target=list_items]"
  end

  test "update learns a category picked by hand" do
    item = households(:home).list_items.create!(name: "Ração")

    patch list_item_path(item), params: { list_item: { name: "Ração", category_id: categories(:produce).id } }, headers: STREAM

    assert_equal categories(:produce), item.reload.category
    assert_equal categories(:produce), ItemCategorizer.call("Ração")
  end

  test "update with a blank or duplicate name renders the form again with a message" do
    item = households(:home).list_items.create!(name: "Banana")

    patch list_item_path(item), params: { list_item: { name: " ", category_id: item.category_id } }, headers: FRAME
    assert_response :unprocessable_entity
    assert_select "form p", text: "Digite um nome"

    patch list_item_path(item), params: { list_item: { name: "leite", category_id: item.category_id } }, headers: FRAME
    assert_response :unprocessable_entity
    assert_select "form p", text: "leite já está na lista"
    assert_equal "Banana", item.reload.name
  end

  test "edit returns 404 off-list and update resyncs an archived row" do
    trip = households(:home).finish_shopping!
    archived = trip.list_items.first
    other = Household.create!(name: "Outra").list_items.create!(name: "Arroz")

    [ archived, other ].each do |item|
      get edit_list_item_path(item), headers: FRAME
      assert_response :not_found
      patch list_item_path(item), params: { list_item: { name: "X", category_id: "" } }, headers: STREAM
      assert_response(item == archived ? :conflict : :not_found)
      assert_includes response.body, "A lista mudou em outro aparelho" if item == archived
    end
  end
end
