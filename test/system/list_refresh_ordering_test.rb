require "application_system_test_case"

class ListRefreshOrderingTest < ApplicationSystemTestCase
  test "an older refresh never replaces a newer broadcast" do
    visit root_path
    connect_turbo_cable_stream_sources
    assert_no_selector "#list_refresh_status:not([hidden])"

    page.execute_script(<<~JS)
      const originalFetch = window.fetch.bind(window)
      let held = false
      window.fetch = async (...args) => {
        const [resource, options = {}] = args
        if (!held && (options.method || "GET").toUpperCase() === "GET") {
          held = true
          const response = await originalFetch(...args)
          const body = await response.text()
          return new Promise((resolve) => {
            window.releaseOlderRefresh = () => resolve(new Response(body, {
              status: response.status,
              headers: response.headers
            }))
            document.body.dataset.refreshHeld = "true"
          })
        }
        return originalFetch(...args)
      }
      document.dispatchEvent(new CustomEvent("list-refresh:request", {
        detail: { reason: "ordering-test", force: true }
      }))
    JS
    assert_selector "body[data-refresh-held='true']"

    households(:home).add_item("Banana nova")
    within("#list_items") { assert_text "Banana nova" }

    page.execute_script(<<~JS)
      window.newerItemWasLost = false
      const observer = new MutationObserver(() => {
        if (!document.getElementById("list_items").textContent.includes("Banana nova")) {
          window.newerItemWasLost = true
        }
      })
      observer.observe(document.querySelector("main"), { subtree: true, childList: true })
      document.addEventListener("list-refresh:success", () => {
        document.body.dataset.refreshSettled = "true"
        observer.disconnect()
      }, { once: true })
      window.releaseOlderRefresh()
    JS

    assert_selector "body[data-refresh-settled='true']"
    within("#list_items") { assert_text "Banana nova" }
    assert_equal false, page.evaluate_script("window.newerItemWasLost")
  end
end
