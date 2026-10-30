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
end
