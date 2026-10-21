require "application_system_test_case"

class ListItemsTest < ApplicationSystemTestCase
  test "opens with the add field focused" do
    visit root_path
    assert_equal "list_item_name", page.evaluate_script("document.activeElement.id")
  end

  test "adding an item shows it in its category and keeps focus on the field" do
    visit root_path

    fill_in "Item", with: "maçã"
    click_button "Adicionar"

    within "#category_#{categories(:produce).id}" do
      assert_text "maçã"
    end
    assert_field "Item", with: ""
    assert_equal "list_item_name", page.evaluate_script("document.activeElement.id")
  end

  test "adding a pending item again shows feedback instead of a duplicate" do
    visit root_path

    fill_in "Item", with: "LEITE"
    click_button "Adicionar"

    assert_text "Leite já está na lista"
    assert_selector "li", text: "Leite", count: 1
    assert_field "Item", with: ""
    assert_equal "list_item_name", page.evaluate_script("document.activeElement.id")
  end

  test "adding a purchased item again brings it back instead of duplicating" do
    visit root_path

    click_button "Leite"
    assert_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"

    fill_in "Item", with: "leite"
    click_button "Adicionar"

    assert_selector "li", text: "Leite", count: 1
    assert_no_selector "##{dom_id(list_items(:milk))} .line-through"
    assert_selector "##{dom_id(list_items(:milk))}.animate-highlight", text: "Leite"
    assert_field "Item", with: ""
    assert_equal "list_item_name", page.evaluate_script("document.activeElement.id")
  end

  test "reviving a purchased item shows up in another open session" do
    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
      assert_selector "##{dom_id(list_items(:detergent))} .line-through"
    end

    visit root_path
    fill_in "Item", with: "detergente"
    click_button "Adicionar"
    assert_no_selector "##{dom_id(list_items(:detergent))} .line-through"

    using_session(:other) do
      assert_selector "li", text: "Detergente", count: 1
      assert_no_selector "##{dom_id(list_items(:detergent))} .line-through"
    end
  end

  test "typing several names adds them all to their categories and summarizes" do
    visit root_path

    fill_in "Item", with: "Banana, Maçã; Coisa nova"
    click_button "Adicionar"

    within("#category_#{categories(:produce).id}") do
      assert_text "Banana"
      assert_text "Maçã"
    end
    within("#category_#{categories(:other).id}") { assert_text "Coisa nova" }
    assert_text "3 itens adicionados"
    assert_selector "li.animate-highlight", count: 3
    assert_field "Item", with: ""
    assert_equal "list_item_name", page.evaluate_script("document.activeElement.id")
  end

  test "adding several names mentions the ones already on the list" do
    visit root_path

    fill_in "Item", with: "Banana, Maçã"
    click_button "Adicionar"
    assert_text "2 itens adicionados"

    fill_in "Item", with: "banana; Leite; Arroz"
    click_button "Adicionar"

    assert_text "1 item adicionado · Banana e Leite já estavam na lista"
    assert_selector "li", text: "Banana", count: 1
    assert_selector "li", text: "Leite", count: 1
    assert_selector "li", text: "Arroz", count: 1
  end

  test "several names show up in another open session" do
    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
    end

    visit root_path
    fill_in "Item", with: "Banana, Maçã"
    click_button "Adicionar"
    assert_text "2 itens adicionados"

    using_session(:other) do
      within("#category_#{categories(:produce).id}") do
        assert_text "Banana"
        assert_text "Maçã"
      end
    end
  end

  test "unknown item goes to Outros" do
    visit root_path

    fill_in "Item", with: "Coisa nova"
    send_keys :enter

    within("#category_#{categories(:other).id}") { assert_text "Coisa nova" }
  end

  test "suggestions list catalog names and gain items the household adds, without duplicates" do
    visit root_path

    assert_selector "input#list_item_name[list=list_item_name_suggestions]"
    assert_selector "datalist#list_item_name_suggestions option[value=Leite]", visible: :all, count: 1

    fill_in "Item", with: "Coisa nova"
    click_button "Adicionar"

    assert_selector "li", text: "Coisa nova"
    assert_selector "datalist", visible: :all, count: 1
    assert_selector "datalist#list_item_name_suggestions option[value='Coisa nova']", visible: :all, count: 1
    assert_selector "datalist#list_item_name_suggestions option[value=Leite]", visible: :all, count: 1
  end

  test "hides empty categories" do
    visit root_path

    assert_selector "#category_#{categories(:dairy).id}"
    assert_no_selector "#category_#{categories(:produce).id}"
  end

  test "marking and unmarking as purchased" do
    visit root_path

    click_button "Leite"
    assert_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"

    click_button "Leite"
    assert_no_selector "##{dom_id(list_items(:milk))} .line-through"
    assert_selector "##{dom_id(list_items(:milk))}", text: "Leite"
  end

  test "changes show up live in another open session" do
    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
    end

    visit root_path
    fill_in "Item", with: "Banana"
    click_button "Adicionar"
    assert_selector "#category_#{categories(:produce).id}", text: "Banana"

    using_session(:other) do
      within("#category_#{categories(:produce).id}") { assert_text "Banana" }
    end

    click_button "Leite"
    assert_selector "##{dom_id(list_items(:milk))} .line-through"

    using_session(:other) do
      assert_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"
    end
  end

  test "catches up on changes missed while the page was in the background" do
    visit root_path
    assert_text "Leite"

    # Skips callbacks, so no broadcast: like one lost while the phone was locked.
    ListItem.insert!({ name: "Banana", normalized_name: "banana", household_id: households(:home).id,
      category_id: categories(:produce).id })
    assert_no_text "Banana"

    page.execute_script(<<~JS)
      Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" })
      document.dispatchEvent(new Event("visibilitychange"))
      Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" })
      document.dispatchEvent(new Event("visibilitychange"))
    JS
    within("#category_#{categories(:produce).id}") { assert_text "Banana" }
    within("#list_summary") { assert_text "2 faltando" }
  end

  test "catches up when the cable reconnects while the page stays visible" do
    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
    end

    visit root_path
    connect_turbo_cable_stream_sources
    assert_text "Leite"
    assert_equal "visible", page.evaluate_script("document.visibilityState")

    page.driver.browser.execute_async_script(<<~JS)
      const done = arguments[arguments.length - 1]
      window.__cableConsumer = await (await import("@hotwired/turbo-rails")).cable.getConsumer()
      window.__cableConsumer.disconnect()
      done()
    JS
    assert_no_selector "turbo-cable-stream-source[connected]"

    using_session(:other) do
      households(:home).add_item("Banana")
      within("#category_#{categories(:produce).id}") { assert_text "Banana" }
    end
    assert_no_text "Banana"

    page.execute_script("window.__cableConsumer.connect()")
    within("#category_#{categories(:produce).id}") { assert_text "Banana" }
    within("#list_summary") { assert_text "2 faltando" }
    assert_equal "visible", page.evaluate_script("document.visibilityState")
  end

  test "a failed refresh shows a retry and a successful retry clears it" do
    visit root_path
    page.execute_script(<<~JS)
      window.__alvaFetch = window.fetch
      window.fetch = (...args) => Promise.reject(new TypeError("Failed to fetch"))
      document.dispatchEvent(new CustomEvent("list-refresh:request", { detail: { reason: "test", force: true } }))
    JS

    assert_text "Não foi possível atualizar a lista"
    assert_button "Tentar novamente"

    page.execute_script("window.fetch = window.__alvaFetch")
    click_button "Tentar novamente"
    assert_no_button "Tentar novamente"
  end

  test "a resync preserves a typed add draft and an in-progress correction" do
    visit root_path
    fill_in "Item", with: "Tomate escrito"
    request_list_refresh
    assert_field "Item", with: "Tomate escrito"

    click_on "Editar Leite"
    fill_in "Nome", with: "Leite corrigido"
    request_list_refresh
    assert_field "Nome", with: "Leite corrigido"
    assert_selector "##{dom_id(list_items(:milk), :row)} form"
  end

  test "a list broadcast preserves a correction draft in the replaced list" do
    visit root_path
    connect_turbo_cable_stream_sources
    click_on "Editar Leite"
    fill_in "Nome", with: "Leite ainda em edição"

    list_items(:milk).update!(name: "Leite atualizado em outro aparelho")
    households(:home).broadcast_list

    assert_field "Nome", with: "Leite ainda em edição"
    assert_selector "##{dom_id(list_items(:milk), :row)} form"
    click_on "Cancelar"
    assert_text "Leite atualizado em outro aparelho"
  end

  test "a list broadcast preserves drafts in multiple correction frames" do
    visit root_path
    connect_turbo_cable_stream_sources
    click_on "Editar Leite"
    click_on "Editar Detergente"
    within("##{dom_id(list_items(:milk), :row)}") { fill_in "Nome", with: "Leite em edição" }
    within("##{dom_id(list_items(:detergent), :row)}") { fill_in "Nome", with: "Detergente em edição" }

    list_items(:milk).update!(name: "Leite atualizado fora")
    list_items(:detergent).update!(name: "Detergente atualizado fora")
    households(:home).broadcast_list

    within("##{dom_id(list_items(:milk), :row)}") { assert_field "Nome", with: "Leite em edição" }
    within("##{dom_id(list_items(:detergent), :row)}") { assert_field "Nome", with: "Detergente em edição" }
    within("##{dom_id(list_items(:milk), :row)}") { click_on "Cancelar" }
    within("##{dom_id(list_items(:detergent), :row)}") { click_on "Cancelar" }
    assert_text "Leite atualizado fora"
    assert_text "Detergente atualizado fora"
  end

  test "a delayed list broadcast preserves correction typing done before replacement" do
    visit root_path
    connect_turbo_cable_stream_sources
    click_on "Editar Leite"
    click_on "Editar Detergente"
    within("##{dom_id(list_items(:milk), :row)}") { fill_in "Nome", with: "Leite rascunho inicial" }
    within("##{dom_id(list_items(:detergent), :row)}") { fill_in "Nome", with: "Detergente rascunho inicial" }

    page.execute_script(<<~JS)
      const holdListRender = (event) => {
        if (event.target.getAttribute("target") !== "list_items") return
        if (event.target.dataset.listRefreshRequestId) return
        document.removeEventListener("turbo:before-stream-render", holdListRender)
        const render = event.detail.render
        event.detail.render = async (streamElement) => {
          document.body.dataset.listStreamHeld = "true"
          await new Promise((resolve) => { window.__releaseListStream = resolve })
          await render(streamElement)
        }
      }
      document.addEventListener("turbo:before-stream-render", holdListRender)
    JS

    assert_selector "#list_items form[data-list-refresh-correction]", count: 2
    households(:home).broadcast_list
    assert_selector "body[data-list-stream-held='true']"
    assert_equal 2, page.evaluate_script("document.querySelectorAll('#list_items form[data-list-refresh-correction]').length")

    within("##{dom_id(list_items(:milk), :row)}") { fill_in "Nome", with: "Leite rascunho mais recente" }
    within("##{dom_id(list_items(:detergent), :row)}") { fill_in "Nome", with: "Detergente rascunho mais recente" }
    page.execute_script("window.__releaseListStream()")

    within("##{dom_id(list_items(:milk), :row)}") do
      assert_field "Nome", with: "Leite rascunho mais recente"
      click_on "Cancelar"
    end
    within("##{dom_id(list_items(:detergent), :row)}") do
      assert_field "Nome", with: "Detergente rascunho mais recente"
      click_button "Salvar"
    end
    assert_text "Detergente rascunho mais recente"
  end

  test "summary counts update live when marking an item" do
    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
      within("#list_summary") { assert_text "1 faltando · 1 no carrinho" }
    end

    visit root_path
    click_button "Leite"
    within("#list_summary") { assert_text "2 no carrinho" }

    using_session(:other) do
      within("#list_summary") { assert_text "2 no carrinho" }
    end
  end

  test "finishing the shopping archives purchased items everywhere and keeps pending" do
    using_session(:other) do
      visit root_path
      connect_turbo_cable_stream_sources
      assert_text "Detergente"
    end

    visit root_path
    click_on "Compra feita"

    # Archived, so gone from the list (it now shows as a buy again chip).
    assert_no_selector "#list_items", text: "Detergente"
    assert_text "Leite"
    assert_no_button "Compra feita"
    within("#list_summary") { assert_text "1 faltando" }

    using_session(:other) do
      assert_no_selector "#list_items", text: "Detergente"
      assert_text "Leite"
      assert_no_button "Compra feita"
    end

    assert_equal 1, ShoppingTrip.count
  end

  test "an item bought on a finished trip can be added again as a new pending item" do
    visit root_path
    click_on "Compra feita"
    assert_no_selector "#list_items", text: "Detergente"

    fill_in "Item", with: "Detergente"
    click_button "Adicionar"

    assert_selector "li", text: "Detergente", count: 1
    assert_no_selector "li .line-through", text: "Detergente"
  end

  test "removing an item added by mistake" do
    visit root_path

    click_button "Remover Leite"
    assert_no_selector "#list_items", text: "Leite"
    assert_text "Detergente"
  end

  test "finish shopping button appears once an item is purchased" do
    visit root_path
    click_on "Compra feita"
    assert_no_button "Compra feita"

    click_button "Leite"
    assert_button "Compra feita"
  end

  private

  def request_list_refresh
    page.driver.browser.execute_async_script(<<~JS)
      const done = arguments[arguments.length - 1]
      const handler = (event) => {
        if (event.detail.reason !== "test") return
        document.removeEventListener("list-refresh:success", handler)
        done()
      }
      document.addEventListener("list-refresh:success", handler)
      document.dispatchEvent(new CustomEvent("list-refresh:request", {
        detail: { reason: "test", force: true }
      }))
    JS
  end
end
