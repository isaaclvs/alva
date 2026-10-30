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

  test "pink color selection persists, syncs on both screens, and stays local to the device" do
    using_session(:other_color_device) do
      visit root_path
      assert_selector 'html[data-color="teal"]'
    end

    visit root_path
    within("#add_screen") { find('summary[title="Personalizar"]').click }
    click_button "Cor Rosa"
    assert_selector 'html[data-color="pink"]'
    assert_equal "rgb(21, 16, 20)", page.evaluate_script("getComputedStyle(document.body).backgroundColor")
    assert_selector 'button[title="Cor Rosa"][aria-pressed="true"]', visible: :all
    assert_equal "#151014", page.evaluate_script("document.querySelector('meta[name=theme-color]').content")
    show_list
    within("#list_screen") { assert_selector 'button[title="Cor Rosa"][aria-pressed="true"]', visible: :all }

    click_button "Tema claro"
    assert_equal "#fcf8fa", page.evaluate_script("document.querySelector('meta[name=theme-color]').content")
    visit root_path
    assert_selector 'html[data-color="pink"][data-theme="light"]'
    assert_equal "rgb(252, 248, 250)", page.evaluate_script("getComputedStyle(document.body).backgroundColor")
    within("#list_screen") do
      find('summary[title="Personalizar"]').click
      click_button "Cor Teal"
    end
    click_button "Tema escuro"
    assert_equal "#09090B", page.evaluate_script("document.querySelector('meta[name=theme-color]').content")
    click_button "Tema claro"
    assert_equal "#F4F4F5", page.evaluate_script("document.querySelector('meta[name=theme-color]').content")

    using_session(:other_color_device) do
      assert_selector 'html[data-color="teal"]'
      assert_selector 'button[title="Cor Teal"][aria-pressed="true"]', visible: :all
    end
  end

  test "color controls are hidden while shopping" do
    visit root_path
    show_list
    click_button "Iniciar compras"
    assert_no_selector "details.shopping-hide", visible: true
  end
end
