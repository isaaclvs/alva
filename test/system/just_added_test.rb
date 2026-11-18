require "application_system_test_case"

class JustAddedTest < ApplicationSystemTestCase
  test "what was just added shows its category and can be fixed on the spot" do
    visit root_path
    add_item "Tahine, Banana"

    within("#recent_adds") do
      assert_text "Acabou de adicionar"
      assert_select "Categoria de Banana", selected: "Hortifruti"
      assert_select "Categoria de Tahine", selected: "Outros"
      select "Laticínios", from: "Categoria de Tahine"
      assert_select "Categoria de Tahine", selected: "Laticínios"
    end

    show_list
    within("#category_#{categories(:dairy).id}") { assert_text "Tahine" }
    assert_equal categories(:dairy), ItemCategorizer.call("tahine")
  end

  test "the category picker and the remove button of a recent add are at least 44px tall" do
    visit root_path
    add_item "Tahine, Banana"

    assert_selector "#recent_adds li"
    assert_touch_target "#recent_adds select.recent-category", "#recent_adds button[aria-label^=\"Remover\"]"
  end

  test "typing a name that is already pending says so before sending" do
    visit root_path

    fill_in "Item", with: "pão, leite"
    assert_selector "#add_duplicate_hint", text: "Leite já está na lista"

    fill_in "Item", with: "pão"
    assert_no_selector "#add_duplicate_hint"
  end

  test "a purchased name is not flagged: adding it brings it back" do
    visit root_path

    fill_in "Item", with: "detergente"
    assert_no_selector "#add_duplicate_hint"
  end
  test "a large batch keeps recent additions above the title and the input above navigation" do
    visit root_path
    add_item (1..12).map { |number| "Produto teste #{number}" }.join(", ")
    assert_selector "#recent_adds li", count: 3

    layout = page.evaluate_script(<<~JS)
      (() => {
        const rect = selector => document.querySelector(selector).getBoundingClientRect()
        const recent = document.querySelector('.recent-adds-list')
        return {
          recentBottom: rect('#recent_adds').bottom,
          titleTop: rect('#add_screen_title').top,
          inputTop: rect('#list_item_form').top,
          inputBottom: rect('#list_item_form').bottom,
          navTop: rect('.app-navigation').top,
          viewport: window.innerHeight,
          scrolls: recent.scrollHeight > recent.clientHeight
        }
      })()
    JS
    assert_operator layout["recentBottom"], :<=, layout["titleTop"]
    assert_operator layout["inputTop"], :>, layout["viewport"] * 0.5
    assert_operator layout["inputBottom"], :<=, layout["navTop"]
  end
  test "successive additions accumulate up to three newest items" do
    visit root_path
    [ "Tahine", "Banana", "Café especial", "Azeite especial" ].each do |name|
      add_item name
      assert_selector "#recent_adds li", text: name
    end
    assert_selector "#recent_adds li", count: 3
    assert_no_selector "#recent_adds li", text: "Tahine"
    assert_equal [ "Azeite especial", "Café especial", "Banana" ], all("#recent_adds li > span.flex-1").map(&:text)
  end

  test "a typo can be taken back from just added and undone from the toast" do
    visit root_path
    add_item "Tahine"
    assert_selector "#recent_adds", text: "Tahine"
    tahine = ListItem.find_by!(name: "Tahine")

    within("##{dom_id(tahine, :recent)}") { click_button "Remover Tahine" }

    assert_no_selector "##{dom_id(tahine, :recent)}"
    assert_nil ListItem.find_by(name: "Tahine")
    within("#toast") do
      assert_selector "[role=status]", text: "Tahine removido"
      click_button "Desfazer"
    end

    assert_selector "##{dom_id(tahine, :recent)}", text: "Tahine"
    assert_selector "##{dom_id(tahine, :recent)} button", text: "×"
    assert_equal 1, ListItem.where(name: "Tahine").count
    show_list
    within("#category_#{categories(:other).id}") { assert_text "Tahine" }
  end

  test "a revived name offers no removal in just added" do
    visit root_path
    add_item "detergente"
    assert_selector "#recent_adds", text: "Detergente"

    assert_no_selector "#recent_adds button", text: "×"
    assert_no_selector "#toast", text: "removido"
  end
end
