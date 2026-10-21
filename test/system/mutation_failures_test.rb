require "application_system_test_case"

class MutationFailuresTest < ApplicationSystemTestCase
  test "network failure keeps the add text and allows a retry" do
    visit root_path
    fill_in "Item", with: "Banana"
    make_next_request_fail(:network)

    click_button "Adicionar"

    assert_text "Falha ao salvar. Conferindo a lista antes de liberar outra tentativa."
    assert_field "Item", with: "Banana"
    within "#list_items" do
      assert_no_text "Banana"
    end
    assert_text "A lista foi atualizada. Confira o estado antes de tentar novamente."

    click_button "Adicionar"

    within "#list_items" do
      assert_text "Banana"
    end
    assert_field "Item", with: ""
  end

  test "server error keeps the add text and allows a retry" do
    visit root_path
    fill_in "Item", with: "Banana"
    make_next_request_fail(:server)

    click_button "Adicionar"

    assert_text "Falha ao salvar. Conferindo a lista antes de liberar outra tentativa."
    assert_field "Item", with: "Banana"
    within "#list_items" do
      assert_no_text "Banana"
    end
    assert_text "A lista foi atualizada. Confira o estado antes de tentar novamente."

    click_button "Adicionar"

    within "#list_items" do
      assert_text "Banana"
    end
    assert_field "Item", with: ""
  end

  test "lost toggle response resyncs before accepting another toggle" do
    visit root_path
    make_next_request_fail(:after_response)

    click_button "Leite"

    assert_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"
    assert_text "A lista foi atualizada. Confira o estado antes de tentar novamente."
  end

  private

  # Injects a single browser-side transport failure while preserving the Rails
  # request path for the after_response case. The latter simulates a committed
  # mutation whose response never reaches the page.
  def make_next_request_fail(kind)
    page.execute_script(<<~JS, kind.to_s)
      const kind = arguments[0]
      const originalFetch = window.fetch.bind(window)
      let failed = false
      window.fetch = async (...args) => {
        if (!failed) {
          failed = true
          if (kind === "after_response") {
            await originalFetch(...args)
            throw new TypeError("Failed to fetch")
          }
          if (kind === "server") {
            const [resource, init = {}] = args
            const headers = new Headers(init.headers)
            headers.set("X-Alva-Test-Server-Error", "1")
            return originalFetch(resource, { ...init, headers })
          }
          throw new TypeError("Failed to fetch")
        }
        return originalFetch(...args)
      }
    JS
  end
end
