require "application_system_test_case"

# A tap on a row shows the state just asked for before the server answers,
# mirroring the native row's `pendingStatus`: dimmed at 60%, the check already
# in the new state and "sending" in the row's status. That keeps a slow network
# from making the row look dead, and a second tap from looking like an error.
class OptimisticMarkTest < ApplicationSystemTestCase
  SENDING_MARKED = "Marcado, enviando"
  SENDING_UNMARKED = "Desmarcado, enviando"
  WAIT_ERROR = "Aguarde a resposta antes de tentar novamente."

  test "a tap shows the requested state as sending before the server answers" do
    visit_list
    hold_next_toggle

    click_button "Leite"

    row = row_selector(list_items(:milk))
    assert_selector "#{row}[data-mutation-in-flight][aria-busy=true]"
    assert_selector "#{row} button[aria-pressed=true]"
    assert_selector "#mutation_progress", text: SENDING_MARKED
    assert_equal "0.6", row_opacity(list_items(:milk), expected: "0.6")
    # The circle is already the bought one: same fill the settled row will have.
    assert_equal "rgb(45, 212, 191)", check_background(list_items(:milk), expected: "rgb(45, 212, 191)")
    assert_equal "block", check_glyph_display(list_items(:milk))

    release_toggle

    assert_selector "#{row} .line-through", text: "Leite"
    assert_no_selector "#{row}[data-mutation-in-flight]"
    assert_no_selector "#{row}[aria-busy=true]"
    assert_selector "#mutation_progress", text: ""
    assert_no_text WAIT_ERROR
  end

  test "unmarking says it is sending the unmarked state" do
    visit_list
    hold_next_toggle

    click_button "Detergente"

    row = row_selector(list_items(:detergent))
    assert_selector "#{row} button[aria-pressed=false]"
    assert_selector "#mutation_progress", text: SENDING_UNMARKED
    assert_equal "none", check_glyph_display(list_items(:detergent))

    release_toggle

    assert_no_selector "#{row} .line-through"
    assert_selector "#{row} button[aria-pressed=false]"
  end

  test "a second tap on the action in flight is a silent no-op" do
    visit_list
    hold_next_toggle
    click_button "Leite"
    assert_selector "#{row_selector(list_items(:milk))}[data-mutation-in-flight]"

    page.execute_script("document.querySelector('#{row_selector(list_items(:milk))} form').requestSubmit()")

    assert_equal 1, page.evaluate_script("window.__toggleAttempts")
    assert_equal true, page.evaluate_script("document.getElementById('mutation_status').hidden")
    assert_no_text WAIT_ERROR

    release_toggle

    assert_selector "#{row_selector(list_items(:milk))} .line-through", text: "Leite"
    assert_equal 1, page.evaluate_script("window.__toggleAttempts")
  end

  test "a rejected mark takes the optimistic state back" do
    visit_list
    hold_next_toggle(reject: true)

    click_button "Leite"
    row = row_selector(list_items(:milk))
    assert_selector "#{row}[data-mutation-in-flight]"
    assert_selector "#{row} button[aria-pressed=true]"

    release_toggle

    assert_no_selector "#{row}[data-mutation-in-flight]"
    assert_no_selector "#{row}[aria-busy=true]"
    assert_selector "#{row} button[aria-pressed=false]"
    assert_equal "", page.evaluate_script("document.getElementById('mutation_progress').textContent")
    assert_equal true, page.evaluate_script("document.getElementById('mutation_status').hidden")
  end

  test "the edit link dims the row until the correction form arrives" do
    visit_list
    hold_next_edit_frame

    click_link "Editar Leite"

    row = row_selector(list_items(:milk))
    assert_selector "#{row} > turbo-frame[busy]"
    assert_equal "0.6", row_opacity(list_items(:milk), expected: "0.6")

    release_edit_frame

    assert_field "Nome", with: "Leite"
    assert_no_selector "#{row} > turbo-frame[busy]"
    assert_equal "1", row_opacity(list_items(:milk), expected: "1")
  end

  private

  def row_selector(item)
    "##{dom_id(item)}"
  end

  def row_opacity(item, expected: nil)
    computed("##{dom_id(item)}", "opacity", expected: expected)
  end

  def check_background(item, expected: nil)
    computed("##{dom_id(item)} .shopping-check", "backgroundColor", expected: expected)
  end

  def check_glyph_display(item)
    computed("##{dom_id(item)} .mutation-check", "display")
  end

  # The row's answer is a transition (120ms, `--duration-fast`), so a single
  # read can catch it mid-way. This waits for the property to reach the value
  # the state deserves, and reports whatever it reached when it never does.
  def computed(selector, property, expected: nil, timeout: 2)
    read = -> { page.evaluate_script("getComputedStyle(document.querySelector(#{selector.to_json})).#{property}") }
    deadline = Time.now + timeout
    loop do
      value = read.call
      return value if expected.nil? || value == expected || Time.now > deadline
      sleep 0.02
    end
  end

  # Holds the toggle's submission open so the test can read what the row shows
  # while the request is on its way. The form posts with a `_method` override,
  # so the browser method is POST, not PATCH. `reject: true` answers 422
  # instead of reaching the server: a known rejection with no uncertain resync.
  def hold_next_toggle(reject: false)
    page.execute_script(<<~JS, reject)
      const rejectMark = arguments[0]
      const originalFetch = window.__alvaOriginalFetch ||= window.fetch.bind(window)
      window.__toggleAttempts = 0
      window.__releaseToggle = () => {}
      window.fetch = async (...args) => {
        const [resource, init = {}] = args
        const url = new URL(resource.url || resource, window.location.href)
        const method = (init.method || resource.method || "GET").toUpperCase()
        if (method !== "GET" && url.pathname.endsWith("/toggle")) {
          window.__toggleAttempts += 1
          if (!window.__toggleHeld) {
            window.__toggleHeld = true
            await new Promise((resolve) => { window.__releaseToggle = resolve })
            if (rejectMark) return new Response("", { status: 422 })
          }
        }
        return originalFetch(...args)
      }
    JS
  end

  def release_toggle
    page.execute_script("window.__releaseToggle()")
  end

  # The edit pencil loads the correction form inside the row's Turbo Frame.
  def hold_next_edit_frame
    page.execute_script(<<~JS)
      const originalFetch = window.__alvaOriginalFetch ||= window.fetch.bind(window)
      window.__releaseEditFrame = () => {}
      window.fetch = async (...args) => {
        const [resource, init = {}] = args
        const url = new URL(resource.url || resource, window.location.href)
        const method = (init.method || resource.method || "GET").toUpperCase()
        if (method === "GET" && url.pathname.endsWith("/edit") && !window.__editFrameHeld) {
          window.__editFrameHeld = true
          await new Promise((resolve) => { window.__releaseEditFrame = resolve })
        }
        return originalFetch(...args)
      }
    JS
  end

  def release_edit_frame
    page.execute_script("window.__releaseEditFrame()")
  end
end
