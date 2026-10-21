require "application_system_test_case"

class MutationFailuresTest < ApplicationSystemTestCase
  REQUEST_ERROR = "Falha ao salvar. Conferindo a lista antes de liberar outra tentativa."
  RESYNCED_ERROR = "A lista foi atualizada. Confira o estado antes de tentar novamente."
  RESYNC_FAILED_ERROR = "Não foi possível confirmar o resultado. Atualize a lista antes de tentar novamente."

  test "network failure keeps add text and retry works after resync" do
    visit root_path
    fill_in "Item", with: "Banana"
    make_next_request_fail(:network)

    click_button "Adicionar"

    assert_field "Item", with: "Banana"
    assert_text RESYNCED_ERROR
    within "#list_items" do
      assert_no_text "Banana"
    end

    click_button "Adicionar"

    within "#list_items" do
      assert_text "Banana"
    end
    assert_field "Item", with: ""
  end

  test "server error keeps add text and retry works after resync" do
    visit root_path
    fill_in "Item", with: "Banana"
    make_next_request_fail(:server)

    click_button "Adicionar"

    assert_field "Item", with: "Banana"
    assert_text RESYNCED_ERROR
    within "#list_items" do
      assert_no_text "Banana"
    end

    click_button "Adicionar"

    within "#list_items" do
      assert_text "Banana"
    end
    assert_field "Item", with: ""
  end

  test "request error appears while resync is delayed" do
    visit root_path
    fill_in "Item", with: "Banana"
    make_next_request_fail(:network, resync_delay: 1200)

    click_button "Adicionar"

    assert_text REQUEST_ERROR
    assert_no_text RESYNCED_ERROR
  end

  test "submit control stays disabled while its request is in flight" do
    visit root_path
    fill_in "Item", with: "Banana"
    make_next_request_fail(:network, request_delay: 1000)

    click_button "Adicionar"

    assert_selector "#list_item_form button[disabled]"
    assert_text RESYNCED_ERROR
  end

  test "failed correction keeps the edited name and category and can be retried" do
    visit root_path
    click_on "Editar Leite"
    fill_in "Nome", with: "Leite de aveia"
    select "Hortifruti", from: "Categoria"
    make_next_request_fail(:network)

    click_button "Salvar"

    assert_text RESYNCED_ERROR
    assert_field "Nome", with: "Leite de aveia"
    assert_select "Categoria", selected: "Hortifruti"

    click_button "Salvar"

    within "#category_#{categories(:produce).id}" do
      assert_text "Leite de aveia"
    end
  end

  test "validation response stays visible and does not request an uncertain resync" do
    households(:home).list_items.create!(name: "Banana")
    visit root_path
    click_on "Editar Banana"
    fill_in "Nome", with: "Leite"
    page.execute_script("window.__uncertainMutationEvents = 0; document.addEventListener('alva:mutation-uncertain', () => window.__uncertainMutationEvents++)")

    click_button "Salvar"

    assert_text "Leite já está na lista"
    assert_field "Nome", with: "Leite"
    assert_equal 0, page.evaluate_script("window.__uncertainMutationEvents")
  end

  test "GET and explicitly non-Turbo forms are ignored" do
    visit root_path

    assert_equal [ false, false ], page.evaluate_script(<<~JS)
      [
        ["get", null],
        ["post", "false"]
      ].map(([method, turbo]) => {
        const form = document.createElement("form")
        form.method = method
        if (turbo) form.dataset.turbo = turbo
        const button = document.createElement("button")
        button.type = "submit"
        form.append(button)
        document.body.append(form)
        const submit = new Event("submit", { bubbles: true, cancelable: true })
        form.dispatchEvent(submit)
        const start = new CustomEvent("turbo:submit-start", {
          detail: { formSubmission: { formElement: form } }
        })
        document.dispatchEvent(start)
        const disabled = button.disabled
        form.remove()
        return disabled
      })
    JS
  end

  test "failed resync remains gated until a successful manual refresh" do
    visit root_path
    fill_in "Item", with: "Banana"
    make_mutation_and_resync_fail_once

    click_button "Adicionar"

    assert_text RESYNC_FAILED_ERROR
    click_button "Adicionar"
    assert_text "Aguarde a resposta antes de tentar novamente."
    within "#list_items" do
      assert_no_text "Banana"
    end

    click_button "Tentar novamente"
    assert_text RESYNCED_ERROR
    click_button "Adicionar"

    within "#list_items" do
      assert_text "Banana"
    end
  end

  test "lost toggle response does not replay and a fresh click is a new action" do
    visit root_path
    make_next_request_fail(:after_response)

    click_button "Leite"

    assert_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"
    assert_text RESYNCED_ERROR
    assert_equal 1, page.evaluate_script("window.__mutationAttempts")

    click_button "Leite"

    assert_no_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"
    assert_equal 2, page.evaluate_script("window.__mutationAttempts")
  end

  test "remove failure keeps the item available and a retry removes it" do
    visit root_path
    make_next_request_fail(:network)

    click_button "Remover Leite"

    assert_text RESYNCED_ERROR
    assert_selector "li", text: "Leite"

    click_button "Remover Leite"

    assert_no_selector "#list_items", text: "Leite"
  end

  test "finish and undo failures require retry and keep the list state current" do
    visit root_path
    click_button "Leite"
    make_next_request_fail(:network)

    click_on "Compra feita"

    assert_text RESYNCED_ERROR
    assert_selector "##{dom_id(list_items(:milk))} .line-through", text: "Leite"
    click_on "Compra feita"
    assert_no_selector "#list_items", text: "Leite"
    assert_text "Compra registrada"

    make_next_request_fail(:network)
    click_button "Desfazer"

    assert_text RESYNCED_ERROR
    assert_no_selector "#list_items", text: "Leite"
    click_button "Desfazer"

    assert_selector "##{dom_id(list_items(:milk))}", text: "Leite"
    assert_no_selector "##{dom_id(list_items(:milk))} .line-through"
  end

  private

  # Injects browser transport outcomes around the real Rails request path.
  # `after_response` commits a real mutation and drops only the response.
  def make_next_request_fail(kind, request_delay: 0, resync_delay: 0)
    page.execute_script(<<~JS, kind.to_s, request_delay, resync_delay)
      const kind = arguments[0]
      const requestDelay = arguments[1]
      const resyncDelay = arguments[2]
      const originalFetch = window.__alvaOriginalFetch ||= window.fetch.bind(window)
      window.__mutationAttempts = 0
      let failed = false
      window.fetch = async (...args) => {
        const [resource, init = {}] = args
        const url = new URL(resource.url || resource, window.location.href)
        const method = (init.method || resource.method || "GET").toUpperCase()
        if (method !== "GET") window.__mutationAttempts += 1

        if (!failed) {
          failed = true
          if (requestDelay) await new Promise((resolve) => setTimeout(resolve, requestDelay))
          if (kind === "after_response") {
            await originalFetch(...args)
            throw new TypeError("Failed to fetch")
          }
          if (kind === "server") {
            const headers = new Headers(init.headers)
            headers.set("X-Alva-Test-Server-Error", "1")
            return originalFetch(resource, { ...init, headers })
          }
          throw new TypeError("Failed to fetch")
        }

        if (resyncDelay && method === "GET" && url.pathname === "#{root_path}") {
          await new Promise((resolve) => setTimeout(resolve, resyncDelay))
        }
        return originalFetch(...args)
      }
    JS
  end

  def make_mutation_and_resync_fail_once
    page.execute_script(<<~JS)
      const originalFetch = window.__alvaOriginalFetch ||= window.fetch.bind(window)
      let mutationFailed = false
      let resyncFailed = false
      window.fetch = async (...args) => {
        const [resource, init = {}] = args
        const url = new URL(resource.url || resource, window.location.href)
        const method = (init.method || resource.method || "GET").toUpperCase()
        if (!mutationFailed && method !== "GET") {
          mutationFailed = true
          throw new TypeError("Failed to fetch")
        }
        if (mutationFailed && !resyncFailed && method === "GET" && url.pathname === "#{root_path}") {
          resyncFailed = true
          throw new TypeError("Failed to fetch")
        }
        return originalFetch(...args)
      }
    JS
  end
end
