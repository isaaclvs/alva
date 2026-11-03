require "test_helper"

class ApplicationSystemTestCase < ActionDispatch::SystemTestCase
  # Browser sessions are heavier than model tests. Keep the full suite stable
  # when its size crosses Rails' automatic parallelization threshold.
  parallelize(workers: 1)

  driven_by :selenium, using: :headless_chrome, screen_size: [ 390, 844 ] do |options|
    # Lets machines without a system Chrome point at another binary.
    options.binary = ENV["CHROME_BIN"] if ENV["CHROME_BIN"]
  end

  private

  # The app has two screens, switched by the bottom navigation: Adicionar
  # (where it opens) and Lista.
  def show_list
    find("#nav_list").click
    assert_selector "#list_screen:not([hidden])"
    assert_selector "#nav_list[aria-current=page]"
  end

  def show_add
    find("#nav_add").click
    assert_selector "#add_screen:not([hidden])"
    assert_selector "#nav_add[aria-current=page]"
  end

  def visit_list
    visit root_path
    show_list
  end

  def add_item(name)
    fill_in "Item", with: name
    click_button "Adicionar item"
  end

  # Theme, color, PDF and "Sair" live in the "⋯" menu of the visible screen.
  # With a block, the menu is closed again afterwards.
  def open_menu
    find("summary[aria-label='Mais opções']", match: :first).click
    return unless block_given?

    within("details[open]") { yield }
    find("details[open] > summary").click if page.has_css?("details[open]", wait: 0)
  end

  def toggle_theme(label)
    open_menu { click_button label }
  end

  # The search row is folded behind the list header's search button.
  def open_search
    find("button[aria-label='Buscar']").click
    assert_field "Buscar na lista"
  end

  # Removing is in the correction form (or a swipe on touch screens).
  def remove_item(name)
    click_link "Editar #{name}"
    click_button "Remover #{name}"
  end

  def focused_element_id
    page.evaluate_script("document.activeElement.id")
  end
end
