require "test_helper"

class ShoppingModeTest < ActionDispatch::IntegrationTest
  test "normal rendering has no shopping marker" do
    get root_path

    assert_select "html[data-shopping-mode]", count: 0
  end

  test "the shopping cookie renders the mode on the server and forces the list screen" do
    cookies[:screen] = "add"
    cookies[:shopping_mode] = "on"
    get root_path

    assert_select "html[data-shopping-mode=on]"
    assert_select "#list_screen:not([hidden])"
    assert_select "#add_screen[hidden]"
    assert_select "#nav_list[aria-current=page]"
    assert_select "input#list_item_name[autofocus]", count: 0
    assert_equal "add", cookies[:screen]
  end

  test "unknown shopping cookie values and the old market cookie leave the mode off" do
    cookies[:shopping_mode] = "yes"
    cookies[:market_mode] = "true"
    get root_path

    assert_select "html[data-shopping-mode]", count: 0
  end

  test "the list header offers Iniciar compras with items, even when all are purchased" do
    get root_path
    assert_select "#list_screen #list_summary button[data-action='shopping#start']", text: "Iniciar compras"
    assert_select "#list_screen button[data-action='shopping#exit']", text: "Sair das compras"

    list_items(:milk).update!(status: :purchased, purchased_at: Time.current)
    get root_path
    assert_select "#list_summary button[data-action='shopping#start']", text: "Iniciar compras"
  end

  test "an empty list has no Iniciar compras but a plain message" do
    ListItem.delete_all
    get root_path

    assert_select "#list_summary button[data-action='shopping#start']", count: 0
    assert_select "#list_items", text: /Nada na lista por enquanto/
    assert_select "#list_screen button[data-action='shopping#exit']"
  end

  test "row controls are tagged to be hidden while shopping and the check keeps its name" do
    get root_path

    assert_select "#list_items a[href='#{edit_list_item_path(list_items(:milk))}'].shopping-hide"
    assert_select "#list_items form.shopping-hide[action='#{list_item_path(list_items(:milk))}']"
    assert_select "#list_items form[action='#{toggle_list_item_path(list_items(:milk))}']:not(.shopping-hide) button.shopping-toggle", text: /Leite/
    assert_select "#list_items form[action='#{shopping_trips_path}']:not(.shopping-hide)"
  end

  test "list refresh fragments do not depend on the shopping cookie" do
    get root_path, as: :turbo_stream
    normal = response.body
    cookies[:shopping_mode] = "on"
    get root_path, as: :turbo_stream

    assert_equal normal, response.body
  end
end
