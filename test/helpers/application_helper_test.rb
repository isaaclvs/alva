require "test_helper"

class ApplicationHelperTest < ActionView::TestCase
  test "theme defaults to dark without a cookie" do
    assert_equal "dark", theme_name
  end

  test "theme follows the saved cookie" do
    cookies[:theme] = "light"
    assert_equal "light", theme_name
  end

  test "theme ignores unknown cookie values" do
    cookies[:theme] = "<script>"
    assert_equal "dark", theme_name
  end

  test "theme color matches the theme" do
    assert_equal "#09090B", theme_color("dark")
    assert_equal "#F4F4F5", theme_color("light")
  end

  test "screen defaults to add" do
    assert_equal "add", current_screen
  end

  test "screen follows the saved cookie and ignores unknown values" do
    cookies[:screen] = "list"
    assert_equal "list", current_screen
    cookies[:screen] = "mercado"
    assert_equal "add", current_screen
  end

  test "the old market mode cookie means the list" do
    cookies[:market_mode] = "true"
    assert_equal "list", current_screen
  end

  test "shopping mode is off unless the device cookie says on" do
    assert_not shopping_mode?
    cookies[:shopping_mode] = "true"
    assert_not shopping_mode?
    cookies[:shopping_mode] = "on"
    assert shopping_mode?
  end

  test "shopping mode forces the list screen without touching the saved one" do
    cookies[:screen] = "add"
    cookies[:shopping_mode] = "on"
    assert_equal "list", current_screen
    cookies.delete(:shopping_mode)
    assert_equal "add", current_screen
  end

  test "the old market mode cookie does not start shopping mode" do
    cookies[:market_mode] = "true"
    assert_not shopping_mode?
  end
end
