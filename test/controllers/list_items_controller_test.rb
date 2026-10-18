require "test_helper"

class ListItemsControllerTest < ActionDispatch::IntegrationTest
  test "index groups items by category" do
    get root_path
    assert_response :success
    assert_select "#category_#{categories(:dairy).id}", text: /Leite/
    assert_select "#uncategorized", text: /Detergente/
  end

  test "create adds a categorized item via turbo stream" do
    assert_difference "ListItem.count", 1 do
      post list_items_path, params: { list_item: { name: "Banana" } }, as: :turbo_stream
    end
    assert_response :success
    assert_equal categories(:produce), ListItem.last.category
    assert_equal households(:home), ListItem.last.household
  end

  test "index offers catalog and household names in a datalist linked to the add field" do
    get root_path
    assert_select "input#list_item_name[list=list_item_name_suggestions]"
    assert_select "datalist#list_item_name_suggestions option[value=Leite]", count: 1
    assert_select "datalist#list_item_name_suggestions option[value=Banana]", count: 1
    assert_select "datalist#list_item_name_suggestions option[value=Detergente]", count: 1
  end

  test "create refreshes the datalist with the new name, once" do
    post list_items_path, params: { list_item: { name: "Coisa nova" } }, as: :turbo_stream

    assert_select "turbo-stream[action=replace][target=list_item_form] datalist#list_item_name_suggestions" do
      assert_select "option[value='Coisa nova']", count: 1
      assert_select "option[value=Leite]", count: 1
    end
  end

  test "the form keeps its datalist when the item is a duplicate" do
    post list_items_path, params: { list_item: { name: "leite" } }, as: :turbo_stream

    assert_select "turbo-stream[target=list_item_form] datalist#list_item_name_suggestions option[value=Leite]", count: 1
  end

  test "the form keeps its datalist when the item is invalid" do
    post list_items_path, params: { list_item: { name: "" } }, as: :turbo_stream

    assert_response :unprocessable_entity
    assert_select "turbo-stream[target=list_item_form] datalist#list_item_name_suggestions option[value=Leite]", count: 1
  end

  test "index as turbo stream refreshes the summary and the list" do
    get root_path, as: :turbo_stream
    assert_response :success
    assert_select "turbo-stream[action=replace][target=list_summary]"
    assert_select "turbo-stream[action=replace][target=list_items]", text: /Leite/
  end

  test "destroy removes the item and refreshes the list" do
    assert_difference "ListItem.count", -1 do
      delete list_item_path(list_items(:milk)), as: :turbo_stream
    end
    assert_response :success
    assert_select "turbo-stream[target=list_items]"
  end

  test "destroy only finds items of the current household" do
    other = Household.create!(name: "Outra").list_items.create!(name: "Arroz")

    delete list_item_path(other), as: :turbo_stream
    assert_response :not_found
    assert ListItem.exists?(other.id)
  end

  test "toggle and destroy don't find archived items" do
    trip = households(:home).shopping_trips.create!(finished_at: Time.current)
    archived = list_items(:detergent)
    archived.update_columns(shopping_trip_id: trip.id)

    patch toggle_list_item_path(archived), as: :turbo_stream
    assert_response :not_found
    delete list_item_path(archived), as: :turbo_stream
    assert_response :not_found
    assert ListItem.exists?(archived.id)
  end

  test "index subscribes to the household stream" do
    get root_path
    assert_select "turbo-cable-stream-source[signed-stream-name]"
  end

  test "create broadcasts to the household stream" do
    assert_list_broadcast households(:home) do
      post list_items_path, params: { list_item: { name: "Arroz" } }, as: :turbo_stream
    end
  end

  test "create with a pending duplicate keeps one item and points to it" do
    assert_no_difference "ListItem.count" do
      post list_items_path, params: { list_item: { name: "leite" } }, as: :turbo_stream
    end
    assert_response :success
    assert_includes response.body, "Leite já está na lista"
    assert_includes response.body, %(target="#{ActionView::RecordIdentifier.dom_id(list_items(:milk))}")
    assert_includes response.body, "animate-highlight"
  end

  test "create with a purchased name revives that item instead of adding a row" do
    purchased = list_items(:detergent)

    assert_no_difference "ListItem.count" do
      post list_items_path, params: { list_item: { name: "detergente" } }, as: :turbo_stream
    end

    assert_response :success
    assert purchased.reload.pending?
    assert_nil purchased.purchased_at
    assert_equal "text/vnd.turbo-stream.html", response.media_type
    assert_select "turbo-stream[action=replace][target=list_item_form]"
    assert_select "turbo-stream[action=replace][target=#{ActionView::RecordIdentifier.dom_id(purchased)}]"
    assert_includes response.body, "animate-highlight"
  end

  test "create with a purchased name broadcasts the list" do
    assert_list_broadcast households(:home) do
      post list_items_path, params: { list_item: { name: "Detergente" } }, as: :turbo_stream
    end
  end

  test "create with blank name re-renders the form" do
    assert_no_difference "ListItem.count" do
      post list_items_path, params: { list_item: { name: " " } }, as: :turbo_stream
    end
    assert_response :unprocessable_entity
  end

  test "toggle flips purchased state" do
    item = list_items(:milk)

    patch toggle_list_item_path(item), as: :turbo_stream
    assert item.reload.purchased?
    assert_not_nil item.purchased_at

    patch toggle_list_item_path(item), as: :turbo_stream
    assert item.reload.pending?
    assert_nil item.purchased_at
  end

  test "shows the finish shopping button when something was purchased" do
    get root_path
    assert_select "form[action='#{shopping_trips_path}'][data-turbo-confirm='Registrar a compra e começar uma nova lista?']" do
      assert_select "button", text: "Compra feita"
    end
  end

  test "hides the finish shopping button when nothing was purchased" do
    list_items(:detergent).destroy!
    get root_path
    assert_select "form[action='#{shopping_trips_path}']", count: 0
  end
end
