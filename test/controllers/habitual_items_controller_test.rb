require "test_helper"

class HabitualItemsControllerTest < ActionDispatch::IntegrationTest
  test "create trims a habit and broadcasts only the habitual section" do
    streams = capture_turbo_stream_broadcasts(households(:home)) do
      post habitual_items_path, params: { habitual_item: { name: "  Café  " } }, as: :turbo_stream
    end

    assert_response :success
    assert_equal "Café", households(:home).habitual_items.sole.name
    assert_equal [ %w[replace habitual_items_list] ], streams.map { |stream| [ stream["action"], stream["target"] ] }
    assert_select "turbo-stream[target=habitual_item_form] input[name='habitual_item[name]']"
    assert_select "turbo-stream[target=toast]", text: /Café salvo nos habituais/
  end

  test "duplicate normalized habitual items receive feedback without adding a second row" do
    households(:home).habitual_items.create!(name: "Café")

    assert_no_difference "HabitualItem.count" do
      post habitual_items_path, params: { habitual_item: { name: " cafe " } }, as: :turbo_stream
    end

    assert_response :unprocessable_entity
    assert_select "turbo-stream[target=habitual_item_form]", text: /Esse item habitual já está salvo/
    assert_select "turbo-stream[target=habitual_item_form] input[name='habitual_item[name]'][value='cafe']"
  end

  test "blank habits receive feedback" do
    assert_no_difference "HabitualItem.count" do
      post habitual_items_path, params: { habitual_item: { name: "  " } }, as: :turbo_stream
    end

    assert_response :unprocessable_entity
    assert_select "turbo-stream[target=habitual_item_form]", text: /Digite o nome do item habitual/
    assert_select "turbo-stream[target=habitual_item_form] input[name='habitual_item[name]']"
  end

  test "names over the form limit preserve the draft and explain the limit" do
    long_name = "a" * 101

    assert_no_difference "HabitualItem.count" do
      post habitual_items_path, params: { habitual_item: { name: long_name } }, as: :turbo_stream
    end

    assert_response :unprocessable_entity
    assert_select "turbo-stream[target=habitual_item_form]", text: /até 100 caracteres/
    assert_select "turbo-stream[target=habitual_item_form] input[name='habitual_item[name]'][value='#{long_name}']"
  end

  test "selected habits use add_item rules and make one list broadcast" do
    household = households(:home)
    selected = household.habitual_items.create!(name: "Arroz")
    household.habitual_items.create!(name: "Leite")

    streams = assert_list_broadcast(household) do
      post add_selected_habitual_items_path,
        params: { habitual_item_ids: [ selected.id ] }, as: :turbo_stream
    end

    assert_response :success
    assert_equal [ "Arroz" ], household.list_items.on_list.pending.order(:name).pluck(:name) - [ "Leite" ]
    assert_select "turbo-stream[target=toast]", text: /1 adicionados à lista/
    assert_equal 4, streams.length
  end

  test "selected habits report names already on the list and do not duplicate them" do
    household = households(:home)
    habit = household.habitual_items.create!(name: "Leite")

    assert_no_difference "ListItem.count" do
      post add_selected_habitual_items_path, params: { habitual_item_ids: [ habit.id ] }, as: :turbo_stream
    end

    assert_response :success
    assert_select "turbo-stream[target=toast]", text: /0 adicionados, 1 já estavam na lista/
  end

  test "malformed and cross-household selected ids add nothing" do
    other = Household.create!(name: "Outra")
    foreign_habit = other.habitual_items.create!(name: "Arroz")
    [ [ "oops" ], [ foreign_habit.id ] ].each do |ids|
      assert_no_difference "ListItem.count" do
        post add_selected_habitual_items_path, params: { habitual_item_ids: ids }, as: :turbo_stream
      end

      assert_response :unprocessable_entity
      assert_select "turbo-stream[target=toast]", text: /Selecione hábitos válidos/
    end
  end

  test "add all handles more than the typed-input limit without truncating" do
    household = households(:home)
    31.times { |index| household.habitual_items.create!(name: "Item de teste #{index}") }

    streams = assert_list_broadcast(household) do
      post add_all_habitual_items_path, as: :turbo_stream
    end

    assert_response :success
    assert_equal 31, household.list_items.on_list.pending.where("name LIKE 'Item de teste %'").count
    assert_equal 4, streams.length
  end

  test "remove is scoped to this household and does not delete list items" do
    habit = households(:home).habitual_items.create!(name: "Leite")
    other = Household.create!(name: "Outra")
    foreign_habit = other.habitual_items.create!(name: "Arroz")

    assert_no_difference "ListItem.count" do
      delete habitual_item_path(habit), as: :turbo_stream
    end
    assert_response :success
    assert_not HabitualItem.exists?(habit.id)
    assert ListItem.exists?(list_items(:milk).id)

    delete habitual_item_path(foreign_habit), as: :turbo_stream
    assert_response :not_found
    assert HabitualItem.exists?(foreign_habit.id)
  end
end
