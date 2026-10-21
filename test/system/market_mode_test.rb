require "application_system_test_case"

class MarketModeTest < ApplicationSystemTestCase
  test "market mode hides habitual items and preserves their draft and selection" do
    habit = Household.first!.habitual_items.create!(name: "Arroz")
    visit root_path
    within("#habitual_items") do
      find("summary").click
      check "Arroz"
      fill_in "Salvar item habitual", with: "Café"
    end

    click_button "Ir ao mercado"
    assert_no_selector "#habitual_items", visible: true
    request_list_refresh
    assert_no_selector "#habitual_items", visible: true
    click_button "Voltar à lista"

    assert_selector "#habitual_items details[open]"
    assert_checked_field "habitual_item_#{habit.id}_checkbox"
    assert_field "Salvar item habitual", with: "Café"
  end

  test "market mode hides entry shortcuts and keeps list actions available" do
    visit root_path

    assert_selector "#entry_controls:not([hidden])"
    page.execute_script("document.getElementById('market_mode_toggle').click()")

    assert_selector "#entry_controls[hidden]", visible: :all
    assert_selector "#market_mode_toggle[aria-pressed='true']", text: "Voltar à lista"
    assert_no_selector "#list_item_name", visible: true
    assert_selector "#list_items", text: "Leite"
    assert_not_equal "list_item_name", page.evaluate_script("document.activeElement.id")
    request_list_refresh
    assert_selector "#entry_controls[hidden]", visible: :all
    assert_not_equal "list_item_name", page.evaluate_script("document.activeElement.id")

    click_button "Leite"
    assert_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"
    assert_button "Compra feita"
    assert_selector "#entry_controls[hidden]", visible: :all
    assert_not_equal "list_item_name", page.evaluate_script("document.activeElement.id")

    click_on "Editar Leite"
    fill_in "Nome", with: "Leite corrigido"
    select "Hortifruti", from: "Categoria"
    click_button "Salvar"
    assert_selector "#category_#{categories(:produce).id}", text: "Leite corrigido"
    assert_selector "#entry_controls[hidden]", visible: :all

    click_button "Remover Leite corrigido"
    click_button "Desfazer"
    assert_selector "#category_#{categories(:produce).id}", text: "Leite corrigido"
    assert_selector "#entry_controls[hidden]", visible: :all
    assert_not_equal "list_item_name", page.evaluate_script("document.activeElement.id")
  end

  test "mode is local to the browser and list broadcasts do not open entry controls or steal focus" do
    using_session(:market_device) do
      visit root_path
      connect_turbo_cable_stream_sources
      click_button "Ir ao mercado"
      assert_selector "#entry_controls[hidden]", visible: :all
      assert_equal "market_mode_toggle", page.evaluate_script("document.activeElement.id")
    end

    visit root_path
    assert_selector "#entry_controls:not([hidden])"
    fill_in "Item", with: "Banana"
    click_button "Adicionar"

    using_session(:market_device) do
      within("#category_#{categories(:produce).id}") { assert_text "Banana" }
      assert_selector "#entry_controls[hidden]", visible: :all
      assert_equal "market_mode_toggle", page.evaluate_script("document.activeElement.id")

      visit root_path
      assert_selector "#entry_controls[hidden]", visible: :all
      assert_not_equal "list_item_name", page.evaluate_script("document.activeElement.id")

      click_button "Voltar à lista"
      assert_selector "#entry_controls:not([hidden])"
      assert_selector "#market_mode_toggle[aria-pressed='false']", text: "Ir ao mercado"
    end
  end

  test "market mode keeps the sticky controls and actions usable with a long list" do
    household = households(:home)
    household.batch_list_broadcasts do
      35.times { |index| household.add_item("Produto de teste #{index + 1}") }
    end

    visit root_path
    assert_selector "#list_items li", count: 37
    click_button "Ir ao mercado"
    assert_selector "#entry_controls[hidden]", visible: :all

    click_button "Produto de teste 35"
    assert_selector "#list_items li .line-through", text: "Produto de teste 35"
    assert_button "Compra feita"
    assert_button "Voltar à lista", visible: true
    click_button "Voltar à lista"

    assert_selector "#entry_controls:not([hidden])"
    assert_selector "#list_items li", count: 37
    assert_selector "#list_items li .line-through", text: "Produto de teste 35"
  end

  private

  def request_list_refresh
    page.driver.browser.execute_async_script(<<~JS)
      const done = arguments[arguments.length - 1]
      const handler = (event) => {
        if (event.detail.reason !== "market-mode-test") return
        document.removeEventListener("list-refresh:success", handler)
        done()
      }
      document.addEventListener("list-refresh:success", handler)
      document.dispatchEvent(new CustomEvent("list-refresh:request", {
        detail: { reason: "market-mode-test", force: true }
      }))
    JS
  end
end
