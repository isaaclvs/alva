require "application_system_test_case"

class DesktopLayoutTest < ApplicationSystemTestCase
  teardown do
    page.current_window.resize_to(390, 844)
  end

  test "desktop navigation and category columns support the shopping flow in both themes" do
    page.current_window.resize_to(1440, 1000)
    visit root_path
    assert_selector ".desktop-brand", text: "Alva"
    assert_operator width_of(".app-shell"), :>, 800
    assert_operator width_of("#add_screen"), :<=, 640

    add_item "Banana"
    assert_text "Banana adicionado em Hortifruti"
    show_list
    assert_equal 2, page.evaluate_script("getComputedStyle(document.querySelector('.shopping-categories')).gridTemplateColumns.split(' ').length")
    click_button "Banana"
    assert_selector "#list_items .line-through", text: "Banana"

    toggle_theme "Tema claro"
    assert_selector "html[data-theme=light]"
    assert_no_horizontal_overflow
    toggle_theme "Tema escuro"
    assert_selector "html[data-theme=dark]"
    assert_no_horizontal_overflow

    page.current_window.resize_to(390, 844)
    assert_no_selector ".desktop-brand"
    assert_selector "#list_screen:not([hidden])"
    assert_equal "block", page.evaluate_script("getComputedStyle(document.querySelector('.shopping-categories')).display")
    assert_no_horizontal_overflow
    show_add
    assert_field "Item"
  end

  private

  def width_of(selector)
    page.evaluate_script("document.querySelector(#{selector.to_json}).getBoundingClientRect().width")
  end

  def assert_no_horizontal_overflow
    assert page.evaluate_script("document.documentElement.scrollWidth <= window.innerWidth")
  end
end
