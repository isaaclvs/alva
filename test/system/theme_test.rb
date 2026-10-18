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

  test "the theme survives a list refresh" do
    visit root_path
    click_button "Tema claro"

    fill_in "Item", with: "maçã"
    click_button "Adicionar"

    assert_text "maçã"
    assert_selector "html[data-theme=light]"
    assert_button "Tema escuro"
  end
end
