require "application_system_test_case"

class ThemeTest < ApplicationSystemTestCase
  test "starts dark" do
    visit root_path
    assert_selector "html[data-theme=dark]"
    assert_button "Tema claro"
  end

  test "toggling switches the theme and survives a reload" do
    visit root_path

    click_button "Tema claro"
    assert_selector "html[data-theme=light]"
    assert_button "Tema escuro"
    assert_equal "#F4F4F5", page.evaluate_script("document.querySelector('meta[name=theme-color]').content")

    visit root_path
    assert_selector "html[data-theme=light]"

    click_button "Tema escuro"
    assert_selector "html[data-theme=dark]"
    assert_equal "#09090B", page.evaluate_script("document.querySelector('meta[name=theme-color]').content")

    visit root_path
    assert_selector "html[data-theme=dark]"
  end

  test "both screens share the theme and their toggles stay in sync" do
    visit root_path
    within("#add_screen") { click_button "Tema claro" }
    assert_selector "html[data-theme=light]"

    show_list
    within("#list_screen") { click_button "Tema escuro" }
    assert_selector "html[data-theme=dark]"

    show_add
    within("#add_screen") { assert_button "Tema claro" }
  end

  test "the theme survives a list refresh" do
    visit root_path
    click_button "Tema claro"

    add_item "maçã"

    assert_text "maçã adicionado"
    assert_selector "html[data-theme=light]"
    assert_button "Tema escuro"
  end

  test "the theme is per device: changing it here leaves the other device alone" do
    using_session(:other) do
      visit_list
      connect_turbo_cable_stream_sources
    end

    visit root_path
    click_button "Tema claro"
    add_item "Banana"
    assert_text "Banana adicionado"

    using_session(:other) do
      within("#category_#{categories(:produce).id}") { assert_text "Banana" }
      assert_selector "html[data-theme=dark]"
      assert_button "Tema claro"
    end
  end
end
