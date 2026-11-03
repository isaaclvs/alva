import { Controller } from "@hotwired/stimulus"
import { plain } from "text_plain"

// Controls stay outside broadcast fragments. Reapply local filters whenever
// Turbo replaces the list, without replacing the search field or its focus.
// Search and pending controls remain visible. The header shortcut focuses search.
export default class extends Controller {
  static targets = ["query", "pending", "clear", "empty", "panel", "toggle"]

  connect() {
    this.observer = new MutationObserver(() => this.apply())
    this.observer.observe(this.element, { childList: true, subtree: true })
    this.apply()
  }

  disconnect() {
    this.observer.disconnect()
  }

  apply() {
    const query = plain(this.queryTarget.value)
    let visible = 0
    this.element.querySelectorAll(".shopping-category").forEach((category) => {
      let matches = 0
      category.querySelectorAll("li[data-search-name]").forEach((row) => {
        row.hidden = !row.dataset.searchName.includes(query) || (this.pendingTarget.checked && row.dataset.purchased === "true")
        if (!row.hidden) matches += 1
      })
      category.hidden = matches === 0
      visible += matches
    })
    this.clearTarget.hidden = !this.queryTarget.value
    this.emptyTarget.hidden = visible > 0 || (!query && !this.pendingTarget.checked)
  }

  toggle() {
    this.open()
  }

  open() {
    this.panelTarget.hidden = false
    this.toggleTarget.setAttribute("aria-expanded", "true")
    this.queryTarget.focus()
  }

  clear() {
    this.queryTarget.value = ""
    this.apply()
    this.queryTarget.focus()
  }

  reset() {
    this.queryTarget.value = ""
    this.pendingTarget.checked = false
    this.panelTarget.hidden = false
    this.toggleTarget.setAttribute("aria-expanded", "true")
    this.apply()
  }
}
