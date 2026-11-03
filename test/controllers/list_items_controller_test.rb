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
    assert_select "turbo-stream[action=replace][target=habitual_items_list]"
  end

  test "index offers archived items to buy again and nothing without history" do
    get root_path
    assert_select "#add_screen #buy_again", count: 1
    assert_select "#buy_again button", count: 0

    list_items(:detergent).update_columns(shopping_trip_id: households(:home).shopping_trips.create!(finished_at: 1.day.ago).id)
    get root_path
    assert_select "#add_screen #buy_again form[action='#{list_items_path}'] button", text: /Detergente/
    assert_select "#add_screen #buy_again input[name='list_item[name]'][value=Detergente]"
  end

  test "index as turbo stream refreshes the buy again chips" do
    get root_path, as: :turbo_stream
    assert_select "turbo-stream[action=replace][target=buy_again]"
  end

  test "create drops the added name from the buy again chips" do
    list_items(:detergent).update_columns(shopping_trip_id: households(:home).shopping_trips.create!(finished_at: 1.day.ago).id)

    post list_items_path, params: { list_item: { name: "Detergente" } }, as: :turbo_stream

    assert_select "turbo-stream[action=replace][target=buy_again]"
    assert_select "turbo-stream[target=buy_again] button", count: 0
  end

  test "destroy removes the item and refreshes the list" do
    assert_difference "ListItem.count", -1 do
      delete list_item_path(list_items(:milk)), as: :turbo_stream
    end
    assert_response :success
    assert_select "turbo-stream[target=list_items]"
  end

  test "destroy answers with an undo toast, not in the broadcast" do
    streams = assert_list_broadcast(households(:home)) do
      delete list_item_path(list_items(:milk)), as: :turbo_stream
    end

    assert_select "turbo-stream[action=update][target=toast]" do
      assert_select "[role=status][data-controller=toast]", text: /Leite removido/
      assert_select "form[action='#{restore_list_items_path}'] button", text: "Desfazer"
      assert_select "form[action='#{restore_list_items_path}'] input[name=token]"
    end
    assert_empty streams.select { |stream| stream["target"] == "toast" }
  end

  test "restore puts the removed item back exactly where it was" do
    milk = list_items(:milk)
    attributes = milk.attributes.except("updated_at")
    delete list_item_path(milk), as: :turbo_stream
    token = css_select("form[action='#{restore_list_items_path}'] input[name=token]").first["value"]

    assert_list_broadcast households(:home) do
      assert_difference "ListItem.count", 1 do
        post restore_list_items_path, params: { token: token }, as: :turbo_stream
      end
    end

    assert_response :success
    assert_equal attributes, ListItem.find(milk.id).attributes.except("updated_at")
    assert_select "turbo-stream[target=list_items]", text: /Leite/
    assert_select "turbo-stream[action=update][target=toast]"
    assert_select "[role=status]", count: 0
  end

  test "restore twice, or after the name was added again, adds nothing" do
    token = ListItemSnapshot.dump(list_items(:milk))
    list_items(:milk).destroy!

    post restore_list_items_path, params: { token: token }, as: :turbo_stream
    assert_no_difference "ListItem.count" do
      post restore_list_items_path, params: { token: token }, as: :turbo_stream
    end
    assert_response :success

    ListItem.find(list_items(:milk).id).destroy!
    households(:home).list_items.create!(name: "leite")
    assert_no_difference "ListItem.count" do
      post restore_list_items_path, params: { token: token }, as: :turbo_stream
    end
    assert_response :success
  end

  test "restore rejects tampered, expired and missing tokens" do
    token = ListItemSnapshot.dump(list_items(:milk))
    list_items(:milk).destroy!

    assert_no_difference "ListItem.count" do
      post restore_list_items_path, params: { token: token.sub(/.\z/) { |c| c == "a" ? "b" : "a" } }, as: :turbo_stream
      assert_response :not_found
      post restore_list_items_path, params: { token: "nonsense" }, as: :turbo_stream
      assert_response :not_found
      post restore_list_items_path, as: :turbo_stream
      assert_response :not_found
      post restore_list_items_path, params: { token: { name: "Leite" } }, as: :turbo_stream
      assert_response :not_found
      travel(ListItemSnapshot::EXPIRES_IN + 1.second) do
        post restore_list_items_path, params: { token: token }, as: :turbo_stream
        assert_response :not_found
      end
    end
  end

  test "restore rejects a snapshot of another household" do
    other = Household.create!(name: "Outra")
    token = ListItemSnapshot.dump(other.list_items.create!(name: "Arroz").tap(&:destroy!))

    assert_no_difference "ListItem.count" do
      post restore_list_items_path, params: { token: token }, as: :turbo_stream
    end
    assert_response :not_found
  end

  test "destroy only finds items of the current household" do
    other = Household.create!(name: "Outra").list_items.create!(name: "Arroz")

    delete list_item_path(other), as: :turbo_stream
    assert_response :not_found
    assert ListItem.exists?(other.id)
  end

  test "toggle and destroy resync with a notice for archived items" do
    trip = households(:home).shopping_trips.create!(finished_at: Time.current)
    archived = list_items(:detergent)
    archived.update_columns(shopping_trip_id: trip.id)

    patch toggle_list_item_path(archived), as: :turbo_stream
    assert_response :conflict
    assert_includes response.media_type, "turbo-stream"
    assert_includes response.body, "A lista mudou em outro aparelho"
    delete list_item_path(archived), as: :turbo_stream
    assert_response :conflict
    assert_includes response.body, "A lista mudou em outro aparelho"
    assert ListItem.exists?(archived.id)
  end

  test "a mutation from a stale row version resyncs without changing the item" do
    item = list_items(:milk)
    stale_version = item.lock_version
    item.toggle_purchased!

    patch toggle_list_item_path(item), params: { lock_version: stale_version }, as: :turbo_stream

    assert_response :conflict
    assert_includes response.body, "A lista mudou em outro aparelho"
    assert item.reload.purchased?
  end

  test "a stale correction and removal do not overwrite or delete a changed row" do
    item = households(:home).list_items.create!(name: "Bananna")
    stale_version = item.lock_version
    item.update!(name: "Banana nova")

    patch list_item_path(item), params: {
      list_item: { name: "Banana antiga", category_id: item.category_id, lock_version: stale_version }
    }, as: :turbo_stream
    assert_response :conflict
    assert_equal "Banana nova", item.reload.name

    delete list_item_path(item), params: { lock_version: stale_version }, as: :turbo_stream
    assert_response :conflict
    assert ListItem.exists?(item.id)
  end

  test "a malformed item id responds with a stale-list refresh" do
    patch toggle_list_item_path("not-a-number"), as: :turbo_stream

    assert_response :conflict
    assert_includes response.body, "A lista mudou em outro aparelho"
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

  test "create confirms the added item only to this device, in the add feedback" do
    streams = capture_turbo_stream_broadcasts(households(:home)) do
      post list_items_path, params: { list_item: { name: "Banana" } }, as: :turbo_stream
    end

    assert_select "turbo-stream[action=update][target=add_feedback] .text-accent-strong", text: "Banana adicionado em Hortifruti"
    assert_select "turbo-stream[action=replace][target=list_item_form] input#list_item_name[autofocus]"
    assert_select "turbo-stream[action=replace][target=list_counter] #list_counter", text: /\A2/
    assert streams.none? { |stream| stream["target"] == "add_feedback" }
    assert_not_includes streams.map(&:to_html).join, "adicionado em"
  end

  test "create shows what was just added, with a category picker, only to this device" do
    streams = capture_turbo_stream_broadcasts(households(:home)) do
      post list_items_path, params: { list_item: { name: "Banana" } }, as: :turbo_stream
    end

    banana = ListItem.find_by!(name: "Banana")
    assert_select "turbo-stream[action=update][target=recent_adds]" do
      assert_select "li##{ActionView::RecordIdentifier.dom_id(banana, :recent)}", text: /Banana/
      assert_select "form[action='#{list_item_path(banana)}'] input[name=from][value=recent]"
      assert_select "select[name='list_item[category_id]'] option[selected][value=?]", categories(:produce).id.to_s
    end
    assert streams.none? { |stream| stream["target"] == "recent_adds" }
  end

  test "an unknown name lands in Outros and its picker asks for a look" do
    post list_items_path, params: { list_item: { name: "Tahine" } }, as: :turbo_stream

    assert_select "turbo-stream[target=recent_adds] select.border-warn"
  end

  test "create with several names lists only the added ones as just added" do
    post list_items_path, params: { list_item: { name: "Banana, Leite" } }, as: :turbo_stream

    assert_select "turbo-stream[target=recent_adds] li", count: 1
    assert_select "turbo-stream[target=recent_adds] li", text: /Banana/
  end

  test "a duplicate leaves the just-added list alone" do
    post list_items_path, params: { list_item: { name: "Leite" } }, as: :turbo_stream

    assert_select "turbo-stream[target=recent_adds]", count: 0
  end

  test "picking a category under just added moves the item, learns it and refreshes that row" do
    post list_items_path, params: { list_item: { name: "Tahine" } }, as: :turbo_stream
    tahine = ListItem.find_by!(name: "Tahine")

    patch list_item_path(tahine), as: :turbo_stream, params: { from: "recent",
      list_item: { name: "Tahine", category_id: categories(:dairy).id, lock_version: tahine.lock_version } }

    assert_response :success
    assert_equal categories(:dairy), tahine.reload.category
    assert_equal categories(:dairy), ItemCategorizer.call("tahine")
    assert_select "turbo-stream[action=replace][target=list_items]"
    assert_select "turbo-stream[action=replace][target=#{ActionView::RecordIdentifier.dom_id(tahine, :recent)}] option[selected][value=?]", categories(:dairy).id.to_s
  end

  test "create with a pending duplicate keeps one item and points to it" do
    assert_no_difference "ListItem.count" do
      post list_items_path, params: { list_item: { name: "leite" } }, as: :turbo_stream
    end
    assert_response :success
    assert_select "turbo-stream[action=update][target=add_feedback] .text-warn", text: "Leite já está na lista"
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

  test "create with several names adds them all and summarizes in the add feedback" do
    assert_difference "ListItem.count", 2 do
      post list_items_path, params: { list_item: { name: "Banana, Arroz" } }, as: :turbo_stream
    end

    assert_response :success
    assert_equal "text/vnd.turbo-stream.html", response.media_type
    assert_select "turbo-stream[action=replace][target=list_items]"
    assert_select "turbo-stream[action=update][target=add_feedback]", text: "2 itens adicionados"
    assert_select "turbo-stream[action=replace][target=list_item_form]" do
      assert_select "datalist#list_item_name_suggestions option[value=Banana]", count: 1
      assert_select "input#list_item_name[value]", count: 0
    end
    ListItem.where(name: %w[Banana Arroz]).each do |item|
      assert_select "turbo-stream[action=replace][target=#{ActionView::RecordIdentifier.dom_id(item)}] li.animate-highlight"
    end
  end

  test "create with several names mentions the ones already on the list and highlights them" do
    purchased = list_items(:detergent)

    assert_difference "ListItem.count", 1 do
      post list_items_path, params: { list_item: { name: "leite; detergente\nBanana" } }, as: :turbo_stream
    end

    assert purchased.reload.pending?
    assert_select "turbo-stream[target=add_feedback]", text: "2 itens adicionados · Leite já estava na lista"
    [ list_items(:milk), purchased, ListItem.find_by!(name: "Banana") ].each do |item|
      assert_select "turbo-stream[action=replace][target=#{ActionView::RecordIdentifier.dom_id(item)}] li.animate-highlight"
    end
  end

  test "create with several names counts revived items as added and says when nothing was added" do
    assert_no_difference "ListItem.count" do
      post list_items_path, params: { list_item: { name: "Leite, leite, Detergente" } }, as: :turbo_stream
    end

    assert_select "turbo-stream[target=add_feedback]", text: "1 item adicionado · Leite já estava na lista"

    assert_no_difference "ListItem.count" do
      post list_items_path, params: { list_item: { name: "Leite, Detergente" } }, as: :turbo_stream
    end

    assert_select "turbo-stream[target=add_feedback] .text-warn", text: "Nada adicionado · Leite e Detergente já estavam na lista"
  end

  test "create with several names broadcasts the list once" do
    assert_list_broadcast households(:home) do
      post list_items_path, params: { list_item: { name: "Arroz, Feijão, Banana, Leite" } }, as: :turbo_stream
    end
  end

  test "create with several names redirects for html" do
    post list_items_path, params: { list_item: { name: "Arroz, Feijão" } }

    assert_redirected_to root_path
    assert_equal 2, ListItem.where(name: %w[Arroz Feijão]).count
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
    assert_select "form[action='#{shopping_trips_path}']:not([data-turbo-confirm])" do
      assert_select "button", text: "Compra feita"
    end
  end

  test "hides the finish shopping button when nothing was purchased" do
    list_items(:detergent).destroy!
    get root_path
    assert_select "form[action='#{shopping_trips_path}']", count: 0
  end
end
