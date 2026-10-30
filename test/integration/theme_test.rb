require "test_helper"

class ThemeTest < ActionDispatch::IntegrationTest
  test "renders the dark theme by default" do
    get root_path
    assert_select "html[data-theme=dark]"
    assert_select "meta[name=theme-color][content='#09090B']"
    assert_select "button[data-controller=theme][aria-label='Tema claro']", count: 2
    assert_select "html[data-color=teal]"
  end

  test "pink color is per-device, persists with the theme, and uses pink dark metadata" do
    cookies[:color] = "pink"
    cookies[:theme] = "dark"
    get root_path
    assert_select 'html[data-color="pink"][data-theme="dark"]'
    assert_select "meta[name=theme-color][content='#151014']"
    assert_select "button[aria-label='Cor Rosa'][aria-pressed=true]", count: 2
  end

  test "renders the theme saved in the cookie" do
    cookies[:theme] = "light"
    get root_path
    assert_select "html[data-theme=light]"
    assert_select "meta[name=theme-color][content='#F4F4F5']"
    assert_select "button[data-controller=theme][aria-label='Tema escuro']", count: 2
  end

  test "falls back to dark for an unknown cookie value" do
    cookies[:theme] = "neon"
    get root_path
    assert_select "html[data-theme=dark]"
  end
end
