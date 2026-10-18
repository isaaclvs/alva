require "test_helper"

class ThemeTest < ActionDispatch::IntegrationTest
  test "renders the dark theme by default" do
    get root_path
    assert_select "html[data-theme=dark]"
    assert_select "meta[name=theme-color][content='#09090B']"
  end

  test "renders the theme saved in the cookie" do
    cookies[:theme] = "light"
    get root_path
    assert_select "html[data-theme=light]"
    assert_select "meta[name=theme-color][content='#F4F4F5']"
  end

  test "falls back to dark for an unknown cookie value" do
    cookies[:theme] = "neon"
    get root_path
    assert_select "html[data-theme=dark]"
  end
end
