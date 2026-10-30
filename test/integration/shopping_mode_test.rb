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
end
